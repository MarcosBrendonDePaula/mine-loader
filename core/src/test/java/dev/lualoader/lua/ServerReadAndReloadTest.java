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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertThrows;

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

        @Override
        public int fuelBurnTime(String item) {
            return "minecraft:coal".equals(item) ? 1600 : 0;
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
    void scriptCanAskHowLongAnItemBurns(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // Quem responde e o jogo. Um mod que precisa de combustivel -- uma rede logistica com
        // gerador -- escreveria a propria tabela de carvao e tabua, e ela nasceria errada no
        // primeiro modpack: o combustivel de outro mod nao estaria nela.
        runtime.load(writeMod(root, "\"chat.send\", \"server.read\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("carvao " .. ctx.server.fuel_burn_time("minecraft:coal"))
                    ctx.server.broadcast("pedra " .. ctx.server.fuel_burn_time("minecraft:stone"))
                end)
                """));

        runtime.triggerAll("server_started", null);

        // Zero, e nao nil: "nao queima" e uma resposta, e devolver nil faria toda conta precisar de
        // um `or 0` antes de somar.
        assertEquals(List.of("carvao 1600", "pedra 0"), bridge.calls);
    }

    @Test
    void askingHowLongAnItemBurnsNeedsServerRead(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("carvao " .. ctx.server.fuel_burn_time("minecraft:coal"))
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertTrue(bridge.calls.isEmpty(), "perguntar o combustivel exige server.read");
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
    void reloadRefreshesCommandTreeAfterSuccessfulReplacement(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        AtomicInteger refreshes = new AtomicInteger();
        runtime.onCommandsChanged(refreshes::incrementAndGet);

        runtime.load(writeMod(root, "\"server.command.register\"", """
                mod.command("antigo", function(ctx) end)
                """));
        assertEquals(0, refreshes.get(), "carga inicial publica a árvore no bootstrap");

        Files.writeString(root.resolve("read_mod").resolve("main.lua"), """
                mod.command("novo", function(ctx) end)
                """, StandardCharsets.UTF_8);
        runtime.reload("read_mod");

        assertEquals(1, refreshes.get());
        assertTrue(runtime.commandNames().contains("novo"));
        assertTrue(!runtime.commandNames().contains("antigo"));
    }

    @Test
    void reloadRestoresOldCommandWhenReplacementFails(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"server.command.register\"", """
                mod.command("oi", function(ctx)
                    ctx.server.broadcast("versao antiga")
                end)
                """));
        Files.writeString(root.resolve("read_mod").resolve("main.lua"), """
                mod.command("oi", function(ctx)
                    ctx.server.broadcast("script quebrado")
                """
                , StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> runtime.reload("read_mod"));
        assertTrue(runtime.commandNames().contains("oi"));
        runtime.runCommand("oi", null, "");
        assertEquals(List.of("versao antiga"), bridge.calls);
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
