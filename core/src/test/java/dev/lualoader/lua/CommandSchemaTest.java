package dev.lualoader.lua;

import dev.lualoader.command.CommandSchema;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.manifest.ModManifest;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void manifestCommandBindsCallbackAndKeepsSchema(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("manifest_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "manifest_mod",
                  "name": "Manifest Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"],
                  "requires": {
                    "capabilities": {
                      "server.command.schema": "1.0.0"
                    }
                  },
                  "commands": {
                    "map": {
                      "children": [
                        { "literal": "on" },
                        { "literal": "zoom", "children": [
                          { "argument": {
                            "name": "level",
                            "type": "integer",
                            "min": 1,
                            "max": 4
                          }}
                        ]}
                      ]
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("map", function(ctx)
                    ctx.server.broadcast("json=" .. tostring(ctx.command.structured)
                        .. " level=" .. tostring(ctx.command.arguments.level + 1))
                end)
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        assertNotNull(runtime.commandSchema("map"));
        assertEquals(3, runtime.commandSchema("map").nodeCount());
        runtime.runCommand("map", null, "zoom 3", List.of("zoom", "3"), Map.of("level", "3"));

        assertEquals(List.of("json=true level=4"), bridge.calls);
    }

    @Test
    void manifestCommandsRequireSchemaCapability(@TempDir Path root) throws IOException {
        Path dir = root.resolve("missing_capability");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "missing_capability",
                  "name": "Missing Capability",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["server.command.register"],
                  "commands": {
                    "admin": { "children": [{ "literal": "status" }] }
                  }
                }
                """, StandardCharsets.UTF_8);

        assertTrue(new ModLoader(LoggerFactory.getLogger("test")).discover(root).isEmpty());
    }

    @Test
    void manifestCommandWithoutLuaCallbackIsRejected(@TempDir Path root) throws IOException {
        Path dir = root.resolve("missing_callback");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "missing_callback",
                  "name": "Missing Callback",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["server.command.register"],
                  "requires": {
                    "capabilities": {
                      "server.command.schema": "1.0.0"
                    }
                  },
                  "commands": {
                    "admin": { "children": [{ "literal": "status" }] }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        assertThrows(IOException.class,
                () -> runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0)));
        assertTrue(runtime.commandNames().isEmpty());
    }

    @Test
    void luaCanExtendManifestCommand(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("extend_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "extend_mod",
                  "name": "Extend Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"],
                  "requires": {
                    "capabilities": {
                      "server.command.schema": "1.0.0"
                    }
                  },
                  "commands": {
                    "admin": { "children": [{ "literal": "status" }] }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("admin", function(ctx)
                    ctx.server.broadcast("path=" .. ctx.command.path[1])
                end)
                mod.command_extend("admin", {
                    { literal = "reload" }
                })
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        assertEquals(2, runtime.commandSchema("admin").nodeCount());
        runtime.runCommand("admin", null, "reload", List.of("reload"), Map.of());

        assertEquals(List.of("path=reload"), bridge.calls);
    }

    @Test
    void commandExtendRejectsIncompatibleBranch(@TempDir Path root) throws IOException {
        Path dir = root.resolve("extend_conflict");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "extend_conflict",
                  "name": "Extend Conflict",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["server.command.register"],
                  "requires": { "capabilities": { "server.command.schema": "1.0.0" } },
                  "commands": { "admin": { "children": [{ "literal": "status" }] } }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("admin", function(ctx) end)
                mod.command_extend("admin", {
                    { argument = { name = "status", type = "word" } }
                })
                """, StandardCharsets.UTF_8);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        assertThrows(IOException.class,
                () -> runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0)));
        assertTrue(runtime.commandNames().isEmpty());
    }

    @Test
    void commandExtendRejectsUnknownCommand(@TempDir Path root) throws IOException {
        Path dir = root.resolve("extend_unknown");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "extend_unknown",
                  "name": "Extend Unknown",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["server.command.register"],
                  "requires": { "capabilities": { "server.command.schema": "1.0.0" } }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command_extend("missing", {
                    { literal = "status" }
                })
                """, StandardCharsets.UTF_8);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        assertThrows(IOException.class,
                () -> runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0)));
        assertTrue(runtime.commandNames().isEmpty());
    }

    @Test
    void manifestAndLuaSchemasCannotDisagree(@TempDir Path root) throws IOException {
        Path dir = root.resolve("conflict_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "conflict_mod",
                  "name": "Conflict Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["server.command.register"],
                  "requires": {
                    "capabilities": {
                      "server.command.schema": "1.0.0"
                    }
                  },
                  "commands": {
                    "map": { "children": [{ "literal": "on" }] }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("map", {
                    { literal = "off" }
                }, function(ctx)
                end)
                """, StandardCharsets.UTF_8);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        assertThrows(IOException.class,
                () -> runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0)));
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
    void manifestSchemaRequiresUsefulAndUnambiguousNodes() {
        ModManifest.CommandDefinition empty = new ModManifest.CommandDefinition();
        assertThrows(IllegalArgumentException.class, () -> CommandSchema.fromManifest(empty));

        ModManifest.CommandNodeDefinition both = new ModManifest.CommandNodeDefinition();
        both.literal = "on";
        both.argument = new ModManifest.CommandArgumentDefinition();
        both.argument.name = "value";
        assertThrows(IllegalArgumentException.class, () -> CommandSchema.fromManifest(definitionOf(both)));

        ModManifest.CommandNodeDefinition neither = new ModManifest.CommandNodeDefinition();
        assertThrows(IllegalArgumentException.class, () -> CommandSchema.fromManifest(definitionOf(neither)));
    }

    @Test
    void mergePreservesOrderAndCombinesCompatibleBranches() {
        CommandSchema base = new CommandSchema(List.of(
                CommandSchema.Node.literal("status", true, List.of()),
                CommandSchema.Node.literal("admin", false, List.of(
                        CommandSchema.Node.literal("list", true, List.of())))));
        CommandSchema extension = new CommandSchema(List.of(
                CommandSchema.Node.literal("admin", true, List.of(
                        CommandSchema.Node.literal("reload", true, List.of()))),
                CommandSchema.Node.literal("help", true, List.of())));

        CommandSchema merged = base.merge(extension);
        assertEquals(List.of("status", "admin", "help"),
                merged.roots().stream().map(CommandSchema.Node::name).toList());
        assertTrue(merged.roots().get(1).executable());
        assertEquals(List.of("list", "reload"),
                merged.roots().get(1).children().stream().map(CommandSchema.Node::name).toList());
    }

    @Test
    void mergeRejectsLiteralArgumentAndDifferentArgumentDefinitions() {
        CommandSchema literal = new CommandSchema(List.of(
                CommandSchema.Node.literal("value", true, List.of())));
        CommandSchema argument = new CommandSchema(List.of(
                CommandSchema.Node.argument(
                        new CommandSchema.Argument("value", "word", null, null, List.of()),
                        true, List.of())));
        assertThrows(IllegalArgumentException.class, () -> literal.merge(argument));

        CommandSchema word = new CommandSchema(List.of(CommandSchema.Node.argument(
                new CommandSchema.Argument("value", "word", null, null, List.of()), true, List.of())));
        CommandSchema integer = new CommandSchema(List.of(CommandSchema.Node.argument(
                new CommandSchema.Argument("value", "integer", 1.0, 4.0, List.of("1", "2")),
                true, List.of())));
        assertThrows(IllegalArgumentException.class, () -> word.merge(integer));
    }

    @Test
    void schemaRejectsConflictingArgumentTypes() {
        CommandSchema.Argument word = new CommandSchema.Argument("value", "word", null, null, List.of());
        CommandSchema.Argument integer = new CommandSchema.Argument("value", "integer", null, null, List.of());

        assertThrows(IllegalArgumentException.class, () -> new CommandSchema(List.of(
                CommandSchema.Node.argument(word, true, List.of()),
                CommandSchema.Node.argument(integer, true, List.of()))));
    }

    private static ModManifest.CommandDefinition definitionOf(ModManifest.CommandNodeDefinition node) {
        ModManifest.CommandDefinition definition = new ModManifest.CommandDefinition();
        definition.children = new ArrayList<>(List.of(node));
        return definition;
    }
}
