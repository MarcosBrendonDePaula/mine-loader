package dev.lualoader.lua;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Leitura de servidor e o que uma recarga precisa descartar. */
class ServerReadAndReloadTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }

        @Override
        public List<String> onlinePlayers() {
            return List.of("Steve", "Alex");
        }

        @Override
        public long timeOfDay() {
            return 13_000L;
        }

        @Override
        public String worldName() {
            return "minecraft:the_nether";
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String permissions, String lua) throws IOException {
        Path dir = root.resolve("read_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "read_mod",
                  "name": "Read Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s]
                }
                """.formatted(permissions), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    @Test
    void scriptCanReadServerState(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"server.read\"", """
                mod.on("server_started", function(ctx)
                    local nomes = ctx.server.players()
                    ctx.server.broadcast(#nomes .. " online, primeiro " .. nomes[1])
                    ctx.server.broadcast("hora " .. ctx.server.time_of_day())
                    ctx.server.broadcast("mundo " .. ctx.server.world_name())
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of(
                "2 online, primeiro Steve",
                "hora 13000",
                "mundo minecraft:the_nether"
        ), bridge.calls);
    }

    @Test
    void serverReadNowProtectsSomething(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // server.read era uma permissao declarada que nao protegia nada.
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("online: " .. #ctx.server.players())
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertTrue(bridge.calls.isEmpty(), "ler o servidor exige server.read");
    }

    @Test
    void reloadDiscardsPendingTasksFromTheOldScript(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    mod.after(5, function(depois)
                        depois.server.broadcast("tarefa do script antigo")
                    end)
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(1, runtime.pendingTasks());

        // A recarga descarta o ambiente antigo; uma tarefa dele chamaria uma funcao orfa.
        runtime.reload("read_mod");
        assertEquals(0, runtime.pendingTasks(), "a recarga precisa descartar as tarefas pendentes");

        for (int tick = 0; tick < 10; tick++) runtime.advanceScheduler();
        assertTrue(bridge.calls.isEmpty(), "nada do script antigo pode rodar depois da recarga");
    }

    @Test
    void reloadRebuildsCommandsInsteadOfDuplicating(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"server.command.register\"", """
                mod.command("oi", function(ctx)
                    ctx.server.broadcast("versao um")
                end)
                """));
        assertTrue(runtime.commandNames().contains("oi"));

        // Reescreve o script e recarrega: o comando precisa apontar para a versao nova.
        Files.writeString(root.resolve("read_mod").resolve("main.lua"), """
                mod.command("oi", function(ctx)
                    ctx.server.broadcast("versao dois")
                end)
                """, StandardCharsets.UTF_8);
        runtime.reload("read_mod");

        assertEquals(1, runtime.commandNames().size());
        runtime.runCommand("oi", null, "");
        assertEquals(List.of("versao dois"), bridge.calls);
    }
}
