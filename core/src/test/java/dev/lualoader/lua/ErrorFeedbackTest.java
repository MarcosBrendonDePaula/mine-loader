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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O erro de um script chegando a quem esta jogando.
 *
 * <p><b>Por que isto importa.</b> Um erro de Lua num callback e registrado e nao propagado -- o que
 * impede um mod quebrado de derrubar o jogo, e e a regra certa. O efeito colateral e cruel: quem
 * clica ve o clique nao fazer nada, e nao ha nada na tela ligando uma coisa a outra.
 *
 * <p>Custou uma investigacao inteira nesta sessao. Uma cor declarada como numero em vez de texto
 * derrubava a montagem de uma tela; a tela nao abria, o log tinha a resposta na primeira linha, e
 * dentro do jogo o sintoma era silencio absoluto -- procurou-se permissao, formato de comando e
 * bloco na mao antes de olhar o lugar certo.
 */
class ErrorFeedbackTest {

    private static final class Bridge extends TestBridge {
    }

    private ModLoader.LoadedMod modQueFalha(Path root, String lua) throws IOException {
        Path dir = root.resolve("quebrado");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "quebrado",
                  "name": "Quebrado",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["chat.send"],
                  "blocks": [{"id": "bloco", "name": "Bloco",
                              "behavior": {"on_use": "aoUsar"}}]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), lua, StandardCharsets.UTF_8);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size());
        return mods.get(0);
    }

    @Test
    void quemClicouSabeQueOScriptFalhou(@TempDir Path root) throws IOException {
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new Bridge());

        runtime.load(modQueFalha(root, """
                local function aoUsar(ctx)
                    error("a cor 4293454056 nao vale")
                end
                return { aoUsar = aoUsar }
                """));

        runtime.triggerBlock("block_used", player,
                new BlockEventData("quebrado:bloco", 1, 2, 3, 0, 1));

        // Sem isto, o clique nao faz nada e nao ha nada na tela dizendo por que.
        assertFalse(player.received.isEmpty(), "quem clicou precisa saber que falhou");

        String aviso = player.received.get(0);
        assertTrue(aviso.contains("quebrado"), "a mensagem diz qual mod: " + aviso);
        assertTrue(aviso.contains("block_used"), "e o que estava fazendo: " + aviso);
        assertTrue(aviso.contains("4293454056"), "e o motivo, como o script o descreveu: " + aviso);
    }

    @Test
    void semJogadorNaoHaAQuemAvisar(@TempDir Path root) throws IOException {
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new Bridge());
        runtime.load(modQueFalha(root, """
                local function aoUsar(ctx) error("falhou") end
                return { aoUsar = aoUsar }
                """));

        // Um evento sem jogador -- tique, vizinho, servidor -- nao pode explodir por causa do
        // aviso: o log continua sendo o registro, e ninguem esta esperando resposta.
        runtime.triggerBlock("block_used", null,
                new BlockEventData("quebrado:bloco", 1, 2, 3, 0, 1));
    }

    @Test
    void umScriptQueFuncionaNaoAvisaNada(@TempDir Path root) throws IOException {
        TestPlayer player = new TestPlayer();
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(new Bridge());
        runtime.load(modQueFalha(root, """
                local function aoUsar(ctx) return true end
                return { aoUsar = aoUsar }
                """));

        runtime.triggerBlock("block_used", player,
                new BlockEventData("quebrado:bloco", 1, 2, 3, 0, 1));

        // O aviso e para quando algo quebra. Avisar sempre viraria ruido, e ruido se ignora --
        // inclusive quando importa.
        assertTrue(player.received.isEmpty(), "nao deveria avisar nada: " + player.received);
    }
}
