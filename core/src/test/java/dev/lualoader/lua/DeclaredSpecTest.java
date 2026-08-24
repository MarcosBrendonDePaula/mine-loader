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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O que um script declara sobre a entidade que cria e o item que entrega.
 *
 * <p>Antes só dava para dizer qual entidade e onde — um cavalo nascia genérico, e não havia como
 * entregar uma espada encantada. É para isso que se usa NBT de entidade e de item no jogo, e é
 * justamente o que um mod não alcançava.
 *
 * <p>O vocabulário é fechado de propósito. NBT cru amarraria cada mod a uma versão do Minecraft: o
 * formato interno de item virou componentes na 1.20.5, e quem tivesse escrito a forma anterior
 * pararia de funcionar sem ter mudado nada.
 */
class DeclaredSpecTest {

    /** Roda um script e devolve a ponte, já com o que ele declarou. */
    private TestBridge run(Path root, String script) throws IOException {
        Path dir = root.resolve("spec_mod");
        Files.createDirectories(dir);

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "spec_mod",
                  "name": "Spec Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": ["entity.spawn", "player.inventory"],
                  "events": {"loader_ready": "on_ready"}
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"),
                "local function on_ready(ctx)\n" + script + "\nend\n"
                        + "return { on_ready = on_ready }\n",
                StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        // Anonima porque TestBridge e abstrata: ela obriga cada teste a dizer o que precisa, para
        // um contrato novo nao passar despercebido por um duble que responde a tudo.
        TestBridge bridge = new TestBridge() {
        };
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"),
                root.resolve("cache"), root.resolve("state"));
        runtime.attach(bridge);
        runtime.load(mods.get(0));

        // Um erro de Lua dentro do callback e logado e nao propagado, entao o que confirma que o
        // script rodou e o efeito -- e nao a ausencia de excecao.
        runtime.triggerAll("loader_ready", player);
        return bridge;
    }

    private final TestPlayer player = new TestPlayer();

    // ------------------------------------------------------------------ entidade

    @Test
    void semTabelaNadaEhDeclarado(@TempDir Path root) throws IOException {
        TestBridge bridge = run(root, """
                    ctx.server.spawn_entity("minecraft:horse", 0, 64, 0)
                """);

        assertTrue(bridge.lastEntitySpec.isEmpty(),
                "sem tabela, o jogo decide tudo: " + bridge.lastEntitySpec);
    }

    @Test
    void oCavaloPodeNascerDomado(@TempDir Path root) throws IOException {
        // O caso mais obvio de NBT de entidade, e o que nao existia.
        TestBridge bridge = run(root, """
                    ctx.server.spawn_entity("minecraft:horse", 0, 64, 0, {
                        name = "Corcel", tame = true, health = 30
                    })
                """);

        assertEquals("Corcel", bridge.lastEntitySpec.name());
        assertEquals(Boolean.TRUE, bridge.lastEntitySpec.tame());
        assertEquals(30.0, bridge.lastEntitySpec.health());
    }

    @Test
    void campoAusenteEhNuloENaoOPadrao(@TempDir Path root) throws IOException {
        // A distincao que o vocabulario precisa manter: um false declarado impede o jogo de
        // escolher outra coisa; ausente deixa o jogo decidir, como faria sem o mod.
        TestBridge bridge = run(root, """
                    ctx.server.spawn_entity("minecraft:zombie", 0, 64, 0, { baby = false })
                """);

        assertEquals(Boolean.FALSE, bridge.lastEntitySpec.baby(), "declarado como false");
        assertNull(bridge.lastEntitySpec.tame(), "nao declarado deveria ser nulo");
        assertNull(bridge.lastEntitySpec.health(), "nao declarado deveria ser nulo");
    }

    // ------------------------------------------------------------------ item

    @Test
    void oItemPodeSairEncantadoENomeado(@TempDir Path root) throws IOException {
        run(root, """
                    ctx.player.give_item("minecraft:diamond_sword", 1, {
                        name = "Espada do Chefe",
                        lore = {"Forjada em cristal", "Nao se quebra"},
                        unbreakable = true,
                        enchantments = { ["minecraft:sharpness"] = 5 }
                    })
                """);

        assertEquals("Espada do Chefe", player.lastItemSpec.name());
        assertEquals(2, player.lastItemSpec.lore().size());
        assertEquals(Boolean.TRUE, player.lastItemSpec.unbreakable());
        assertEquals(5, player.lastItemSpec.enchantments().get("minecraft:sharpness"));
    }

    @Test
    void encantamentoComNivelZeroEhDescartado(@TempDir Path root) throws IOException {
        // Nivel zero nao e um encantamento fraco: e a ausencia dele. Guardar faria o item mostrar
        // uma linha de encantamento sem efeito nenhum.
        run(root, """
                    ctx.player.give_item("minecraft:diamond_sword", 1, {
                        enchantments = { ["minecraft:sharpness"] = 0, ["minecraft:unbreaking"] = 2 }
                    })
                """);

        assertNull(player.lastItemSpec.enchantments().get("minecraft:sharpness"));
        assertEquals(2, player.lastItemSpec.enchantments().get("minecraft:unbreaking"));
    }

    @Test
    void itemSemTabelaContinuaComum(@TempDir Path root) throws IOException {
        run(root, """
                    ctx.player.give_item("minecraft:stone", 4)
                """);

        assertTrue(player.lastItemSpec.isEmpty(), "sem tabela, item comum");
        assertEquals(4, player.countItem("minecraft:stone"), "o item deveria ter sido entregue");
    }
}
