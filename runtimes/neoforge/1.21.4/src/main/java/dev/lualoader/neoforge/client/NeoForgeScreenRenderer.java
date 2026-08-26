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
        // A camada vira profundidade, e nao ordem de desenho.
        //
        // A ordem da lista nao basta: o jogo desenha icone de item com deslocamento proprio de
        // cerca de cem, entao um item do fundo passa por cima de qualquer retangulo desenhado
        // depois. O degrau e maior que esse deslocamento justamente para o painel de cima cobrir o
        // item de baixo.
        if (element.layer() > 0) {
            graphics.pose().pushPose();
            graphics.pose().translate(0f, 0f, element.layer() * 250f);
        }
        try {
            desenhar(graphics, font, element, x, y);
        } finally {
            if (element.layer() > 0) graphics.pose().popPose();
        }
    }

    private static void desenhar(GuiGraphics graphics, Font font,
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
                    // Zero vira um: uma pilha de zero e considerada vazia pelo jogo e nao
                    // desenha nada, e o mod que escreveu zero queria o icone sem numero -- que e o
                    // que um vale. Sumir em silencio e o pior desfecho.
                    ItemStack stack = new ItemStack(
                            BuiltInRegistries.ITEM.getOptional(id).orElse(net.minecraft.world.item.Items.AIR),
                            Math.max(1, element.count()));
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

                    ItemStack stack = new ItemStack(
                            BuiltInRegistries.ITEM.getOptional(id).orElse(net.minecraft.world.item.Items.AIR),
                            Math.max(1, cell.count()));
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
                    // O recorte vem do elemento, e a folha tambem: e o que permite usar a arte de
                    // interface como ela e distribuida -- uma folha unica com tudo dentro.
                    graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured,
                            texture, x, y, element.u(), element.v(), element.w(), element.h(),
                            element.sheetWidth(), element.sheetHeight());
                }
            }
            default -> {
                // Tipo desconhecido: ignorado de proposito, para uma tela nova nao quebrar em um
                // cliente antigo.
            }
        }
    }


    /**
     * Desenha uma moldura de nove pedacos a partir de uma folha.
     *
     * <p>Os quatro cantos saem inteiros, as quatro bordas esticam num eixo so, e o miolo estica nos
     * dois. E o que permite a mesma arte servir a uma tela de qualquer tamanho -- esticar a imagem
     * inteira deformaria o canto arredondado e engordaria a linha da borda.
     *
     * <p>As bordas sao por lado porque a arte de mod costuma ser assimetrica: a moldura do Logistic
     * Pipes tem o pe mais alto que o topo, para caber o texto que ele desenha ali.
     */
    private static void moldura(GuiGraphics graphics, ScreenModel.Element element, int x, int y) {
        String folha = element.texture();
        if (folha == null || folha.isEmpty()) return;

        ResourceLocation textura = ResourceLocation.tryParse(folha);
        if (textura == null) return;

        int su = element.u();
        int sv = element.v();
        int sw = element.sourceWidth() > 0 ? element.sourceWidth() : element.w();
        int sh = element.sourceHeight() > 0 ? element.sourceHeight() : element.h();

        // As bordas nao podem somar mais que o destino, senao os cantos se sobrepoem e o miolo
        // recebe largura negativa -- que no jogo aparece como a textura espelhada.
        int cima = Math.min(element.borderTop(), Math.min(sh, element.h()) / 2);
        int baixo = Math.min(element.borderBottom(), Math.min(sh, element.h()) / 2);
        int esquerda = Math.min(element.borderLeft(), Math.min(sw, element.w()) / 2);
        int direita = Math.min(element.borderRight(), Math.min(sw, element.w()) / 2);

        int miolaL = Math.max(0, element.w() - esquerda - direita);
        int miolaA = Math.max(0, element.h() - cima - baixo);
        int fonteL = Math.max(1, sw - esquerda - direita);
        int fonteA = Math.max(1, sh - cima - baixo);

        int folhaL = element.sheetWidth();
        int folhaA = element.sheetHeight();

        // Cantos, no tamanho original.
        esticar(graphics, textura, x, y, su, sv, esquerda, cima, esquerda, cima, folhaL, folhaA);
        esticar(graphics, textura, x + element.w() - direita, y, su + sw - direita, sv,
                direita, cima, direita, cima, folhaL, folhaA);
        esticar(graphics, textura, x, y + element.h() - baixo, su, sv + sh - baixo,
                esquerda, baixo, esquerda, baixo, folhaL, folhaA);
        esticar(graphics, textura, x + element.w() - direita, y + element.h() - baixo,
                su + sw - direita, sv + sh - baixo, direita, baixo, direita, baixo, folhaL, folhaA);

        // Bordas: cada uma estica num eixo so.
        if (miolaL > 0) {
            esticar(graphics, textura, x + esquerda, y, su + esquerda, sv,
                    miolaL, cima, fonteL, cima, folhaL, folhaA);
            esticar(graphics, textura, x + esquerda, y + element.h() - baixo,
                    su + esquerda, sv + sh - baixo, miolaL, baixo, fonteL, baixo, folhaL, folhaA);
        }
        if (miolaA > 0) {
            esticar(graphics, textura, x, y + cima, su, sv + cima,
                    esquerda, miolaA, esquerda, fonteA, folhaL, folhaA);
            esticar(graphics, textura, x + element.w() - direita, y + cima,
                    su + sw - direita, sv + cima, direita, miolaA, direita, fonteA, folhaL, folhaA);
        }

        // E o miolo, esticado nos dois.
        if (miolaL > 0 && miolaA > 0) {
            esticar(graphics, textura, x + esquerda, y + cima, su + esquerda, sv + cima,
                    miolaL, miolaA, fonteL, fonteA, folhaL, folhaA);
        }
    }

    /** Um pedaco da folha esticado para um retangulo do tamanho pedido. */
    private static void esticar(GuiGraphics graphics, ResourceLocation textura,
                                int x, int y, int u, int v, int larguraDestino, int alturaDestino,
                                int larguraFonte, int alturaFonte, int folhaL, int folhaA) {
        if (larguraDestino <= 0 || alturaDestino <= 0) return;
        graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured,
                textura, x, y, u, v, larguraDestino, alturaDestino,
                larguraFonte, alturaFonte, folhaL, folhaA);
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
    /**
     * A janela do jogo desenhada por regra, para quem chama de fora do modelo de tela.
     *
     * <p>Existe para a janela declarada: um mod que nao trouxe arte ainda merece uma tela
     * apresentavel, e repetir o bisel la daria duas versoes do mesmo cinza.
     */
    public static void vanillaPanel(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        graphics.fill(x, y, x + w, y + 2, 0xFFFFFFFF);
        graphics.fill(x, y, x + 2, y + h, 0xFFFFFFFF);
        graphics.fill(x, y + h - 2, x + w, y + h, 0xFF555555);
        graphics.fill(x + w - 2, y, x + w, y + h, 0xFF555555);
    }

    /** O encaixe de um slot: o mesmo bisel, invertido, que o jogo usa no inventario. */
    public static void slotWell(GuiGraphics graphics, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        graphics.fill(x, y, x + w, y + 1, 0xFF373737);
        graphics.fill(x, y, x + 1, y + h, 0xFF373737);
        graphics.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        graphics.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
    }

    private static void panel(GuiGraphics graphics, ScreenModel.Element element, int x, int y) {
        // Um painel com arte propria e desenhado em nove pedacos, e nao pela regra de bisel: quem
        // trouxe a imagem quer a imagem.
        if (element.style().equals("sheet")) {
            moldura(graphics, element, x, y);
            return;
        }

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
            var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
            if (level != null && type != null
                    && type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND)
                    instanceof LivingEntity living) {
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

        graphics.renderItem(new ItemStack(
                BuiltInRegistries.ITEM.getOptional(id).orElse(net.minecraft.world.item.Items.AIR)), x, y);
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
        return new ItemStack(BuiltInRegistries.ITEM.getOptional(id)
                .orElse(net.minecraft.world.item.Items.AIR)).getHoverName().getString()
                + "\n" + itemId;
    }
}
