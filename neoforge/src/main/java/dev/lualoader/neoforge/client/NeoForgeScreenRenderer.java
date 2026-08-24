package dev.lualoader.neoforge.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Desenho de elementos, compartilhado pelas três superfícies do loader.
 *
 * <p>Uma tela própria, o HUD e uma sobreposição sobre tela do jogo desenham exatamente os mesmos
 * elementos; só muda onde fica a origem e o que acontece com um clique.
 *
 * <p>É o par do {@code ScreenRenderer} do adaptador Fabric, e o que sobrou de específico depois que
 * a leitura da descrição e o cálculo de posição foram para o núcleo: daqui para baixo é só chamada
 * de API de desenho. As duas plataformas concordam sobre onde o elemento fica e discordam apenas
 * sobre como pintá-lo.
 */
public final class NeoForgeScreenRenderer {
    private NeoForgeScreenRenderer() {
    }

    /**
     * Como a fonte do cliente mede um texto.
     *
     * <p>É o único dado de plataforma que a geometria do núcleo precisa.
     */
    public static final ScreenLayout.TextMetrics METRICS = new ScreenLayout.TextMetrics() {
        @Override
        public int width(String text) {
            return Minecraft.getInstance().font.width(text);
        }

        @Override
        public int lineHeight() {
            return Minecraft.getInstance().font.lineHeight;
        }
    };

    public static int[] resolve(ScreenModel.Element element, ScreenLayout.Bounds surface,
                                ScreenLayout.Bounds gui) {
        return ScreenLayout.resolve(element, surface, gui, METRICS);
    }

    public static int[] measure(ScreenModel.Element element) {
        return ScreenLayout.measure(element, METRICS);
    }

    public static int cellAt(ScreenModel.Element element, int x, int y, int mouseX, int mouseY) {
        return ScreenLayout.cellAt(element, x, y, mouseX, mouseY, METRICS);
    }

    public static boolean contains(ScreenModel.Element element, int x, int y,
                                   int mouseX, int mouseY) {
        return ScreenLayout.contains(element, x, y, mouseX, mouseY, METRICS);
    }

