package dev.lualoader.manifest;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;

import java.lang.reflect.Type;

/**
 * Permite escrever uma textura como referência a um recurso nomeado.
 *
 * <p>Sem isto, {@code "texture": "@cristal"} seria erro de leitura: o campo espera um objeto, e uma
 * string no lugar faz o Gson recusar o manifesto inteiro. A forma curta é a que se quer usar dez
 * vezes seguidas — obrigar {@code {"ref": "cristal"}} em cada bloco devolveria metade da verborragia
 * que os recursos nomeados existem para eliminar.
 *
 * <p>As duas formas convivem de propósito. Um mod que declara a textura no lugar continua válido, e
 * é o que permite a mudança entrar sem quebrar nenhum manifesto existente.
 */
public final class TextureReferenceAdapter
        implements JsonDeserializer<ModManifest.TextureDefinition> {

    /** O que marca uma referência, e não um caminho. */
    public static final char MARKER = '@';

    @Override
    public ModManifest.TextureDefinition deserialize(JsonElement element, Type type,
                                                     JsonDeserializationContext context) {
        if (element.isJsonObject()) {
            // A forma longa continua sendo lida pelo caminho normal do Gson. Chamar o contexto com
            // a mesma classe daria recursao infinita, entao a leitura e feita campo a campo.
            return fromObject(element.getAsJsonObject());
        }

        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new JsonParseException(
                    "textura deve ser um objeto ou uma referencia como \"@nome\"");
        }

        String text = element.getAsString().trim();
        if (text.isEmpty() || text.charAt(0) != MARKER) {
            throw new JsonParseException(
                    "referencia de textura precisa comecar com " + MARKER + ": " + text);
        }

        ModManifest.TextureDefinition definition = new ModManifest.TextureDefinition();
        definition.ref = text.substring(1);
        return definition;
    }

    private static ModManifest.TextureDefinition fromObject(com.google.gson.JsonObject object) {
        ModManifest.TextureDefinition definition = new ModManifest.TextureDefinition();

        if (object.has("source")) definition.source = string(object, "source");
        if (object.has("path")) definition.path = string(object, "path");
        if (object.has("url")) definition.url = string(object, "url");
        if (object.has("sha256")) definition.sha256 = string(object, "sha256");
        if (object.has("fallback")) definition.fallback = string(object, "fallback");
        if (object.has("ref")) definition.ref = string(object, "ref");
        if (object.has("max_bytes")) definition.maxBytes = object.get("max_bytes").getAsLong();

        return definition;
    }

    private static String string(com.google.gson.JsonObject object, String field) {
        JsonElement value = object.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
