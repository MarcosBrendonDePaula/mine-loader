package dev.lualoader.client;

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

    /** Um elemento e seus atributos, todos opcionais fora do tipo. */
    public record Element(String type, String id, int x, int y, int w, int h, String anchor,
                          String text, String value, String item, String texture, String tooltip,
                          int color, int count, double progress, double scale,
                          String group, int columns, int cell, int content, List<Cell> cells,
                          String style, int border, int borderLight, int borderDark,
                          boolean shadow, String entity) {
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
                    text(element, "entity", "")));
        }
        return list;
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
        String text = text(object, field, "#FFFFFFFF");
        try {
            String cleaned = text.startsWith("#") ? text.substring(1) : text;
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
}
