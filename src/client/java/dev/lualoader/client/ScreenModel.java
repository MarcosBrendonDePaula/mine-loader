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
    /** Um elemento e seus atributos, todos opcionais fora do tipo. */
    public record Element(String type, String id, int x, int y, int w, int h, String anchor,
                          String text, String value, String item, String texture, String tooltip,
                          int color, int count, double progress, double scale) {
    }

    private final String title;
    private final int width;
    private final int height;
    private final boolean blur;
    private final boolean dim;
    private final List<Element> elements;

    private ScreenModel(String title, int width, int height, boolean blur, boolean dim,
                        List<Element> elements) {
        this.title = title;
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
            JsonElement raiz = JsonParser.parseString(json);
            if (!raiz.isJsonObject()) return null;
            JsonObject objeto = raiz.getAsJsonObject();

            return new ScreenModel(
                    texto(objeto, "title", ""),
                    inteiro(objeto, "width", 256),
                    inteiro(objeto, "height", 166),
                    booleano(objeto, "blur", false),
                    booleano(objeto, "dim", true),
                    elementos(objeto));
        } catch (RuntimeException error) {
            return null;
        }
    }

    private static List<Element> elementos(JsonObject objeto) {
        List<Element> lista = new ArrayList<>();
        if (!objeto.has("elements") || !objeto.get("elements").isJsonArray()) return lista;

        JsonArray array = objeto.getAsJsonArray("elements");
        for (JsonElement entrada : array) {
            if (!entrada.isJsonObject()) continue;
            JsonObject elemento = entrada.getAsJsonObject();

            lista.add(new Element(
                    texto(elemento, "type", ""),
                    texto(elemento, "id", ""),
                    inteiro(elemento, "x", 0),
                    inteiro(elemento, "y", 0),
                    inteiro(elemento, "w", 0),
                    inteiro(elemento, "h", 0),
                    texto(elemento, "anchor", ""),
                    texto(elemento, "text", ""),
                    texto(elemento, "value", ""),
                    texto(elemento, "item", ""),
                    texto(elemento, "texture", ""),
                    texto(elemento, "tooltip", ""),
                    cor(elemento),
                    inteiro(elemento, "count", 1),
                    decimal(elemento, "progress", 0.0),
                    decimal(elemento, "scale", 1.0)));
        }
        return lista;
    }

    private static boolean booleano(JsonObject objeto, String campo, boolean padrao) {
        try {
            return objeto.has(campo) ? objeto.get(campo).getAsBoolean() : padrao;
        } catch (RuntimeException error) {
            return padrao;
        }
    }

    private static String texto(JsonObject objeto, String campo, String padrao) {
        return objeto.has(campo) && objeto.get(campo).isJsonPrimitive()
                ? objeto.get(campo).getAsString()
                : padrao;
    }

    private static int inteiro(JsonObject objeto, String campo, int padrao) {
        try {
            return objeto.has(campo) ? objeto.get(campo).getAsInt() : padrao;
        } catch (RuntimeException error) {
            return padrao;
        }
    }

    private static double decimal(JsonObject objeto, String campo, double padrao) {
        try {
            return objeto.has(campo) ? objeto.get(campo).getAsDouble() : padrao;
        } catch (RuntimeException error) {
            return padrao;
        }
    }

    /** Converte {@code #RRGGBBAA} no inteiro ARGB que o renderizador usa. */
    private static int cor(JsonObject objeto) {
        String texto = texto(objeto, "color", "#FFFFFFFF");
        try {
            String limpo = texto.startsWith("#") ? texto.substring(1) : texto;
            if (limpo.length() == 6) limpo = limpo + "FF";
            long rgba = Long.parseLong(limpo, 16);

            int r = (int) ((rgba >> 24) & 0xFF);
            int g = (int) ((rgba >> 16) & 0xFF);
            int b = (int) ((rgba >> 8) & 0xFF);
            int a = (int) (rgba & 0xFF);
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (RuntimeException error) {
            return 0xFFFFFFFF;
        }
    }
}
