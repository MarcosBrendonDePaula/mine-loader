package dev.lualoader.manifest;

import dev.lualoader.resources.RemoteResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderCoreTest {
    @TempDir
    Path temp;

    @Test
    void discoversValidModAndBlock() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        Files.writeString(mod.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "demo_mod",
                  "name": "Demo",
                  "version": "1.0.0",
                  "entrypoint": "main.lua",
                  "blocks": [{
                    "id": "demo_block",
                    "name": "Demo Block",
                    "material": {"map_color": "red"},
                    "settings": {"hardness": 3.0, "resistance": 6.0}
                  }]
                }
                """);
        Files.writeString(mod.resolve("main.lua"), "return {}\n");

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(temp);
        assertEquals(1, mods.size());
        assertEquals("demo_mod", mods.getFirst().manifest().id);
        assertEquals("demo_block", mods.getFirst().manifest().blocks.getFirst().id);
    }

    @Test
    void rejectsEntrypointOutsideModDirectory() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        Files.writeString(mod.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "demo_mod",
                  "name": "Demo",
                  "version": "1.0.0",
                  "entrypoint": "../main.lua"
                }
                """);
        Files.writeString(temp.resolve("main.lua"), "return {}\n");

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(temp);
        assertTrue(mods.isEmpty());
    }

    @Test
    void resolvesAndHashesLocalTexture() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        Path texture = mod.resolve("assets/demo_mod/textures/block/demo.png");
        Files.createDirectories(texture.getParent());
        BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        ImageIO.write(image, "png", texture.toFile());

        var definition = new ModManifest.TextureDefinition();
        definition.source = "local";
        definition.path = "assets/demo_mod/textures/block/demo.png";
        var manager = new RemoteResourceManager(temp.resolve("cache"));
        var resolved = manager.resolveTexture(mod, definition);

        assertEquals(16, resolved.width());
        assertEquals(16, resolved.height());
        assertEquals(64, resolved.sha256().length());
        assertTrue(Files.isRegularFile(resolved.path()));
    }

    /**
     * O cache responde sem abrir conexão, quando o manifesto declara o hash.
     *
     * <p>A URL aponta para um host que não existe: se houvesse download, o teste falharia. É a única
     * forma honesta de provar ausência de rede — contar downloads exigiria um servidor de mentira, e
     * um servidor de mentira que responde rápido esconderia exatamente o custo que se quer evitar.
     *
     * <p>Até aqui o recurso era baixado <b>sempre</b>: o cache era indexado pelo hash do conteúdo, e
     * o conteúdo só se conhecia depois de baixar. Ele economizava disco e não economizava rede.
     */
    @Test
    void cacheRespondeSemBaixarQuandoOHashEDeclarado() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        Path cache = temp.resolve("cache");
        var manager = new RemoteResourceManager(cache);

        byte[] conteudo = "modelo de mentira".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(conteudo));

        Files.createDirectories(cache);
        Files.write(cache.resolve(hash + ".img"), conteudo);

        var resource = new ModManifest.ResourceDefinition();
        resource.from = "https://host.que.nao.existe.invalid/modelo.json";
        resource.sha256 = hash;

        assertArrayEquals(conteudo, manager.resolveBytes(mod, resource, null));
    }

    /** Sem o hash declarado não há como consultar o cache antes: a chave é o próprio conteúdo. */
    @Test
    void semHashDeclaradoOCacheNaoTemComoResponderAntes() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        var manager = new RemoteResourceManager(temp.resolve("cache"));

        var resource = new ModManifest.ResourceDefinition();
        resource.from = "https://host.que.nao.existe.invalid/modelo.json";

        assertThrows(IOException.class, () -> manager.resolveBytes(mod, resource, null));
    }

    @Test
    void rejectsOversizedLocalTexture() throws Exception {
        Path mod = Files.createDirectories(temp.resolve("demo_mod"));
        Path texture = mod.resolve("big.bin");
        Files.write(texture, new byte[2048]);

        var definition = new ModManifest.TextureDefinition();
        definition.source = "local";
        definition.path = "big.bin";
        definition.maxBytes = 1024;
        var manager = new RemoteResourceManager(temp.resolve("cache"));

        assertThrows(IOException.class, () -> manager.resolveTexture(mod, definition));
    }
}
