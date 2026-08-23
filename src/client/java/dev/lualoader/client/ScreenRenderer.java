package dev.lualoader.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Desenho de elementos, compartilhado pelas três superfícies do loader.
 *
 * <p>Uma tela própria, o HUD e uma sobreposição sobre tela do jogo desenham exatamente os mesmos
 * elementos; só muda onde fica a origem e o que acontece com um clique. Antes cada superfície tinha
 * a própria cópia do desenho, e um tipo novo precisava ser escrito duas vezes — foi assim que o
 * {@code tooltip} chegou a existir no protocolo sem nunca ser desenhado em lugar nenhum.
 */
public final class ScreenRenderer {
    private ScreenRenderer() {
    }

    /** Um retângulo em coordenadas de interface. */
    public record Bounds(int x, int y, int w, int h) {
    }

    /**
     * Resolve a posição final de um elemento.
     *
     * @param surface área que as âncoras comuns usam como referência
     * @param gui     janela da tela do jogo por baixo, usada pelas âncoras {@code gui_}; quando não
     *                há tela de container, passe a mesma {@code surface}
     */
    public static int[] resolve(ScreenModel.Element element, Bounds surface, Bounds gui) {
        int[] size = measure(element);
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
    public static int[] measure(ScreenModel.Element element) {
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
                TextRenderer textRenderer = MinecraftClient.getInstance().textRenderer;
                double scale = element.scale() <= 0 ? 1.0 : element.scale();
                yield new int[]{
                        (int) Math.round(textRenderer.getWidth(element.text()) * scale),
                        (int) Math.round(textRenderer.fontHeight * scale)};
            }
            default -> new int[]{element.w(), element.h()};
        };
    }

    /**
     * Desenha um elemento na posição já resolvida.
     *
     * <p>{@code button} e {@code input} não aparecem aqui: são widgets do jogo, criados e desenhados
     * por quem hospeda a superfície, porque precisam de foco e de captura de teclado.
     */
    public static void draw(DrawContext context, TextRenderer textRenderer,
                            ScreenModel.Element element, int x, int y) {
        switch (element.type()) {
            case "panel" -> panel(context, element, x, y);
            case "label" -> {
                if (element.scale() == 1.0) {
                    context.drawText(textRenderer, Text.literal(element.text()),
                            x, y, element.color(), element.shadow());
                } else {
                    // A escala multiplica a matriz, entao a posicao precisa ser dividida por ela.
                    // Escala inteira mantem a fonte bitmap nitida; uma fracionaria interpola e
                    // borra, e o arredondamento abaixo evita ainda cair em meio pixel.
                    float scale = (float) element.scale();
                    context.getMatrices().push();
                    context.getMatrices().scale(scale, scale, 1f);
                    context.drawText(textRenderer, Text.literal(element.text()),
                            Math.round(x / scale), Math.round(y / scale),
                            element.color(), element.shadow());
                    context.getMatrices().pop();
                }
            }
            case "progress" -> {
                context.fill(x, y, x + element.w(), y + element.h(), 0xFF303030);
                int filled = (int) (element.w() * Math.max(0, Math.min(1, element.progress())));
                context.fill(x, y, x + filled, y + element.h(), element.color());
            }
            case "item" -> {
                Identifier id = Identifier.tryParse(element.item());
                if (id != null && Registries.ITEM.containsId(id)) {
                    ItemStack stack = new ItemStack(Registries.ITEM.get(id), element.count());
                    context.drawItem(stack, x, y);
                    context.drawItemInSlot(textRenderer, stack, x, y);
                }
            }
            case "grid" -> {
                int columns = Math.max(1, element.columns());
                for (int position = 0; position < element.cells().size(); position++) {
                    ScreenModel.Cell cell = element.cells().get(position);
                    Identifier id = Identifier.tryParse(cell.item());
                    if (id == null || !Registries.ITEM.containsId(id)) continue;

                    int cellX = x + (position % columns) * element.cell();
                    int cellY = y + (position / columns) * element.cell();

                    ItemStack stack = new ItemStack(Registries.ITEM.get(id), cell.count());
                    context.drawItem(stack, cellX, cellY);
                    context.drawItemInSlot(textRenderer, stack, cellX, cellY);
                }
            }
            case "viewport" -> {
                // O recorte e a moldura sao feitos por quem hospeda a superficie, que conhece o
                // deslocamento da rolagem. Aqui o viewport em si nao desenha nada.
            }
            case "image" -> {
                Identifier texture = Identifier.tryParse(element.texture());
                if (texture != null) {
                    context.drawTexture(texture, x, y, 0, 0,
                            element.w(), element.h(), element.w(), element.h());
                }
            }
            default -> {
                // Tipo desconhecido: ignorado de proposito, para uma tela nova nao quebrar em um
                // cliente antigo.
            }
        }
    }

    /**
     * Desenha um painel, com ou sem bisel.
     *
     * <p>A janela do Minecraft não é uma imagem: é um retângulo cinza com uma borda clara em cima e
     * à esquerda e uma escura embaixo e à direita, o que dá a impressão de luz vindo do canto
     * superior esquerdo. Descrever isso como regra em vez de textura deixa o painel acompanhar
     * qualquer tamanho, e dispensa o mod distribuir e o cliente baixar uma imagem.
     *
     * <p>{@code slot} e {@code inset} invertem as bordas: a sombra passa para cima, e o retângulo
     * parece afundado — é o que distingue um slot de um botão, no jogo inteiro.
     */
    private static void panel(DrawContext context, ScreenModel.Element element, int x, int y) {
        int right = x + element.w();
        int bottom = y + element.h();
        String style = element.style();

        // As cores do jogo, para um painel sair igual ao do inventário sem o mod declarar nada.
        int background = switch (style) {
            case "vanilla" -> element.color() == 0xFFFFFFFF ? 0xFFC6C6C6 : element.color();
            case "slot", "inset" -> element.color() == 0xFFFFFFFF ? 0xFF8B8B8B : element.color();
            default -> element.color();
        };
        context.fill(x, y, right, bottom, background);

        if (style.equals("flat")) return;

        int thickness = Math.max(1, Math.min(element.border(), Math.min(element.w(), element.h()) / 2));
        boolean sunken = style.equals("slot") || style.equals("inset");

        int light = sunken ? element.borderDark() : element.borderLight();
        int dark = sunken ? element.borderLight() : element.borderDark();

        // Cima e esquerda recebem a luz; baixo e direita, a sombra. Invertido, o retângulo afunda.
        context.fill(x, y, right, y + thickness, light);
        context.fill(x, y, x + thickness, bottom, light);
        context.fill(x, bottom - thickness, right, bottom, dark);
        context.fill(right - thickness, y, right, bottom, dark);
    }

    /**
     * Desenha a caixa de ajuda do elemento sob o cursor, se houver.
     *
     * <p>Precisa acontecer depois de todo o resto, senão o elemento seguinte cobriria a caixa.
     *
     * @param tooltip conteúdo do campo {@code tooltip}, com {@code \n} separando linhas
     */
    public static void drawTooltip(DrawContext context, TextRenderer textRenderer,
                                   String tooltip, int mouseX, int mouseY) {
        if (tooltip == null || tooltip.isBlank()) return;

        List<Text> lines = new ArrayList<>();
        for (String line : tooltip.split("\n", -1)) lines.add(Text.literal(line));

        context.drawTooltip(textRenderer, lines, mouseX, mouseY);
    }

    /**
     * Índice da célula sob o cursor, a partir de 1, ou zero quando não há nenhuma.
     *
     * <p>É o mesmo número que volta ao script no valor do evento, para o mod saber qual item foi
     * clicado sem receber uma posição em pixels que ele teria de traduzir.
     */
    public static int cellAt(ScreenModel.Element element, int x, int y, int mouseX, int mouseY) {
        if (!element.type().equals("grid") || !contains(element, x, y, mouseX, mouseY)) return 0;

        int columns = Math.max(1, element.columns());
        int column = (mouseX - x) / element.cell();
        int row = (mouseY - y) / element.cell();
        if (column >= columns) return 0;

        int position = row * columns + column;
        return position >= 0 && position < element.cells().size() ? position + 1 : 0;
    }

    /**
     * Texto de ajuda do elemento sob o cursor, ou {@code null}.
     *
     * <p>Uma grade responde pela célula apontada, e não pela grade inteira: é o que faz o nome do
     * item aparecer ao passar por ele, como acontece no inventário.
     */
    public static String tooltipAt(ScreenModel.Element element, int x, int y, int mouseX, int mouseY) {
        if (element.type().equals("grid")) {
            int cell = cellAt(element, x, y, mouseX, mouseY);
            if (cell == 0) return null;

            ScreenModel.Cell content = element.cells().get(cell - 1);
            return content.tooltip().isBlank() ? itemName(content.item()) : content.tooltip();
        }
        if (contains(element, x, y, mouseX, mouseY)) {
            // Um item sem texto declarado responde pelo proprio nome, como no inventario. Sem isto
            // o mod teria de mandar o nome de cada item, que ele nem conhece: o servidor tem o
            // identificador, e a traducao vive no cliente.
            if (element.tooltip().isBlank() && element.type().equals("item")) {
                return itemName(element.item());
            }
            if (!element.tooltip().isBlank()) return element.tooltip();
        }
        return null;
    }

    /**
     * Nome traduzido de um item, com o identificador abaixo.
     *
     * <p>O servidor descreve a tela em identificadores, porque e o que ele tem: a traducao depende
     * do idioma escolhido em cada cliente. Mostrar so {@code minecraft:iron_ingot} obrigaria quem
     * joga a decorar identificadores; mostrar so "Lingote de Ferro" tiraria de quem escreve o mod a
     * informacao de que precisa. Os dois juntos servem aos dois, como o jogo faz com F3+H ligado.
     */
    private static String itemName(String itemId) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return itemId;

        // A quebra e sempre a mesma que drawTooltip divide. O separador do sistema traria um
        // retorno de carro no Windows, que viraria um caractere solto na caixa de ajuda.
        return new ItemStack(Registries.ITEM.get(id)).getName().getString() + "\n" + itemId;
    }

    /** Indica se o cursor está dentro da área do elemento. */
    public static boolean contains(ScreenModel.Element element, int x, int y, int mouseX, int mouseY) {
        int[] size = measure(element);
        return mouseX >= x && mouseX < x + size[0]
                && mouseY >= y && mouseY < y + size[1];
    }
}
