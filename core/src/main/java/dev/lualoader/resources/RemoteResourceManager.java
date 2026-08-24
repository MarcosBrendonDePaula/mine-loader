package dev.lualoader.resources;

import dev.lualoader.manifest.ModManifest;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/** Resolve texturas locais e remotas fora da thread do jogo. */
public final class RemoteResourceManager {
    private static final long DEFAULT_MAX_BYTES = 1_048_576;
    private static final int MAX_IMAGE_DIMENSION = 4096;

    private final Path cacheDirectory;
    private final HttpClient httpClient;

    public RemoteResourceManager(Path cacheDirectory) throws IOException {
        this.cacheDirectory = cacheDirectory.toAbsolutePath().normalize();
        Files.createDirectories(this.cacheDirectory);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public ResolvedTexture resolveTexture(Path modDirectory,
                                          ModManifest.TextureDefinition definition) throws IOException {
        return resolveTexture(modDirectory, definition, null);
    }

    /**
     * @param remoteBase base usada quando a textura local nao existe, para mods publicados na web
     */
    public ResolvedTexture resolveTexture(Path modDirectory,
                                          ModManifest.TextureDefinition definition,
                                          String remoteBase) throws IOException {
        if (definition == null) {
            throw new IOException("textura ausente");
        }

        String source = definition.source == null
                ? "local"
                : definition.source.toLowerCase(Locale.ROOT);
        return switch (source) {
            case "local" -> resolveLocalOrBase(modDirectory, definition, remoteBase);
            case "remote" -> resolveRemote(definition);
            default -> throw new IOException("fonte de textura desconhecida: " + definition.source);
        };
    }

    /** Procura a textura no disco e, quando ha base declarada, no endereco correspondente. */
    private ResolvedTexture resolveLocalOrBase(Path modDirectory,
                                               ModManifest.TextureDefinition definition,
                                               String remoteBase) throws IOException {
        Path root = modDirectory.toAbsolutePath().normalize();
        Path file = definition.path == null ? null : root.resolve(definition.path).normalize();

        if (file != null && Files.isRegularFile(file)) {
            return resolveLocal(modDirectory, definition);
        }
        if (remoteBase != null && !remoteBase.isBlank() && definition.path != null) {
            String base = remoteBase.endsWith("/") ? remoteBase : remoteBase + "/";
            ModManifest.TextureDefinition asRemote = new ModManifest.TextureDefinition();
            asRemote.source = "remote";
            asRemote.url = base + definition.path.replace('\\', '/');
            asRemote.fallback = definition.fallback;
            asRemote.maxBytes = definition.maxBytes;
            asRemote.sha256 = definition.sha256;
            return resolveRemote(asRemote);
        }
        return resolveLocal(modDirectory, definition);
    }

    private ResolvedTexture resolveLocal(Path modDirectory,
                                         ModManifest.TextureDefinition definition) throws IOException {
        if (definition.path == null || definition.path.isBlank()) {
            throw new IOException("textura local sem path");
        }
        Path root = modDirectory.toAbsolutePath().normalize();
        Path file = root.resolve(definition.path).normalize();
        if (!file.startsWith(root)) {
            throw new IOException("path da textura sai da pasta do mod");
        }
        if (!Files.isRegularFile(file)) {
            throw new IOException("textura local não encontrada: " + definition.path);
        }

        long maxBytes = limit(definition.maxBytes);
        if (Files.size(file) > maxBytes) {
            throw new IOException("textura local excede o limite de " + maxBytes + " bytes");
        }
        byte[] bytes = Files.readAllBytes(file);
        ImageInfo image = validateImage(bytes);
        return new ResolvedTexture(file, sha256(bytes), image.width(), image.height(), false);
    }

    private ResolvedTexture resolveRemote(ModManifest.TextureDefinition definition) throws IOException {
        if (definition.url == null || definition.url.isBlank()) {
            throw new IOException("textura remota sem url");
        }

        URI uri;
        try {
            uri = URI.create(definition.url);
        } catch (IllegalArgumentException error) {
            throw new IOException("URL de textura inválida", error);
        }
        requireHttps(uri);

        long maxBytes = limit(definition.maxBytes);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "image/png,image/jpeg,image/*;q=0.8")
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("download de textura interrompido", error);
        }

