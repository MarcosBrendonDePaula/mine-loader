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

        LuaValue dim = definition.get("dim");
        json.addProperty("dim", dim.isnil() || dim.toboolean());

        LuaValue elements = definition.get("elements");
        if (!elements.istable()) {
            throw new InvalidScreenException("a tela precisa de uma lista em elements");
        }
        json.add("elements", elements(( LuaTable) elements));

        return finish(json);
    }

    /**
     * Monta o JSON de uma sobreposição sobre uma tela do jogo.
     *
     * <p>Diferente de {@link #screen(LuaTable)}, não há janela própria: o mod desenha sobre uma tela
     * que o jogo abriu. Por isso não existe {@code width}, {@code height}, {@code blur} nem
     * {@code dim} — quem manda no fundo é a tela de baixo, e uma sobreposição que a escurecesse
     * estaria decidindo pelo jogo.
     *
     * @param definition tabela Lua com {@code target} e {@code elements}
     */
    public static String overlay(LuaTable definition) {
        JsonObject json = new JsonObject();
        json.addProperty("version", ScreenProtocol.VERSION);

        LuaValue declared = definition.get("target");
        String target = declared.isnil()
                ? "container"
                : declared.tojstring().trim().toLowerCase(Locale.ROOT);
        if (!ScreenProtocol.TARGETS.contains(target)) {
            throw new InvalidScreenException("target desconhecido: " + target
                    + " (use um de " + ScreenProtocol.TARGETS + ")");
        }
        json.addProperty("target", target);

        LuaValue elements = definition.get("elements");
        if (!elements.istable()) {
            throw new InvalidScreenException("a sobreposicao precisa de uma lista em elements");
        }
        json.add("elements", elements((LuaTable) elements));

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
        String text = json.toString();
        if (text.length() > ScreenProtocol.MAX_PAYLOAD_CHARS) {
            throw new InvalidScreenException("descricao de tela excede "
                    + ScreenProtocol.MAX_PAYLOAD_CHARS + " caracteres");
        }
        return text;
    }

    private static JsonArray elements(LuaTable list) {
        JsonArray array = new JsonArray();
        int total = list.length();

        if (total > ScreenProtocol.MAX_ELEMENTS) {
            throw new InvalidScreenException("a tela tem " + total + " elementos, acima do limite de "
                    + ScreenProtocol.MAX_ELEMENTS);
        }

        for (int index = 1; index <= total; index++) {
            LuaValue entry = list.get(index);
            if (entry.isnil()) continue;
            if (!entry.istable()) {
                throw new InvalidScreenException("elemento " + index + " precisa ser uma tabela");
            }
            array.add(element((LuaTable) entry, index));
        }
        return array;
    }

    private static JsonObject element(LuaTable source, int index) {
        String type = source.get("type").isnil()
                ? ""
                : source.get("type").tojstring().trim().toLowerCase(Locale.ROOT);

        if (!ScreenProtocol.ELEMENTS.contains(type)) {
            throw new InvalidScreenException("elemento " + index + " tem tipo desconhecido: " + type);
        }

        JsonObject json = new JsonObject();
        json.addProperty("type", type);

        // Elementos que recebem interacao precisam de id, senao o evento voltaria sem dono.
        LuaValue id = source.get("id");
        if (ScreenProtocol.INTERACTIVE.contains(type)) {
            if (id.isnil() || id.tojstring().isBlank()) {
                throw new InvalidScreenException("elemento " + type + " em " + index + " precisa de id");
            }
            json.addProperty("id", text(id, "", "id"));
        } else if (!id.isnil()) {
            json.addProperty("id", text(id, "", "id"));
        }

        json.addProperty("x", coordinate(source.get("x"), 0, "x"));
        json.addProperty("y", coordinate(source.get("y"), 0, "y"));

        int width = source.get("w").isnil() ? 0 : size(source.get("w"), 0, "w");
        int height = source.get("h").isnil() ? 0 : size(source.get("h"), 0, "h");
        if (!source.get("w").isnil()) json.addProperty("w", width);
        if (!source.get("h").isnil()) json.addProperty("h", height);

        // Um botao ou campo sem tamanho era aceito em silencio e o cliente arbitrava um minimo,
        // o que produzia um elemento fora do lugar esperado. E melhor recusar e dizer o motivo.
        // Uma grade dimensiona-se pelas celulas, entao nao exige w e h; os demais interativos sim,
        // porque sem tamanho o cliente arbitraria um minimo e o elemento sairia do lugar esperado.
        if (ScreenProtocol.INTERACTIVE.contains(type) && !type.equals("grid")) {
            if (width <= 0 || height <= 0) {
                throw new InvalidScreenException("elemento " + type + " em " + index
                        + " precisa de w e h maiores que zero");
            }
        }

        LuaValue anchor = source.get("anchor");
        if (!anchor.isnil()) {
            String value = anchor.tojstring().trim().toLowerCase(Locale.ROOT);
            if (!ScreenProtocol.ANCHORS.contains(value)) {
                throw new InvalidScreenException("ancora desconhecida em " + index + ": " + value);
            }
            json.addProperty("anchor", value);
        }

        // Elementos com group sao desenhados dentro do viewport de mesmo id, recortados e
        // deslocados pela rolagem. E o que permite uma lista maior que a janela.
        copyText(source, json, "group");
        copyText(source, json, "text");
        copyText(source, json, "value");
        copyText(source, json, "item");
        copyText(source, json, "entity");
        copyText(source, json, "texture");
        copyText(source, json, "tooltip");

        if (!source.get("color").isnil()) {
            json.addProperty("color", color(source.get("color")));
        }
        if (!source.get("count").isnil()) {
            json.addProperty("count", (int) clamp(source.get("count").todouble(), 1, 64, "count"));
        }
        if (!source.get("progress").isnil()) {
            json.addProperty("progress", clamp(source.get("progress").todouble(), 0, 1, "progress"));
        }
        // Texto escuro sobre painel claro fica sujo com sombra: a sombra e escura tambem, e as duas
        // se misturam. O jogo desenha titulo de container sem sombra pelo mesmo motivo.
        if (!source.get("shadow").isnil()) {
            json.addProperty("shadow", source.get("shadow").toboolean());
        }
        if (!source.get("scale").isnil()) {
            json.addProperty("scale", clamp(source.get("scale").todouble(), 0.25, 4, "scale"));
        }

        if (type.equals("panel")) {
            LuaValue style = source.get("style");
            String name = style.isnil()
                    ? "flat"
                    : style.tojstring().trim().toLowerCase(Locale.ROOT);
            if (!ScreenProtocol.PANEL_STYLES.contains(name)) {
                throw new InvalidScreenException("estilo de painel desconhecido em " + index + ": "
                        + name + " (use um de " + ScreenProtocol.PANEL_STYLES + ")");
            }
            json.addProperty("style", name);

            if (!source.get("border").isnil()) {
                json.addProperty("border", (int) clamp(source.get("border").todouble(), 0, 16, "border"));
            }
            if (!source.get("border_light").isnil()) {
                json.addProperty("border_light", color(source.get("border_light")));
            }
            if (!source.get("border_dark").isnil()) {
                json.addProperty("border_dark", color(source.get("border_dark")));
            }
        }
        if (type.equals("grid")) {
            json.addProperty("columns",
                    (int) clamp(source.get("columns").isnil() ? 9 : source.get("columns").todouble(),
                            1, ScreenProtocol.MAX_COLUMNS, "columns"));
            // O passo entre celulas: 18 e o do inventario do jogo, entao uma grade sem ajuste ja
            // sai alinhada ao que o jogador conhece.
            json.addProperty("cell",
                    (int) clamp(source.get("cell").isnil() ? 18 : source.get("cell").todouble(),
                            8, 64, "cell"));
            json.add("cells", cells(source.get("items"), index));
        }
        if (type.equals("viewport")) {
            // Altura total do conteudo: e o que diz ao cliente ate onde a rolagem pode ir, sem ele
            // precisar medir elementos que talvez nem estejam nesta descricao.
            LuaValue content = source.get("content");
            json.addProperty("content", content.isnil()
                    ? 0
                    : (int) clamp(content.todouble(), 0, ScreenProtocol.MAX_CONTENT_SIZE, "content"));
        }
        return json;
    }

    /**
     * Monta as células de uma grade.
     *
     * <p>Aceita a forma curta — só o identificador do item — e a completa, com quantidade e texto de
     * ajuda. A curta existe porque o caso comum é justamente uma lista de itens, e escrever uma
     * tabela por célula ali seria ruído.
     */
    private static JsonArray cells(LuaValue items, int index) {
        if (!items.istable()) {
            throw new InvalidScreenException("grade em " + index + " precisa de uma lista em items");
        }

        LuaTable list = (LuaTable) items;
        int total = list.length();
        if (total > ScreenProtocol.MAX_CELLS) {
            throw new InvalidScreenException("grade em " + index + " tem " + total
                    + " celulas, acima do limite de " + ScreenProtocol.MAX_CELLS);
        }

        JsonArray cells = new JsonArray();
        for (int position = 1; position <= total; position++) {
            LuaValue entry = list.get(position);
            if (entry.isnil()) continue;

            JsonObject cell = new JsonObject();
            if (entry.istable()) {
                LuaTable table = (LuaTable) entry;
                cell.addProperty("item", text(table.get("item"), "", "item"));
                if (!table.get("count").isnil()) {
                    cell.addProperty("count",
                            (int) clamp(table.get("count").todouble(), 1, 64, "count"));
                }
                if (!table.get("tooltip").isnil()) {
                    cell.addProperty("tooltip", text(table.get("tooltip"), "", "tooltip"));
                }
            } else {
                cell.addProperty("item", text(entry, "", "item"));
            }
            cells.add(cell);
        }
        return cells;
    }

    private static void copyText(LuaTable source, JsonObject destination, String field) {
        LuaValue value = source.get(field);
        if (value.isnil()) return;
        destination.addProperty(field, text(value, "", field));
    }

    private static String text(LuaValue value, String fallback, String field) {
        if (value.isnil()) return fallback;
        String text = value.tojstring();
        if (text.length() > ScreenProtocol.MAX_TEXT_LENGTH) {
            throw new InvalidScreenException(field + " excede "
                    + ScreenProtocol.MAX_TEXT_LENGTH + " caracteres");
        }
        return text;
    }

    private static int coordinate(LuaValue value, int fallback, String field) {
        if (value.isnil()) return fallback;
        return (int) clamp(value.todouble(), -ScreenProtocol.MAX_SCREEN_SIZE,
                ScreenProtocol.MAX_SCREEN_SIZE, field);
    }

    private static int size(LuaValue value, int fallback, String field) {
        if (value.isnil()) return fallback;
        return (int) clamp(value.todouble(), 0, ScreenProtocol.MAX_SCREEN_SIZE, field);
    }

    private static double clamp(double value, double minimum, double maximum, String field) {
        if (Double.isNaN(value) || value < minimum || value > maximum) {
            throw new InvalidScreenException(field + " deve estar entre " + minimum + " e " + maximum
                    + ", recebido " + value);
        }
        return value;
    }

    /** Aceita {@code #RRGGBB} e {@code #RRGGBBAA}, devolvendo sempre a forma com alfa. */
    private static String color(LuaValue value) {
        String text = value.tojstring().trim();
        if (!text.matches("^#([0-9a-fA-F]{6}|[0-9a-fA-F]{8})$")) {
            throw new InvalidScreenException("cor invalida: " + text + " (use #RRGGBB ou #RRGGBBAA)");
        }
        return text.length() == 7 ? text + "FF" : text;
    }
}
