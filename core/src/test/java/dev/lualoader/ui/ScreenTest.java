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
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        return writeMod(root, lua, "\"chat.send\", \"player.menu\", \"server.read\"");
    }

    private ModLoader.LoadedMod writeMod(Path root, String lua, String permissions) throws IOException {
        Path dir = root.resolve("ui_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "ui_mod",
                  "name": "UI Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s]
                }
                """.formatted(permissions), StandardCharsets.UTF_8);
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
    void overlayIsRegisteredForAGameScreen(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.set_overlay("catalogo", {
                        target = "inventory",
                        elements = {
                            { type = "panel", anchor = "gui_top_right", x = 4, y = 0,
                              w = 80, h = 166, color = "#101010C0" },
                            { type = "item", anchor = "gui_top_right", x = 10, y = 8,
                              item = "minecraft:iron_ingot", tooltip = "Lingote de ferro" },
                            { type = "button", id = "abrir", anchor = "gui_top_right",
                              x = 10, y = 30, w = 60, h = 20, text = "Receitas" }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        String json = player.overlays.get("ui_mod:catalogo");
        assertNotNull(json, "a sobreposicao precisa chegar prefixada pelo mod");
        assertTrue(json.contains("\"target\":\"inventory\""), json);
        // O tooltip ja existia no protocolo; o que faltava era o cliente desenha-lo.
        assertTrue(json.contains("\"tooltip\":\"Lingote de ferro\""), json);
        assertTrue(json.contains("\"anchor\":\"gui_top_right\""), json);
    }

    @Test
    void overlayEventReachesTheScreenCallback(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Uma sobreposicao usa o mesmo callback de uma tela: para o mod, o clique chega igual,
        // e a diferenca fica em onde o elemento aparece.
        runtime.load(writeMod(root, """
                mod.screen("catalogo", function(ctx)
                    ctx.server.broadcast("clicou:" .. ctx.ui.element)
                end)
                """));

        runtime.triggerScreenEvent("ui_mod:catalogo", "abrir", "click", "", player);

        assertEquals(List.of("clicou:abrir"), bridge.calls);
    }

    @Test
    void overlayIsRemovedById(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.set_overlay("aviso", {
                        target = "pause",
                        elements = { { type = "label", x = 4, y = 4, text = "Mod ativo" } }
                    })
                end)

                mod.on("player_left", function(ctx)
                    ctx.server.broadcast(tostring(ctx.player.clear_overlay("aviso")))
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertTrue(player.overlays.containsKey("ui_mod:aviso"));

        runtime.triggerAll("player_left", player);
        assertEquals(List.of("true"), bridge.calls);
        assertTrue(player.overlays.isEmpty(), "clear_overlay precisa remover o registro");
    }

    @Test
    void unknownOverlayTargetIsRefused(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O alvo tambem e vocabulario fechado: um mod nao nomeia classes do cliente.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local ok, erro = pcall(function()
                        ctx.player.set_overlay("x", {
                            target = "net.minecraft.HackScreen",
                            elements = {}
                        })
                    end)
                    ctx.server.broadcast(tostring(ok) .. "|" .. tostring(erro))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(1, bridge.calls.size());
        assertTrue(bridge.calls.get(0).startsWith("false|"), bridge.calls.get(0));
        assertTrue(bridge.calls.get(0).contains("target desconhecido"), bridge.calls.get(0));
        assertTrue(player.overlays.isEmpty());
    }

    @Test
    void registeredItemsCanBeListedAndFiltered(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local todos = ctx.server.items()
                    local ferro = ctx.server.items({ namespace = "minecraft", contains = "iron" })
                    ctx.server.broadcast(#todos .. "|" .. table.concat(ferro, ","))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("4|minecraft:iron_ingot,minecraft:iron_sword"), bridge.calls);
    }

    @Test
    void itemListingRespectsItsLimit(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O teto existe para um catalogo ser paginado de proposito, e nao por acidente.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local pagina = ctx.server.items({ limit = 2 })
                    local ok, erro = pcall(function() ctx.server.items({ limit = 0 }) end)
                    ctx.server.broadcast(#pagina .. "|" .. tostring(ok))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("2|false"), bridge.calls);
    }

    @Test
    void listingItemsNeedsServerRead(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Ler o registro do jogo e leitura de servidor, e por isso passa pela mesma permissao que
        // ja protege a lista de jogadores e a hora do dia.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local ok = pcall(function() ctx.server.items() end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """, "\"chat.send\", \"player.menu\""));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of("false"), bridge.calls);
    }

    @Test
    void gridReplacesHandPlacedSlots(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O ponto da grade: 45 slots sao um elemento, e nao 45 com x e y calculados a mao.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local celulas = {}
                    for i = 1, 45 do celulas[i] = "minecraft:stone" end
                    celulas[1] = { item = "minecraft:diamond", count = 3, tooltip = "Diamante" }

                    ctx.player.open_screen("grade", {
                        title = "Grade",
                        elements = {
                            { type = "grid", id = "itens", x = 8, y = 8,
                              columns = 9, cell = 18, items = celulas }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"type\":\"grid\""), player.screenJson);
        assertTrue(player.screenJson.contains("\"columns\":9"), player.screenJson);
        // A forma curta e a completa convivem na mesma lista.
        assertTrue(player.screenJson.contains("\"count\":3"), player.screenJson);
        assertTrue(player.screenJson.contains("\"tooltip\":\"Diamante\""), player.screenJson);
    }

    @Test
    void gridNeedsAnIdAndRefusesTooManyCells(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local grandes = {}
                    for i = 1, 600 do grandes[i] = "minecraft:stone" end

                    local _, semId = pcall(function()
                        ctx.player.open_screen("a", { elements = {
                            { type = "grid", x = 0, y = 0, items = {} } } })
                    end)
                    local _, demais = pcall(function()
                        ctx.player.open_screen("b", { elements = {
                            { type = "grid", id = "g", x = 0, y = 0, items = grandes } } })
                    end)
                    ctx.server.broadcast(tostring(semId))
                    ctx.server.broadcast(tostring(demais))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(2, bridge.calls.size());
        assertTrue(bridge.calls.get(0).contains("precisa de id"), bridge.calls.get(0));
        assertTrue(bridge.calls.get(1).contains("acima do limite"), bridge.calls.get(1));
    }

    @Test
    void viewportDeclaresWhatScrolls(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O servidor declara a area e a altura do conteudo; a rolagem em si acontece no cliente,
        // para nao custar uma ida a rede por entalhe da roda.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("lista", {
                        title = "Lista",
                        elements = {
                            { type = "viewport", id = "area", x = 8, y = 8, w = 180, h = 90,
                              content = 400 },
                            { type = "grid", id = "itens", group = "area", x = 0, y = 0,
                              columns = 9, items = { "minecraft:stone" } }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"type\":\"viewport\""), player.screenJson);
        assertTrue(player.screenJson.contains("\"content\":400"), player.screenJson);
        assertTrue(player.screenJson.contains("\"group\":\"area\""), player.screenJson);
    }

    @Test
    void cellClickCarriesTheCellIndex(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O cliente resolve qual celula foi apontada e manda o indice no valor, para o script nao
        // receber uma posicao em pixels que teria de traduzir.
        runtime.load(writeMod(root, """
                mod.screen("grade", function(ctx)
                    ctx.server.broadcast(ctx.ui.element .. "#" .. ctx.ui.value)
                end)
                """));

        runtime.triggerScreenEvent("ui_mod:grade", "itens", "click", "12", player);

        assertEquals(List.of("itens#12"), bridge.calls);
    }

    @Test
    void recipesAnswerBothQuestionsAboutAnItem(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // As duas perguntas que um catalogo existe para responder: como se obtem, e para que serve.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local produz = ctx.server.recipes_for("minecraft:iron_sword")
                    local usa = ctx.server.recipes_using("minecraft:stick")

                    local receita = produz[1]
                    ctx.server.broadcast(receita.id .. "|" .. receita.type
                        .. "|" .. receita.output.item .. "x" .. receita.output.count
                        .. "|" .. receita.width .. "x" .. receita.height
                        .. "|" .. #receita.ingredients
                        .. "|" .. receita.ingredients[1][1])
                    ctx.server.broadcast("usa:" .. #usa)
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of(
                "minecraft:iron_sword|minecraft:crafting_shaped|minecraft:iron_swordx1|1x3|3"
                        + "|minecraft:iron_ingot",
                "usa:1"), bridge.calls);
    }

    @Test
    void recipeLookupIsCappedAndNeedsPermission(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Consultar receitas varre o livro inteiro, porque o jogo nao indexa por item: o teto
        // empurra o mod a perguntar so pelo que vai mostrar agora.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local ok = pcall(function()
                        ctx.server.recipes_for("minecraft:iron_sword", 999)
                    end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertEquals(List.of("false"), bridge.calls);
    }

    @Test
    void recipeLookupNeedsServerRead(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local ok = pcall(function() ctx.server.recipes_for("minecraft:stick") end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """, "\"chat.send\", \"player.menu\""));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of("false"), bridge.calls);
    }

    /**
     * Carrega o exemplo do catalogo do repositorio e exercita o caminho inteiro.
     *
     * <p>Um exemplo que nao roda e pior que nenhum: e a primeira coisa que alguem copia. Aqui ele
     * passa pelas mesmas validacoes de um mod de verdade, e cada clique cai no callback como cairia
     * em jogo.
     */
    @Test
    void catalogExampleRunsEndToEnd(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "catalogo");
        Path target = root.resolve("catalogo");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        runtime.triggerAll("player_joined", player);

        // A sobreposicao chega com a grade rolavel dentro do viewport.
        String overlay = player.overlays.get("catalogo:hud");
        assertNotNull(overlay, "o exemplo precisa registrar a sobreposicao");
        assertTrue(overlay.contains("\"target\":\"inventory\""), overlay);
        assertTrue(overlay.contains("\"type\":\"viewport\""), overlay);
        assertTrue(overlay.contains("\"type\":\"grid\""), overlay);
        assertTrue(overlay.contains("minecraft:iron_ingot"), overlay);

        // Clicar na segunda celula abre a tela com aquele item.
        runtime.triggerScreenEvent("catalogo:hud", "itens", "click", "2", player);
        assertEquals("catalogo:livro", player.screenId);
        assertTrue(player.screenJson.contains("minecraft:iron_ingot"), player.screenJson);

        // Digitar na busca filtra a lista sem reabrir a tela.
        runtime.triggerScreenEvent("catalogo:livro", "busca", "change", "sword", player);
        assertTrue(player.screenJson.contains("1 resultado(s)"), player.screenJson);
        assertTrue(player.screenJson.contains("minecraft:iron_sword"), player.screenJson);

        // Clicar no resultado mostra quem produz o item, com a receita desenhada.
        runtime.triggerScreenEvent("catalogo:livro", "itens", "click", "1", player);
        assertTrue(player.screenJson.contains("\"id\":\"entrada\""), player.screenJson);

        // Alternar mostra para que o item serve, e nao como se obtem.
        runtime.triggerScreenEvent("catalogo:livro", "alternar", "click", "", player);
        assertTrue(player.screenJson.contains("Ver receita"), player.screenJson);
    }

    @Test
    void scrollableContentMayBeTallerThanAnyScreen(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O conteudo rolavel mede justamente o que nao cabe na tela: uma lista com o registro
        // inteiro do jogo passa de tres mil pixels. Validar esse campo com o teto de uma janela
        // anulava a razao de ele existir, e so aparecia com uma lista de verdade -- nao com as
        // quatro entradas do dublê.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("longa", {
                        title = "Lista longa",
                        elements = {
                            { type = "viewport", id = "area", x = 8, y = 8, w = 144, h = 108,
                              content = 3024 }
                        }
                    })

                    local _, absurdo = pcall(function()
                        ctx.player.open_screen("maior", { elements = {
                            { type = "viewport", id = "a", x = 0, y = 0, w = 10, h = 10,
                              content = 999999 } } })
                    end)
                    ctx.server.broadcast(tostring(absurdo))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"content\":3024"), player.screenJson);
        // Continua havendo teto, so que proprio: o campo nao e ilimitado.
        assertEquals(1, bridge.calls.size());
        assertTrue(bridge.calls.get(0).contains("content"), bridge.calls.get(0));
    }

    /**
     * O mesmo exemplo, contra um registro do tamanho do real.
     *
     * <p>Os quatro itens do dublê escondiam duas falhas que apareceram no primeiro minuto em jogo:
     * a altura do conteúdo rolável passava do teto, e a lista inteira estourava o limite de células
     * por grade. Nenhuma das duas é visível com uma lista curta, e as duas são certas com uma lista
     * de verdade — por isso este teste existe ao lado do outro, e não no lugar dele.
     */
    @Test
    void catalogExampleSurvivesAFullRegistry(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "catalogo");
        Path target = root.resolve("catalogo");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        RecordingBridge bridge = new RecordingBridge();
        // Perto do que o vanilla 1.21.1 registra.
        bridge.items.clear();
        for (int index = 0; index < 1342; index++) {
            bridge.items.add(String.format("minecraft:item_%04d", index));
        }

        TestPlayer player = new TestPlayer();
        // Uma tela estreita, como a de escala 3: cabem seis colunas ao lado do inventario, e a
        // paginacao acompanha isso em vez de assumir um numero fixo.
        player.screenSize = new int[]{427, 240};

        LuaRuntime runtime = runtime(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        runtime.triggerAll("player_joined", player);

        String overlay = player.overlays.get("catalogo:hud");
        assertNotNull(overlay, "com o registro cheio a sobreposicao precisa continuar sendo enviada");
        assertTrue(overlay.contains("1342 itens"), overlay);
        assertTrue(overlay.contains("\"columns\":6"), overlay);
        assertTrue(overlay.contains("\"text\":\"1/7\""), overlay);

        // Virar de pagina e clicar na primeira celula precisa abrir o item daquela pagina, e nao o
        // primeiro da lista: o indice da celula e relativo a pagina.
        runtime.triggerScreenEvent("catalogo:hud", "proxima", "click", "", player);
        assertTrue(player.overlays.get("catalogo:hud").contains("\"text\":\"2/7\""),
                player.overlays.get("catalogo:hud"));

        // Seis colunas por 32 linhas dao 192 por pagina: a primeira celula da pagina 2 e a 193a.
        runtime.triggerScreenEvent("catalogo:hud", "itens", "click", "1", player);
        assertTrue(player.screenJson.contains("minecraft:item_0192"), player.screenJson);
    }

    @Test
    void panelStyleDrawsAWindowWithoutATexture(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O visual de janela do jogo e bisel, nao imagem: descrito como regra, acompanha qualquer
        // tamanho e dispensa o mod distribuir textura.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("janela", {
                        title = "Janela",
                        elements = {
                            { type = "panel", style = "vanilla", x = 0, y = 0, w = 176, h = 166 },
                            { type = "panel", style = "slot", x = 8, y = 8, w = 16, h = 16,
                              border = 1 }
                        }
                    })

                    local _, erro = pcall(function()
                        ctx.player.open_screen("x", { elements = {
                            { type = "panel", style = "neon", x = 0, y = 0, w = 8, h = 8 } } })
                    end)
                    ctx.server.broadcast(tostring(erro))
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"style\":\"vanilla\""), player.screenJson);
        assertTrue(player.screenJson.contains("\"style\":\"slot\""), player.screenJson);
        // O vocabulario de estilo tambem e fechado.
        assertEquals(1, bridge.calls.size());
        assertTrue(bridge.calls.get(0).contains("estilo de painel desconhecido"), bridge.calls.get(0));
    }

    @Test
    void textCanBeDrawnWithoutShadow(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Texto escuro sobre painel claro fica sujo com sombra, porque a sombra tambem e escura e
        // as duas se misturam. O jogo desenha titulo de container sem sombra pelo mesmo motivo.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("janela", {
                        elements = {
                            { type = "panel", style = "vanilla", x = 0, y = 0, w = 176, h = 90 },
                            { type = "label", x = 8, y = 8, text = "Titulo",
                              color = "#404040", shadow = false },
                            { type = "label", x = 8, y = 20, text = "Sobre o mundo",
                              color = "#FFFFFF" }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);

        assertTrue(player.screenJson.contains("\"shadow\":false"), player.screenJson);
        // Sem declarar, a sombra continua ligada: e o que a maioria das telas sobre o mundo quer.
        assertFalse(player.screenJson.contains("\"shadow\":true"), player.screenJson);
    }

    @Test
    void modsDeclareTheirOwnProcesses(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // O livro do jogo so conhece as receitas do jogo. Uma mecanica inventada por um mod -- dar
        // trigo a uma vaca e receber leite -- nao existe la, e sem este registro seria invisivel a
        // qualquer catalogo.
        runtime.load(writeMod(root, """
                mod.process("ordenha", {
                    title = "Alimentar",
                    inputs = { "minecraft:wheat" },
                    output = { item = "minecraft:milk_bucket", count = 1, chance = 0.5 },
                    by = "minecraft:cow"
                })

                mod.on("player_joined", function(ctx)
                    local produz = ctx.server.processes({ produces = "minecraft:milk_bucket" })
                    local usa = ctx.server.processes({ uses = "minecraft:wheat" })
                    local vaca = ctx.server.processes({ by = "minecraft:cow" })

                    local p = produz[1]
                    ctx.server.broadcast(p.id .. "|" .. p.title .. "|" .. p.by
                        .. "|" .. p.inputs[1] .. "|" .. p.output.item
                        .. "x" .. p.output.count .. "@" .. p.output.chance)
                    ctx.server.broadcast(#usa .. "," .. #vaca)
                end)

                return {}
                """));

        runtime.triggerAll("player_joined", player);

        assertEquals(List.of(
                "ui_mod:ordenha|Alimentar|minecraft:cow|minecraft:wheat|minecraft:milk_bucketx1@0.5",
                "1,1"), bridge.calls);
    }

    @Test
    void processesFromEveryModAreVisibleToACatalog(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // O registro e global de proposito: um catalogo e um mod que lista o que os outros
        // declararam, e nao teria como fazer isso se cada mod so enxergasse os proprios.
        Path outro = root.resolve("outro");
        Files.createDirectories(outro.resolve("moageiro"));
        Files.writeString(outro.resolve("moageiro").resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "moageiro",
                  "name": "Moageiro",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(outro.resolve("moageiro").resolve("main.lua"), """
                mod.process("moer", {
                    title = "Moer",
                    inputs = { "minecraft:iron_ore" },
                    output = { item = "minecraft:iron_nugget", count = 6 }
                })
                return {}
                """, StandardCharsets.UTF_8);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(outro).get(0));

        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local todos = ctx.server.processes()
                    ctx.server.broadcast(#todos .. ":" .. todos[1].id)
                end)
                return {}
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of("1:moageiro:moer"), bridge.calls);
    }

    @Test
    void invalidProcessesAreRefusedWithAClearMessage(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        runtime.load(writeMod(root, """
                local erros = {}

                local function tentar(fn)
                    local ok, erro = pcall(fn)
                    erros[#erros + 1] = tostring(erro)
                end

                tentar(function() mod.process("sem_saida", { inputs = {} }) end)
                tentar(function()
                    mod.process("chance_ruim", {
                        output = { item = "minecraft:stone", chance = 2 }
                    })
                end)

                mod.on("player_joined", function(ctx)
                    for _, erro in ipairs(erros) do ctx.server.broadcast(erro) end
                end)

                return {}
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(2, bridge.calls.size());
        assertTrue(bridge.calls.get(0).contains("precisa de uma tabela em output"), bridge.calls.get(0));
        assertTrue(bridge.calls.get(1).contains("chance"), bridge.calls.get(1));
    }

    @Test
    void dropsAnswerWhereAnItemComesFrom(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // Para a maior parte do jogo, esta e a resposta verdadeira: minerio, pedra e madeira chegam
        // ao jogador por mineracao, e nao por receita.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local da = ctx.server.drops_of("minecraft:iron_ore")
                    local de = ctx.server.dropped_by("minecraft:raw_iron")
                    ctx.server.broadcast(table.concat(da, ",") .. "|" .. table.concat(de, ","))
                end)
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of("minecraft:raw_iron|minecraft:iron_ore"), bridge.calls);
    }

    @Test
    void dropsAlsoAnswerForEntities(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // Matar um mob e loot, e portanto dado consultavel. Um catalogo pergunta "o que isto
        // derruba" sem saber se e bloco ou bicho, entao a mesma operacao responde pelos dois.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    local ovelha = ctx.server.drops_of("minecraft:sheep")
                    local quem = ctx.server.dropped_by("minecraft:white_wool")
                    ctx.server.broadcast(table.concat(ovelha, ",") .. "|" .. table.concat(quem, ","))
                end)
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of("minecraft:white_wool,minecraft:mutton|minecraft:sheep"), bridge.calls);
    }

    @Test
    void interactionsThatLiveInCodeNeedAProcess(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);

        // Tosquiar uma ovelha nao e loot: e codigo dentro da entidade, e nenhuma consulta ao jogo
        // revela. Declarado como processo, aparece no catalogo ao lado do que o jogo sabe dizer --
        // e a razao de o registro de processos existir.
        runtime.load(writeMod(root, """
                mod.process("tosquia", {
                    title = "Tosquiar",
                    inputs = { "minecraft:shears" },
                    output = { item = "minecraft:white_wool", count = 1 },
                    by = "minecraft:sheep"
                })

                mod.on("player_joined", function(ctx)
                    local do_loot = ctx.server.dropped_by("minecraft:white_wool")
                    local declarado = ctx.server.processes({ produces = "minecraft:white_wool" })

                    ctx.server.broadcast(#do_loot .. " por loot, " .. #declarado .. " declarado(s)")
                    ctx.server.broadcast(declarado[1].title .. " com " .. declarado[1].inputs[1]
                        .. " em " .. declarado[1].by)
                end)

                return {}
                """));

        runtime.triggerAll("player_joined", new TestPlayer());

        assertEquals(List.of(
                "1 por loot, 1 declarado(s)",
                "Tosquiar com minecraft:shears em minecraft:sheep"), bridge.calls);
    }

    @Test
    void catalogExamplePagesThroughEveryRecipe(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "catalogo");
        Path target = root.resolve("catalogo");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        RecordingBridge bridge = new RecordingBridge();
        // La e o caso real: barbante, tingimento a partir de cada cor, e a ovelha. Mostrar as tres
        // primeiras e esconder o resto em silencio faz quem le concluir que aquilo e tudo.
        bridge.recipes.clear();
        for (int index = 0; index < 8; index++) {
            bridge.recipes.add(("""
                    {"id":"minecraft:wool_%d","type":"minecraft:crafting_shaped",\
                    "output":{"item":"minecraft:white_wool","count":1},"width":1,"height":1,\
                    "ingredients":[["minecraft:string"]]}\
                    """).formatted(index));
        }
        bridge.items.add("minecraft:white_wool");
        bridge.drops.put("minecraft:sheep", List.of("minecraft:white_wool"));

        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        runtime.triggerAll("player_joined", player);
        runtime.triggerScreenEvent("catalogo:hud", "abrir_livro", "click", "", player);
        runtime.triggerScreenEvent("catalogo:livro", "busca", "change", "white_wool", player);
        runtime.triggerScreenEvent("catalogo:livro", "itens", "click", "1", player);

        // Oito receitas mais um drop, tres por pagina: quatro paginas, e o total fica visivel.
        assertTrue(player.screenJson.contains("1/3"), player.screenJson);
        assertTrue(player.screenJson.contains("(9)"), player.screenJson);

        runtime.triggerScreenEvent("catalogo:livro", "receita_proxima", "click", "", player);
        assertTrue(player.screenJson.contains("2/3"), player.screenJson);

        // A ultima pagina traz o drop, que vem depois das receitas na lista unificada.
        runtime.triggerScreenEvent("catalogo:livro", "receita_proxima", "click", "", player);
        assertTrue(player.screenJson.contains("3/3"), player.screenJson);
        assertTrue(player.screenJson.contains("minecraft:sheep"), player.screenJson);

        // Trocar de modo volta para a primeira pagina, senao a tela abriria vazia.
        runtime.triggerScreenEvent("catalogo:livro", "alternar", "click", "", player);
        assertFalse(player.screenJson.contains("3/3"), player.screenJson);
    }

    @Test
    void bigRecipesGetFewerPerPage(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "catalogo");
        Path target = root.resolve("catalogo");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        RecordingBridge bridge = new RecordingBridge();
        bridge.items.add("minecraft:beacon");
        bridge.drops.clear();

        // Seis receitas 3x3. Uma ocupa 62 px contra 26 de uma 1x1, entao um numero fixo por pagina
        // empurraria a terceira para fora da janela -- que foi o que aconteceu em jogo.
        bridge.recipes.clear();
        for (int index = 0; index < 6; index++) {
            StringBuilder ingredients = new StringBuilder();
            for (int slot = 0; slot < 9; slot++) {
                ingredients.append(slot == 0 ? "" : ",").append("[\"minecraft:glass\"]");
            }
            bridge.recipes.add(("""
                    {"id":"minecraft:beacon_%d","type":"minecraft:crafting_shaped",\
                    "output":{"item":"minecraft:beacon","count":1},"width":3,"height":3,\
                    "ingredients":[%s]}\
                    """).formatted(index, ingredients));
        }

        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        runtime.triggerAll("player_joined", player);
        runtime.triggerScreenEvent("catalogo:hud", "abrir_livro", "click", "", player);
        runtime.triggerScreenEvent("catalogo:livro", "busca", "change", "beacon", player);
        runtime.triggerScreenEvent("catalogo:livro", "itens", "click", "1", player);

        // Seis receitas de 62 px em uma area de 104: uma por pagina, e nao tres.
        assertTrue(player.screenJson.contains("(6)"), player.screenJson);
        assertTrue(player.screenJson.contains("1/6"),
                "receita 3x3 deveria render uma por pagina: " + player.screenJson);
    }

    @Test
    void theBookWindowFollowsTheScreen(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "catalogo");
        Path target = root.resolve("catalogo");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        RecordingBridge bridge = new RecordingBridge();
        LuaRuntime runtime = runtime(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        // Uma janela fixa sobra numa tela grande e aperta numa pequena. Com o tamanho informado
        // pelo cliente, ela usa quase tudo, respeitando um teto.
        TestPlayer grande = new TestPlayer();
        grande.screenSize = new int[]{854, 480};
        runtime.triggerAll("player_joined", grande);
        runtime.triggerScreenEvent("catalogo:hud", "abrir_livro", "click", "", grande);
        assertTrue(grande.screenJson.contains("\"width\":560"), grande.screenJson);
        assertTrue(grande.screenJson.contains("\"height\":320"), grande.screenJson);

        TestPlayer pequena = new TestPlayer();
        pequena.screenSize = new int[]{427, 240};
        runtime.triggerAll("player_joined", pequena);
        runtime.triggerScreenEvent("catalogo:hud", "abrir_livro", "click", "", pequena);
        assertTrue(pequena.screenJson.contains("\"width\":387"), pequena.screenJson);
        assertTrue(pequena.screenJson.contains("\"height\":200"), pequena.screenJson);
    }

    @Test
    void protocolVocabularyIsClosed() {
        // Documenta o contrato: quem acrescentar uma acao precisa fazer aqui, e nao no cliente.
        assertEquals(java.util.Set.of("click", "change", "submit", "close"), ScreenProtocol.ACTIONS);
        assertTrue(ScreenProtocol.INTERACTIVE.stream().allMatch(ScreenProtocol.ELEMENTS::contains),
                "todo elemento interativo precisa ser um elemento valido");
        assertFalse(ScreenProtocol.ELEMENTS.contains("script"),
                "o cliente interpreta dados, nunca codigo");
        // O alvo de uma sobreposicao segue a mesma regra: nomes proprios, nunca classes do jogo.
        assertTrue(ScreenProtocol.TARGETS.contains("inventory"));
        assertTrue(ScreenProtocol.TARGETS.stream().noneMatch(target -> target.contains(".")),
                "um alvo nomeia uma tela, e nao uma classe do cliente");
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

    @Test
    void interactiveElementNeedsSize(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Um campo sem altura era aceito e o cliente arbitrava um minimo, o que colocava o elemento
        // fora do lugar esperado e transbordando a janela.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("ruim", {
                        elements = {
                            { type = "input", id = "nome", x = 10, y = 10, w = 200, h = 0 }
                        }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertNull(player.screenId, "um campo sem altura precisa ser recusado na carga");
    }

    @Test
    void nonInteractiveElementMayOmitSize(@TempDir Path root) throws IOException {
        RecordingBridge bridge = new RecordingBridge();
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = runtime(bridge);

        // Um rotulo se dimensiona pelo proprio texto, entao nao precisa declarar tamanho.
        runtime.load(writeMod(root, """
                mod.on("player_joined", function(ctx)
                    ctx.player.open_screen("ok", {
                        elements = { { type = "label", x = 10, y = 10, text = "sem tamanho" } }
                    })
                end)
                """));

        runtime.triggerAll("player_joined", player);
        assertEquals("ui_mod:ok", player.screenId);
    }
}
