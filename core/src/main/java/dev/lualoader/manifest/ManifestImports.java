package dev.lualoader.manifest;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
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

    /** Teto de tamanho para um pedaço de manifesto vindo da rede. */
    private static final long MAX_REMOTE_BYTES = 262_144;

    private final Path modRoot;
    private final Path cacheDirectory;
    private final HttpClient httpClient;
    private String remoteBase;

    /** Constrói um resolvedor apenas local: qualquer acesso remoto será recusado. */
    public ManifestImports(Path modRoot) {
        this(modRoot, null);
    }

    /**
     * @param cacheDirectory onde guardar pedaços baixados, indexados por hash;
     *                       {@code null} desabilita import remoto
     */
    public ManifestImports(Path modRoot, Path cacheDirectory) {
        this.modRoot = modRoot.toAbsolutePath().normalize();
        this.cacheDirectory = cacheDirectory == null ? null : cacheDirectory.toAbsolutePath().normalize();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Define a base usada para caminhos relativos que nao existirem no disco. */
    public ManifestImports withRemoteBase(String remoteBase) {
        this.remoteBase = normalizeBase(remoteBase);
        return this;
    }

    /** Base remota em uso, ja normalizada com barra final. */
    public String remoteBase() {
        return remoteBase;
    }

    private static String normalizeBase(String base) {
        if (base == null || base.isBlank()) return null;
        String trimmed = base.trim();
        return trimmed.endsWith("/") ? trimmed : trimmed + "/";
    }

    /** Lê o manifesto resolvendo todos os imports encontrados. */
    public JsonElement readResolved(Path manifestPath) throws IOException {
        JsonElement root;
        try (Reader reader = Files.newBufferedReader(manifestPath, StandardCharsets.UTF_8)) {
            root = JsonParser.parseReader(reader);
        }

        // A base precisa valer ja no primeiro import, entao e lida antes de resolver.
        if (remoteBase == null && root.isJsonObject() && root.getAsJsonObject().has("remote_base")) {
            JsonElement declared = root.getAsJsonObject().get("remote_base");
            if (declared.isJsonPrimitive()) remoteBase = normalizeBase(declared.getAsString());
        }
        return resolve(root, new ArrayDeque<>(), 0);
    }

    /**
     * Resolve um caminho relativo, preferindo o arquivo local e caindo para a base remota.
     *
     * @return conteudo do arquivo, ou {@code null} se nao existir em lugar nenhum
     */
    public byte[] readRelative(String relativePath) throws IOException {
        Path local = safeResolve(relativePath);
        if (Files.isRegularFile(local)) {
            return Files.readAllBytes(local);
        }
        if (remoteBase == null) return null;
        return fetchRemote(remoteBase + relativePath.replace('\\', '/'), null);
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
            // sha256 é o único acompanhante aceito, porque descreve o próprio import.
            for (String field : object.keySet()) {
                if (!field.equals("$import") && !field.equals("sha256")) {
                    throw new IOException("$import so aceita sha256 ao lado; campo invalido: " + field);
                }
            }
            JsonElement target = object.get("$import");
            if (!target.isJsonPrimitive() || !target.getAsJsonPrimitive().isString()) {
                throw new IOException("$import precisa ser um caminho ou URL em texto");
            }

            String expectedHash = null;
            if (object.has("sha256")) {
                JsonElement hash = object.get("sha256");
                if (!hash.isJsonPrimitive() || !hash.getAsJsonPrimitive().isString()) {
                    throw new IOException("sha256 precisa ser texto");
                }
                expectedHash = hash.getAsString();
            }

            String reference = target.getAsString();
            if (isRemote(reference)) {
                return loadRemote(reference, expectedHash, chain, depth);
            }
            return loadImported(reference, chain, depth);
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
            // Nao esta no disco: com uma base declarada, o mesmo caminho e buscado na rede.
            if (remoteBase != null) {
                return loadRemote(remoteBase + relativePath.replace('\\', '/'), null, chain, depth);
            }
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

    /** Um import é remoto quando aponta para um endereço de rede em vez de um arquivo. */
    private static boolean isRemote(String reference) {
        String lower = reference.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    /** Baixa um pedaço de manifesto da rede e resolve os imports que ele próprio contiver. */
    private JsonElement loadRemote(String url, String expectedHash, Deque<Path> chain, int depth)
            throws IOException {
        byte[] bytes = fetchRemote(url, expectedHash);
        Path cacheKey = cachePath(url, expectedHash);

        if (chain.contains(cacheKey)) {
            throw new IOException("import circular detectado em " + url);
        }

        JsonElement imported = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8));
        chain.push(cacheKey);
        try {
            return resolve(imported, chain, depth + 1);
        } finally {
            chain.pop();
        }
    }

    /**
     * Busca um recurso remoto, com ou sem hash fixo.
     *
     * <p>Com {@code sha256} declarado, o conteudo e imutavel: uma vez baixado e verificado, o
     * cache responde para sempre e uma divergencia impede a carga.
     *
     * <p>Sem hash, o recurso e buscado a cada carga, para que o mod acompanhe o que foi publicado.
     * Nesse modo o loader confia no host: quem controla aquele endereco decide o que sera
     * carregado. Se a rede falhar, a ultima copia conhecida e usada para o mod nao morrer offline.
     */
    public byte[] fetchRemote(String url, String expectedHash) throws IOException {
        if (cacheDirectory == null) {
            throw new IOException("acesso remoto desabilitado neste contexto: " + url);
        }
        if (!url.trim().toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new IOException("recurso remoto exige https: " + url);
        }

        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException error) {
            throw new IOException("URL invalida: " + url, error);
        }

        Files.createDirectories(cacheDirectory);
        Path cached = cachePath(url, expectedHash);
        boolean pinned = expectedHash != null && !expectedHash.isBlank();

        // Conteudo fixado por hash nao muda: se ja esta em cache, nao volta a rede.
        if (pinned && Files.isRegularFile(cached)) {
            return Files.readAllBytes(cached);
        }

        byte[] bytes;
        try {
            bytes = download(uri);
        } catch (IOException error) {
            if (!pinned && Files.isRegularFile(cached)) {
                // Sem hash e sem rede: a ultima versao conhecida mantem o mod vivo.
                return Files.readAllBytes(cached);
            }
            throw error;
        }

        if (pinned) {
            String digest = sha256(bytes);
            if (!digest.equalsIgnoreCase(expectedHash)) {
                throw new IOException("sha256 nao confere para " + url
                        + "; esperado " + expectedHash + ", obtido " + digest);
            }
        }

        writeCache(cached, bytes);
        return bytes;
    }

    /**
     * Caminho de cache do recurso.
     *
     * <p>Fixado por hash, o proprio hash indexa o arquivo e o conteudo e definitivo. Sem hash, o
     * indice e derivado da URL, porque o conteudo daquele endereco pode mudar a cada publicacao.
     */
    private Path cachePath(String url, String expectedHash) throws IOException {
        if (expectedHash != null && !expectedHash.isBlank()) {
            return cacheDirectory.resolve(expectedHash.toLowerCase(Locale.ROOT) + ".fixed");
        }
        return cacheDirectory.resolve(sha256(url.trim().getBytes(StandardCharsets.UTF_8)) + ".latest");
    }

    private void writeCache(Path cached, byte[] bytes) throws IOException {
        Path temporary = Files.createTempFile(cacheDirectory, "download-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private byte[] download(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("download de import interrompido", error);
        }

        try (InputStream body = response.body()) {
            // Um redirecionamento pode ter saido de https; o destino final precisa valer a regra.
            if (!"https".equalsIgnoreCase(response.uri().getScheme())) {
                throw new IOException("import remoto redirecionou para fora de https: " + response.uri());
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("servidor retornou HTTP " + response.statusCode() + " para " + uri);
            }
            long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (declared > MAX_REMOTE_BYTES) {
                throw new IOException("import remoto excede o limite de " + MAX_REMOTE_BYTES + " bytes");
            }
            return readLimited(body, MAX_REMOTE_BYTES);
        }
    }

    private static byte[] readLimited(InputStream stream, long maxBytes) throws IOException {
        var buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int read;
        while ((read = stream.read(chunk)) != -1) {
            total += read;
            if (total > maxBytes) {
                throw new IOException("import remoto excede o limite de " + maxBytes + " bytes");
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 indisponivel", error);
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