        try (InputStream body = response.body()) {
            requireHttps(response.uri());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("servidor retornou HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > maxBytes) {
                throw new IOException("textura remota excede o limite de " + maxBytes + " bytes");
            }

            byte[] bytes = readLimited(body, maxBytes);
            String digest = sha256(bytes);
            if (definition.sha256 != null && !definition.sha256.isBlank()
                    && !digest.equalsIgnoreCase(definition.sha256)) {
                throw new IOException("hash SHA-256 da textura não confere");
            }

            ImageInfo image = validateImage(bytes);
            Path cached = cacheDirectory.resolve(digest + ".img");
            if (!Files.exists(cached)) {
                Path temporary = Files.createTempFile(cacheDirectory, "download-", ".tmp");
                try {
                    Files.write(temporary, bytes);
                    Files.move(temporary, cached, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            return new ResolvedTexture(cached, digest, image.width(), image.height(), true);
        }
    }

    /**
     * Resolve um recurso qualquer, sem supor que ele seja uma imagem.
     *
     * <p>Existe porque a resolução — local ou remoto, tamanho máximo, cache, integridade — é a
     * mesma para todo tipo de recurso, e só a validação difere. Um modelo é JSON, um som é áudio, e
     * nenhum dos dois passa por {@code ImageIO}; era isso que impedia o resolvedor de textura de
     * servir aos dois.
     *
     * @return os bytes do recurso, já conferidos contra o sha256 quando declarado
     */
    public byte[] resolveBytes(Path modDirectory, ModManifest.ResourceDefinition resource,
                               String remoteBase) throws IOException {
        if (resource == null || resource.from == null || resource.from.isBlank()) {
            throw new IOException("recurso sem origem");
        }

        byte[] bytes = ResourceCatalog.isRemote(resource.from)
                ? downloadBytes(resource.from, limit(resource.maxBytes))
                : localBytes(modDirectory, resource, remoteBase);

        if (resource.sha256 != null && !resource.sha256.isBlank()) {
            String digest = sha256(bytes);
            if (!digest.equalsIgnoreCase(resource.sha256)) {
                throw new IOException("hash SHA-256 do recurso nao confere");
            }
        }
        return bytes;
    }

    /** Le do disco do mod, caindo para a base remota quando o arquivo nao existe. */
    private byte[] localBytes(Path modDirectory, ModManifest.ResourceDefinition resource,
                              String remoteBase) throws IOException {
        Path root = modDirectory.toAbsolutePath().normalize();
        Path file = root.resolve(resource.from).normalize();

        if (!file.startsWith(root)) {
            throw new IOException("caminho do recurso sai da pasta do mod: " + resource.from);
        }
        if (!Files.isRegularFile(file)) {
            // Mesma regra da textura: um mod publicado na web referencia os proprios arquivos por
            // caminho relativo, e a base e onde eles moram de verdade.
            if (remoteBase != null && !remoteBase.isBlank()) {
                String base = remoteBase.endsWith("/") ? remoteBase : remoteBase + "/";
                return downloadBytes(base + resource.from.replace('\\', '/'),
                        limit(resource.maxBytes));
            }
            throw new IOException("recurso local nao encontrado: " + resource.from);
        }

        long maxBytes = limit(resource.maxBytes);
        if (Files.size(file) > maxBytes) {
            throw new IOException("recurso local excede o limite de " + maxBytes + " bytes");
        }
        return Files.readAllBytes(file);
    }

    private byte[] downloadBytes(String url, long maxBytes) throws IOException {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException error) {
            throw new IOException("URL de recurso invalida: " + url, error);
        }
        requireHttps(uri);

        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<InputStream> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("download de recurso interrompido", error);
        }

        try (InputStream body = response.body()) {
            requireHttps(response.uri());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException("servidor retornou HTTP " + response.statusCode());
            }
            return readLimited(body, maxBytes);
        }
    }

    private static byte[] readLimited(InputStream stream, long maxBytes) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        long total = 0;
        try (var output = new java.io.ByteArrayOutputStream()) {
            while ((read = stream.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new IOException("recurso excede o limite de " + maxBytes + " bytes");
                }
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static ImageInfo validateImage(byte[] bytes) throws IOException {
        BufferedImage image;
        try (ByteArrayInputStream input = new ByteArrayInputStream(bytes)) {
            image = ImageIO.read(input);
        }
        if (image == null) throw new IOException("arquivo não é uma imagem suportada");
        if (image.getWidth() > MAX_IMAGE_DIMENSION || image.getHeight() > MAX_IMAGE_DIMENSION) {
            throw new IOException("dimensões da textura excedem " + MAX_IMAGE_DIMENSION + "x" + MAX_IMAGE_DIMENSION);
        }
        return new ImageInfo(image.getWidth(), image.getHeight());
    }

    private static void requireHttps(URI uri) throws IOException {
        if (uri == null || !"https".equalsIgnoreCase(uri.getScheme())) {
            throw new IOException("somente URLs HTTPS são permitidas para recursos remotos");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IOException("URL remota sem host");
        }
    }

    private static long limit(long configured) {
        if (configured <= 0) return DEFAULT_MAX_BYTES;
        return Math.min(configured, 16L * 1024L * 1024L);
    }

    private static String sha256(byte[] bytes) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 indisponível", error);
        }
    }

    public record ResolvedTexture(Path path, String sha256, int width, int height, boolean remote) {
    }

    private record ImageInfo(int width, int height) {
    }
}
