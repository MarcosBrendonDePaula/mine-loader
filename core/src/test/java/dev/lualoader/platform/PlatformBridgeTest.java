package dev.lualoader.platform;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prova que o núcleo executa mods sem nenhuma plataforma de jogo presente.
 *
 * <p>Estes testes rodam no módulo `core`, cujo classpath não contém Minecraft nem Fabric.
 * Passar aqui significa que a lógica de mods está de fato desacoplada da plataforma e que
 * um novo adaptador só precisa implementar {@link GameBridge} e {@link PlayerHandle}.
 */
class PlatformBridgeTest {

    /** Bridge de teste: registra as chamadas em vez de tocar em um jogo real. */
    private static final class RecordingBridge implements GameBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add("broadcast:" + message);
        }

        @Override
        public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
            calls.add("variant:" + blockId + "@" + x + "," + y + "," + z + "=" + variant);
        }

        @Override
        public void setBlockProperty(String blockId, String property, float value) {
            calls.add("property:" + blockId + "." + property + "=" + value);
        }

        @Override
        public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
            calls.add("luminance:" + blockId + "@" + x + "," + y + "," + z + "=" + luminance);
        }

        @Override
        public boolean isWorldAvailable() {
            return true;
        }

        /** Mundo simulado: so guarda o que foi escrito, o resto e ar. */
        final java.util.Map<String, String> world = new java.util.HashMap<>();

        @Override
        public String getBlock(int x, int y, int z) {
            return world.getOrDefault(x + "," + y + "," + z, "minecraft:air");
        }

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            world.put(x + "," + y + "," + z, blockId);
            calls.add("set:" + blockId + "@" + x + "," + y + "," + z);
        }

        @Override
        public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
            int changed = 0;
            for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
                for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                    for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                        world.put(x + "," + y + "," + z, blockId);
                        changed++;
                    }
                }
            }
            calls.add("fill:" + blockId + "=" + changed);
            return changed;
        }
    }

    private static final class FakePlayer implements PlayerHandle {
        final List<String> received = new ArrayList<>();

        @Override
        public String name() {
            return "Steve";
        }

        @Override
        public String uuid() {
            return "00000000-0000-0000-0000-000000000001";
        }

        @Override
        public void sendMessage(String message) {
            received.add(message);
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String permissions, String lua) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s]
                }
                """.formatted(permissions), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        List<ModLoader.LoadedMod> mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "o mod de teste deveria ter sido descoberto");
        return mods.get(0);
    }

    @Test
    void luaReachesTheGameOnlyThroughTheBridge(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"world.write\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.broadcast("ola")
                    ctx.server.set_block_variant("test_mod:bloco", 1, 2, 3, 7)
                    ctx.server.set_block_property("test_mod:bloco", "hardness", 4)
                    ctx.server.set_block_luminance("test_mod:bloco", 1, 2, 3, 9)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of(
                "broadcast:ola",
                "variant:test_mod:bloco@1,2,3=7",
                "property:test_mod:bloco.hardness=4.0",
                "luminance:test_mod:bloco@1,2,3=9"
        ), bridge.calls, "cada operação Lua deve virar exatamente uma chamada de bridge");
    }

    @Test
    void playerHandleIsNeutral(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"player.read\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.send_message("bem-vindo, " .. ctx.player.name)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("bem-vindo, Steve"), player.received);
    }

    @Test
    void permissionIsCheckedBeforeTheBridgeIsCalled(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // O manifesto não declara world.write.
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.set_block_variant("test_mod:bloco", 1, 2, 3, 7)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "a bridge não pode ser chamada sem a permissão declarada");
    }

    @Test
    void detachedBridgeRefusesGameOperations() {
        assertThrows(BridgeException.class, () -> GameBridge.DETACHED.broadcast("x"));
        assertThrows(BridgeException.class, () -> GameBridge.DETACHED.setBlockVariant("a:b", 0, 0, 0, 0));
    }

    @Test
    void blockEventCyclesThroughDeclaredVariants(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"world.write\"", """
                mod.on("block_used", function(ctx)
                    local proxima = (ctx.block.variant + 1) % ctx.block.variant_count
                    ctx.server.set_block_variant(ctx.block.id, ctx.block.x, ctx.block.y, ctx.block.z, proxima)
                end)
                """));

        // Duas variantes declaradas: 0 -> 1 -> 0.
        runtime.triggerBlock("block_used", player, new BlockEventData("test_mod:bloco", 4, 5, 6, 0, 2));
        runtime.triggerBlock("block_used", player, new BlockEventData("test_mod:bloco", 4, 5, 6, 1, 2));

        assertEquals(List.of(
                "variant:test_mod:bloco@4,5,6=1",
                "variant:test_mod:bloco@4,5,6=0"
        ), bridge.calls, "cada clique deve avancar para a proxima variante e voltar ao inicio");
    }

    @Test
    void blockContextIsAbsentOnNonBlockEvents(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    if ctx.block == nil then
                        ctx.server.broadcast("sem bloco")
                    end
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of("broadcast:sem bloco"), bridge.calls);
    }

    @Test
    void blockEventsReachOnlyTheOwningMod(@TempDir Path root) throws IOException {
        // Dois mods registram block_used; cada um so pode ver o proprio bloco.
        for (String modId : List.of("alpha_mod", "beta_mod")) {
            Path dir = root.resolve(modId);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("mod.json"), """
                    {
                      "schema": 1,
                      "id": "%s",
                      "name": "%s",
                      "version": "0.1.0",
                      "entrypoint": "main.lua",
                      "permissions": ["chat.send"],
                      "blocks": [{"id": "stone", "name": "Stone"}]
                    }
                    """.formatted(modId, modId), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("main.lua"), """
                    mod.on("block_used", function(ctx)
                        ctx.server.broadcast("%s viu " .. ctx.block.id)
                    end)
                    """.formatted(modId), StandardCharsets.UTF_8);
        }

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (ModLoader.LoadedMod mod : new ModLoader(LoggerFactory.getLogger("test")).discover(root)) {
            runtime.load(mod);
        }

        runtime.triggerBlock("block_used", null,
                new BlockEventData("alpha_mod:stone", 0, 0, 0, 0, 1));

        assertEquals(List.of("broadcast:alpha_mod viu alpha_mod:stone"), bridge.calls,
                "beta_mod nao pode receber um evento de bloco do alpha_mod");
    }

    @Test
    void scriptCanBuildWithSetBlockAndFill(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"world.read\", \"world.write\"", """
                mod.on("server_started", function(ctx)
                    -- Uma base solida e um pilar: o basico de qualquer construcao.
                    ctx.server.fill("minecraft:stone", 0, 0, 0, 2, 0, 2)
                    ctx.server.set_block("minecraft:glowstone", 1, 1, 1)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertEquals(List.of(
                "fill:minecraft:stone=9",
                "set:minecraft:glowstone@1,1,1"
        ), bridge.calls);
        assertEquals("minecraft:glowstone", bridge.getBlock(1, 1, 1));
        assertEquals("minecraft:stone", bridge.getBlock(2, 0, 2));
    }

    @Test
    void scriptCanReadBlocksBack(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"world.read\", \"world.write\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.set_block("minecraft:dirt", 5, 6, 7)
                    ctx.server.broadcast("li: " .. ctx.server.get_block(5, 6, 7))
                    ctx.server.broadcast("vazio: " .. ctx.server.get_block(9, 9, 9))
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.contains("broadcast:li: minecraft:dirt"), bridge.calls.toString());
        assertTrue(bridge.calls.contains("broadcast:vazio: minecraft:air"), bridge.calls.toString());
    }

    @Test
    void oversizedFillIsRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // 100x100x100 = 1.000.000 de blocos, muito acima do limite.
        runtime.load(writeMod(root, "\"world.write\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.fill("minecraft:stone", 0, 0, 0, 99, 99, 99)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "um fill gigante nao pode chegar a plataforma");
    }

    @Test
    void worldWriteRequiresItsOwnPermission(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // Le o mundo, mas nao declara world.write.
        runtime.load(writeMod(root, "\"world.read\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.set_block("minecraft:stone", 0, 0, 0)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "escrever sem world.write deve ser barrado");
    }

    @Test
    void absurdCoordinatesAreRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"world.write\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.set_block("minecraft:stone", 999999999, 0, 0)
                end)
                """));

        runtime.triggerAll("server_started", null);

        assertTrue(bridge.calls.isEmpty(), "coordenada fora do mundo deve ser barrada");
    }
}
