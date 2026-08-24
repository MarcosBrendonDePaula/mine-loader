package dev.lualoader.structure;

import dev.lualoader.manifest.ModManifest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Traduz um arquivo de estrutura do jogo para a declaração que o loader já sabe posicionar.
 *
 * <p>O formato em texto — paleta de caracteres mais camadas — continua sendo o melhor para algo
 * pequeno e para revisar num diff. Mas obriga a desenhar tudo à mão, o que não escala: uma
 * construção de mil blocos não se transcreve. O {@code .nbt} é o que o bloco de estrutura do jogo
 * grava, então dá para construir dentro do Minecraft, salvar e distribuir junto do mod.
 *
 * <p>A tradução produz a mesma {@code StructureDefinition} que um manifesto escreveria à mão. Isso
 * não é economia de código: é o que faz {@code origin}, o teto de volume e tudo o mais valerem
 * igual para os dois caminhos, sem um segundo posicionador para manter em paralelo.
 */
public final class StructureNbt {
    private StructureNbt() {
    }

    /**
     * Símbolos que uma paleta traduzida pode usar.
     *
     * <p>Sem aspas, sem barra e sem o que precise de escape em JSON: uma estrutura traduzida pode
     * ser gravada de volta como manifesto, e um símbolo que exigisse escape estragaria isso.
     * O espaço fica de fora porque já significa "preserve o que está aqui".
     */
    private static final String SYMBOLS =
            "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
                    + "!#$%&()*+,-.:;<=>?@[]^_{|}~";

    /** O que o posicionador entende por "preserve o que já existe". */
    private static final char TRANSPARENT = ' ';

    /**
     * Lê um arquivo de estrutura e devolve a declaração equivalente.
     *
     * @param id nome dado à estrutura, usado nas mensagens de erro do posicionador
     */
    public static ModManifest.StructureDefinition read(byte[] bytes, String id) throws IOException {
        Map<String, Object> root = Nbt.read(bytes);

        List<Object> size = Nbt.list(root, "size");
        int width = Nbt.integerAt(size, 0, 0);
        int height = Nbt.integerAt(size, 1, 0);
        int depth = Nbt.integerAt(size, 2, 0);

        if (width <= 0 || height <= 0 || depth <= 0) {
            throw new IOException("estrutura sem tamanho valido: " + width + "x" + height + "x" + depth);
        }

        List<String> palette = readPalette(root);
        Grid grid = fillGrid(root, width, height, depth, palette);

        return toDefinition(id, grid, height, depth);
    }

    /** Os identificadores de bloco da paleta do arquivo, na ordem em que os índices apontam. */
    private static List<String> readPalette(Map<String, Object> root) throws IOException {
        List<String> names = new ArrayList<>();

        for (Object entry : Nbt.list(root, "palette")) {
            if (!(entry instanceof Map)) {
                throw new IOException("entrada de paleta invalida");
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> state = (Map<String, Object>) entry;

            String name = Nbt.string(state, "Name");
            if (name == null || name.isBlank()) throw new IOException("bloco da paleta sem Name");

            // As propriedades do estado sao descartadas: o loader posiciona pelo identificador, e
            // guardar o que ele nao aplica faria a estrutura prometer uma orientacao que nao teria.
            names.add(name);
        }

        if (names.isEmpty()) throw new IOException("estrutura sem paleta");
        return names;
    }

    /**
     * A grade de simbolos e a paleta que a acompanha.
     *
     * <p>Nascem juntas -- so ao percorrer os blocos se sabe quais aparecem de fato -- e por isso
     * voltam juntas. Guardar a paleta num campo entre as duas chamadas funcionaria e seria uma
     * armadilha: duas leituras ao mesmo tempo se atropelariam.
     */
    private record Grid(char[][][] cells, Map<String, Character> palette) {
    }

    /** Distribui os blocos do arquivo numa grade, deixando o resto transparente. */
    private static Grid fillGrid(Map<String, Object> root, int width, int height, int depth,
                                       List<String> palette) throws IOException {
        // Índice na paleta traduzida, e não na do arquivo: só os blocos que aparecem de fato
        // recebem símbolo, e uma paleta grande com poucos blocos usados não gasta os símbolos.
        Map<String, Character> symbols = new LinkedHashMap<>();
        char[][][] grid = new char[height][depth][width];
        for (char[][] layer : grid) {
            for (char[] row : layer) java.util.Arrays.fill(row, TRANSPARENT);
        }

        for (Object entry : Nbt.list(root, "blocks")) {
            if (!(entry instanceof Map)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> block = (Map<String, Object>) entry;

            List<Object> position = Nbt.list(block, "pos");
            int x = Nbt.integerAt(position, 0, -1);
            int y = Nbt.integerAt(position, 1, -1);
            int z = Nbt.integerAt(position, 2, -1);

            if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) continue;

            int state = Nbt.integer(block, "state", -1);
            if (state < 0 || state >= palette.size()) continue;

            String name = palette.get(state);
            // O ar capturado pelo bloco de estrutura fica transparente: uma estrutura que
            // sobrescrevesse com ar abriria buracos no que ja estava no mundo.
            if (name.equals("minecraft:air") || name.equals("minecraft:cave_air")
                    || name.equals("minecraft:void_air") || name.equals("minecraft:structure_void")) {
                continue;
            }

            Character symbol = symbols.get(name);
            if (symbol == null) {
                if (symbols.size() >= SYMBOLS.length()) {
                    throw new IOException("estrutura usa mais de " + SYMBOLS.length()
                            + " blocos diferentes, que e o limite da paleta em texto");
                }
                symbol = SYMBOLS.charAt(symbols.size());
                symbols.put(name, symbol);
            }
            grid[y][z][x] = symbol;
        }

        return new Grid(grid, symbols);
    }

    private static ModManifest.StructureDefinition toDefinition(String id, Grid grid,
                                                                int height, int depth) {
        ModManifest.StructureDefinition definition = new ModManifest.StructureDefinition();
        definition.id = id;
        definition.name = id;
        // O bloco de estrutura grava a partir do canto minimo, e nao do centro.
        definition.origin = "corner";

        definition.palette = new LinkedHashMap<>();
        for (Map.Entry<String, Character> entry : grid.palette().entrySet()) {
            definition.palette.put(String.valueOf(entry.getValue()), entry.getKey());
        }
        definition.palette.put(String.valueOf(TRANSPARENT), "");

        definition.layers = new ArrayList<>(height);
        for (int y = 0; y < height; y++) {
            List<String> layer = new ArrayList<>(depth);
            for (int z = 0; z < depth; z++) {
                layer.add(new String(grid.cells()[y][z]));
            }
            definition.layers.add(layer);
        }
        return definition;
    }
}
