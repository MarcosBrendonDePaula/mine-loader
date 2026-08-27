package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.BlockEventData;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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
    void worldEffectsUseSeparateBoundedOperations(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithCapabilities(root,
                "\"world.explode\", \"world.lightning\", \"chat.send\"",
                "\"world.explode\": \"1.0.0\", \"world.lightning\": \"1.0.0\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.explode(1.5, 64.25, -2.5, 4.0, true)
                    ctx.server.strike_lightning(3.5, 70.0, -4.5)
                    ctx.server.broadcast("efeitos")
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of("efeitos"), bridge.calls);
        assertArrayEquals(new double[]{1.5, 64.25, -2.5}, bridge.lastExplosionPosition);
        assertEquals(4.0f, bridge.lastExplosionPower);
        assertTrue(bridge.lastExplosionBreakBlocks);
        assertArrayEquals(new double[]{3.5, 70.0, -4.5}, bridge.lastLightningPosition);
    }

    @Test
    void equipmentAndSlotsAreExposedAsStableTables(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        player.slots.put(5, new PlayerHandle.ItemStackView("minecraft:iron_ingot", 12));
        player.equipment = new PlayerHandle.Equipment(
                new PlayerHandle.ItemStackView("minecraft:iron_sword", 1),
                new PlayerHandle.ItemStackView("minecraft:torch", 8),
                new PlayerHandle.ItemStackView("minecraft:iron_helmet", 1),
                new PlayerHandle.ItemStackView("minecraft:iron_chestplate", 1),
                new PlayerHandle.ItemStackView("minecraft:iron_leggings", 1),
                new PlayerHandle.ItemStackView("minecraft:iron_boots", 1));

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithCapabilities(root,
                "\"chat.send\", \"player.read\", \"player.inventory\"",
                "\"player.equipment.read\": \"1.0.0\", \"player.inventory.slot\": \"1.0.0\"", """
                mod.on("player_joined", function(ctx)
                    local slot = ctx.player.inventory_slot(5)
                    local gear = ctx.player.equipment()
                    ctx.player.set_inventory_slot(6, "minecraft:stone", 3)
                    ctx.server.broadcast(slot.item .. ":" .. slot.count .. "|"
                        .. gear.main_hand.item .. "|" .. gear.head.item)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("minecraft:iron_ingot:12|minecraft:iron_sword|minecraft:iron_helmet"),
                bridge.calls);
        assertEquals(new PlayerHandle.ItemStackView("minecraft:stone", 3), player.slots.get(6));
    }

    @Test
    void blockBrokenIsGlobalAndCanCancel(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("block_broken", function(ctx)
                    if ctx.block.id == "minecraft:iron_ore" then
                        ctx.player.send_message("bloqueado")
                        return false
                    end
                end)
                """));

        TestPlayer player = new TestPlayer();
        boolean cancelled = runtime.triggerBlock("block_broken", player,
                new BlockEventData("minecraft:iron_ore", 1, 2, 3, 0, 1));

        assertTrue(cancelled);
        assertEquals(List.of("bloqueado"), player.received);

        boolean allowed = runtime.triggerBlock("block_broken", player,
                new BlockEventData("minecraft:gold_ore", 1, 2, 3, 0, 1));
        assertTrue(!allowed);
        assertEquals(List.of("bloqueado"), player.received);
    }

    @Test
    void worldEffectsUseDefaultsAndRejectUnsafeArguments(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithCapabilities(root,
                "\"world.explode\", \"world.lightning\", \"chat.send\"",
                "\"world.explode\": \"1.0.0\", \"world.lightning\": \"1.0.0\"", """
                mod.on("server_started", function(ctx)
                    local default_ok = pcall(function() ctx.server.explode(0, 64, 0, 1) end)
                    local zero = pcall(function() ctx.server.explode(0, 64, 0, 0) end)
                    local high = pcall(function() ctx.server.explode(0, 64, 0, 9) end)
                    local infinite = pcall(function() ctx.server.explode(0, 64, 0, math.huge) end)
                    local bad_coordinate = pcall(function()
                        ctx.server.strike_lightning(math.huge, 70, 0)
                    end)
                    ctx.server.broadcast(tostring(default_ok) .. "|" .. tostring(zero)
                        .. "|" .. tostring(high) .. "|" .. tostring(infinite)
                        .. "|" .. tostring(bad_coordinate))
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of("true|false|false|false|false"), bridge.calls);
        assertEquals(1.0f, bridge.lastExplosionPower);
        assertTrue(!bridge.lastExplosionBreakBlocks);
        assertEquals(null, bridge.lastLightningPosition);
    }

    @Test
    void worldEffectsNeedPermissionAndCapability(@TempDir Path root) throws IOException {
        RecordingBridge missingPermissionBridge = new RecordingBridge();
        LuaRuntime missingPermissionRuntime = new LuaRuntime(LoggerFactory.getLogger("test"));
        missingPermissionRuntime.attach(missingPermissionBridge);
        missingPermissionRuntime.load(writeMod(root.resolve("permission"), "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    local ok = pcall(function() ctx.server.explode(0, 64, 0, 1) end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """));
        missingPermissionRuntime.triggerAll("server_started", null);

        RecordingBridge missingCapabilityBridge = new RecordingBridge();
        LuaRuntime missingCapabilityRuntime = new LuaRuntime(LoggerFactory.getLogger("test"));
        missingCapabilityRuntime.attach(missingCapabilityBridge);
        missingCapabilityRuntime.load(writeMod(root.resolve("capability"),
                "\"chat.send\", \"world.explode\"", """
                mod.on("server_started", function(ctx)
                    local ok = pcall(function() ctx.server.explode(0, 64, 0, 1) end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """));
        missingCapabilityRuntime.triggerAll("server_started", null);

        assertEquals(List.of("false"), missingPermissionBridge.calls);
        assertEquals(List.of("false"), missingCapabilityBridge.calls);
        assertEquals(null, missingPermissionBridge.lastExplosionPosition);
        assertEquals(null, missingCapabilityBridge.lastExplosionPosition);
    }

    @Test
    void slotsRejectUnsafeArgumentsAndZeroClears(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        player.slots.put(5, new PlayerHandle.ItemStackView("minecraft:iron_ingot", 12));

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithCapabilities(root,
                "\"chat.send\", \"player.read\", \"player.inventory\"",
                "\"player.equipment.read\": \"1.0.0\", \"player.inventory.slot\": \"1.0.0\"", """
                mod.on("player_joined", function(ctx)
                    local empty = ctx.player.inventory_slot(7)
                    ctx.player.set_inventory_slot(6, "minecraft:stone", 3)
                    local cleared = pcall(function()
                        ctx.player.set_inventory_slot(6, nil, 0)
                    end)
                    local negative = pcall(function()
                        ctx.player.inventory_slot(-1)
                    end)
                    local high_slot = pcall(function()
                        ctx.player.inventory_slot(64)
                    end)
                    local high_count = pcall(function()
                        ctx.player.set_inventory_slot(6, "minecraft:stone", 65)
                    end)
                    ctx.server.broadcast(empty.item .. ":" .. empty.count .. "|"
                        .. tostring(cleared) .. "|" .. tostring(negative) .. "|"
                        .. tostring(high_slot) .. "|" .. tostring(high_count))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("minecraft:air:0|true|false|false|false"), bridge.calls);
        assertEquals(null, player.slots.get(6));
    }

    @Test
    void emptyEquipmentUsesAirAndZero(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeModWithCapabilities(root,
                "\"chat.send\", \"player.read\"",
                "\"player.equipment.read\": \"1.0.0\"", """
                mod.on("player_joined", function(ctx)
                    local gear = ctx.player.equipment()
                    ctx.server.broadcast(gear.main_hand.item .. ":" .. gear.main_hand.count .. "|"
                        .. gear.off_hand.item .. ":" .. gear.off_hand.count .. "|"
                        .. gear.feet.item .. ":" .. gear.feet.count)
                end)
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of("minecraft:air:0|minecraft:air:0|minecraft:air:0"), bridge.calls);
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
