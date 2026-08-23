package dev.lualoader.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Locale;

/**
 * Converte a descrição de tela escrita em Lua no JSON que trafega até o cliente, validando pelo
 * caminho.
 *
 * <p>A validação acontece aqui, no núcleo, e não no cliente: um erro de script precisa aparecer como
 * mensagem clara para quem escreveu o mod, e não como uma tela quebrada na máquina de quem joga. O
 * cliente, do outro lado, ignora o que não entende em vez de falhar — as duas metades dessa regra
 * são o que mantém a tela previsível entre versões diferentes.
 */
public final class ScreenBuilder {
    private ScreenBuilder() {
    }

    /** Falha de descrição, com mensagem destinada a quem escreveu o mod. */
    public static final class InvalidScreenException extends RuntimeException {
        public InvalidScreenException(String message) {
            super(message);
        }
    }

    /**
     * Monta o JSON de uma tela.
     *
     * @param definition tabela Lua com {@code title}, {@code width}, {@code height} e
     *                   {@code elements}
     */
    public static String screen(LuaTable definition) {
        JsonObject json = new JsonObject();
        json.addProperty("version", ScreenProtocol.VERSION);
        json.addProperty("title", text(definition.get("title"), "", "title"));

        json.addProperty("width", size(definition.get("width"), 256, "width"));
        json.addProperty("height", size(definition.get("height"), 166, "height"));

        // O jogo desfoca o mundo atras de qualquer tela desde a 1.20.5. Isso serve a um menu de
        // pausa, mas atrapalha um painel consultado durante o jogo, entao o padrao e sem desfoque.
        json.addProperty("blur", definition.get("blur").toboolean());

        LuaValue escurecer = definition.get("dim");
        json.addProperty("dim", escurecer.isnil() || escurecer.toboolean());

        LuaValue elements = definition.get("elements");
        if (!elements.istable()) {
            throw new InvalidScreenException("a tela precisa de uma lista em elements");
        }
        json.add("elements", elements(( LuaTable) elements));

        return finish(json);
    }

    /** Monta o JSON de um HUD, que é apenas a lista de elementos. */
    public static String hud(LuaTable elements) {
        JsonObject json = new JsonObject();
        json.addProperty("version", ScreenProtocol.VERSION);
        json.add("elements", elements(elements));
        return finish(json);
    }

    private static String finish(JsonObject json) {
        String texto = json.toString();
        if (texto.length() > ScreenProtocol.MAX_PAYLOAD_CHARS) {
            throw new InvalidScreenException("descricao de tela excede "
                    + ScreenProtocol.MAX_PAYLOAD_CHARS + " caracteres");
        }
        return texto;
    }

    private static JsonArray elements(LuaTable lista) {
        JsonArray array = new JsonArray();
        int total = lista.length();

        if (total > ScreenProtocol.MAX_ELEMENTS) {
            throw new InvalidScreenException("a tela tem " + total + " elementos, acima do limite de "
                    + ScreenProtocol.MAX_ELEMENTS);
        }

        for (int indice = 1; indice <= total; indice++) {
            LuaValue entrada = lista.get(indice);
            if (entrada.isnil()) continue;
            if (!entrada.istable()) {
                throw new InvalidScreenException("elemento " + indice + " precisa ser uma tabela");
            }
            array.add(element((LuaTable) entrada, indice));
        }
        return array;
    }

