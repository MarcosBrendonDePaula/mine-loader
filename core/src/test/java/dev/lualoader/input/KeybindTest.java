package dev.lualoader.input;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.TestPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeybindTest {
    @Test
    void bindingIsDeclaredPublishedAndTriggeredOnServer(@TempDir Path root) throws IOException {
        ModLoader.LoadedMod mod = writeMod(root, "\"chat.send\", \"client.input.register\"", """
                mod.keybind("toggle", function(ctx)
                    ctx.player.send_message(ctx.keybind.id .. "|" .. ctx.keybind.key .. "|"
                        .. ctx.keybind.action .. "|" .. ctx.keybind.modifiers[1])
                end)
                """);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("keybind-test"));
        runtime.load(mod);

        assertEquals(1, runtime.keybindDefinitions().size());
        KeybindProtocol.Binding binding = runtime.keybindDefinitions().get(0);
        assertEquals("test_mod:toggle", binding.qualifiedId());
        assertEquals(List.of("shift"), binding.modifiers());
        assertEquals(List.of(binding), KeybindProtocol.decode(KeybindProtocol.encode(List.of(binding))));

        TestPlayer player = new TestPlayer();
        assertTrue(runtime.triggerKeybind("test_mod:toggle", player));
        assertEquals(List.of("toggle|key.keyboard.m|pressed|shift"), player.received);
    }

    @Test
    void keybindsRequireTheirPermission(@TempDir Path root) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest("\"chat.send\""), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        assertTrue(new ModLoader(LoggerFactory.getLogger("keybind-test")).discover(root).isEmpty());
    }

    @Test
    void scriptCannotRegisterUndeclaredKeybind(@TempDir Path root) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["client.input.register"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.keybind("toggle", function(ctx) end)
                """, StandardCharsets.UTF_8);
        ModLoader.LoadedMod mod = new ModLoader(LoggerFactory.getLogger("keybind-test"))
                .discover(root).get(0);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("keybind-test"));
        assertThrows(IOException.class, () -> runtime.load(mod));
    }

    private ModLoader.LoadedMod writeMod(Path root, String permissions, String lua) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest(permissions), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        List<ModLoader.LoadedMod> mods = new ModLoader(LoggerFactory.getLogger("keybind-test")).discover(root);
        assertEquals(1, mods.size());
        return mods.get(0);
    }

    private String manifest(String permissions) {
        return """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s],
                  "keybinds": [
                    { "id": "toggle", "key": "key.keyboard.m", "category": "test" , "modifiers": ["shift"] }
                  ]
                }
                """.formatted(permissions);
    }
}
