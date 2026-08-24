package dev.lualoader.lua;

import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.EntityDefinition;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A fase de registro: o que um script declara antes de o jogo congelar os registros.
 *
 * <p>Ela existe por uma divergência que não dá para consertar: o Lua do Fabric carrega na
 * inicialização do mod e o do NeoForge quando o servidor sobe. Registrar por script valeria só no
 * primeiro, e uma operação que funciona numa plataforma e não na outra quebra a promessa central do
 * projeto.
 *
 * <p>O que o script declara <b>entra no manifesto em memória</b>, e não vai direto ao registro do
 * jogo. Parece indireto e é o contrário: dali em diante a espécie é indistinguível de uma declarada
 * em JSON, e ganha de graça a ordenação por herança, a tabela de saque, a tradução, a textura e o
 * modelo do ovo. A primeira versão registrava direto, pulava o montador de recursos, e o sintoma
 * era um ovo de criação sem ícone — que nenhum teste de servidor pega.
 */
class RegistrationPhaseTest {

    /** Escreve um mod com um script de registro e roda a fase. */
    private List<EntityDefinition> run(Path root, String permissions, String script)
            throws IOException {
        Path dir = root.resolve("bestiary");
        Files.createDirectories(dir.resolve("scripts"));

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "permissions": [%s],
                  "registration": { "on_register": "scripts/registrar.lua" }
                }
                """.formatted(permissions), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("scripts/registrar.lua"), script, StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        if (mods.isEmpty()) return List.of();

        new RegistrationRuntime(LoggerFactory.getLogger("test"), null).runAll(mods);
        return mods.get(0).manifest().entities;
    }

    // ------------------------------------------------------------------ o que funciona

    @Test
    void oScriptAcrescentaAoManifesto(@TempDir Path root) throws IOException {
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({
                        id = "guardiao",
                        name = "Guardiao",
                        base = "minecraft:iron_golem",
                        defaults = { health = 60.0 },
                    })
                end
                """);

        assertEquals(1, entities.size(), "a especie deveria ter entrado no manifesto");
        EntityDefinition entity = entities.get(0);

        // O id fica sem namespace, e e mais forte que carimbar um: a especie mora no manifesto
        // daquele mod, entao o namespace dela e o dele por construcao. Um script nao tem como
        // publicar conteudo no nome de outro mod nem se quiser.
        assertEquals("guardiao", entity.id);
        assertEquals("minecraft:iron_golem", entity.base);
        assertEquals(60.0, entity.defaults.health);
    }

    @Test
    void gerarPorLacoEOMotivoDaFaseExistir(@TempDir Path root) throws IOException {
        // O que o manifesto nao faz: tres variantes de um laco, em vez de tres blocos de JSON
        // quase iguais. Acrescentar a quarta e mudar um numero.
        var entities = run(root, "\"entity.register\"", """
                local NIVEIS = { { "bronze", 40 }, { "prata", 80 }, { "ouro", 160 } }
                return function(ctx)
                    for _, nivel in ipairs(NIVEIS) do
                        ctx.register.entity({
                            id = "guardiao_" .. nivel[1],
                            name = "Guardiao de " .. nivel[1],
                            base = "minecraft:iron_golem",
                            defaults = { health = nivel[2] },
                        })
                    end
                end
                """);

        assertEquals(3, entities.size());
        assertEquals(List.of("guardiao_bronze", "guardiao_prata", "guardiao_ouro"),
                entities.stream().map(entity -> entity.id).toList());
        assertEquals(160.0, entities.get(2).defaults.health);
    }

    @Test
    void aparenciaDeclaradaPorScriptValeIgualADeJson(@TempDir Path root) throws IOException {
        // Sem isto uma especie gerada por laco nascia condenada a parecer a base, e o script
        // viraria um caminho de segunda classe -- o oposto do que a fase existe para ser.
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({
                        id = "guardiao",
                        name = "Guardiao",
                        base = "minecraft:iron_golem",
                        texture = "@pele",
                        model = "@forma",
                        tags = { "bestiary:guardians" },
                    })
                end
                """);

        EntityDefinition entity = entities.get(0);
        assertEquals("@pele", entity.texture);
        assertEquals("@forma", entity.model);
        assertEquals(List.of("bestiary:guardians"), entity.tags);
    }

    @Test
    void saqueEOvoDeclaradosPorScript(@TempDir Path root) throws IOException {
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({
                        id = "guardiao",
                        name = "Guardiao",
                        base = "minecraft:iron_golem",
                        loot = {
                            table = "minecraft:entities/zombie",
                            drops = {{ item = "minecraft:emerald", min = 2, max = 5,
                                       chance = 0.5, requires_player_kill = true }},
                        },
                        spawn_egg = { name = "Ovo", primary_color = 255 },
                    })
                end
                """);

        EntityDefinition entity = entities.get(0);
        assertEquals("minecraft:entities/zombie", entity.loot.table);
        assertEquals(1, entity.loot.drops.size());
        assertEquals("minecraft:emerald", entity.loot.drops.get(0).item);
        assertEquals(5, entity.loot.drops.get(0).max);
        assertEquals(0.5f, entity.loot.drops.get(0).chance);
        assertTrue(entity.loot.drops.get(0).requiresPlayerKill);

        assertNotNull(entity.spawnEgg);
        assertEquals(255, entity.spawnEgg.primaryColor);
    }

    @Test
    void nascimentoNaturalDeclaradoPorScript(@TempDir Path root) throws IOException {
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({
                        id = "guardiao",
                        name = "Guardiao",
                        base = "minecraft:iron_golem",
                        category = "monster",
                        spawn = {
                            biomes = { "#minecraft:is_mountain" },
                            weight = 8, min_group = 1, max_group = 2,
                            min_light = 0, max_light = 7, min_y = 60,
                        },
                    })
                end
                """);

        var spawn = entities.get(0).spawn;
        assertNotNull(spawn);
        assertEquals(List.of("#minecraft:is_mountain"), spawn.biomes);
        assertEquals(8, spawn.weight);
        assertEquals(7, spawn.maxLight);
        assertEquals(60, spawn.minY);
        // Nulo e "a faixa do mundo", e nao zero: um limite zerado prenderia a criatura ao fundo do
        // mundo, onde ela nunca nasceria.
        assertNull(spawn.maxY);
    }

    @Test
    void oScriptEnxergaOQueJaFoiDeclarado(@TempDir Path root) throws IOException {
        // A leitura da mesma fase: um mod que registra em cima do bestiario de outro precisa saber
        // o que ja existe. Nesta fase nada foi registrado no jogo ainda, nem o que veio de JSON --
        // entao o que ele enxerga sao os manifestos.
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({
                        id = "quantas_" .. #ctx.register.declared(),
                        name = "Contagem",
                        base = "minecraft:iron_golem",
                    })
                end
                """);

        assertEquals("quantas_0", entities.get(0).id);
    }

    // ------------------------------------------------------------------ o que e recusado

    @Test
    void semPermissaoOScriptNaoRegistra(@TempDir Path root) throws IOException {
        // A permissao e exigida na carga do manifesto: um mod que declara a fase sem pedir
        // entity.register e recusado antes de rodar linha nenhuma.
        var entities = run(root, "\"chat.send\"", """
                return function(ctx)
                    ctx.register.entity({ id = "x", name = "X", base = "minecraft:pig" })
                end
                """);
        assertTrue(entities.isEmpty(), "o mod inteiro deveria ter sido recusado");
    }

    @Test
    void especieSemBaseNaoEntra(@TempDir Path root) throws IOException {
        // Sem base nao ha modelo nem IA: o registro produziria um bicho invisivel, que nao se
        // parece com erro de script.
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({ id = "fantasma", name = "Fantasma" })
                end
                """);
        assertTrue(entities.isEmpty());
    }

    @Test
    void idRepetidoNoMesmoModEEcusado(@TempDir Path root) throws IOException {
        // O segundo registro estoura, e o script para ali. O primeiro ja entrou -- o que importa e
        // nao haver dois com o mesmo id, porque o segundo sobrescreveria o primeiro em silencio.
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({ id = "g", name = "G", base = "minecraft:pig" })
                    ctx.register.entity({ id = "g", name = "Outro", base = "minecraft:cow" })
                end
                """);

        assertEquals(1, entities.size(), "so o primeiro deveria ter entrado");
        assertEquals("g", entities.get(0).id);
    }

    @Test
    void erroNoScriptNaoDerrubaOManifesto(@TempDir Path root) throws IOException {
        // Um script que estoura no meio: o que ele registrou antes vale, e o mod continua
        // carregado. Derrubar tudo transformaria um bestiario torto em "o jogo nao abre".
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    ctx.register.entity({ id = "g", name = "G", base = "minecraft:pig" })
                    error("estourei de proposito")
                end
                """);

        assertEquals(1, entities.size());
        assertEquals("g", entities.get(0).id);
    }

    @Test
    void oSandboxValeNaFaseDeRegistro(@TempDir Path root) throws IOException {
        // A fase roda com o jogo carregando, sem ninguem olhando: e o pior momento possivel para
        // um script alcancar disco ou processo.
        var entities = run(root, "\"entity.register\"", """
                return function(ctx)
                    if io ~= nil or os ~= nil or require ~= nil then
                        ctx.register.entity({ id = "vazou", name = "Vazou",
                                              base = "minecraft:pig" })
                    end
                end
                """);

        assertTrue(entities.isEmpty(), "io, os e require nao deveriam existir na fase de registro");
    }

    @Test
    void scriptQueNaoDevolveFuncaoERecusado(@TempDir Path root) throws IOException {
        var entities = run(root, "\"entity.register\"", "return 42\n");
        assertTrue(entities.isEmpty());
    }

    @Test
    void modSemFaseDeRegistroNaoEAfetado(@TempDir Path root) throws IOException {
        Path dir = root.resolve("simples");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "simples",
                  "name": "Simples",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": [
                    { "id": "guardiao", "name": "Guardiao", "base": "minecraft:iron_golem" }
                  ]
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        new RegistrationRuntime(LoggerFactory.getLogger("test"), null).runAll(mods);

        // O caminho normal e o JSON: quem nao gera nada nunca escreve um script de registro, e a
        // fase nao pode mexer no que ele declarou.
        var entities = mods.get(0).manifest().entities;
        assertEquals(1, entities.size());
        assertEquals("guardiao", entities.get(0).id);
        assertFalse(entities.get(0).id.contains(":"),
                "o id declarado em JSON nao leva namespace, como o vindo de script");
    }
}
