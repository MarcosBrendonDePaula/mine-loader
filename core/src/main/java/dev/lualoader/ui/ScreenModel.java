package dev.lualoader.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Descrição de tela recebida do servidor, já em forma utilizável pelo renderizador.
 *
 * <p>Nada aqui executa: é apenas leitura de dados. Um elemento de tipo desconhecido é mantido na
 * lista com o tipo cru e ignorado no desenho — assim um cliente com versão mais antiga abre a tela
 * sem os elementos que não entende, em vez de uma tela quebrada.
 */
public final class ScreenModel {
    /** Uma célula de grade: um item, sua quantidade e o texto de ajuda. */
    public record Cell(String item, int count, String tooltip) {
    }

    /** Uma célula de mapa já convertida para ARGB pelo core. */
    public record MapCell(int color) {
    }

    /** Marcador em coordenadas normalizadas no rectângulo do mapa. */
    public record MapMarker(String type, String label, double x, double z, int color) {
    }

    /** Um elemento e seus atributos, todos opcionais fora do tipo. */
    public record Element(String type, String id, int x, int y, int w, int h, String anchor,
                          String text, String value, String item, String texture, String tooltip,
                          int color, int count, double progress, double scale,
                          String group, int columns, int cell, int content, List<Cell> cells,
                          String style, int border, int borderLight, int borderDark,
                          boolean shadow, String entity,
                          int u, int v, int sheetWidth, int sheetHeight,
                          int sourceWidth, int sourceHeight,
                          int borderTop, int borderRight, int borderBottom, int borderLeft,
                          int layer,
                          List<MapCell> mapCells, List<MapMarker> mapMarkers,
                          int mapColumns, int mapRows,
                          double mapDirectionX, double mapDirectionZ,
                          boolean mapRound, boolean mapGrid, String mapNorth,
                          String mapRender, int mapResolution, int mapRadius,
                          int mapUpdateTicks, String mapRotate, String mapCamera) {
    }

    private final String title;
    private final String target;
    private final int width;
    private final int height;
    private final boolean blur;
    private final boolean dim;
    private final List<Element> elements;

    private ScreenModel(String title, String target, int width, int height, boolean blur,
                        boolean dim, List<Element> elements) {
        this.title = title;
        this.target = target;
        this.width = width;
        this.height = height;
        this.blur = blur;
        this.dim = dim;
        this.elements = elements;
    }

    /** Se o mundo atras deve ser desfocado. */
    public boolean blur() {
        return blur;
    }

    /** Se o mundo atras deve ser escurecido. */
    public boolean dim() {
        return dim;
    }

    public String title() {
        return title;
    }

