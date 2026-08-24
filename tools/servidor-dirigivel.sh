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
# A plataforma vem da variavel PLATAFORMA, que aceita fabric (padrao) ou neoforge:
#   PLATAFORMA=neoforge tools/servidor-dirigivel.sh iniciar
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

# Qual adaptador subir. Cada um tem os proprios arquivos, para as duas saidas nunca se misturarem
# -- ja aconteceu, e um log misturado faz a leitura mentir.
PLATAFORMA="${PLATAFORMA:-fabric}"
case "$PLATAFORMA" in
    # Os dois pontos na frente nao sao enfeite: sem eles o Gradle procura a tarefa em todos os
    # subprojetos e sobe o servidor do NeoForge junto, os dois escrevendo no mesmo log. O
    # resultado e um log que mistura as duas plataformas e faz a leitura mentir.
    fabric)   TAREFA=":runServer" ;;
    neoforge) TAREFA=":neoforge:runServer" ;;
    *) echo "PLATAFORMA deve ser fabric ou neoforge, veio $PLATAFORMA" >&2; exit 1 ;;
esac

ENTRADA="$RAIZ/build/servidor-$PLATAFORMA-comandos"
SAIDA="$RAIZ/build/servidor-$PLATAFORMA.log"
PID="$RAIZ/build/servidor-$PLATAFORMA.pid"

# Processos de servidor desta plataforma que ficaram para tras. O daemon do Gradle nunca entra: ele
# e reaproveitado entre execucoes e mata-lo custa um build inteiro de volta.
orfaos_desta_plataforma() {
    local marca
    if [ "$PLATAFORMA" = "neoforge" ]; then marca="neoforge"; else marca="fabric"; fi

    wmic process where "name='java.exe'" get processid,commandline 2>/dev/null         | tr -d '
'         | awk -v marca="$marca" '
            tolower($0) ~ marca && $0 !~ /GradleDaemon/ && NF > 1 { print $NF }
        '
}

iniciar() {
    # O PID guardado e do subshell que alimenta a entrada, e nao do servidor: ele sobrevive a morte
    # do jogo. Por isso quem decide e o processo do servidor, e nao o PID -- com o tail vivo e o
    # jogo morto, o script dizia "ja esta rodando" e devolvia um log velho, que o chamador lia como
    # se fosse desta execucao. Custou uma leitura errada e a conclusao errada junto.
    if [ -f "$PID" ] && kill -0 "$(cat "$PID")" 2>/dev/null \
            && [ -n "$(orfaos_desta_plataforma)" ]; then
        echo "Ja esta rodando (pid $(cat "$PID"))."
        return 0
    fi
    rm -f "$PID"

    # Um servidor anterior que nao morreu segura o session.lock do mundo e o arquivo de log, e o
    # novo falha com um erro que nao diz isso -- "outro processo bloqueou parte do arquivo".
    # Acontece sempre que uma execucao e interrompida, entao o script limpa antes de subir.
    orfaos=$(orfaos_desta_plataforma)
    if [ -n "$orfaos" ]; then
        echo "Encerrando servidor anterior de $PLATAFORMA: $orfaos"
        for pid in $orfaos; do
            taskkill //PID "$pid" //F >/dev/null 2>&1 || kill -9 "$pid" 2>/dev/null
        done
        sleep 3
    fi

    mkdir -p "$RAIZ/build"
    if [ "$PLATAFORMA" = "neoforge" ]; then
        mkdir -p "$RAIZ/neoforge/run"
        echo "eula=true" > "$RAIZ/neoforge/run/eula.txt"
    else
        mkdir -p "$RAIZ/run"
        echo "eula=true" > "$RAIZ/run/eula.txt"
    fi

    # O arquivo precisa existir antes do tail, senao ele sai na hora.
    : > "$ENTRADA"
    : > "$SAIDA"

    # O tail alimenta a entrada do servidor e nunca termina, entao o servidor nao ve fim de arquivo
    # e continua aceitando comandos.
    ( tail -n +1 -f "$ENTRADA" | "$RAIZ/gradlew" -p "$RAIZ" $TAREFA --console=plain \
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
    # Os dois sinais, porque a ordem entre eles muda com a plataforma e faltar qualquer um manda
    # comando cedo demais. No Fabric os comandos dos mods saem depois de "Done ("; no NeoForge eles
    # sao publicados antes de o mundo existir, e um comando enviado ali falha com "serverlevel is
    # null" -- que nao parece com "cedo demais" e ja fez uma verificacao inteira ser lida errada.
    grep -q 'Comandos de mod publicados' "$SAIDA" 2>/dev/null \
        && grep -q 'Done (' "$SAIDA" 2>/dev/null
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
