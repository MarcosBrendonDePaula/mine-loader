package dev.lualoader.install;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
import org.slf4j.Logger;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

/**
 * Instala um mod a partir do endereço do manifesto dele.
 *
 * <p>Instalar um mod é a operação mais poderosa do loader: acrescenta código ao servidor. Por isso
 * ela acontece em dois passos, e não em um. O primeiro baixa e valida sem gravar nada, e devolve o
 * que o mod <em>declara</em> — nome, versão e, principalmente, a lista de permissões que ele pede.
 * O segundo só grava depois que alguém leu aquilo e concordou.
 *
 * <p>Um passo só seria mais simples de usar e pior de confiar: quem cola um link não tem como saber
 * o que vem nele, e a lista de permissões é a única coisa que responde isso antes de o código rodar.
 *
 * <p><b>O que não é baixado.</b> Só o manifesto vem para o disco. Scripts e texturas continuam
 * remotos, resolvidos pelo caminho que o loader já tinha para mods publicados na web — é o que
 * {@code remote_base} sempre fez. Assim a instalação é um arquivo, e não uma árvore, e atualizar o
 * mod na origem atualiza para quem instalou.
 */
public final class ModInstaller {
    /** Teto do manifesto baixado. Um mod.json de verdade tem alguns kilobytes. */
    public static final long MAX_MANIFEST_BYTES = 256 * 1024;

    private final Logger logger;
    private final Path modsDirectory;
    private final HttpClient httpClient;

    public ModInstaller(Logger logger, Path modsDirectory) {
        this.logger = logger;
        this.modsDirectory = modsDirectory;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /**
     * O que o mod declara, lido sem gravar nada.
     *
     * @param manifestJson o manifesto já com {@code remote_base} preenchido, guardado para o
     *                     segundo passo não precisar baixar de novo -- e para o que foi mostrado
     *                     ser exatamente o que sera gravado
     */
    public record Preview(String id,
                          String name,
                          String version,
                          String description,
                          List<String> authors,
                          List<String> permissions,
                          int blocks,
                          int items,
                          boolean replacesExisting,
                          String sourceUrl,
                          String manifestJson,
                          String entrypoint,
                          String remoteBase) {
    }

    /** Falha de instalação com mensagem destinada a quem está no jogo, e não ao log. */
    public static final class InstallException extends RuntimeException {
        public InstallException(String message) {
            super(message);
        }
    }

    /**
     * Baixa e valida o manifesto, sem gravar.
     *
     * <p>A validação é a mesma da carga normal: o manifesto é escrito num diretório temporário e
     * passa pelo {@link ModLoader}. Reusar o carregador em vez de conferir os campos aqui é o que
     * garante que "instalou" e "carrega" queiram dizer a mesma coisa -- uma validação própria
     * divergiria da real na primeira regra nova.
     */
    public Preview preview(String url) throws IOException {
        String normalized = url == null ? "" : url.trim();
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("https://")) {
            throw new InstallException("o endereco precisa comecar com https://");
        }

        String body = fetch(normalized);

        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (RuntimeException error) {
            throw new InstallException("o endereco nao devolveu um mod.json valido");
        }

        // O remote_base sai do proprio endereco: e a pasta que contem o manifesto, que e onde os
        // scripts e as texturas do mod moram. Um manifesto que ja declare o seu manda, porque o
        // autor pode ter publicado os arquivos em outro lugar.
        if (!root.has("remote_base")) {
            root.addProperty("remote_base", parentOf(normalized));
        }
        String manifestJson = new Gson().toJson(root);

        ModManifest manifest = validate(manifestJson);
        boolean replaces = Files.isDirectory(modsDirectory.resolve(manifest.id));

        return new Preview(
                manifest.id,
                manifest.name == null ? manifest.id : manifest.name,
                manifest.version == null ? "" : manifest.version,
                manifest.description == null ? "" : manifest.description,
                List.copyOf(manifest.authors),
                List.copyOf(manifest.permissions),
                manifest.blocks == null ? 0 : manifest.blocks.size(),
                manifest.items == null ? 0 : manifest.items.size(),
                replaces,
                normalized,
                manifestJson,
                manifest.entrypoint,
                root.get("remote_base").getAsString());
    }

