package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.PlayerHandle;
import dev.lualoader.platform.TestBridge;
import dev.lualoader.platform.TestPlayer;
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

class UsefulApiTest {
    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();
        String droppedItem;
        double droppedX;
        double droppedY;
        double droppedZ;
        int droppedCount;

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }

        @Override
        public int dropItem(String itemId, double x, double y, double z, int count) {
            droppedItem = itemId;
            droppedX = x;
            droppedY = y;
            droppedZ = z;
            droppedCount = count;
            return count;
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String permissions, String lua) throws IOException {
        return writeModWithCapabilities(root, permissions, "", lua);
    }

    private ModLoader.LoadedMod writeModWithEvery(Path root, String permissions, String lua)
            throws IOException {
        return writeModWithCapabilities(root, permissions,
                "\"scheduler.every\": \"1.0.0\"", lua);
    }

    private ModLoader.LoadedMod writeModWithCapabilities(Path root, String permissions,
                                                          String capabilities, String lua)
            throws IOException {
        Path dir = root.resolve("useful_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "useful_mod",
                  "name": "Useful Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s],
                  "requires": {"capabilities": {%s}}
                }
                """.formatted(permissions, capabilities), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    @Test
    void playerSnapshotsAreStableLuaTables(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        player.effects = List.of(new PlayerHandle.ActiveEffect(
                "minecraft:speed", 120, 1, false, true));
        player.movement = new PlayerHandle.Movement(
                0.25, -0.08, 0.5, false, true, true, false, false, true);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\", \"player.read\"", """
                mod.on("player_joined", function(ctx)
                    local effects = ctx.player.effects()
                    local movement = ctx.player.movement()
                    local e = effects[1]
                    local v = movement.velocity
                    ctx.server.broadcast(#effects .. "|" .. e.id .. "|" .. e.duration
                        .. "|" .. e.amplifier .. "|" .. tostring(e.ambient)
                        .. "|" .. tostring(movement.on_ground)
                        .. "|" .. tostring(movement.sneaking)
                        .. "|" .. v.x .. "|" .. v.y .. "|" .. tostring(movement.gliding))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("1|minecraft:speed|120|1|false|false|true|0.25|-0.08|true"),
                bridge.calls);
    }

    @Test
    void dropItemUsesCoordinatesAndReturnsCreatedCount(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\", \"entity.spawn\"", """
                mod.on("server_started", function(ctx)
                    local dropped = ctx.server.drop_item("minecraft:diamond", 1.5, 64.25, -2.5, 130)
                    ctx.server.broadcast(tostring(dropped))
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of("130"), bridge.calls);
        assertEquals("minecraft:diamond", bridge.droppedItem);
        assertEquals(1.5, bridge.droppedX);
        assertEquals(64.25, bridge.droppedY);
        assertEquals(-2.5, bridge.droppedZ);
        assertEquals(130, bridge.droppedCount);
    }

    @Test
    void dropItemRejectsUnsafeQuantities(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\", \"entity.spawn\"", """
                mod.on("server_started", function(ctx)
                    local ok = pcall(function()
                        ctx.server.drop_item("minecraft:diamond", 0, 64, 0, 4097)
                    end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of("false"), bridge.calls);
        assertEquals(0, bridge.droppedCount);
    }

    @Test
    void everyRepeatsUntilCallbackReturnsFalse(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithEvery(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    local count = 0
                    mod.every(2, function(depois)
                        count = count + 1
                        depois.server.broadcast("run " .. count)
                        return count < 3
                    end)
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(1, runtime.pendingTasks());
        for (int tick = 0; tick < 6; tick++) runtime.advanceScheduler();

        assertEquals(List.of("run 1", "run 2", "run 3"), bridge.calls);
        assertEquals(0, runtime.pendingTasks());
    }

    @Test
    void everyNeedsItsCapability(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    mod.every(1, function(depois)
                        depois.server.broadcast("nao devia ser agendada")
                    end)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(0, runtime.pendingTasks());
        assertTrue(bridge.calls.isEmpty());
    }

    @Test
    void everyCanBeCancelledByOwner(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithEvery(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    local id = mod.every(1, function(depois)
                        depois.server.broadcast("nao devia correr")
                    end)
                    ctx.server.broadcast(tostring(mod.cancel(id)))
                end)
                """));

        runtime.triggerAll("server_started", null);
        for (int tick = 0; tick < 3; tick++) runtime.advanceScheduler();

        assertEquals(List.of("true"), bridge.calls);
        assertEquals(0, runtime.pendingTasks());
        assertTrue(bridge.droppedItem == null);
    }
}
