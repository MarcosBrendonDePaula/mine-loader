package dev.lualoader.manifest;

import dev.lualoader.resources.ResourcePackAssembler;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Contratos de conteúdo declarados no manifesto: loot, tags, itens e aba criativa. */
class ContentContractTest {

    private Path writeMod(Path root, String manifest) throws IOException {
        Path dir = root.resolve("content_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    @Test
    void lootAndTagsBecomeDataPackFiles(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [
                    {
                      "id": "gem_block",
                      "name": "Gem Block",
                      "loot": {"mode": "item", "item": "minecraft:diamond", "count": 3},
                      "tags": ["minecraft:mineable/pickaxe"]
                    }
                  ]
                }
                """);

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(1, mods.size());
        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path loot = out.resolve("pack/data/content_mod/loot_table/blocks/gem_block.json");
        assertTrue(Files.isRegularFile(loot), "loot table deveria ter sido gerada");
        String lootJson = Files.readString(loot, StandardCharsets.UTF_8);
        assertTrue(lootJson.contains("minecraft:diamond"), "loot deveria dropar o item declarado");
        assertTrue(lootJson.contains("\"count\": 3"), "loot deveria respeitar o count declarado");

        Path tag = out.resolve("pack/data/minecraft/tags/block/mineable/pickaxe.json");
        assertTrue(Files.isRegularFile(tag), "tag deveria ter sido gerada");
        String tagJson = Files.readString(tag, StandardCharsets.UTF_8);
        assertTrue(tagJson.contains("content_mod:gem_block"), "o bloco deveria estar na tag");
        assertTrue(tagJson.contains("\"replace\": false"), "a tag nao pode substituir o conteudo vanilla");
    }

    @Test
    void lootModeNoneProducesEmptyTable(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [
                    {"id": "ghost_block", "name": "Ghost", "loot": {"mode": "none"}}
                  ]
                }
                """);

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(discover(root), out.resolve("pack"));

        String json = Files.readString(
                out.resolve("pack/data/content_mod/loot_table/blocks/ghost_block.json"),
                StandardCharsets.UTF_8);
        assertTrue(json.contains("\"pools\": []"), "loot.mode=none nao deve dropar nada");
    }