    /**
     * Grava o mod na pasta de mods.
     *
     * <p>Grava o mesmo texto que a prévia validou, e não uma nova busca: entre ver as permissões e
     * concordar com elas, o endereço poderia passar a servir outro conteúdo -- e quem concordou
     * teria concordado com o que leu, não com o que chegou depois.
     *
     * @return a pasta onde o mod foi gravado
     */
    public Path install(Preview preview) throws IOException {
        Path directory = modsDirectory.resolve(preview.id());
        Files.createDirectories(directory);
        Files.writeString(directory.resolve("mod.json"), preview.manifestJson(),
                StandardCharsets.UTF_8);

        // O entrypoint vem junto, e nao fica remoto como as texturas.
        //
        // Duas razoes, e a segunda e a que importa. A primeira e mecanica: o loader exige o
        // entrypoint em disco, entao um mod instalado sem ele seria recusado na carga seguinte.
        //
        // A segunda e que o script e codigo. Uma textura buscada a cada partida no maximo muda de
        // aparencia; um script buscado a cada partida pode mudar de comportamento depois de alguem
        // ter lido as permissoes e concordado. Gravar o script na instalacao e o que faz "o que eu
        // aprovei e o que roda" continuar verdade amanha.
        if (preview.entrypoint() != null && !preview.entrypoint().isBlank()) {
            String scriptUrl = preview.remoteBase() + preview.entrypoint();
            Path target = directory.resolve(preview.entrypoint()).normalize();
            if (!target.startsWith(directory)) {
                throw new InstallException("o entrypoint declarado sai da pasta do mod");
            }

            Files.createDirectories(target.getParent());
            Files.writeString(target, fetch(scriptUrl), StandardCharsets.UTF_8);
            logger.info("Entrypoint {} gravado a partir de {}", preview.entrypoint(), scriptUrl);
        }

        logger.info("Mod {} v{} instalado a partir de {}",
                preview.id(), preview.version(), preview.sourceUrl());
        return directory;
    }

    /** Remove um mod instalado. Devolve {@code false} quando ele nao existia. */
    public boolean uninstall(String modId) throws IOException {
        Path directory = modsDirectory.resolve(modId);
        if (!Files.isDirectory(directory)) return false;

        // Só o que está dentro da pasta do mod, e em ordem de folha para raiz: apagar de fora para
        // dentro deixaria diretorios nao vazios para tras.
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
        logger.info("Mod {} removido", modId);
        return true;
    }

    private ModManifest validate(String manifestJson) throws IOException {
        Path temporary = Files.createTempDirectory("lua-loader-install");
        try {
            // A pasta precisa se chamar como o mod: o loader confere isso, e um nome fixo aqui
            // faria todo manifesto ser recusado por um motivo que nao e dele.
            String declaredId = readId(manifestJson);
            Path modDirectory = temporary.resolve(declaredId);
            Files.createDirectories(modDirectory);
            Files.writeString(modDirectory.resolve("mod.json"), manifestJson,
                    StandardCharsets.UTF_8);

            // O entrypoint ainda nao foi baixado, e o loader exige o arquivo em disco. Um vazio
            // satisfaz a checagem sem fingir conteudo: o que vale e o manifesto ser aceito.
            String entrypoint = readEntrypoint(manifestJson);
            if (entrypoint != null && !entrypoint.isBlank()) {
                Path script = modDirectory.resolve(entrypoint).normalize();
                if (script.startsWith(modDirectory)) {
                    Files.createDirectories(script.getParent());
                    Files.writeString(script, "return {}", StandardCharsets.UTF_8);
                }
            }

            List<ModLoader.LoadedMod> found = new ModLoader(logger).discover(temporary);
            if (found.isEmpty()) {
                throw new InstallException(
                        "o manifesto foi recusado pelo loader; veja o log para o motivo");
            }
            return found.get(0).manifest();
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            } catch (IOException ignored) {
                // Uma pasta temporaria que sobrou nao justifica falhar uma instalacao que deu certo.
            }
        }
    }

    private static String readId(String manifestJson) {
        JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();
        if (!root.has("id")) throw new InstallException("o manifesto nao declara id");
        return root.get("id").getAsString();
    }

    private static String readEntrypoint(String manifestJson) {
        JsonObject root = JsonParser.parseString(manifestJson).getAsJsonObject();
        return root.has("entrypoint") ? root.get("entrypoint").getAsString() : null;
    }

    private String fetch(String url) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<byte[]> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

            if (response.statusCode() != 200) {
                throw new InstallException("o endereco respondeu " + response.statusCode());
            }
            if (response.body().length > MAX_MANIFEST_BYTES) {
                throw new InstallException("o manifesto passa de "
                        + (MAX_MANIFEST_BYTES / 1024) + " KiB");
            }
            return new String(response.body(), StandardCharsets.UTF_8);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("busca interrompida", error);
        } catch (IllegalArgumentException error) {
            throw new InstallException("endereco invalido: " + url);
        }
    }

    /** A pasta que contem o manifesto, com barra no fim. */
    private static String parentOf(String url) {
        int query = url.indexOf('?');
        String semQuery = query < 0 ? url : url.substring(0, query);
        int barra = semQuery.lastIndexOf('/');
        return barra < 0 ? semQuery : semQuery.substring(0, barra + 1);
    }
}
