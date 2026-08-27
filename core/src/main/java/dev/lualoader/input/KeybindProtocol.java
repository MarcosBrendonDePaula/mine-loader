package dev.lualoader.input;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contrato comum da entrada declarativa do MineLoader.
 *
 * <p>O cliente recebe apenas dados do binding e devolve o identificador do binding pressionado. O
 * Lua continua a executar no servidor; nenhum código do mod atravessa a rede.
 */
public final class KeybindProtocol {
    private static final Gson GSON = new Gson();
    private static final Type BINDING_LIST = new TypeToken<List<Binding>>() { }.getType();

    /** Versão do significado dos campos e dos canais de hotkey. */
    public static final int VERSION = 1;

    /** Canal servidor -> cliente que publica o catálogo do jogador. */
    public static final String CHANNEL_SET = "keybinds_set";

    /** Canal cliente -> servidor que relata uma tecla pressionada. */
    public static final String CHANNEL_EVENT = "keybind_event";

    /** Teto de bindings publicados de uma vez. */
    public static final int MAX_BINDINGS = 128;

    /** Teto dos identificadores enviados pela rede. */
    public static final int MAX_ID_LENGTH = 64;

    /** Teto do texto de categoria. */
    public static final int MAX_CATEGORY_LENGTH = 64;

    /** Modificadores portáveis aceites pelo contrato. */
    public static final Set<String> MODIFIERS = Set.of("ctrl", "shift", "alt");

    private KeybindProtocol() {
    }

    /** Binding já resolvido para um mod concreto e pronto para ser enviado ao cliente. */
    public record Binding(String modId, String id, String key, String category,
                          List<String> modifiers) {
        public Binding {
            modId = requireText(modId, "modId", MAX_ID_LENGTH);
            id = requireText(id, "id", MAX_ID_LENGTH);
            key = requireText(key, "key", MAX_ID_LENGTH).toLowerCase(Locale.ROOT);
            category = requireText(category, "category", MAX_CATEGORY_LENGTH);
            modifiers = normalizeModifiers(modifiers);
        }

        /** Nome que volta no evento cliente -> servidor. */
        public String qualifiedId() {
            return modId + ":" + id;
        }
    }

    /** Serializa somente uma lista limitada e já validada pelo core. */
    public static String encode(List<Binding> bindings) {
        List<Binding> safe = bindings == null ? List.of() : List.copyOf(bindings);
        if (safe.size() > MAX_BINDINGS) {
            throw new IllegalArgumentException("limite de " + MAX_BINDINGS + " hotkeys atingido");
        }
        return GSON.toJson(safe, BINDING_LIST);
    }

    /**
     * Lê o catálogo recebido pelo cliente e rejeita dados que não pertencem ao contrato.
     *
     * <p>O método é usado também por testes do core; os bridges só precisam escolher como testar a
     * tecla concreta na plataforma.
     */
    public static List<Binding> decode(String json) {
        if (json == null || json.length() > 64 * 1024) {
            throw new IllegalArgumentException("catálogo de hotkeys inválido");
        }
        try {
            List<Binding> bindings = GSON.fromJson(json, BINDING_LIST);
            if (bindings == null || bindings.size() > MAX_BINDINGS) {
                throw new IllegalArgumentException("catálogo de hotkeys excede o limite");
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Binding binding : bindings) {
                if (!ids.add(binding.qualifiedId())) {
                    throw new IllegalArgumentException("hotkey duplicada: " + binding.qualifiedId());
                }
                validateKey(binding.key());
            }
            return List.copyOf(bindings);
        } catch (JsonParseException | IllegalStateException | NullPointerException error) {
            throw new IllegalArgumentException("catálogo de hotkeys inválido", error);
        }
    }

    /** Valida o formato lógico de tecla, sem conhecer GLFW, Minecraft ou o bridge. */
    public static void validateKey(String key) {
        String normalized = requireText(key, "key", MAX_ID_LENGTH).toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("key.keyboard.")
                || !normalized.matches("key\\.keyboard\\.[a-z0-9_.-]+")) {
            throw new IllegalArgumentException(
                    "key deve usar o formato key.keyboard.<nome>: " + key);
        }
    }

    /** Normaliza os modificadores e recusa palavras específicas de uma plataforma. */
    public static List<String> normalizeModifiers(List<String> modifiers) {
        if (modifiers == null || modifiers.isEmpty()) return List.of();
        if (modifiers.size() > MODIFIERS.size()) {
            throw new IllegalArgumentException("modificadores de hotkey em excesso");
        }
        List<String> result = new ArrayList<>();
        for (String modifier : modifiers) {
            String normalized = requireText(modifier, "modifier", 16).toLowerCase(Locale.ROOT);
            if (!MODIFIERS.contains(normalized)) {
                throw new IllegalArgumentException("modificador de hotkey desconhecido: " + modifier);
            }
            if (result.contains(normalized)) {
                throw new IllegalArgumentException("modificador de hotkey duplicado: " + modifier);
            }
            result.add(normalized);
        }
        return List.copyOf(result);
    }

    private static String requireText(String value, String field, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(field + " de hotkey inválido");
        }
        return value;
    }
}
