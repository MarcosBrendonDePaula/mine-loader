package dev.lualoader.ui;

/**
 * Onde cada elemento de uma tela vai parar, e o que está sob o cursor.
 *
 * <p>Isto é aritmética sobre a descrição — âncoras, tamanhos, a célula apontada numa grade — e não
 * depende de plataforma nenhuma. Fica no núcleo pelo mesmo motivo que a validação: alinhamento é
 * onde moram os erros sutis, e um erro aqui precisa ser testável sem abrir o jogo.
 *
 * <p>A única coisa que o núcleo não sabe é quanto um texto mede, porque isso depende da fonte
 * carregada no cliente. Ela entra por {@link TextMetrics}, e é a fronteira inteira entre este
 * arquivo e o renderizador de cada adaptador.
 */
public final class ScreenLayout {
    private ScreenLayout() {
    }

    /** Um retângulo em coordenadas de interface. */
    public record Bounds(int x, int y, int w, int h) {
    }

    /** Quanto um texto ocupa na fonte do cliente que está desenhando. */
    public interface TextMetrics {
        int width(String text);

        int lineHeight();
    }

    /**
     * Resolve a posição final de um elemento.
     *
     * @param surface área que as âncoras comuns usam como referência
     * @param gui     janela da tela do jogo por baixo, usada pelas âncoras {@code gui_}; quando não
     *                há tela de container, passe a mesma {@code surface}
     */
    public static int[] resolve(ScreenModel.Element element, Bounds surface, Bounds gui,
                                TextMetrics metrics) {
        int[] size = measure(element, metrics);
        int width = size[0];
        int height = size[1];

        String anchor = element.anchor();
        // Sem âncora, a coordenada parte do canto da superfície: é o que alguém espera ao escrever
        // x = 4, y = 4, e vale igual nas três superfícies.
        if (anchor.isBlank()) {
            return new int[]{surface.x() + element.x(), surface.y() + element.y()};
        }

        Bounds base = anchor.startsWith("gui_") ? gui : surface;
        int baseX = base.x();
        int baseY = base.y();

        switch (anchor) {
            case "top" -> baseX += base.w() / 2 - width / 2;
            case "top_right" -> baseX += base.w() - width;
            case "left" -> baseY += base.h() / 2 - height / 2;
            case "right" -> {
                baseX += base.w() - width;
                baseY += base.h() / 2 - height / 2;
            }
            case "bottom_left" -> baseY += base.h() - height;
            case "bottom" -> {
                baseX += base.w() / 2 - width / 2;
                baseY += base.h() - height;
            }
            case "bottom_right" -> {
                baseX += base.w() - width;
                baseY += base.h() - height;
            }
            case "center" -> {
                baseX += base.w() / 2 - width / 2;
                baseY += base.h() / 2 - height / 2;
            }
            // As âncoras de janela colam o elemento à borda da tela do jogo, e não à da tela toda.
            // A da direita é a que motiva o recurso: é onde cabe um painel lateral sem cobrir os
            // slots do inventário, em qualquer resolução.
            case "gui_top_right" -> baseX += base.w();
            case "gui_right" -> {
                baseX += base.w();
                baseY += base.h() / 2 - height / 2;
            }
            case "gui_left" -> {
                baseX -= width;
                baseY += base.h() / 2 - height / 2;
            }
            case "gui_center" -> {
                baseX += base.w() / 2 - width / 2;
                baseY += base.h() / 2 - height / 2;
            }
            default -> {
                // top_left e gui_top_left: a origem já é o canto, nada a descontar.
            }
        }
        return new int[]{baseX + element.x(), baseY + element.y()};
    }

    /**
     * Largura e altura ocupadas por um elemento.
     *
     * <p>{@code label} e {@code item} dimensionam-se pelo conteúdo, e é por isso que o protocolo não
     * exige {@code w} e {@code h} neles. Sem esta medida não haveria como centralizá-los numa âncora
     * nem saber se o cursor está sobre eles.
     */
    public static int[] measure(ScreenModel.Element element, TextMetrics metrics) {
        return switch (element.type()) {
            case "item" -> new int[]{16, 16};
            case "grid" -> {
                // A grade mede-se pelas celulas: quem escreve a tela declara colunas e passo, e o
                // numero de linhas sai da divisao. E o que substitui calcular x e y de cada slot.
                int columns = Math.max(1, element.columns());
                int rows = (int) Math.ceil(element.cells().size() / (double) columns);
                yield new int[]{columns * element.cell(), Math.max(0, rows) * element.cell()};
            }
            case "label" -> {
                double scale = element.scale() <= 0 ? 1.0 : element.scale();
                yield new int[]{
                        (int) Math.round(metrics.width(element.text()) * scale),
                        (int) Math.round(metrics.lineHeight() * scale)};
            }
            default -> new int[]{element.w(), element.h()};
        };
    }

    /**
     * Qual célula de uma grade está sob o cursor, contando a partir de 1; 0 se nenhuma.
     *
     * <p>É o mesmo número que volta ao script no valor do evento, para o mod saber qual item foi
     * clicado sem receber uma posição em pixels que ele teria de traduzir.
     */
    public static int cellAt(ScreenModel.Element element, int x, int y, int mouseX, int mouseY,
                             TextMetrics metrics) {
        if (!element.type().equals("grid") || !contains(element, x, y, mouseX, mouseY, metrics)) {
            return 0;
        }

        int columns = Math.max(1, element.columns());
        int column = (mouseX - x) / element.cell();
        int row = (mouseY - y) / element.cell();
        if (column >= columns) return 0;

        int position = row * columns + column;
        return position >= 0 && position < element.cells().size() ? position + 1 : 0;
    }

    /** Indica se o cursor está dentro da área do elemento. */
    public static boolean contains(ScreenModel.Element element, int x, int y,
                                   int mouseX, int mouseY, TextMetrics metrics) {
        int[] size = measure(element, metrics);
        return mouseX >= x && mouseX < x + size[0]
                && mouseY >= y && mouseY < y + size[1];
    }
}
