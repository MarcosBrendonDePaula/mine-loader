package dev.lualoader.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Resolve referências {@code $import} dentro do manifesto, permitindo dividir um mod em arquivos.
 *
 * <p>Um mod que declara muitos blocos, itens ou estruturas produz um {@code mod.json} grande
 * demais para ler. Com import, cada parte vive em seu próprio arquivo:
 *
 * <pre>{@code
 * {
 *   "blocks": { "$import": "blocks/todos.json" },
 *   "structures": [ { "$import": "structures/torre.json" } ]
 * }
 * }</pre>
 *
 * <p>Um objeto que contenha {@code $import} é substituído pelo conteúdo do arquivo apontado,
 * seja ele um objeto ou um array. O arquivo importado também pode importar outros.
 *
 * <p>Todo caminho é resolvido dentro da pasta do mod. Caminhos absolutos, {@code ..} ou qualquer
 * coisa que escape da raiz são recusados, pelo mesmo motivo que o entrypoint Lua é: o manifesto
 * não pode ler arquivos arbitrários da máquina.
 */
public final class ManifestImports {
    /** Profundidade máxima de imports encadeados, para recusar cadeias longas ou circulares. */
    private static final int MAX_DEPTH = 16;

    private final Path modRoot;

    public ManifestImports(Path modRoot) {
        this.modRoot = modRoot.toAbsolutePath().normalize();
    }

    /** Lê o manifesto resolvendo todos os imports encontrados. */
    public JsonElement readResolved(Path manifestPath) throws IOException {
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        }
        return resolve(root, new ArrayDeque<>(), 0);
    }

    private JsonElement resolve(JsonElement element, Deque<Path> chain, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IOException("imports aninhados demais (limite " + MAX_DEPTH + ")");
        }

        if (element.isJsonArray()) {
            JsonArray resolved = new JsonArray();
            for (JsonElement item : element.getAsJsonArray()) {
                resolved.add(resolve(item, chain, depth));
            }
            return resolved;
        }

        if (!element.isJsonObject()) return element;

        JsonObject object = element.getAsJsonObject();
        if (object.has("$import")) {
            if (object.size() > 1) {
                throw new IOException("$import nao pode ser combinado com outros campos no mesmo objeto");
            }
            JsonElement target = object.get("$import");
            if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                throw new IOException("$import precisa ser um caminho em texto");
            }
            return loadImported(target.getAsString(), chain, depth);
        }

        JsonObject resolved = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            resolved.add(entry.getKey(), resolve(entry.getValue(), chain, depth));
        }
        return resolved;
    }

    private JsonElement loadImported(String relativePath, Deque<Path> chain, int depth) throws IOException {
        Path target = safeResolve(relativePath);

        if (chain.contains(target)) {
            throw new IOException("import circular detectado em " + relativePath);
        }
        if (!Files.isRegularFile(target)) {
            throw new IOException("arquivo importado nao encontrado: " + relativePath);
        }

        JsonElement imported;
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            imported = JsonParser.parseReader(reader);
        }

        chain.push(target);
        try {
            return resolve(imported, chain, depth + 1);
        } finally {
            chain.pop();
        }
    }

    /** Garante que o caminho importado permanece dentro da pasta do mod. */
    private Path safeResolve(String relativePath) throws IOException {
        if (relativePath.isBlank()) {
            throw new IOException("caminho de import vazio");
        }
        if (relativePath.startsWith("/") || relativePath.startsWith("\\") || relativePath.contains(":")) {
            throw new IOException("caminho de import precisa ser relativo a pasta do mod: " + relativePath);
        }

        Path resolved = modRoot.resolve(relativePath).normalize();
        if (!resolved.startsWith(modRoot)) {
            throw new IOException("import sai da pasta do mod: " + relativePath);
        }
        return resolved;
    }
}
