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
        Dimensions dimensions = measure(structure);
        if (dimensions.volume() > MAX_VOLUME) {
            throw new IllegalArgumentException(
                    "estrutura excede o limite de " + MAX_VOLUME + " blocos: " + dimensions.volume());
        }

        // bottom_center centra o desenho no ponto pedido; corner usa o ponto como canto minimo.
        boolean centered = !"corner".equals(
                structure.origin == null ? "bottom_center" : structure.origin.trim().toLowerCase(Locale.ROOT));
        int offsetX = centered ? anchorX - dimensions.width() / 2 : anchorX;
        int offsetZ = centered ? anchorZ - dimensions.depth() / 2 : anchorZ;

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

                    bridge.setBlock(blockId, offsetX + x, anchorY + y, offsetZ + z);
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
