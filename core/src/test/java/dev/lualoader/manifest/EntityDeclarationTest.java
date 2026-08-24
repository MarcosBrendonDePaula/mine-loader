package dev.lualoader.manifest;

import dev.lualoader.platform.EntityDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.slf4j.event.Level;
import org.slf4j.helpers.LegacyAbstractLogger;

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
 * A espécie declarada no manifesto, verificada sem abrir o jogo.
 *
 * <p>Toda recusa aqui existe porque a alternativa é pior que um erro: uma espécie sem base nasce
 * invisível, uma com categoria errada nunca aparece, e uma com caixa de colisão fora de escala
 * atravessa parede. Nenhum dos três se parece com erro de manifesto para quem escreveu o mod, e
 * nenhum dos três deixa rastro no log — então precisam morrer na carga.
 */
class EntityDeclarationTest {

    private Path writeMod(Path root, String manifest) throws IOException {
        Path dir = root.resolve("bestiary");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    /** Um manifesto invalido nao derruba a carga: e logado e o mod fica de fora. */
    private static final class CapturingLogger extends LegacyAbstractLogger {
        final List<String> errors = new ArrayList<>();

        @Override
        protected void handleNormalizedLoggingCall(Level level, Marker marker, String message,
                                                   Object[] arguments, Throwable throwable) {
            if (level != Level.ERROR) return;
            StringBuilder rendered = new StringBuilder(message);
            for (Object argument : arguments == null ? new Object[0] : arguments) {
                int slot = rendered.indexOf("{}");
                if (slot < 0) break;
                rendered.replace(slot, slot + 2, String.valueOf(argument));
            }
            errors.add(rendered.toString());
        }

        @Override
        protected String getFullyQualifiedCallerName() {
            return null;
        }

        @Override
        public boolean isTraceEnabled() {
            return false;
        }

        @Override
        public boolean isDebugEnabled() {
            return false;
        }

        @Override
        public boolean isInfoEnabled() {
            return false;
        }

        @Override
        public boolean isWarnEnabled() {
            return false;
        }

        @Override
        public boolean isErrorEnabled() {
            return true;
        }
    }

    /** Carrega esperando recusa, e devolve o que foi dito sobre ela. */
    private List<String> refusalFor(Path root) throws IOException {
        CapturingLogger logger = new CapturingLogger();
        List<ModLoader.LoadedMod> mods = new ModLoader(logger).discover(root);

        assertTrue(mods.isEmpty(), "o mod invalido nao deveria ter sido carregado");
        assertFalse(logger.errors.isEmpty(), "a recusa deveria ter sido registrada");
        return logger.errors;
    }

    /** Monta um manifesto com uma unica especie, cujo corpo o teste descreve. */
    private static String manifestWith(String entityBody) {
        return """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": [
                    {
                %s
                    }
                  ]
                }
                """.formatted(entityBody);
    }

    private static final String MINIMAL = """
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie\"""";

    // ------------------------------------------------------------------ o que passa

    @Test
    void especieMinimaHerdaTudoDaBase(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith(MINIMAL));

        EntityDefinition entity = discover(dir).get(0).manifest().entities.get(0);
        assertEquals("stone_guardian", entity.id);
        assertEquals("minecraft:zombie", entity.base);

        // Zero e nulo aqui significam "herda", e nao "vale zero". Um padrao numerico proprio faria
        // uma especie declarada sem tamanho nascer com a caixa errada em vez da da base.
        assertNull(entity.category, "sem declarar, a categoria e a da base");
        assertEquals(0.0f, entity.width);
        assertEquals(0.0f, entity.height);
        assertEquals(0, entity.trackingRange);
        assertNull(entity.defaults, "sem declarar, nasce como a base nasce");
        assertNull(entity.spawnEgg, "ovo so existe quando pedido");
        assertTrue(entity.summonable);
        assertTrue(entity.saveable);
    }

