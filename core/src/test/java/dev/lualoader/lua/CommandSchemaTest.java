package dev.lualoader.lua;

import dev.lualoader.command.CommandSchema;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.TestBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CommandSchemaTest {
    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    @Test
    void structuredCommandExposesNamedArguments(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("schema_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "schema_mod",
                  "name": "Schema Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"],
                  "requires": {
                    "capabilities": {
                      "server.command.schema": "1.0.0"
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("map", {
                    { literal = "on" },
                    { literal = "zoom", children = {
                        { argument = {
                            name = "level",
                            type = "integer",
                            min = 1,
                            max = 4,
                            suggestions = { "1", "2", "3", "4" }
                        }}
                    }}
                }, function(ctx)
                    ctx.server.broadcast("structured=" .. tostring(ctx.command.structured)
                        .. " action=" .. ctx.argv[1]
                        .. " level=" .. tostring(ctx.command.arguments.level + 1))
                end)
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        assertNotNull(runtime.commandSchema("map"));
        assertEquals(List.of("level"), runtime.commandSchema("map").argumentNames());

        runtime.runCommand("map", null, "zoom 3", List.of("zoom", "3"), Map.of("level", "3"));

        assertEquals(List.of("structured=true action=zoom level=4"), bridge.calls);
    }

    @Test
    void legacyCommandStillUsesTheOldOverload(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("legacy_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "legacy_mod",
                  "name": "Legacy Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("legacy", function(ctx)
                    ctx.server.broadcast("structured=" .. tostring(ctx.command.structured)
                        .. " sub=" .. ctx.subcommand)
                end)
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        assertEquals(null, runtime.commandSchema("legacy"));
        runtime.runCommand("legacy", null, "hello");

        assertEquals(List.of("structured=false sub=hello"), bridge.calls);
    }

    @Test
    void schemaRejectsConflictingArgumentTypes() {
        CommandSchema.Argument word = new CommandSchema.Argument("value", "word", null, null, List.of());
        CommandSchema.Argument integer = new CommandSchema.Argument("value", "integer", null, null, List.of());

        assertThrows(IllegalArgumentException.class, () -> new CommandSchema(List.of(
                CommandSchema.Node.argument(word, true, List.of()),
                CommandSchema.Node.argument(integer, true, List.of()))));
    }
}