    /**
     * Tela do jogo sobre a qual desenhar, em uma sobreposição.
     *
     * <p>Vazio numa tela própria e num HUD, que não se prendem a nada.
     */
    public String target() {
        return target;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public List<Element> elements() {
        return elements;
    }

    /** Lê a descrição. Devolve {@code null} quando o texto não é uma tela válida. */
    public static ScreenModel parse(String json) {
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) return null;
            JsonObject object = root.getAsJsonObject();

            return new ScreenModel(
                    text(object, "title", ""),
                    text(object, "target", ""),
                    integer(object, "width", 256),
                    integer(object, "height", 166),
                    bool(object, "blur", false),
                    bool(object, "dim", true),
                    elements(object));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static List<Element> elements(JsonObject object) {
        List<Element> list = new ArrayList<>();
        if (!object.has("elements") || !object.get("elements").isJsonArray()) return list;

        JsonArray array = object.getAsJsonArray("elements");
        for (JsonElement entry : array) {
            if (!entry.isJsonObject()) continue;
            JsonObject element = entry.getAsJsonObject();

            list.add(new Element(
                    text(element, "type", ""),
                    text(element, "id", ""),
                    integer(element, "x", 0),
                    integer(element, "y", 0),
                    integer(element, "w", 0),
                    integer(element, "h", 0),
                    text(element, "anchor", ""),
                    text(element, "text", ""),
                    text(element, "value", ""),
                    text(element, "item", ""),
                    text(element, "texture", ""),
                    text(element, "tooltip", ""),
                    color(element),
                    integer(element, "count", 1),
                    decimal(element, "progress", 0.0),
                    decimal(element, "scale", 1.0),
                    text(element, "group", ""),
                    integer(element, "columns", 9),
                    integer(element, "cell", 18),
                    integer(element, "content", 0),
                    cells(element),
                    text(element, "style", "flat"),
                    integer(element, "border", 2),
                    colorOf(element, "border_light", 0xFFFFFFFF),
                    colorOf(element, "border_dark", 0xFF555555),
                    bool(element, "shadow", true),
                    text(element, "entity", ""),
                    // O recorte de uma imagem, e a folha de onde ela sai.
                    //
                    // Sem isto o unico desenho possivel e o PNG inteiro com o tamanho exato do
                    // arquivo -- e uma folha de interface do jogo tem 256x256 com o painel num
                    // canto. Um mod que quisesse a propria arte de tela tinha que recortar o PNG
                    // em pedacos, um arquivo por elemento.
                    //
                    // O padrao da folha e 256, que e o tamanho de toda folha de GUI do jogo: quem
                    // usar o formato de sempre nao precisa dizer nada.
                    integer(element, "u", 0),
                    integer(element, "v", 0),
                    integer(element, "sheet_w", 256),
                    integer(element, "sheet_h", 256),
                    // O recorte na folha, quando ele nao tem o tamanho do elemento na tela.
                    //
                    // Sao coisas diferentes e ate aqui eram a mesma: uma moldura de 256x199 pode
                    // aparecer com 195 de largura, e para isso o desenho tem que ser em nove
                    // pedacos -- cantos inteiros, bordas esticadas num eixo, miolo nos dois.
                    integer(element, "sw", integer(element, "w", 0)),
                    integer(element, "sh", integer(element, "h", 0)),
                    // A espessura de cada borda. O padrao de cada uma e a borda geral: uma moldura
                    // simetrica diz um numero so, e a do Logistic Pipes -- que tem o pe mais alto
                    // que o topo -- diz os que precisa.
                    integer(element, "border_top", integer(element, "border", 2)),
                    integer(element, "border_right", integer(element, "border", 2)),
                    integer(element, "border_bottom", integer(element, "border", 2)),
                    integer(element, "border_left", integer(element, "border", 2)),
                    // A camada, para um painel ficar por cima do que veio antes.
                    //
                    // A ordem da lista nao basta: o jogo desenha icone de item com deslocamento de
                    // profundidade proprio, entao um item do fundo passa por cima de qualquer
                    // retangulo desenhado depois. Sem uma camada, uma janela sobreposta e
                    // impossivel -- e uma janela sobreposta e o jeito natural de configurar uma
                    // coisa sem perder de vista o resto.
                    integer(element, "layer", 0),
                    mapCells(element), mapMarkers(element),
                    integer(element, "map_columns", 0), integer(element, "map_rows", 0),
                    decimal(element, "map_direction_x", 0.0), decimal(element, "map_direction_z", 0.0),
                    bool(element, "map_round", false), bool(element, "map_grid", false),
                    text(element, "map_north", "N"),
                    text(element, "map_render", "server_cells"),
                    integer(element, "map_resolution", 0),
                    integer(element, "map_radius", 0),
                    integer(element, "map_update_ticks", 0),
                    text(element, "map_rotate", "north"),
                    text(element, "map_camera", "")));
        }
        return list;
    }

    /** Lê as células compactas do mapa. Vazia quando o elemento não é um mapa. */
    private static List<MapCell> mapCells(JsonObject element) {
        List<MapCell> cells = new ArrayList<>();
        if (!element.has("map_cells") || !element.get("map_cells").isJsonArray()) return cells;

        JsonArray array = element.getAsJsonArray("map_cells");
        int total = Math.min(array.size(), MapHudProtocol.MAX_CELLS);
        for (int index = 0; index < total; index++) {
            cells.add(new MapCell(colorValue(array.get(index), 0x00000000)));
        }
        return cells;
    }

    /** Lê marcadores de mapa, ignorando entradas inválidas ou fora do limite. */
    private static List<MapMarker> mapMarkers(JsonObject element) {
        List<MapMarker> markers = new ArrayList<>();
        if (!element.has("map_markers") || !element.get("map_markers").isJsonArray()) return markers;

        JsonArray array = element.getAsJsonArray("map_markers");
        int total = Math.min(array.size(), MapHudProtocol.MAX_MARKERS);
        for (int index = 0; index < total; index++) {
            JsonElement entry = array.get(index);
            if (!entry.isJsonObject()) continue;
            JsonObject marker = entry.getAsJsonObject();
            String type = text(marker, "type", "waypoint");
            if (!MapHudProtocol.MARKER_TYPES.contains(type)) continue;
            String label = text(marker, "label", "");
            if (label.length() > MapHudProtocol.MAX_MARKER_LABEL) {
                label = label.substring(0, MapHudProtocol.MAX_MARKER_LABEL);
            }
            double x = normalized(marker, "x");
            double z = normalized(marker, "z");
            markers.add(new MapMarker(type, label, x, z, colorOf(marker, "color", 0xFFFFFFFF)));
        }
        return markers;
    }

    private static double normalized(JsonObject object, String field) {
        try {
            double value = object.has(field) ? object.get(field).getAsDouble() : 0.5;
            if (!Double.isFinite(value)) return 0.5;
            return Math.max(0.0, Math.min(1.0, value));
        } catch (RuntimeException error) {
            return 0.5;
        }
    }

    private static int colorValue(JsonElement value, int fallback) {
        try {
            if (value == null || !value.isJsonPrimitive()) return fallback;
            if (value.getAsJsonPrimitive().isNumber()) return value.getAsInt();
            String cleaned = value.getAsString().trim();
            if (cleaned.startsWith("#")) cleaned = cleaned.substring(1);
            if (cleaned.length() == 6) cleaned = cleaned + "FF";
            long rgba = Long.parseLong(cleaned, 16);
            int r = (int) ((rgba >> 24) & 0xFF);
            int g = (int) ((rgba >> 16) & 0xFF);
            int b = (int) ((rgba >> 8) & 0xFF);
            int a = (int) (rgba & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    /** Lê as células de uma grade. Vazia quando o elemento não é uma grade. */
    private static List<Cell> cells(JsonObject element) {
        List<Cell> cells = new ArrayList<>();
        if (!element.has("cells") || !element.get("cells").isJsonArray()) return cells;

        for (JsonElement entry : element.getAsJsonArray("cells")) {
            if (!entry.isJsonObject()) continue;
            JsonObject cell = entry.getAsJsonObject();
            cells.add(new Cell(
                    text(cell, "item", ""),
                    integer(cell, "count", 1),
                    text(cell, "tooltip", "")));
        }
        return cells;
    }

    private static boolean bool(JsonObject object, String field, boolean fallback) {
        try {
            return object.has(field) ? object.get(field).getAsBoolean() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static String text(JsonObject object, String field, String fallback) {
        return object.has(field) && object.get(field).isJsonPrimitive()
                ? object.get(field).getAsString()
                : fallback;
    }

    private static int integer(JsonObject object, String field, int fallback) {
        try {
            return object.has(field) ? object.get(field).getAsInt() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    private static double decimal(JsonObject object, String field, double fallback) {
        try {
            return object.has(field) ? object.get(field).getAsDouble() : fallback;
        } catch (RuntimeException error) {
            return fallback;
        }
    }

    /** Converte {@code #RRGGBBAA} no inteiro ARGB que o renderizador usa. */
    private static int color(JsonObject object) {
        return colorOf(object, "color", 0xFFFFFFFF);
    }

    private static int colorOf(JsonObject object, String field, int fallback) {
        if (!object.has(field)) return fallback;
        return colorValue(object.get(field), fallback);
    }
}
