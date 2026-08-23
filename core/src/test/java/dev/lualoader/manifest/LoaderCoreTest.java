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