    @Test
    void camposDeclaradosSaoLidos(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "frost_hound",
                      "name": "Cao de Gelo",
                      "base": "minecraft:wolf",
                      "category": "creature",
                      "width": 0.8,
                      "height": 1.2,
                      "tracking_range": 10,
                      "update_interval": 2,
                      "fire_immune": true,
                      "summonable": false,
                      "saveable": false,
                      "tags": ["minecraft:freeze_hurts_extra_types"],
                      "defaults": {
                        "health": 24.0,
                        "tame": true,
                        "glowing": true,
                        "no_gravity": false,
                        "attributes": { "minecraft:generic.movement_speed": 0.35 },
                        "effects": [
                          { "id": "minecraft:speed", "duration": 200, "amplifier": 1 }
                        ],
                        "equipment": {
                          "head": { "item": "minecraft:iron_helmet", "drop_chance": 0.25 }
                        }
                      },
                      "loot": {
                        "table": "minecraft:entities/wolf",
                        "drops": [
                          { "item": "minecraft:packed_ice", "min": 1, "max": 3, "chance": 0.5,
                            "requires_player_kill": true }
                        ]
                      },
                      "spawn_egg": {
                        "name": "Ovo de Cao de Gelo",
                        "primary_color": 12379391,
                        "secondary_color": 3355647
                      }"""));

        EntityDefinition entity = discover(dir).get(0).manifest().entities.get(0);
        assertEquals("minecraft:wolf", entity.base);
        assertEquals("creature", entity.category);
        assertEquals(0.8f, entity.width);
        assertEquals(1.2f, entity.height);
        assertEquals(10, entity.trackingRange);
        assertEquals(2, entity.updateInterval);
        assertTrue(entity.fireImmune);
        assertFalse(entity.summonable);
        assertFalse(entity.saveable);
        assertEquals(List.of("minecraft:freeze_hurts_extra_types"), entity.tags);

        // O ponto do reuso: o mesmo vocabulario de spawn_entity, lido do JSON sem tradutor proprio.
        assertNotNull(entity.defaults);
        assertEquals(24.0, entity.defaults.health);
        assertEquals(Boolean.TRUE, entity.defaults.tame);
        assertEquals(Boolean.TRUE, entity.defaults.glowing);
        assertEquals(Boolean.FALSE, entity.defaults.noGravity);
        assertEquals(0.35, entity.defaults.attributesOrEmpty()
                .get("minecraft:generic.movement_speed"));
        assertEquals(1, entity.defaults.effectsOrEmpty().size());
        assertEquals("minecraft:speed", entity.defaults.effectsOrEmpty().get(0).id);
        assertEquals("minecraft:iron_helmet",
                entity.defaults.equipmentOrEmpty().get("head").item);

        assertEquals("minecraft:entities/wolf", entity.loot.table);
        assertEquals(1, entity.loot.drops.size());
        EntityDefinition.EntityDropDefinition drop = entity.loot.drops.get(0);
        assertEquals("minecraft:packed_ice", drop.item);
        assertEquals(3, drop.max);
        assertEquals(0.5f, drop.chance);
        assertTrue(drop.requiresPlayerKill);

        assertNotNull(entity.spawnEgg);
        assertTrue(entity.spawnEgg.register);
        assertEquals(12379391, entity.spawnEgg.primaryColor);
    }

    // ------------------------------------------------------------------ o que e recusado

    @Test
    void especieSemBaseERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "ghost",
                      "name": "Fantasma\""""));

        // Sem base nao ha modelo nem IA, e o registro produziria um bicho invisivel -- que nao se
        // parece com erro de manifesto para quem escreveu o mod.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("precisa de uma base")),
                "a recusa deveria falar da base");
    }

    @Test
    void baseSemNamespaceERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "ghost",
                      "name": "Fantasma",
                      "base": "zombie\""""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("precisa de uma base")));
    }

    @Test
    void categoriaDesconhecidaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "category": "boss\""""));

        List<String> errors = refusalFor(dir);
        // A mensagem lista as categorias validas: quem escreveu precisa saber o que usar no lugar,
        // e nao so que errou.
        assertTrue(errors.stream().anyMatch(e -> e.contains("category") && e.contains("monster")),
                "a recusa deveria listar as categorias validas, veio: " + errors);
    }

    @Test
    void especieDuplicadaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": [
                    { "id": "twin", "name": "Gemeo", "base": "minecraft:pig" },
                    { "id": "twin", "name": "Outro Gemeo", "base": "minecraft:cow" }
                  ]
                }
                """);

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("entidade duplicada")));
    }

    @Test
    void caixaDeColisaoForaDeEscalaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "titan",
                      "name": "Tita",
                      "base": "minecraft:iron_golem",
                      "height": 40.0"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("height")));
    }

    @Test
    void dropComFaixaInvertidaERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "loot": {
                        "drops": [ { "item": "minecraft:stone", "min": 5, "max": 2 } ]
                      }"""));

        // max menor que min nao tem conserto obvio: sortear na faixa vazia daria zero em silencio.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("max")));
    }

    @Test
    void dropComChanceZeroERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "loot": {
                        "drops": [ { "item": "minecraft:stone", "chance": 0.0 } ]
                      }"""));

        // Chance zero e um drop declarado que nunca cai: quem escreveu quis outra coisa.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("chance")));
    }

    @Test
    void itemDeDropSemNamespaceERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "loot": {
                        "drops": [ { "item": "stone" } ]
                      }"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("namespace")));
    }

    @Test
    void corDeOvoForaDaFaixaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "spawn_egg": { "primary_color": -1 }"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("primary_color")));
    }

    @Test
    void vidaZeradaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao de Pedra",
                      "base": "minecraft:zombie",
                      "defaults": { "health": 0.0 }"""));

        // Uma especie que nasce com zero de vida morre no primeiro tique, e o mod pareceria nao ter
        // registrado nada.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("health")));
    }
    // ------------------------------------------------------------------ dividir em arquivos

    @Test
    void aListaInteiraVemDeOutroArquivo(@TempDir Path dir) throws IOException {
        Path mod = dir.resolve("bestiary");
        Files.createDirectories(mod.resolve("parts"));
        Files.writeString(mod.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": { "$import": "parts/entities.json" }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(mod.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);

        // O arquivo importado traz a lista inteira, e ainda importa uma especie de outro arquivo:
        // e o que permite um bestiario grande nao virar um mod.json de mil linhas.
        Files.writeString(mod.resolve("parts/entities.json"), """
                [
                  { "$import": "parts/guardiao.json" },
                  { "id": "sentinela", "name": "Sentinela", "base": "bestiary:guardiao" }
                ]
                """, StandardCharsets.UTF_8);
        Files.writeString(mod.resolve("parts/guardiao.json"), """
                { "id": "guardiao", "name": "Guardiao", "base": "minecraft:iron_golem" }
                """, StandardCharsets.UTF_8);

        List<EntityDefinition> entities = discover(dir).get(0).manifest().entities;
        assertEquals(2, entities.size(), "as duas especies deveriam ter vindo do arquivo");
        assertEquals("guardiao", entities.get(0).id);
        assertEquals("minecraft:iron_golem", entities.get(0).base);

        // Uma especie do arquivo importado herda de outra do mesmo arquivo: o import acontece
        // antes da validacao, entao a heranca nao sabe de onde cada uma veio.
        assertEquals("sentinela", entities.get(1).id);
        assertEquals("bestiary:guardiao", entities.get(1).base);
    }

    @Test
    void arquivoImportadoInvalidoERecusadoComOMesmoErro(@TempDir Path dir) throws IOException {
        Path mod = dir.resolve("bestiary");
        Files.createDirectories(mod.resolve("parts"));
        Files.writeString(mod.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": { "$import": "parts/entities.json" }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(mod.resolve("main.lua"), "return {}", StandardCharsets.UTF_8);
        Files.writeString(mod.resolve("parts/entities.json"), """
                [ { "id": "fantasma", "name": "Fantasma" } ]
                """, StandardCharsets.UTF_8);

        // Dividir em arquivos nao pode afrouxar a validacao: a especie sem base e recusada com a
        // mesma mensagem que teria dentro do mod.json.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("precisa de uma base")));
    }
    // ------------------------------------------------------------------ comportamento declarado

    @Test
    void oComportamentoDeclaradoELido(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": {
                        "clear": true,
                        "goals": [
                          { "type": "float", "priority": 0 },
                          { "type": "avoid", "priority": 1, "entity": "minecraft:wolf",
                            "range": 12.0, "speed": 1.3 },
                          { "type": "follow_item", "priority": 2,
                            "items": ["minecraft:diamond"] }
                        ],
                        "targets": [ { "type": "hurt_by", "priority": 1 } ]
                      }"""));

        EntityDefinition entity = discover(dir).get(0).manifest().entities.get(0);
        assertNotNull(entity.ai);
        assertTrue(entity.ai.clear);
        assertEquals(3, entity.ai.goals.size());
        assertEquals(1, entity.ai.targets.size());

        assertEquals("minecraft:wolf", entity.ai.goals.get(1).entity);
        assertEquals(12.0, entity.ai.goals.get(1).range);
        assertEquals(List.of("minecraft:diamond"), entity.ai.goals.get(2).items);
    }

    @Test
    void metaDesconhecidaERecusadaComOVocabulario(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "goals": [ { "type": "voar_para_a_lua" } ] }"""));

        List<String> errors = refusalFor(dir);
        // Recusada, e nao ignorada: ignorar daria uma criatura que nao faz o que o manifesto diz
        // que ela faz. E a mensagem lista o vocabulario, para nao virar adivinhacao.
        assertTrue(errors.stream().anyMatch(e -> e.contains("meta desconhecida")
                        && e.contains("wander")),
                "a recusa deveria listar o vocabulario, veio: " + errors);
    }

    @Test
    void fugirSemDeQuemFugirERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "goals": [ { "type": "avoid" } ] }"""));

        // Uma meta de fugir sem alvo nunca dispara, e nao da erro no jogo.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("de quem fugir")));
    }

    @Test
    void seguirItemSemItemERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "goals": [ { "type": "follow_item" } ] }"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("item que atraia")));
    }

    @Test
    void velocidadeZeradaERecusada(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "goals": [ { "type": "wander", "speed": 0.0 } ] }"""));

        // Velocidade zero deixaria a criatura parada tentando andar, para sempre.
        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("speed")));
    }

    @Test
    void alvoDesconhecidoERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "targets": [ { "type": "amar" } ] }"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("alvo desconhecido")));
    }

    @Test
    void cacarSemDizerQuemERecusado(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith("""
                      "id": "stone_guardian",
                      "name": "Guardiao",
                      "base": "minecraft:zombie",
                      "ai": { "targets": [ { "type": "attack_entity" } ] }"""));

        assertTrue(refusalFor(dir).stream().anyMatch(e -> e.contains("quem cacar")));
    }

    @Test
    void semAiDeclaradaAEspecieHerdaADaBase(@TempDir Path dir) throws IOException {
        writeMod(dir, manifestWith(MINIMAL));

        // Nulo, e nao uma lista vazia: a diferenca entre "herda a IA do zumbi" e "nao faz nada"
        // decide se a criatura anda.
        assertNull(discover(dir).get(0).manifest().entities.get(0).ai);
    }
}
