package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.PlayerHandle;
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

/**
 * Janela com lógica acoplada: o clique vira evento e o script redesenha.
 *
 * <p>É o que separa um painel de itens de uma interface: sem receber o clique, o mod só consegue
 * mostrar, nunca reagir.
 */
class MenuInteractionTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    private static final class FakePlayer implements PlayerHandle {
        final List<String> received = new ArrayList<>();
        String menuId;
        List<String> items = List.of();

        @Override
        public String name() {
            return "Steve";
        }

        @Override
        public String uuid() {
            return "00000000-0000-0000-0000-000000000002";
        }

        @Override
        public void sendMessage(String message) {
            received.add(message);
        }

        @Override
        public void sendActionBar(String message) {
            received.add("[bar] " + message);
        }

        @Override
        public String heldItem() {
            return "minecraft:air";
        }

        @Override
        public int countItem(String itemId) {
            return 0;
        }

        @Override
        public int giveItem(String itemId, int count) {
            return 0;
        }

        @Override
        public int takeItem(String itemId, int count) {
            return 0;
        }

        @Override
        public int[] position() {
            return new int[]{0, 64, 0};
        }

        @Override
        public float[] health() {
            return new float[]{20f, 20f};
        }

        @Override
        public void teleport(double x, double y, double z) {
        }

        @Override
        public void openMenu(String id, String title, int rows, List<String> items) {
            this.menuId = id;
            this.items = items;
        }

        @Override
        public boolean updateMenu(List<String> items) {
            if (menuId == null) return false;
            this.items = items;
            return true;
        }

        @Override
        public String openMenuId() {
            return menuId;
        }

        @Override
        public void closeMenu() {
            menuId = null;
        }
    }

    private ModLoader.LoadedMod writeMod(Path root, String lua) throws IOException {
        Path dir = root.resolve("ui_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "ui_mod",
                  "name": "UI Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "player.menu"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    private static final String CONTADOR = """
            mod.state.contador = mod.state.contador or 0

            local function desenhar()
                return {
                    { item = "minecraft:emerald", count = math.max(1, mod.state.contador), label = "Somar" },
                    { item = "minecraft:redstone", label = "Zerar" }
                }
            end

            mod.menu("painel", function(ctx)
                if ctx.menu.slot == 0 then
                    ctx.state.contador = ctx.state.contador + 1
                elseif ctx.menu.slot == 1 then
                    ctx.state.contador = 0
                end
                ctx.player.update_menu(desenhar())
                ctx.server.broadcast("clique no slot " .. ctx.menu.slot
                    .. " botao " .. ctx.menu.button
                    .. " item " .. ctx.menu.item
                    .. " contador " .. ctx.state.contador)
            end)

            mod.on("player_joined", function(ctx)
                ctx.player.open_menu("painel", "Painel", 1, desenhar())
            end)
            """;

    @Test
    void clickReachesTheScriptWithSlotAndItem(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, CONTADOR));

        runtime.triggerAll("player_joined", player);
        assertEquals("ui_mod:painel", player.menuId);

        runtime.triggerMenuClick("ui_mod", "ui_mod:painel", 0, 0, "minecraft:emerald", player);

        assertEquals(List.of("clique no slot 0 botao 0 item minecraft:emerald contador 1"),
                bridge.calls);
    }

    @Test
    void menuRedrawsWithoutClosing(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, CONTADOR));

        runtime.triggerAll("player_joined", player);
        runtime.triggerMenuClick("ui_mod", "ui_mod:painel", 0, 0, "minecraft:emerald", player);
        runtime.triggerMenuClick("ui_mod", "ui_mod:painel", 0, 0, "minecraft:emerald", player);

        // A janela continua aberta e o conteudo acompanha o estado.
        assertEquals("ui_mod:painel", player.openMenuId(), "a janela nao pode fechar ao redesenhar");
        assertTrue(player.items.get(0).startsWith("minecraft:emerald;2;"), player.items.toString());
    }

    @Test
    void clickFromAnotherModIsIgnored(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        FakePlayer player = new FakePlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(writeMod(root, CONTADOR));

        runtime.triggerAll("player_joined", player);
        // Mesmo id de menu, mas atribuido a outro mod: nao pode acionar o callback.
        runtime.triggerMenuClick("outro_mod", "ui_mod:painel", 0, 0, "minecraft:emerald", player);

        assertTrue(bridge.calls.isEmpty(), "uma janela pertence ao mod que a registrou");
    }

    @Test
    void commandReceivesSubcommandAndWords(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("ui_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "ui_mod",
                  "name": "UI Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("loja", function(ctx)
                    ctx.server.broadcast("sub=" .. ctx.subcommand
                        .. " n=" .. #ctx.argv
                        .. " segundo=" .. tostring(ctx.argv[2]))
                end)
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        runtime.runCommand("loja", null, "comprar diamante 3");

        assertEquals(List.of("sub=comprar n=3 segundo=diamante"), bridge.calls);
    }

    @Test
    void commandWithoutArgumentsHasEmptySubcommand(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);

        Path dir = root.resolve("ui_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "ui_mod",
                  "name": "UI Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send", "server.command.register"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), """
                mod.command("loja", function(ctx)
                    ctx.server.broadcast("vazio=" .. tostring(ctx.subcommand == "") .. " n=" .. #ctx.argv)
                end)
                """, StandardCharsets.UTF_8);

        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));
        runtime.runCommand("loja", null, "");

        assertEquals(List.of("vazio=true n=0"), bridge.calls);
    }
}
