package dev.lualoader.ui;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Telas desenhadas: validação no núcleo e o evento voltando ao mod dono.
 *
 * <p>Estes testes rodam sem cliente e sem rede. É a razão de a validação estar no núcleo: um erro de
 * descrição precisa aparecer como mensagem para quem escreveu o mod, e ser verificável aqui.
 */
class ScreenTest {

    private static final class RecordingBridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
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

    private LuaRuntime runtime(RecordingBridge bridge) {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        return runtime;
    }

    @Test
    void screenIsDescribedAsData(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("forja", {
                        title = "Forja",
                        width = 200,
                        height = 120,
                        elements = {
                            { type = "panel", x = 0, y = 0, w = 200, h = 120, color = "#202020" },
                            { type = "label", x = 8, y = 8, text = "Bem-vindo", color = "#FFD700" },
                            { type = "progress", x = 8, y = 24, w = 180, h = 6, progress = 0.5 },
                            { type = "button", id = "forjar", x = 8, y = 40, w = 60, h = 20, text = "Forjar" }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals("ui_mod:forja", player.screenId, "o id da tela e prefixado pelo mod");
        assertTrue(player.screenJson.contains("\"version\":1"), player.screenJson);
        assertTrue(player.screenJson.contains("\"title\":\"Forja\""), player.screenJson);
        assertTrue(player.screenJson.contains("\"type\":\"button\""), player.screenJson);
        // Cor sem alfa e normalizada para a forma completa.
        assertTrue(player.screenJson.contains("#202020FF"), player.screenJson);
    }

    @Test
    void clickReturnsToTheOwningMod(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.screen("forja", function(ctx)
                    ctx.server.broadcast(ctx.ui.element .. ":" .. ctx.ui.action .. ":" .. ctx.ui.value)
                end)
                """));

        runtime.triggerScreenEvent("ui_mod:forja", "forjar", "click", "", player);
        runtime.triggerScreenEvent("ui_mod:forja", "nome", "change", "cristal", player);

        assertEquals(List.of("forjar:click:", "nome:change:cristal"), bridge.calls);
    }

    @Test
    void unknownActionFromClientIsDiscarded(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.screen("forja", function(ctx)
                    ctx.server.broadcast("nao deveria rodar")
                end)
                """));

        // O vocabulario e fechado: o cliente nao inventa acoes.
        runtime.triggerScreenEvent("ui_mod:forja", "x", "executar_codigo", "", player);

        assertTrue(bridge.calls.isEmpty(), "acao fora do protocolo precisa ser descartada");
    }

    @Test
    void eventForAnotherModsScreenIsIgnored(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.screen("forja", function(ctx)
                    ctx.server.broadcast("nao deveria rodar")
                end)
                """));

        runtime.triggerScreenEvent("outro_mod:forja", "x", "click", "", player);
        assertTrue(bridge.calls.isEmpty(), "uma tela pertence ao mod que a registrou");
    }

    @Test
    void clientWithoutLoaderGetsFalse(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        player.screensSupported = false;
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local abriu = ctx.player.open_screen("forja", {
                        elements = { { type = "label", x = 0, y = 0, text = "oi" } }
                    })
                    ctx.server.broadcast("abriu=" .. tostring(abriu))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        // O mod precisa poder decidir o que fazer, e nao descobrir por uma tela que nunca aparece.
        assertEquals(List.of("abriu=false"), bridge.calls);
        assertNull(player.screenId);
    }

    @Test
    void invalidDescriptionsAreRefusedWithAClearMessage(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    -- Botao sem id: o evento voltaria sem dono.
                    ctx.player.open_screen("ruim", {
                        elements = { { type = "button", x = 0, y = 0, text = "sem id" } }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertNull(player.screenId, "uma descricao invalida nao pode chegar ao cliente");
    }

    @Test
    void unknownElementTypeIsRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("ruim", {
                        elements = { { type = "webview", x = 0, y = 0 } }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertNull(player.screenId, "tipo fora do protocolo nao pode ser enviado");
    }

    @Test
    void tooManyElementsIsRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local muitos = {}
                    for indice = 1, 400 do
                        muitos[indice] = { type = "label", x = 0, y = indice, text = "linha" }
                    end
                    ctx.player.open_screen("grande", { elements = muitos })
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertNull(player.screenId, "uma tela acima do limite travaria o cliente");
    }

    @Test
    void hudAcceptsElementsAndClears(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.set_hud({
                        { type = "label", x = 4, y = 4, text = "Missao 3/8", color = "#FFD700" },
                        { type = "progress", x = 4, y = 16, w = 80, h = 4, progress = 0.375 }
                    })
                end)

                mod.on("player_left", function(ctx)
                    ctx.player.set_hud({})
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertTrue(player.hudJson.contains("Missao 3/8"), player.hudJson);
        assertTrue(player.hudJson.contains("\"progress\":0.375"), player.hudJson);

        runtime.triggerAll("player_left", player);
        assertTrue(player.hudJson.contains("\"elements\":[]"), player.hudJson);
    }

    @Test
    void protocolVocabularyIsClosed() {
        // Documenta o contrato: quem acrescentar uma acao precisa fazer aqui, e nao no cliente.
        assertEquals(java.util.Set.of("click", "change", "submit", "close"), ScreenProtocol.ACTIONS);
        assertTrue(ScreenProtocol.INTERACTIVE.stream().allMatch(ScreenProtocol.ELEMENTS::contains),
                "todo elemento interativo precisa ser um elemento valido");
        assertFalse(ScreenProtocol.ELEMENTS.contains("script"),
                "o cliente interpreta dados, nunca codigo");
    }

    @Test
    void backgroundEffectsAreOptional(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Sem declarar nada: sem desfoque, com escurecimento. O desfoque do jogo serve a um menu de
        // pausa, mas atrapalha um painel consultado durante a partida.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("padrao", {
                        elements = { { type = "label", x = 0, y = 0, text = "oi" } }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"blur\":false"), player.screenJson);
        assertTrue(player.screenJson.contains("\"dim\":true"), player.screenJson);
    }

    @Test
    void modCanAskForBlur(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("menu", {
                        blur = true,
                        dim = false,
                        elements = { { type = "label", x = 0, y = 0, text = "oi" } }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"blur\":true"), player.screenJson);
        assertTrue(player.screenJson.contains("\"dim\":false"), player.screenJson);
    }
}