    @Test
    void standaloneItemGeneratesModel(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": [
                    {
                      "id": "gem",
                      "name": "Gem",
                      "rarity": "rare",
                      "texture": {"fallback": "minecraft:item/diamond"}
                    }
                  ]
                }
                """);

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(1, mods.size(), "mod com item standalone deveria carregar");
        assertEquals(1, mods.get(0).manifest().items.size());

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        String model = Files.readString(
                out.resolve("pack/assets/content_mod/models/item/gem.json"),
                StandardCharsets.UTF_8);
        assertTrue(model.contains("minecraft:item/diamond"), "modelo deveria usar a textura de fallback");
    }

    @Test
    void foodAndFuelArePartOfTheItemContract(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": [{
                    "id": "racao",
                    "name": "Ração",
                    "food": {"nutrition": 6, "saturation": 0.8, "always_edible": true},
                    "fuel_burn_time": 400
                  }]
                }
                """);

        var item = discover(root).getFirst().manifest().items.getFirst();
        assertNotNull(item.food);
        assertEquals(6, item.food.nutrition);
        assertEquals(0.8, item.food.saturation, 0.0001);
        assertTrue(item.food.alwaysEdible);
        assertEquals(400, item.fuelBurnTime);
    }

    @Test
    void invalidFoodAndFuelContractsAreRejected(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": [{
                    "id": "bad_food",
                    "name": "Bad Food",
                    "food": {"nutrition": 21},
                    "fuel_burn_time": 32768
                  }]
                }
                """);
        assertTrue(discover(root).isEmpty(), "limites de comida e combustível deveriam ser rejeitados");
    }

    @Test
    void standardContractExposesFoodAndFuelCapabilities() {
        RuntimeContract contract = RuntimeContract.standard();
        assertEquals("1.0.0", contract.capabilityVersion("registry.item.food"));
        assertEquals("1.0.0", contract.capabilityVersion("registry.item.fuel"));
    }

    @Test
    void invalidItemContractsAreRejected(@TempDir Path root) throws IOException {
        // Durabilidade exige stack unitário; o manifesto abaixo viola isso.
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": [
                    {"id": "blade", "name": "Blade", "max_damage": 100, "max_stack_size": 64}
                  ]
                }
                """);
        assertTrue(discover(root).isEmpty(), "item com durabilidade e stack > 1 deveria ser rejeitado");
    }

    @Test
    void unknownRarityIsRejected(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "items": [
                    {"id": "gem", "name": "Gem", "rarity": "lendaria"}
                  ]
                }
                """);
        assertTrue(discover(root).isEmpty(), "rarity desconhecida deveria ser rejeitada");
    }

    @Test
    void creativeTabIconMustBeQualified(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "creative_tab": {"id": "main", "name": "Content", "icon": "gem"}
                }
                """);
        assertTrue(discover(root).isEmpty(), "icone sem namespace deveria ser rejeitado");
    }

    @Test
    void diagnosticsReportFieldsThatDoNothing(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [
                    {
                      "id": "gem_block",
                      "name": "Gem Block",
                      "placement": {"waterloggable": true},
                      "behavior": {"on_place": "antigo", "on_random_tick": "on_tick"},
                      "render": {"emissive": true}
                    }
                  ]
                }
                """);

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(1, mods.size(), "campos nao implementados avisam, mas nao impedem a carga");

        List<String> ignored = new ManifestDiagnostics(LoggerFactory.getLogger("test"))
                .collectIgnored(mods.get(0).manifest());

        assertTrue(ignored.stream().anyMatch(f -> f.contains("placement.waterloggable")));
        // on_place e o campo antigo, substituido por on_placed: continua avisado.
        assertTrue(ignored.stream().anyMatch(f -> f.contains("behavior.on_place")));
        // on_use e on_random_tick passaram a ser aplicados, entao saem da lista de ignorados.
        assertFalse(ignored.stream().anyMatch(f -> f.contains("behavior.on_use")));
        assertFalse(ignored.stream().anyMatch(f -> f.contains("behavior.on_random_tick")));
        assertTrue(ignored.stream().anyMatch(f -> f.contains("render.emissive")));
    }

    @Test
    void implementedFieldsAreNotReportedAsIgnored(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "content_mod",
                  "name": "Content Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [
                    {
                      "id": "gem_block",
                      "name": "Gem Block",
                      "loot": {"mode": "self"},
                      "tags": ["minecraft:mineable/pickaxe"],
                      "state": {"properties": [{"name": "lit", "type": "bool", "values": ["false", "true"]}]}
                    }
                  ]
                }
                """);

        List<String> ignored = new ManifestDiagnostics(LoggerFactory.getLogger("test"))
                .collectIgnored(discover(root).get(0).manifest());

        assertFalse(ignored.stream().anyMatch(f -> f.contains("loot")), "loot esta implementado");
        assertFalse(ignored.stream().anyMatch(f -> f.contains("tags")), "tags estao implementadas");
        assertFalse(ignored.stream().anyMatch(f -> f.contains("state")), "state esta implementado");
    }

    /**
     * Uma ferramenta declarada chega ao registro com o que a faz ferramenta.
     *
     * <p>Item com durabilidade e textura nao e ferramenta: sem nivel de colheita nao derruba
     * minerio, sem velocidade leva o mesmo tempo que a mao, e sem dano e enfeite. O teste guarda os
     * quatro campos que separam uma coisa da outra.
     */
    @Test
    void toolsCarryWhatMakesThemTools(@TempDir Path root) throws IOException {
        Path dir = root.resolve("ferramentas");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "ferramentas",
                  "name": "Ferramentas",
                  "version": "1.0.0",
                  "items": [{
                    "id": "picareta_de_rubi",
                    "name": "Picareta de Rubi",
                    "max_stack_size": 1,
                    "tool": {
                      "type": "pickaxe",
                      "level": 3,
                      "speed": 8.0,
                      "damage": 2.0,
                      "durability": 900,
                      "enchantability": 12,
                      "repair_item": "minecraft:diamond"
                    }
                  }]
                }
                """, StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertEquals(1, mods.size());

        var tool = mods.get(0).manifest().items.get(0).tool;
        assertNotNull(tool, "a ferramenta deveria ter sido lida");
        assertEquals("pickaxe", tool.type);
        assertEquals(3, tool.level);
        assertEquals(8.0, tool.speed);
        assertEquals(2.0, tool.damage);
        assertEquals(900, tool.durability);
        assertEquals("minecraft:diamond", tool.repairItem);
    }

    @Test
    void armorCarriesWhereItGoesAndHowMuchItProtects(@TempDir Path root) throws IOException {
        Path dir = root.resolve("armaduras");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "armaduras",
                  "name": "Armaduras",
                  "version": "1.0.0",
                  "items": [{
                    "id": "elmo_de_rubi",
                    "name": "Elmo de Rubi",
                    "max_stack_size": 1,
                    "armor": {
                      "slot": "helmet",
                      "protection": 3,
                      "toughness": 1.5,
                      "knockback_resistance": 0.1,
                      "durability": 200
                    }
                  }]
                }
                """, StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        var armor = mods.get(0).manifest().items.get(0).armor;

        assertNotNull(armor, "a armadura deveria ter sido lida");
        assertEquals("helmet", armor.slot);
        assertEquals(3, armor.protection);
        assertEquals(1.5, armor.toughness);
        assertEquals(200, armor.durability);
    }

    /**
     * Manifesto invalido de ferramenta ou armadura nao carrega.
     *
     * <p>A recusa e verificada pela ausencia na lista, e nao por excecao: discover registra a falha
     * no log e segue, para um mod defeituoso nao impedir a carga dos outros.
     */
    @Test
    void invalidToolsAndArmorAreRefused(@TempDir Path root) throws IOException {
        record Caso(String nome, String corpo, String esperado) {
        }

        var casos = java.util.List.of(
                new Caso("tipo",
                        """
                        "tool": { "type": "chave_inglesa" }
                        """,
                        "tipo de ferramenta desconhecido"),
                new Caso("nivel",
                        """
                        "tool": { "type": "pickaxe", "level": 9 }
                        """,
                        "level de ferramenta"),
                new Caso("empilha",
                        """
                        "tool": { "type": "pickaxe" }, "max_stack_size": 64
                        """,
                        "precisa de max_stack_size 1"),
                new Caso("slot",
                        """
                        "armor": { "slot": "capa" }
                        """,
                        "slot de armadura desconhecido"),
                new Caso("ambos",
                        """
                        "tool": { "type": "pickaxe" }, "armor": { "slot": "helmet" }
                        """,
                        "nao pode ser ferramenta e armadura"));

        for (Caso caso : casos) {
            // Cada caso ganha a propria raiz: discover varre as subpastas do que recebe, entao
            // apontar direto para a pasta do mod nao encontraria mod nenhum -- e um teste que
            // espera recusa passaria por nao ter validado nada.
            Path raiz = root.resolve("raiz_" + caso.nome());
            Path dir = raiz.resolve("mod_" + caso.nome());
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("mod.json"), """
                    {
                      "schema": 1,
                      "id": "teste_%s",
                      "name": "Teste",
                      "version": "1.0.0",
                      "items": [{
                        "id": "peca",
                        "name": "Peca",
                        "max_stack_size": 1,
                        %s
                      }]
                    }
                    """.formatted(caso.nome(), caso.corpo()), StandardCharsets.UTF_8);

            // discover nao propaga: um mod defeituoso e registrado no log e os outros seguem
            // carregando. A recusa aparece como ausencia na lista, e nao como excecao.
            var carregados = new ModLoader(LoggerFactory.getLogger("test")).discover(raiz);
            assertTrue(carregados.isEmpty(),
                    "o caso " + caso.nome() + " deveria ter sido recusado, mas carregou");
        }
    }
}
