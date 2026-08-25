package dev.lualoader.platform;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.ItemEventData;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private static final class RecordingBridge extends TestBridge {
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
        public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
            calls.add("sound:" + soundId + "@" + x + "," + y + "," + z);
        }

        @Override
        public void spawnParticles(String particleId, double x, double y, double z,
                                   int count, double spread) {
            calls.add("particles:" + particleId + "x" + count);
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

    /** Jogador de teste: a base cobre o contrato, e aqui ficam apenas os apelidos usados. */
    private static final class FakePlayer extends TestPlayer {
        String menuTitle;
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

    @Test
    void scriptCanCancelTheDefaultAction(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("block_used", function(ctx)
                    return false
                end)
                """));

        boolean cancelled = runtime.triggerBlock("block_used", null,
                new BlockEventData("test_mod:bloco", 0, 0, 0, 0, 1));

        assertTrue(cancelled, "devolver false deve cancelar a acao padrao");
    }

    @Test
    void observingScriptDoesNotCancel(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // Um script que so observa nao devolve nada; o jogo precisa seguir normalmente.
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("block_used", function(ctx)
                    ctx.server.broadcast("vi o clique")
                end)
                """));

        boolean cancelled = runtime.triggerBlock("block_used", null,
                new BlockEventData("test_mod:bloco", 0, 0, 0, 0, 1));

        assertFalse(cancelled, "nao devolver nada nao pode cancelar");
        assertEquals(List.of("broadcast:vi o clique"), bridge.calls);
    }

    @Test
    void stateIsSharedBetweenCallbacksAndSurvivesReload(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        var mod = writeMod(root, "\"chat.send\"", """
                mod.state.cliques = mod.state.cliques or 0

                mod.on("block_used", function(ctx)
                    ctx.state.cliques = ctx.state.cliques + 1
                    ctx.server.broadcast("cliques: " .. ctx.state.cliques)
                end)
                """);
        runtime.load(mod);

        var event = new BlockEventData("test_mod:bloco", 0, 0, 0, 0, 1);
        runtime.triggerBlock("block_used", null, event);
        runtime.triggerBlock("block_used", null, event);

        // Recarregar o script nao pode apagar o que o mod acumulou.
        runtime.reload("test_mod");
        runtime.triggerBlock("block_used", null, event);

        assertEquals(List.of(
                "broadcast:cliques: 1",
                "broadcast:cliques: 2",
                "broadcast:cliques: 3"
        ), bridge.calls);
    }

    @Test
    void stateIsNotSharedBetweenMods(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

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
                      "permissions": ["chat.send"]
                    }
                    """.formatted(modId, modId), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("main.lua"), """
                    mod.state.marca = "%s"

                    mod.on("server_started", function(ctx)
                        ctx.server.broadcast("%s vê " .. tostring(ctx.state.marca))
                    end)
                    """.formatted(modId, modId), StandardCharsets.UTF_8);
        }

        for (ModLoader.LoadedMod mod : new ModLoader(LoggerFactory.getLogger("test")).discover(root)) {
            runtime.load(mod);
        }
        runtime.triggerAll("server_started", null);

        assertEquals(List.of(
                "broadcast:alpha_mod vê alpha_mod",
                "broadcast:beta_mod vê beta_mod"
        ), bridge.calls, "cada mod so pode enxergar o proprio estado");
    }

    private ModLoader.LoadedMod writeItemMod(Path root, String lua) throws IOException {
        Path dir = root.resolve("test_mod");
        Files.createDirectories(dir);
        Files.createDirectories(dir.resolve("scripts"));
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "test_mod",
                  "name": "Test Mod",
                  "version": "0.1.0",
                  "permissions": ["chat.send", "world.write"],
                  "items": [
                    {
                      "id": "varinha",
                      "name": "Varinha",
                      "behavior": {
                        "on_use": "scripts/on_use.lua",
                        "on_use_on_block": "scripts/on_use_on_block.lua"
                      }
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("scripts/on_use.lua"), lua, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("scripts/on_use_on_block.lua"), """
                return function(ctx)
                    ctx.server.broadcast("usei em " .. ctx.item.target_block ..
                        " (" .. ctx.item.x .. "," .. ctx.item.y .. "," .. ctx.item.z .. ")")
                end
                """, StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size(), "o mod de item deveria carregar");
        return mods.get(0);
    }

    @Test
    void itemHandlerReceivesItsOwnEvent(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeItemMod(root, """
                return function(ctx)
                    ctx.server.broadcast("usei " .. ctx.item.id)
                end
                """));

        runtime.triggerItem("item_used", null,
                new ItemEventData("test_mod:varinha", null, 0, 0, 0, false));

        assertEquals(List.of("broadcast:usei test_mod:varinha"), bridge.calls);
    }

    @Test
    void itemUsedOnBlockCarriesTargetAndPosition(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeItemMod(root, """
                return function(ctx) end
                """));

        runtime.triggerItem("item_used_on_block", null,
                new ItemEventData("test_mod:varinha", "minecraft:stone", 4, 5, 6, true));

        assertEquals(List.of("broadcast:usei em minecraft:stone (4,5,6)"), bridge.calls);
    }

    @Test
    void itemEventCanBeCancelled(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeItemMod(root, """
                return function(ctx) return false end
                """));

        boolean cancelled = runtime.triggerItem("item_used", null,
                new ItemEventData("test_mod:varinha", null, 0, 0, 0, false));

        assertTrue(cancelled, "um item tambem deve poder impedir a acao padrao");
    }

    @Test
    void itemEventDoesNotLeakToOtherMods(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeItemMod(root, """
                return function(ctx)
                    ctx.server.broadcast("nao deveria ver")
                end
                """));

        // Item de outro mod: o handler declarado aqui nao pode ser chamado.
        runtime.triggerItem("item_used", null,
                new ItemEventData("outro_mod:varinha", null, 0, 0, 0, false));

        assertTrue(bridge.calls.isEmpty(), "evento de item pertence ao mod que declarou o item");
    }

    @Test
    void scriptCanMovePlayerInventory(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        player.inventory.put("minecraft:diamond", 5);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"player.read\", \"player.inventory\"", """
                mod.on("player_joined", function(ctx)
                    local tinha = ctx.player.count_item("minecraft:diamond")
                    local pagou = ctx.player.take_item("minecraft:diamond", 3)
                    ctx.player.give_item("minecraft:emerald", 1)
                    ctx.player.send_message("tinha " .. tinha .. ", paguei " .. pagou)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("tinha 5, paguei 3"), player.received);
        assertEquals(2, player.inventory.get("minecraft:diamond"));
        assertEquals(1, player.inventory.get("minecraft:emerald"));
    }

    @Test
    void inventoryNeedsItsOwnPermission(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        player.inventory.put("minecraft:diamond", 5);

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // Declara player.read, mas nao player.inventory: pode ver, nao pode mexer.
        runtime.load(writeMod(root, "\"player.read\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.take_item("minecraft:diamond", 5)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(5, player.inventory.get("minecraft:diamond"),
                "sem player.inventory o script nao pode remover itens");
    }

    @Test
    void readingPlayerNeedsPermissionToo(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        // player.read passou a proteger algo: antes era uma permissao sem uso.
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.send_message("mao: " .. ctx.player.held_item())
                end)
                """));

        runtime.triggerAll("player_joined", player);

        // O que este caso protege e o vazamento: o item na mao nao pode chegar a quem nao declarou
        // player.read. O jogador passou a receber um AVISO de que a chamada falhou -- que e outra
        // coisa, e existe para um mod quebrado nao virar silencio dentro do jogo.
        for (String mensagem : player.received) {
            assertFalse(mensagem.startsWith("mao: "),
                    "o item na mao vazou sem permissao: " + mensagem);
        }
        assertTrue(player.received.stream().anyMatch(m -> m.contains("permiss")),
                "quem jogou deveria saber que o mod tentou algo sem permissao: " + player.received);
    }

    @Test
    void teleportNeedsMovePermission(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, "\"player.move\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.teleport(10, 70, -20)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(10, player.position()[0]);
        assertEquals(70, player.position()[1]);
        assertEquals(-20, player.position()[2]);
    }

    @Test
    void scriptCanGiveFeedbackWithSoundAndParticles(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"world.read\"", """
                mod.on("block_used", function(ctx)
                    ctx.server.play_sound("minecraft:block.note_block.bell", ctx.block.x, ctx.block.y, ctx.block.z)
                    ctx.server.spawn_particles("minecraft:happy_villager", ctx.block.x, ctx.block.y, ctx.block.z, 12)
                end)
                """));

        runtime.triggerBlock("block_used", null, new BlockEventData("test_mod:bloco", 1, 2, 3, 0, 1));

        assertEquals(List.of(
                "sound:minecraft:block.note_block.bell@1,2,3",
                "particles:minecraft:happy_villagerx12"
        ), bridge.calls);
    }

    @Test
    void scheduledTaskRunsAfterTheRequestedTicks(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    mod.after(3, function(ctx2)
                        ctx2.server.broadcast("passaram 3 ticks")
                    end)
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertEquals(1, runtime.pendingTasks(), "a tarefa deveria estar agendada");

        runtime.advanceScheduler();
        runtime.advanceScheduler();
        assertTrue(bridge.calls.isEmpty(), "ainda nao venceu");

        runtime.advanceScheduler();
        assertEquals(List.of("broadcast:passaram 3 ticks"), bridge.calls);
        assertEquals(0, runtime.pendingTasks(), "a tarefa nao pode repetir");
    }

    @Test
    void failingScheduledTaskDoesNotStopTheOthers(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    mod.after(1, function(ctx2)
                        error("falhei de proposito")
                    end)
                    mod.after(1, function(ctx2)
                        ctx2.server.broadcast("eu rodei")
                    end)
                end)
                """));

        runtime.triggerAll("server_started", null);
        runtime.advanceScheduler();

        assertEquals(List.of("broadcast:eu rodei"), bridge.calls,
                "o erro de uma tarefa nao pode impedir a seguinte");
    }

    @Test
    void modCanRegisterAndRunCommand(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"server.command.register\"", """
                mod.command("saudacao", function(ctx)
                    ctx.player.send_message("ola, argumentos: " .. ctx.args)
                end)
                """));

        assertTrue(runtime.commandNames().contains("saudacao"));
        assertTrue(runtime.runCommand("saudacao", player, "mundo"));
        assertEquals(List.of("ola, argumentos: mundo"), player.received);
    }

    @Test
    void unknownCommandIsReported(@TempDir Path root) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new RecordingBridge());
        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("tick", function(ctx) end)
                """));

        assertFalse(runtime.runCommand("nao_existe", null, ""),
                "um comando nao registrado precisa ser reportado, nao ignorado");
    }

    @Test
    void registeringCommandNeedsPermission(@TempDir Path root) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new RecordingBridge());

        // server.command.register era uma permissao sem uso; agora protege o registro.
        // Registrar no corpo do script sem permissao derruba a carga do mod, que e o certo:
        // o manifesto promete algo que o codigo nao pode fazer.
        var mod = writeMod(root, "\"chat.send\"", """
                mod.command("proibido", function(ctx) end)
                """);

        assertThrows(IOException.class, () -> runtime.load(mod),
                "sem a permissao a carga do mod deve falhar");
        assertTrue(runtime.commandNames().isEmpty(),
                "e nenhum comando pode ficar registrado");
    }

    @Test
    void twoModsCannotShareACommandName(@TempDir Path root) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new RecordingBridge());

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
                      "permissions": ["server.command.register"]
                    }
                    """.formatted(modId, modId), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("main.lua"), """
                    mod.command("duplicado", function(ctx) end)
                    """, StandardCharsets.UTF_8);
        }

        for (ModLoader.LoadedMod mod : new ModLoader(LoggerFactory.getLogger("test")).discover(root)) {
            try {
                runtime.load(mod);
            } catch (IOException expected) {
                // O segundo mod falha ao registrar um nome ja tomado.
            }
        }
        assertEquals(1, runtime.commandNames().size(), "o nome pertence a quem registrou primeiro");
    }

    @Test
    void blockKeepsItsOwnDataPerPosition(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\", \"world.read\", \"world.write\"", """
                mod.on("block_used", function(ctx)
                    local dados = ctx.server.get_block_data(ctx.block.x, ctx.block.y, ctx.block.z)
                    dados.usos = (dados.usos or 0) + 1
                    dados.dono = "alguem"
                    ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z, dados)
                    ctx.server.broadcast("usos aqui: " .. dados.usos)
                end)
                """));

        var first = new BlockEventData("test_mod:bloco", 1, 1, 1, 0, 1);
        var second = new BlockEventData("test_mod:bloco", 9, 9, 9, 0, 1);

        runtime.triggerBlock("block_used", null, first);
        runtime.triggerBlock("block_used", null, first);
        // Outra posicao tem contagem propria: o dado pertence ao bloco, nao ao mod.
        runtime.triggerBlock("block_used", null, second);

        assertEquals(List.of(
                "broadcast:usos aqui: 1",
                "broadcast:usos aqui: 2",
                "broadcast:usos aqui: 1"
        ), bridge.calls);
    }

    @Test
    void blockDataSurvivesAsJson(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"world.read\", \"world.write\"", """
                mod.on("block_used", function(ctx)
                    ctx.server.set_block_data(ctx.block.x, ctx.block.y, ctx.block.z,
                        { texto = "ola", numero = 7, ligado = true, dentro = { profundo = 1 } })
                end)
                """));

        runtime.triggerBlock("block_used", null, new BlockEventData("test_mod:bloco", 2, 3, 4, 0, 1));

        String json = bridge.getBlockData(2, 3, 4);
        assertTrue(json.contains("\"texto\": \"ola\""), json);
        assertTrue(json.contains("\"numero\": 7"), json);
        assertTrue(json.contains("\"ligado\": true"), json);
        assertTrue(json.contains("\"profundo\": 1"), json);
    }

    @Test
    void scriptCanOpenAMenuForThePlayer(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"player.menu\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_menu("loja", "Loja", 3, {
                        { item = "minecraft:diamond", count = 5 },
                        "minecraft:emerald"
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.received.contains("[menu] Loja (3 linhas)"), player.received.toString());
        assertEquals("test_mod:loja", player.menuId, "o id do menu e prefixado pelo mod");
        assertEquals(List.of("minecraft:diamond;5;", "minecraft:emerald;1;"), player.menuItems);
    }

    @Test
    void menuNeedsItsOwnPermission(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_menu("loja", "Loja", 3, {})
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertNull(player.menuId, "abrir menu exige player.menu");
    }

    @Test
    void entityOperationsNeedTheirPermissions(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        runtime.load(writeMod(root, "\"chat.send\"", """
                mod.on("server_started", function(ctx)
                    ctx.server.spawn_entity("minecraft:pig", 0, 64, 0)
                    ctx.server.broadcast("invocou")
                end)
                """));

        runtime.triggerAll("server_started", null);
        assertTrue(bridge.calls.isEmpty(), "invocar entidade exige entity.spawn");
    }
}
