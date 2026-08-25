package dev.lualoader.lua;

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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O bloco para onde quem joga esta olhando.
 *
 * <p>Sem isto, todo comando de mod pede coordenada digitada, e quem joga abre o F3 para descobrir
 * onde esta o bloco que ve na frente. E a diferenca entre "mire e use" e "anote tres numeros".
 */
class LookingAtTest {

    private static final class Bridge extends TestBridge {
        final List<String> calls = new ArrayList<>();

        @Override
        public void broadcast(String message) {
            calls.add(message);
        }
    }

    private ModLoader.LoadedMod mod(Path root, String permissoes, String lua) throws IOException {
        Path dir = root.resolve("mira");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "mira",
                  "name": "Mira",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s]
                }
                """.formatted(permissoes), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0);
    }

    private static final String OLHA = """
            mod.on("player_joined", function(ctx)
                local alvo = ctx.player.looking_at()
                if alvo == nil then
                    ctx.server.broadcast("nada")
                else
                    ctx.server.broadcast(alvo.x .. "," .. alvo.y .. "," .. alvo.z .. " " .. alvo.side)
                end
            end)
            """;

    @Test
    void devolveOBlocoMiradoEOLadoAtingido(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        TestPlayer player = new TestPlayer();
        player.lookingAt = new int[]{10, 64, -3, 1};   // lado 1 = cima

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(mod(root, "\"chat.send\", \"player.read\"", OLHA));
        runtime.triggerAll("player_joined", player);

        // O lado chega por NOME, e nao por numero: quem escreve o mod compara com "up".
        assertEquals(List.of("10,64,-3 up"), bridge.calls);
    }

    @Test
    void olharParaOCeuNaoEUmaPosicao(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        TestPlayer player = new TestPlayer();
        player.lookingAt = null;

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(mod(root, "\"chat.send\", \"player.read\"", OLHA));
        runtime.triggerAll("player_joined", player);

        // Nil, e nao zero: devolver uma posicao faria o mod agir sobre a origem do mundo sem
        // ninguem ter mirado nela.
        assertEquals(List.of("nada"), bridge.calls);
    }

    @Test
    void mirarExigePermissaoDeLerOJogador(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        TestPlayer player = new TestPlayer();
        player.lookingAt = new int[]{1, 2, 3, 0};

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        // Sem player.read: saber para onde alguem olha e ler o jogador.
        runtime.load(mod(root, "\"chat.send\"", OLHA));
        runtime.triggerAll("player_joined", player);

        assertTrue(bridge.calls.isEmpty(), "mirar sem permissao deveria parar antes: " + bridge.calls);
    }

    @Test
    void oAlcanceTemTeto(@TempDir Path root) throws IOException {
        Bridge bridge = new Bridge();
        TestPlayer player = new TestPlayer();
        player.lookingAt = new int[]{1, 2, 3, 0};

        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(mod(root, "\"chat.send\", \"player.read\"", """
                mod.on("player_joined", function(ctx)
                    local ok = pcall(function() ctx.player.looking_at(500) end)
                    ctx.server.broadcast(tostring(ok))
                end)
                """));
        runtime.triggerAll("player_joined", player);

        // Sem teto, um script varreria o mundo inteiro procurando o primeiro bloco de uma linha.
        assertEquals(List.of("false"), bridge.calls);
    }
}
