package dev.lualoader.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
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

    /**
     * Como a fonte do cliente mede um texto.
     *
     * <p>É o único dado de plataforma que a geometria do núcleo precisa, e é por isso que ela pôde
     * sair daqui: o resto do cálculo é aritmética sobre a descrição.
     */
    public static final ScreenLayout.TextMetrics METRICS = new ScreenLayout.TextMetrics() {
        @Override
        public int width(String text) {
            return MinecraftClient.getInstance().textRenderer.getWidth(text);
        }

        @Override
        public int lineHeight() {
            return MinecraftClient.getInstance().textRenderer.fontHeight;
        }
    };

    /** Resolve a posição final de um elemento. */
    public static int[] resolve(ScreenModel.Element element, ScreenLayout.Bounds surface,
                                ScreenLayout.Bounds gui) {
        return ScreenLayout.resolve(element, surface, gui, METRICS);
    }

    /** Largura e altura ocupadas por um elemento. */
    public static int[] measure(ScreenModel.Element element) {
        return ScreenLayout.measure(element, METRICS);
    }

    /**
     * Desenha um elemento na posição já resolvida.
     *
     * <p>{@code button} e {@code input} não aparecem aqui: são widgets do jogo, criados e desenhados
     * por quem hospeda a superfície, porque precisam de foco e de captura de teclado.
     */
    public static void draw(DrawContext context, TextRenderer textRenderer,
                            ScreenModel.Element element, int x, int y) {
        // A camada vira profundidade, e nao ordem de desenho.
        //
        // A ordem da lista nao basta: o jogo desenha icone de item com deslocamento proprio de
        // cerca de cem, entao um item do fundo passa por cima de qualquer retangulo desenhado
        // depois. O degrau e maior que esse deslocamento justamente para o painel de cima cobrir o
        // item de baixo.
        if (element.layer() > 0) {
            context.getMatrices().push();
            context.getMatrices().translate(0f, 0f, element.layer() * 250f);
        }
        try {
            desenhar(context, textRenderer, element, x, y);
        } finally {
            if (element.layer() > 0) context.getMatrices().pop();
        }
    }

    private static void desenhar(DrawContext context, TextRenderer textRenderer,
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
                    // Zero vira um: uma pilha de zero e considerada vazia pelo jogo e nao
                    // desenha nada, e o mod que escreveu zero queria o icone sem numero -- que e o
                    // que um vale. Sumir em silencio e o pior desfecho.
                    ItemStack stack = new ItemStack(Registries.ITEM.get(id),
                            Math.max(1, element.count()));
                    context.drawItem(stack, x, y);
                    context.drawItemInSlot(textRenderer, stack, x, y);
                } else if (id != null && Registries.ENTITY_TYPE.containsId(id)) {
                    // Um identificador de mob num slot de item: acontece em lista misturada, como
                    // "o que derruba isto", onde a fonte tanto pode ser um bloco quanto um bicho.
                    // Deixar o slot vazio seria pior que desenhar o bicho pequeno ali.
                    drawEntity(context, id, x, y, 16, 16);
                }
            }
            case "entity" -> entity(context, element, x, y);
            case "grid" -> {
                int columns = Math.max(1, element.columns());
                for (int position = 0; position < element.cells().size(); position++) {
                    ScreenModel.Cell cell = element.cells().get(position);
                    Identifier id = Identifier.tryParse(cell.item());
                    if (id == null || !Registries.ITEM.containsId(id)) continue;

                    int cellX = x + (position % columns) * element.cell();
                    int cellY = y + (position / columns) * element.cell();

                    ItemStack stack = new ItemStack(Registries.ITEM.get(id), Math.max(1, cell.count()));
                    context.drawItem(stack, cellX, cellY);
                    context.drawItemInSlot(textRenderer, stack, cellX, cellY);
                }
            }
            case "viewport" -> {
                // O recorte e a moldura sao feitos por quem hospeda a superficie, que conhece o
                // deslocamento da rolagem. Aqui o viewport em si nao desenha nada.
            }
            case "map" -> map(context, textRenderer, element, x, y);
            case "image" -> {
                Identifier texture = Identifier.tryParse(element.texture());
                if (texture != null) {
                    // O recorte vem do elemento, e a folha tambem: e o que permite usar a arte de
                    // interface como ela e distribuida -- uma folha unica com tudo dentro.
                    context.drawTexture(texture, x, y, element.u(), element.v(),
                            element.w(), element.h(),
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
     * Desenha uma moldura de nove pedacos a partir de uma folha.
     *
     * <p>Os quatro cantos saem inteiros, as quatro bordas esticam num eixo so, e o miolo estica nos
     * dois. E o que permite a mesma arte servir a uma tela de qualquer tamanho -- esticar a imagem
     * inteira deformaria o canto arredondado e engordaria a linha da borda.
     *
     * <p>As bordas sao por lado porque a arte de mod costuma ser assimetrica: a moldura do Logistic
     * Pipes tem o pe mais alto que o topo, para caber o texto que ele desenha ali.
     */
    private static void moldura(DrawContext context, ScreenModel.Element element, int x, int y) {
        String folha = element.texture();
        if (folha == null || folha.isEmpty()) return;

        Identifier textura = Identifier.tryParse(folha);
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

        esticar(context, textura, x, y, su, sv, esquerda, cima, esquerda, cima, folhaL, folhaA);
        esticar(context, textura, x + element.w() - direita, y, su + sw - direita, sv,
                direita, cima, direita, cima, folhaL, folhaA);
        esticar(context, textura, x, y + element.h() - baixo, su, sv + sh - baixo,
                esquerda, baixo, esquerda, baixo, folhaL, folhaA);
        esticar(context, textura, x + element.w() - direita, y + element.h() - baixo,
                su + sw - direita, sv + sh - baixo, direita, baixo, direita, baixo, folhaL, folhaA);

        if (miolaL > 0) {
            esticar(context, textura, x + esquerda, y, su + esquerda, sv,
                    miolaL, cima, fonteL, cima, folhaL, folhaA);
            esticar(context, textura, x + esquerda, y + element.h() - baixo,
                    su + esquerda, sv + sh - baixo, miolaL, baixo, fonteL, baixo, folhaL, folhaA);
        }
        if (miolaA > 0) {
            esticar(context, textura, x, y + cima, su, sv + cima,
                    esquerda, miolaA, esquerda, fonteA, folhaL, folhaA);
            esticar(context, textura, x + element.w() - direita, y + cima,
                    su + sw - direita, sv + cima, direita, miolaA, direita, fonteA, folhaL, folhaA);
        }
        if (miolaL > 0 && miolaA > 0) {
            esticar(context, textura, x + esquerda, y + cima, su + esquerda, sv + cima,
                    miolaL, miolaA, fonteL, fonteA, folhaL, folhaA);
        }
    }

    /** Um pedaco da folha esticado para um retangulo do tamanho pedido. */
    private static void esticar(DrawContext context, Identifier textura,
                                int x, int y, int u, int v, int larguraDestino, int alturaDestino,
                                int larguraFonte, int alturaFonte, int folhaL, int folhaA) {
        if (larguraDestino <= 0 || alturaDestino <= 0) return;
        context.drawTexture(textura, x, y, larguraDestino, alturaDestino,
                (float) u, (float) v, larguraFonte, alturaFonte, folhaL, folhaA);
    }

    /**
     * A janela do jogo desenhada por regra, para quem chama de fora do modelo de tela.
     *
     * <p>Existe para a janela declarada: um mod que nao trouxe arte ainda merece uma tela
     * apresentavel, e repetir o bisel la daria duas versoes do mesmo cinza.
     */
    public static void vanillaPanel(DrawContext context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xFFC6C6C6);
        context.fill(x, y, x + w, y + 2, 0xFFFFFFFF);
        context.fill(x, y, x + 2, y + h, 0xFFFFFFFF);
        context.fill(x, y + h - 2, x + w, y + h, 0xFF555555);
        context.fill(x + w - 2, y, x + w, y + h, 0xFF555555);
    }

    /** O encaixe de um slot: o mesmo bisel, invertido, que o jogo usa no inventario. */
    public static void slotWell(DrawContext context, int x, int y, int w, int h) {
        context.fill(x, y, x + w, y + h, 0xFF8B8B8B);
        context.fill(x, y, x + w, y + 1, 0xFF373737);
        context.fill(x, y, x + 1, y + h, 0xFF373737);
        context.fill(x, y + h - 1, x + w, y + h, 0xFFFFFFFF);
        context.fill(x + w - 1, y, x + w, y + h, 0xFFFFFFFF);
    }

    private static void panel(DrawContext context, ScreenModel.Element element, int x, int y) {
        // Um painel com arte propria e desenhado em nove pedacos, e nao pela regra de bisel: quem
        // trouxe a imagem quer a imagem.
        if (element.style().equals("sheet")) {
            moldura(context, element, x, y);
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
                context.fill(x, y, right, y + 1, dark);
                context.fill(x, y + 1, right, y + 2, light);
            } else {
                context.fill(x, y, x + 1, bottom, dark);
                context.fill(x + 1, y, x + 2, bottom, light);
            }
            return;
        }

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

    /** Desenha a grelha compacta e os marcadores do minimapa sem criar um elemento por célula. */
    private static void map(DrawContext context, TextRenderer textRenderer,
                            ScreenModel.Element element, int x, int y) {
        int width = Math.max(1, element.w());
        int height = Math.max(1, element.h());
        int columns = Math.max(1, element.mapColumns());
        int rows = Math.max(1, element.mapRows());
        int cellWidth = Math.max(1, width / columns);
        int cellHeight = Math.max(1, height / rows);
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        double radiusX = width / 2.0;
        double radiusY = height / 2.0;

        // A sombra exterior dá profundidade sem depender de uma textura do mod.
        context.fill(x - 3, y - 3, x + width + 3, y + height + 3, 0xD90A0E14);
        context.fill(x - 1, y - 1, x + width + 1, y + height + 1, 0xFF53606B);

        boolean textured = "client_topdown".equals(element.mapRender())
                || "client_camera".equals(element.mapRender());
        if (textured) {
            TopDownMapRenderer.draw(context, element, x, y);
        }

        List<ScreenModel.MapCell> cells = element.mapCells();
        if (!textured) for (int row = 0; row < rows; row++) {
            for (int column = 0; column < columns; column++) {
                double cellCenterX = x + (column + 0.5) * width / columns;
                double cellCenterY = y + (row + 0.5) * height / rows;
                if (element.mapRound() && !insideEllipse(cellCenterX, cellCenterY,
                        centerX, centerY, radiusX, radiusY)) continue;
                int position = row * columns + column;
                if (position >= cells.size()) continue;
                int color = cells.get(position).color();
                if ((color >>> 24) == 0) continue;
                int left = x + column * width / columns;
                int top = y + row * height / rows;
                int right = x + (column + 1) * width / columns;
                int bottom = y + (row + 1) * height / rows;
                context.fill(left, top, right, bottom, color);
                if (element.mapGrid() && cellWidth >= 3 && cellHeight >= 3) {
                    context.fill(left, top, right, top + 1, 0x30304050);
                    context.fill(left, top, left + 1, bottom, 0x30304050);
                }
            }
        }

        if (element.mapRound()) {
            drawEllipseCorners(context, x, y, width, height);
        }

        for (ScreenModel.MapMarker marker : element.mapMarkers()) {
            int markerX = x + (int) Math.round(marker.x() * Math.max(0, width - 1));
            int markerY = y + (int) Math.round(marker.z() * Math.max(0, height - 1));
            if (element.mapRound() && !insideEllipse(markerX, markerY,
                    centerX, centerY, radiusX, radiusY)) continue;
            drawMapMarker(context, textRenderer, element, marker, markerX, markerY,
                    x, y, width, height);
        }

        String north = element.mapNorth();
        if (north != null && !north.isBlank()) {
            int labelX = centerX - textRenderer.getWidth(north) / 2;
            context.drawText(textRenderer, Text.literal(north), labelX, y + 3,
                    0xFFFFFFFF, true);
        }
    }

    private static boolean insideEllipse(double px, double py, double cx, double cy,
                                         double radiusX, double radiusY) {
        double dx = (px - cx) / Math.max(1.0, radiusX - 1.0);
        double dy = (py - cy) / Math.max(1.0, radiusY - 1.0);
        return dx * dx + dy * dy <= 1.0;
    }

    private static void drawEllipseCorners(DrawContext context, int x, int y, int width, int height) {
        int right = x + width;
        int bottom = y + height;
        context.fill(x, y, x + 2, y + 2, 0xFF0A0E14);
        context.fill(right - 2, y, right, y + 2, 0xFF0A0E14);
        context.fill(x, bottom - 2, x + 2, bottom, 0xFF0A0E14);
        context.fill(right - 2, bottom - 2, right, bottom, 0xFF0A0E14);
    }

    private static void drawMapMarker(DrawContext context, TextRenderer textRenderer,
                                      ScreenModel.Element element, ScreenModel.MapMarker marker,
                                      int x, int y, int mapX, int mapY, int mapWidth, int mapHeight) {
        int color = marker.color();
        switch (marker.type()) {
            case "player" -> {
                context.fill(x - 3, y - 3, x + 4, y + 4, 0xCC101318);
                context.fill(x - 2, y - 2, x + 3, y + 3, color);
                double directionX = element.mapDirectionX();
                double directionZ = element.mapDirectionZ();
                double length = Math.sqrt(directionX * directionX + directionZ * directionZ);
                if (length > 0.01) {
                    int tipX = x + (int) Math.round(directionX / length * 7);
                    int tipY = y + (int) Math.round(directionZ / length * 7);
                    context.fill(tipX - 1, tipY - 1, tipX + 2, tipY + 2, color);
                }
            }
            case "entity" -> {
                context.fill(x - 3, y - 3, x + 4, y + 4, 0xCC101318);
                context.fill(x - 2, y - 2, x + 3, y + 3, color);
            }
            default -> {
                context.fill(x - 3, y - 3, x + 4, y + 4, 0xCC101318);
                context.fill(x - 2, y - 4, x + 3, y + 3, color);
                context.fill(x - 1, y - 6, x + 2, y - 3, color);
                if (marker.label() != null && !marker.label().isBlank()) {
                    int labelX = Math.min(mapX + mapWidth - textRenderer.getWidth(marker.label()) - 2,
                            Math.max(mapX + 2, x + 5));
                    int labelY = Math.max(mapY + 2, y - 8);
                    context.drawText(textRenderer, Text.literal(marker.label()), labelX, labelY,
                            0xFFFFFFFF, true);
                }
            }
        }
    }

    /**
     * Cache de entidades para desenho.
     *
     * <p>Uma entidade precisa existir para ser desenhada, e cria-la a cada quadro seria caro e
     * geraria lixo sem parar. Elas nunca entram no mundo: servem so de modelo para o renderizador.
     */
    private static final java.util.Map<Identifier, net.minecraft.entity.LivingEntity> ENTITIES =
            new java.util.HashMap<>();

    private static void entity(DrawContext context, ScreenModel.Element element, int x, int y) {
        Identifier id = Identifier.tryParse(element.entity());
        if (id == null) return;

        drawEntity(context, id, x, y, Math.max(8, element.w()), Math.max(8, element.h()));
    }

    /**
     * Desenha uma entidade viva dentro de um retangulo.
     *
     * <p>Cai para o ovo de spawn quando a entidade nao pode ser desenhada -- um tipo que nao e
     * LivingEntity, ou um mod que recusa criar a instancia fora do mundo. Um icone aproximado diz
     * mais que um espaco vazio.
     */
    private static void drawEntity(DrawContext context, Identifier id, int x, int y,
                                   int width, int height) {
        net.minecraft.entity.LivingEntity living = livingEntity(id);
        if (living == null) {
            drawItemIcon(context, MinecraftClient.getInstance().textRenderer,
                    id.getNamespace() + ":" + id.getPath() + "_spawn_egg", x, y);
            return;
        }

        // O tamanho e escolhido para o bicho caber na altura pedida, e nao o contrario: um Enderman
        // e um galinha tem alturas muito diferentes, e uma escala fixa deixaria um deles fora.
        float tall = Math.max(0.5f, living.getHeight());
        int size = (int) (height / tall * 0.9f);

        net.minecraft.client.gui.screen.ingame.InventoryScreen.drawEntity(
                context, x, y, x + width, y + height, Math.max(4, size), 0.0f,
                x + width / 2f, y + height / 2f, living);
    }

    private static net.minecraft.entity.LivingEntity livingEntity(Identifier id) {
        if (ENTITIES.containsKey(id)) return ENTITIES.get(id);

        net.minecraft.entity.LivingEntity created = null;
        try {
            var world = MinecraftClient.getInstance().world;
            var type = Registries.ENTITY_TYPE.get(id);
            if (world != null && type != null) {
                var entity = type.create(world);
                if (entity instanceof net.minecraft.entity.LivingEntity alive) created = alive;
            }
        } catch (RuntimeException ignored) {
            // Uma entidade de mod pode recusar existir fora do mundo. O ovo de spawn cobre o caso.
        }

        ENTITIES.put(id, created);
        return created;
    }

    /** Esquece as entidades de desenho. Chamado ao sair do mundo, que as invalida. */
    public static void forgetEntities() {
        ENTITIES.clear();
    }

    /** Desenha o ícone de um item, sem quantidade. Usado por elementos que só ilustram. */
    public static void drawItemIcon(DrawContext context, TextRenderer textRenderer,
                                    String itemId, int x, int y) {
        Identifier id = Identifier.tryParse(itemId);
        if (id == null || !Registries.ITEM.containsId(id)) return;

        context.drawItem(new ItemStack(Registries.ITEM.get(id)), x, y);
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
        return ScreenLayout.cellAt(element, x, y, mouseX, mouseY, METRICS);
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
        return ScreenLayout.contains(element, x, y, mouseX, mouseY, METRICS);
    }
}
