package dev.lualoader.structure;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.GameBridge;

import java.util.List;
import java.util.Locale;

/**
 * Posiciona no mundo uma estrutura declarada no manifesto.
 *
 * <p>Vive no núcleo, não no adaptador: transformar o desenho em coordenadas é aritmética, não
 * conhecimento de plataforma. Assim a mesma estrutura vale para qualquer backend e pode ser
 * testada sem abrir o jogo.
 *
 * <p>Eixos: cada entrada de {@code layers} é uma camada em Y, da mais baixa para a mais alta;
 * dentro dela, cada string avança em Z e cada caractere avança em X.
 */
public final class StructurePlacer {
    /** Mesmo teto de {@code fill}: evita que um desenho enorme trave a thread do servidor. */
    public static final int MAX_VOLUME = 32_768;

    private final GameBridge bridge;

    public StructurePlacer(GameBridge bridge) {
        this.bridge = bridge;
    }

    /** Resultado de um posicionamento, para o script saber o que aconteceu. */
    public record Placement(int placed, int skipped) {
    }

    /**
     * Coloca a estrutura ancorada na posição indicada.
     *
     * @param anchorX ponto de ancoragem, interpretado conforme {@code origin}
     */
    public Placement place(ModManifest.StructureDefinition structure, int anchorX, int anchorY, int anchorZ) {
        return place(structure, anchorX, anchorY, anchorZ, 0);
    }

    /**
     * Coloca a estrutura girada em torno do eixo vertical.
     *
     * <p>Sem rotacao uma masmorra nasce sempre virada para o mesmo lado, e um script que queira
     * variar precisa declarar quatro copias do mesmo desenho. O giro e em quartos de volta porque e
     * o que o mundo em blocos permite: qualquer outro angulo nao cai na grade.
     *
     * <p>O giro acontece nas coordenadas, e nao no bloco: uma escada colocada por aqui continua
     * apontando para onde apontava. Girar o proprio bloco depende de ler e escrever estado, que o
     * loader ainda nao faz -- e esta registrado como lacuna em vez de meio implementado.
     *
     * @param quarterTurns quartos de volta no sentido horario; qualquer inteiro, inclusive negativo
     */
    public Placement place(ModManifest.StructureDefinition structure,
                           int anchorX, int anchorY, int anchorZ, int quarterTurns) {
        // Math.floorMod para um giro negativo cair em 0..3 em vez de continuar negativo.
        int turns = Math.floorMod(quarterTurns, 4);
        Dimensions dimensions = measure(structure);
        if (dimensions.volume() > MAX_VOLUME) {
            throw new IllegalArgumentException(
                    "estrutura excede o limite de " + MAX_VOLUME + " blocos: " + dimensions.volume());
        }

        // bottom_center centra o desenho no ponto pedido; corner usa o ponto como canto minimo.
        boolean centered = !"corner".equals(
                structure.origin == null ? "bottom_center" : structure.origin.trim().toLowerCase(Locale.ROOT));
        // Um giro de um ou tres quartos troca largura e profundidade, e a centralizacao precisa
        // saber disso: sem isto a estrutura girada nasce deslocada em vez de centrada.
        boolean swapped = turns == 1 || turns == 3;
        int spanX = swapped ? dimensions.depth() : dimensions.width();
        int spanZ = swapped ? dimensions.width() : dimensions.depth();

        int offsetX = centered ? anchorX - spanX / 2 : anchorX;
        int offsetZ = centered ? anchorZ - spanZ / 2 : anchorZ;

        int placed = 0;
        int skipped = 0;

        for (int y = 0; y < structure.layers.size(); y++) {
            List<String> layer = structure.layers.get(y);
            if (layer == null) continue;

            for (int z = 0; z < layer.size(); z++) {
                String row = layer.get(z);
                if (row == null) continue;

                for (int x = 0; x < row.length(); x++) {
                    String symbol = String.valueOf(row.charAt(x));
                    if (!structure.palette.containsKey(symbol)) {
                        throw new IllegalArgumentException(
                                "simbolo fora da paleta na estrutura " + structure.id + ": '" + symbol + "'");
                    }

                    String blockId = structure.palette.get(symbol);
                    if (blockId == null || blockId.isBlank()) {
                        // Simbolo transparente: preserva o que ja existe no mundo.
                        skipped++;
                        continue;
                    }

                    int rotatedX = switch (turns) {
                        case 1 -> dimensions.depth() - 1 - z;
                        case 2 -> dimensions.width() - 1 - x;
                        case 3 -> z;
                        default -> x;
                    };
                    int rotatedZ = switch (turns) {
                        case 1 -> x;
                        case 2 -> dimensions.depth() - 1 - z;
                        case 3 -> dimensions.width() - 1 - x;
                        default -> z;
                    };

                    bridge.setBlock(blockId, offsetX + rotatedX, anchorY + y, offsetZ + rotatedZ);
                    placed++;
                }
            }
        }

        return new Placement(placed, skipped);
    }

    /** Dimensões do desenho, tolerando linhas de comprimentos diferentes. */
    public static Dimensions measure(ModManifest.StructureDefinition structure) {
        int height = structure.layers == null ? 0 : structure.layers.size();
        int depth = 0;
        int width = 0;

        if (structure.layers != null) {
            for (List<String> layer : structure.layers) {
                if (layer == null) continue;
                depth = Math.max(depth, layer.size());
                for (String row : layer) {
                    if (row != null) width = Math.max(width, row.length());
                }
            }
        }
        return new Dimensions(width, height, depth);
    }

    public record Dimensions(int width, int height, int depth) {
        public long volume() {
            return (long) width * height * depth;
        }
    }
}
