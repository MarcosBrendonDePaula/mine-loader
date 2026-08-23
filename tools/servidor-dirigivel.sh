#!/usr/bin/env bash
#
# Sobe o servidor de desenvolvimento com a entrada padrao vinda de um arquivo.
#
# O servidor do Minecraft le comandos do console. Redirecionando esse console para um arquivo que
# cresce, qualquer um -- pessoa ou ferramenta -- passa a executar comandos escrevendo linhas nele,
# sem precisar de uma janela aberta e de alguem digitando.
#
# E o que permite verificar uma mudanca de ponta a ponta sozinho: subir, dar os comandos, ler o log
# e conferir o resultado. Sem isto, cada verificacao depende de alguem estar no jogo no momento
# certo.
#
#   tools/servidor-dirigivel.sh iniciar     sobe o servidor
#   tools/servidor-dirigivel.sh esperar     bloqueia ate ele aceitar comandos
#   tools/servidor-dirigivel.sh cmd "say oi"    envia um comando
#   tools/servidor-dirigivel.sh log 40      ultimas linhas do log
#   tools/servidor-dirigivel.sh parar       encerra
#
# O servidor nao tem cliente: telas, HUD e sobreposicao nao sao verificaveis por aqui. Tudo que
# vive no servidor -- blocos, inventarios, receitas, comandos, estado -- e.

set -u

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENTRADA="$RAIZ/build/servidor-comandos"
SAIDA="$RAIZ/build/servidor-saida.log"
PID="$RAIZ/build/servidor.pid"

iniciar() {
    if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null; then
        echo "Ja esta rodando (pid $(cat "$PID"))."
        return 0
    fi

    # Um servidor de outra origem -- outra plataforma, outra janela -- escrevendo no mesmo arquivo
    # mistura as saidas, e a leitura do log passa a mentir. Ja aconteceu, e custou tempo.
    if pgrep -f "runServer" >/dev/null 2>&1; then
        echo "Aviso: ja ha um runServer neste computador. Pare-o antes, ou os logs se misturam." >&2
    fi

    mkdir -p "$RAIZ/build" "$RAIZ/run"
    echo "eula=true" > "$RAIZ/run/eula.txt"

    # O arquivo precisa existir antes do tail, senao ele sai na hora.
    : > "$ENTRADA"
    : > "$SAIDA"

    # O tail alimenta a entrada do servidor e nunca termina, entao o servidor nao ve fim de arquivo
    # e continua aceitando comandos.
    ( tail -n +1 -f "$ENTRADA" | "$RAIZ/gradlew" -p "$RAIZ" runServer --console=plain \
        > "$SAIDA" 2>&1 ) &

    echo $! > "$PID"
    echo "Servidor subindo. Log em $SAIDA"
    echo "Espere por 'Done' antes de enviar comandos."
}

cmd() {
    if [ ! -f "$ENTRADA" ]; then
        echo "O servidor nao foi iniciado." >&2
        return 1
    fi
    # Sem a barra: o console do servidor recebe comandos sem prefixo.
    echo "$1" >> "$ENTRADA"
}

pronto() {
    # Os comandos dos mods sao publicados no fim da inicializacao, depois de todo mod carregar.
    # "Done (" aparece antes disso, e esperar por ele mandava comandos cedo demais.
    grep -q 'Comandos de mod publicados' "$SAIDA" 2>/dev/null
}

esperar() {
    local limite=${1:-180}
    local passou=0

    while [ "$passou" -lt "$limite" ]; do
        pronto && { echo "Servidor pronto."; return 0; }
        sleep 3
        passou=$((passou + 3))
    done

    echo "O servidor nao ficou pronto em ${limite}s. Veja $SAIDA" >&2
    return 1
}

parar() {
    cmd "stop" 2>/dev/null
    sleep 3

    if [ -f "$PID" ]; then
        kill "$(cat "$PID")" 2>/dev/null
        rm -f "$PID"
    fi
    echo "Servidor parado."
}

case "${1:-}" in
    iniciar) iniciar ;;
    esperar) esperar "${2:-180}" ;;
    cmd)     cmd "${2:?informe o comando}" ;;
    log)     tail -n "${2:-30}" "$SAIDA" ;;
    pronto)  pronto && echo "pronto" || echo "ainda carregando" ;;
    parar)   parar ;;
    *)
        echo "uso: $0 {iniciar|esperar [s]|cmd <comando>|log [n]|pronto|parar}" >&2
        exit 1
        ;;
esac