    private static JsonObject element(LuaTable origem, int indice) {
        String tipo = origem.get("type").isnil()
                ? ""
                : origem.get("type").tojstring().trim().toLowerCase(Locale.ROOT);

        if (!ScreenProtocol.ELEMENTS.contains(tipo)) {
            throw new InvalidScreenException("elemento " + indice + " tem tipo desconhecido: " + tipo);
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", tipo);

        // Elementos que recebem interacao precisam de id, senao o evento voltaria sem dono.
        LuaValue id = origem.get("id");
        if (ScreenProtocol.INTERACTIVE.contains(tipo)) {
            if (id.isnil() || id.tojstring().isBlank()) {
                throw new InvalidScreenException("elemento " + tipo + " em " + indice + " precisa de id");
            }
            json.addProperty("id", text(id, "", "id"));
        } else if (!id.isnil()) {
            json.addProperty("id", text(id, "", "id"));
        }

        json.addProperty("x", coordinate(origem.get("x"), 0, "x"));
        json.addProperty("y", coordinate(origem.get("y"), 0, "y"));
        if (!origem.get("w").isnil()) json.addProperty("w", size(origem.get("w"), 0, "w"));
        if (!origem.get("h").isnil()) json.addProperty("h", size(origem.get("h"), 0, "h"));

        LuaValue anchor = origem.get("anchor");
        if (!anchor.isnil()) {
            String valor = anchor.tojstring().trim().toLowerCase(Locale.ROOT);
            if (!ScreenProtocol.ANCHORS.contains(valor)) {
                throw new InvalidScreenException("ancora desconhecida em " + indice + ": " + valor);
            }
            json.addProperty("anchor", valor);
        }

        copyText(origem, json, "text");
        copyText(origem, json, "value");
        copyText(origem, json, "item");
        copyText(origem, json, "texture");
        copyText(origem, json, "tooltip");

        if (!origem.get("color").isnil()) {
            json.addProperty("color", color(origem.get("color")));
        }
        if (!origem.get("count").isnil()) {
            json.addProperty("count", (int) clamp(origem.get("count").todouble(), 1, 64, "count"));
        }
        if (!origem.get("progress").isnil()) {
            json.addProperty("progress", clamp(origem.get("progress").todouble(), 0, 1, "progress"));
        }
        if (!origem.get("scale").isnil()) {
            json.addProperty("scale", clamp(origem.get("scale").todouble(), 0.25, 4, "scale"));
        }
        return json;
    }

    private static void copyText(LuaTable origem, JsonObject destino, String campo) {
        LuaValue valor = origem.get(campo);
        if (valor.isnil()) return;
        destino.addProperty(campo, text(valor, "", campo));
    }

    private static String text(LuaValue valor, String padrao, String campo) {
        if (valor.isnil()) return padrao;
        String texto = valor.tojstring();
        if (texto.length() > ScreenProtocol.MAX_TEXT_LENGTH) {
            throw new InvalidScreenException(campo + " excede "
                    + ScreenProtocol.MAX_TEXT_LENGTH + " caracteres");
        }
        return texto;
    }

    private static int coordinate(LuaValue valor, int padrao, String campo) {
        if (valor.isnil()) return padrao;
        return (int) clamp(valor.todouble(), -ScreenProtocol.MAX_SCREEN_SIZE,
                ScreenProtocol.MAX_SCREEN_SIZE, campo);
    }

    private static int size(LuaValue valor, int padrao, String campo) {
        if (valor.isnil()) return padrao;
        return (int) clamp(valor.todouble(), 0, ScreenProtocol.MAX_SCREEN_SIZE, campo);
    }

    private static double clamp(double valor, double minimo, double maximo, String campo) {
        if (Double.isNaN(valor) || valor < minimo || valor > maximo) {
            throw new InvalidScreenException(campo + " deve estar entre " + minimo + " e " + maximo
                    + ", recebido " + valor);
        }
        return valor;
    }

    /** Aceita {@code #RRGGBB} e {@code #RRGGBBAA}, devolvendo sempre a forma com alfa. */
    private static String color(LuaValue valor) {
        String texto = valor.tojstring().trim();
        if (!texto.matches("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")) {
            throw new InvalidScreenException("cor invalida: " + texto + " (use #RRGGBB ou #RRGGBBAA)");
        }
        return texto.length() == 7 ? texto + "FF" : texto;
    }
}
