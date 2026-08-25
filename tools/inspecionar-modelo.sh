#!/usr/bin/env bash
# Diz o que o pacote gerado manda desenhar, sem abrir o jogo.
#
#   tools/inspecionar-modelo.sh logistica:cano        o que cada estado desenha
#   tools/inspecionar-modelo.sh logistica:cano.obj    os grupos do arquivo, com as caixas
#
# O pacote e montado ao subir o servidor ou o cliente; sem ele nao ha o que inspecionar.
set -euo pipefail

RAIZ="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$RAIZ/core/build/classes/java/main"
SAIDA="$RAIZ/build/inspecionar-modelo"

if [ ! -d "$CLASSES" ]; then
    echo "O nucleo ainda nao foi compilado. Rode: ./gradlew :core:compileJava" >&2
    exit 1
fi

mkdir -p "$SAIDA"
javac -cp "$CLASSES" -d "$SAIDA" "$RAIZ/tools/inspecionar-modelo/InspecionarModelo.java"

# No Windows o java e nativo e nao entende o caminho do Git Bash (/e/...), entao os caminhos vao
# convertidos e o separador de classpath e ponto e virgula. Sem isso a classe existe e nao e achada,
# e a mensagem fala de "classe principal" -- que nao tem nada a ver com a causa.
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*)
        CLASSES="$(cygpath -w "$CLASSES")"
        SAIDA="$(cygpath -w "$SAIDA")"
        CP="$CLASSES;$SAIDA"
        ;;
    *)
        CP="$CLASSES:$SAIDA"
        ;;
esac

exec java -cp "$CP" InspecionarModelo "$@"
