package dev.lualoader.lua;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Guarda e recupera o estado de um mod em disco.
 *
 * <p>Sem isto, {@code mod.state} vive apenas em memória: um mod que conta, acumula ou registra
 * progresso perde tudo quando o servidor para. O estado é gravado como JSON, um arquivo por mod,
 * para poder ser inspecionado e corrigido à mão quando algo der errado.
 *
 * <p>Só tipos que sobrevivem a JSON são gravados: texto, número, booleano e tabelas desses tipos.
 * Funções e valores de plataforma são descartados com aviso, porque não fariam sentido depois de um
 * reinício — uma função salva não teria como ser reconstruída.
 */
public final class StateStore {
    /** Profundidade máxima de tabelas aninhadas, para recusar estruturas circulares. */
    private static final int MAX_DEPTH = 32;

    private final Logger logger;
    private final Path directory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public StateStore(Logger logger, Path directory) {
        this.logger = logger;
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
    }

    /** Indica se há um lugar configurado para gravar. Sem ele, o estado é apenas de memória. */
    public boolean isEnabled() {
        return directory != null;
    }

    /** Lê o estado gravado do mod, devolvendo uma tabela vazia quando não há nada salvo. */
    public LuaTable load(String modId) {
        LuaTable table = new LuaTable();
        if (directory == null) return table;

        Path file = fileFor(modId);
        if (!Files.isRegularFile(file)) return table;

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (parsed.isJsonObject()) {
                fillTable(table, parsed.getAsJsonObject());
                logger.info("Estado restaurado para {}", modId);
            }
        } catch (IOException | RuntimeException error) {
            // Um estado corrompido não pode impedir o mod de carregar: ele recomeça vazio.
            logger.error("Falha ao ler o estado de {}: {}", modId, error.getMessage());
        }
        return table;
    }

    /** Grava o estado do mod. Uma gravação parcial nunca substitui o arquivo bom. */
    public void save(String modId, LuaTable state) {
        if (directory == null || state == null) return;

        try {
            Files.createDirectories(directory);
            JsonObject json = toJsonObject(modId, state, 0);

            Path file = fileFor(modId);
            Path temporary = Files.createTempFile(directory, "state-", ".tmp");
            try {
                Files.writeString(temporary, gson.toJson(json), StandardCharsets.UTF_8);
                Files.move(temporary, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException | RuntimeException error) {
            logger.error("Falha ao gravar o estado de {}: {}", modId, error.getMessage());
        }
    }

    /** Converte uma tabela Lua em texto JSON, para guardar fora do runtime. */
    public String toJsonText(String owner, LuaTable table) {
        return gson.toJson(toJsonObject(owner, table, 0));
    }

    /** Converte texto JSON de volta em tabela Lua. Texto invalido vira tabela vazia. */
    public LuaTable fromJsonText(String json) {
        LuaTable table = new LuaTable();
        if (json == null || json.isBlank()) return table;
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (parsed.isJsonObject()) fillTable(table, parsed.getAsJsonObject());
        } catch (RuntimeException error) {
            logger.warn("Dados ignorados por nao serem JSON valido: {}", error.getMessage());
        }
        return table;
    }

    private Path fileFor(String modId) {
        // O id do mod já é validado como [a-z0-9_-], então não escapa do diretório.
        return directory.resolve(modId + ".json");
    }

    private JsonObject toJsonObject(String modId, LuaTable table, int depth) {
        JsonObject json = new JsonObject();
        if (depth > MAX_DEPTH) {
            logger.warn("Estado de {} ignorado abaixo de {} niveis de profundidade", modId, MAX_DEPTH);
            return json;
        }

        for (LuaValue key : table.keys()) {
            JsonElement value = toJson(modId, table.get(key), depth);
            if (value == null) continue;
            json.add(key.tojstring(), value);
        }
        return json;
    }

    /**
     * Indica se a tabela é uma sequência: chaves inteiras de 1 a n, sem buracos nem outras chaves.
     *
     * <p>É a mesma noção que o {@code #} do Lua usa, e a única em que a ordem tem significado.
     */
    private static boolean isSequence(LuaTable table) {
        int length = table.length();
        if (length == 0) return false;

        int keys = 0;
        for (LuaValue key : table.keys()) {
            if (!key.isnumber()) return false;

            double number = key.todouble();
            if (number != Math.floor(number) || number < 1 || number > length) return false;
            keys++;
        }
        return keys == length;
    }

    private JsonArray toJsonArray(String modId, LuaTable table, int depth) {
        JsonArray array = new JsonArray();
        if (depth > MAX_DEPTH) {
            logger.warn("Estado de {} ignorado abaixo de {} niveis de profundidade", modId, MAX_DEPTH);
            return array;
        }

        for (int index = 1; index <= table.length(); index++) {
            JsonElement value = toJson(modId, table.get(index), depth);
            // Um nulo no meio quebraria as posicoes seguintes, entao a lista para onde parou.
            if (value == null) break;
            array.add(value);
        }
        return array;
    }

    private JsonElement toJson(String modId, LuaValue value, int depth) {
        if (value.isboolean()) return new JsonPrimitive(value.toboolean());
        if (value.isnumber()) {
            double number = value.todouble();
            // Inteiros voltam como inteiros, para o Lua não receber 3.0 onde gravou 3.
            if (number == Math.floor(number) && !Double.isInfinite(number)) {
                return new JsonPrimitive((long) number);
            }
            return new JsonPrimitive(number);
        }
        if (value.isstring()) return new JsonPrimitive(value.tojstring());
        if (value.istable()) {
            LuaTable table = (LuaTable) value;
            // Uma lista precisa voltar como lista. Gravada como objeto, ela retornava com chaves
            // de texto e fora de ordem: o Lua recebia algo em que # da zero e ipairs nao itera,
            // ou seja, uma lista cheia virava uma lista vazia sem erro nenhum.
            return isSequence(table)
                    ? toJsonArray(modId, table, depth + 1)
                    : toJsonObject(modId, table, depth + 1);
        }

        if (!value.isnil()) {
            logger.warn("Estado de {} ignora um valor do tipo {}, que nao sobrevive a um reinicio",
                    modId, value.typename());
        }
        return null;
    }

    private void fillTable(LuaTable table, JsonObject json) {
        for (String key : json.keySet()) {
            LuaValue value = fromJson(json.get(key));
            if (value != null) table.set(key, value);
        }
    }

    private LuaValue fromJson(JsonElement element) {
        if (element.isJsonPrimitive()) {
            JsonPrimitive primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) return LuaValue.valueOf(primitive.getAsBoolean());
            if (primitive.isNumber()) {
                double number = primitive.getAsDouble();
                return number == Math.floor(number)
                        ? LuaValue.valueOf((int) number)
                        : LuaValue.valueOf(number);
            }
            return LuaValue.valueOf(primitive.getAsString());
        }
        if (element.isJsonObject()) {
            LuaTable nested = new LuaTable();
            fillTable(nested, element.getAsJsonObject());
            return nested;
        }
        if (element.isJsonArray()) {
            // Arrays viram tabelas indexadas a partir de 1, como é natural em Lua.
            LuaTable list = new LuaTable();
            JsonArray array = element.getAsJsonArray();
            for (int index = 0; index < array.size(); index++) {
                LuaValue item = fromJson(array.get(index));
                if (item != null) list.set(index + 1, item);
            }
            return list;
        }
        return null;
    }
}