    /**
     * Desenha um elemento na posição já resolvida.
     *
     * <p>{@code button} e {@code input} não aparecem aqui: são widgets do jogo, criados e desenhados
     * por quem hospeda a superfície, porque precisam de foco e de captura de teclado.
     */
    public static void draw(GuiGraphics graphics, Font font,
                            ScreenModel.Element element, int x, int y) {
        switch (element.type()) {
            case "panel" -> panel(graphics, element, x, y);
            case "label" -> {
                if (element.scale() == 1.0) {
                    graphics.drawString(font, Component.literal(element.text()),
                            x, y, element.color(), element.shadow());
                } else {
                    // A escala multiplica a matriz, entao a posicao precisa ser dividida por ela.
                    // Escala inteira mantem a fonte bitmap nitida; uma fracionaria interpola e
                    // borra, e o arredondamento abaixo evita ainda cair em meio pixel.
                    float scale = (float) element.scale();
                    graphics.pose().pushPose();
                    graphics.pose().scale(scale, scale, 1f);
                    graphics.drawString(font, Component.literal(element.text()),
                            Math.round(x / scale), Math.round(y / scale),
                            element.color(), element.shadow());
                    graphics.pose().popPose();
                }
            }
            case "progress" -> {
                graphics.fill(x, y, x + element.w(), y + element.h(), 0xFF303030);
                int filled = (int) (element.w() * Math.max(0, Math.min(1, element.progress())));
                graphics.fill(x, y, x + filled, y + element.h(), element.color());
            }
            case "item" -> {
                ResourceLocation id = ResourceLocation.tryParse(element.item());
                if (id != null && BuiltInRegistries.ITEM.containsKey(id)) {
                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id), element.count());
                    graphics.renderItem(stack, x, y);
                    graphics.renderItemDecorations(font, stack, x, y);
                } else if (id != null && BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
                    // Um identificador de mob num slot de item: acontece em lista misturada, como
                    // "o que derruba isto", onde a fonte tanto pode ser um bloco quanto um bicho.
                    // Deixar o slot vazio seria pior que desenhar o bicho pequeno ali.
                    drawEntity(graphics, id, x, y, 16, 16);
                }
            }
            case "entity" -> entity(graphics, element, x, y);
            case "grid" -> {
                int columns = Math.max(1, element.columns());
                for (int position = 0; position < element.cells().size(); position++) {
                    ScreenModel.Cell cell = element.cells().get(position);
                    ResourceLocation id = ResourceLocation.tryParse(cell.item());
                    if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;

                    int cellX = x + (position % columns) * element.cell();
                    int cellY = y + (position / columns) * element.cell();

                    ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id), cell.count());
                    graphics.renderItem(stack, cellX, cellY);
                    graphics.renderItemDecorations(font, stack, cellX, cellY);
                }
            }
            case "viewport" -> {
                // O recorte e a moldura sao feitos por quem hospeda a superficie, que conhece o
                // deslocamento da rolagem. Aqui o viewport em si nao desenha nada.
            }
            case "image" -> {
                ResourceLocation texture = ResourceLocation.tryParse(element.texture());
                if (texture != null) {
                    graphics.blit(texture, x, y, 0, 0,
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
    private static void panel(GuiGraphics graphics, ScreenModel.Element element, int x, int y) {
        int right = x + element.w();
        int bottom = y + element.h();
        String style = element.style();

        // Uma divisoria e uma linha de duas cores, e nao um retangulo: escura primeiro e clara
        // depois, como um sulco. Duas cores porque uma linha chapada sobre painel cinza some.
        if (style.equals("divider")) {
            boolean horizontal = element.w() >= element.h();
            int dark = element.borderDark();
            int light = element.borderLight();

            if (horizontal) {
                graphics.fill(x, y, right, y + 1, dark);
                graphics.fill(x, y + 1, right, y + 2, light);
            } else {
                graphics.fill(x, y, x + 1, bottom, dark);
                graphics.fill(x + 1, y, x + 2, bottom, light);
            }
            return;
        }

        // As cores do jogo, para um painel sair igual ao do inventário sem o mod declarar nada.
        int background = switch (style) {
            case "vanilla" -> element.color() == 0xFFFFFFFF ? 0xFFC6C6C6 : element.color();
            case "slot", "inset" -> element.color() == 0xFFFFFFFF ? 0xFF8B8B8B : element.color();
            default -> element.color();
        };
        graphics.fill(x, y, right, bottom, background);

        if (style.equals("flat")) return;

        int thickness = Math.max(1, Math.min(element.border(), Math.min(element.w(), element.h()) / 2));
        boolean sunken = style.equals("slot") || style.equals("inset");

        int light = sunken ? element.borderDark() : element.borderLight();
        int dark = sunken ? element.borderLight() : element.borderDark();

        // Cima e esquerda recebem a luz; baixo e direita, a sombra. Invertido, o retângulo afunda.
        graphics.fill(x, y, right, y + thickness, light);
        graphics.fill(x, y, x + thickness, bottom, light);
        graphics.fill(x, bottom - thickness, right, bottom, dark);
        graphics.fill(right - thickness, y, right, bottom, dark);
    }

    /**
     * Cache de entidades para desenho.
     *
     * <p>Uma entidade precisa existir para ser desenhada, e cria-la a cada quadro seria caro e
     * geraria lixo sem parar. Elas nunca entram no mundo: servem so de modelo para o renderizador.
     */
    private static final Map<ResourceLocation, LivingEntity> ENTITIES = new HashMap<>();

    private static void entity(GuiGraphics graphics, ScreenModel.Element element, int x, int y) {
        ResourceLocation id = ResourceLocation.tryParse(element.entity());
        if (id == null) return;

        drawEntity(graphics, id, x, y, Math.max(8, element.w()), Math.max(8, element.h()));
    }

    /**
     * Desenha uma entidade viva dentro de um retangulo.
     *
     * <p>Cai para o ovo de spawn quando a entidade nao pode ser desenhada -- um tipo que nao e
     * LivingEntity, ou um mod que recusa criar a instancia fora do mundo. Um icone aproximado diz
     * mais que um espaco vazio.
     */
    private static void drawEntity(GuiGraphics graphics, ResourceLocation id, int x, int y,
                                   int width, int height) {
        LivingEntity living = livingEntity(id);
        if (living == null) {
            drawItemIcon(graphics, Minecraft.getInstance().font,
                    id.getNamespace() + ":" + id.getPath() + "_spawn_egg", x, y);
            return;
        }

        // O tamanho e escolhido para o bicho caber na altura pedida, e nao o contrario: um Enderman
        // e um galinha tem alturas muito diferentes, e uma escala fixa deixaria um deles fora.
        float tall = Math.max(0.5f, living.getBbHeight());
        int size = (int) (height / tall * 0.9f);

        InventoryScreen.renderEntityInInventoryFollowsAngle(
                graphics, x, y, x + width, y + height, Math.max(4, size), 0.0f,
                x + width / 2f, y + height / 2f, living);
    }

    private static LivingEntity livingEntity(ResourceLocation id) {
        if (ENTITIES.containsKey(id)) return ENTITIES.get(id);

        LivingEntity created = null;
        try {
            var level = Minecraft.getInstance().level;
            var type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (level != null && type != null && type.create(level) instanceof LivingEntity living) {
                created = living;
            }
        } catch (RuntimeException ignored) {
            // Um mod pode recusar criar a entidade fora do mundo. Guardar o nulo evita tentar de
            // novo a cada quadro, e o desenho cai para o ovo de spawn.
            created = null;
        }

        ENTITIES.put(id, created);
        return created;
    }

    /** As entidades guardadas apontam para um mundo; trocar de mundo torna todas invalidas. */
    public static void forgetEntities() {
        ENTITIES.clear();
    }

    /** Desenha o ícone de um item, sem quantidade. Usado por elementos que só ilustram. */
    public static void drawItemIcon(GuiGraphics graphics, Font font, String itemId, int x, int y) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return;

        graphics.renderItem(new ItemStack(BuiltInRegistries.ITEM.get(id)), x, y);
    }

    /**
     * Desenha a caixa de ajuda do elemento sob o cursor, se houver.
     *
     * <p>Precisa acontecer depois de todo o resto, senão o elemento seguinte cobriria a caixa.
     *
     * @param tooltip conteúdo do campo {@code tooltip}, com {@code \n} separando linhas
     */
    public static void drawTooltip(GuiGraphics graphics, Font font,
                                   String tooltip, int mouseX, int mouseY) {
        if (tooltip == null || tooltip.isBlank()) return;

        List<Component> lines = new ArrayList<>();
        for (String line : tooltip.split("\n", -1)) lines.add(Component.literal(line));

        graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
    }

    /**
     * Texto de ajuda do elemento sob o cursor, ou {@code null}.
     *
     * <p>Uma grade responde pela célula apontada, e não pela grade inteira: é o que faz o nome do
     * item aparecer ao passar por ele, como acontece no inventário.
     */
    public static String tooltipAt(ScreenModel.Element element, int x, int y,
                                   int mouseX, int mouseY) {
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
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return itemId;

        // A quebra e sempre a mesma que drawTooltip divide. O separador do sistema traria um
        // retorno de carro no Windows, que viraria um caractere solto na caixa de ajuda.
        return new ItemStack(BuiltInRegistries.ITEM.get(id)).getHoverName().getString()
                + "\n" + itemId;
    }
}
