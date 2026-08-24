package dev.lualoader.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.manifest.ModLoader;
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
 * A tabela de saque de uma espécie declarada, gerada no data pack virtual.
 *
 * <p>Existe porque um tipo de entidade procura a tabela com o próprio id, e não a da base: sem
 * gerar nada, um zumbi declarado por um mod herdaria modelo, IA e vida do zumbi e morreria sem
 * deixar carne podre. Ninguém leria isso como defeito do loader — leria como decisão de quem
 * escreveu o mod.
 *
 * <p>Cada caso confere o JSON de verdade, e não que o arquivo existe. Um arquivo mal formado é
 * recusado pelo jogo em silêncio, e o sintoma volta a ser o mesmo: o bicho não cai nada.
 */
class EntityLootTableTest {

    private void writeMod(Path root, String entities) throws IOException {
        Path dir = root.resolve("bestiary");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "bestiary",
                  "name": "Bestiary",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "entities": %s
                }
                """.formatted(entities), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
    }

    private String assembleAndRead(Path root, Path out, String relative) throws IOException {
        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertFalse(mods.isEmpty(), "o mod do teste deveria ter carregado");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path file = out.resolve("pack").resolve(relative);
        assertTrue(Files.isRegularFile(file), "faltou o arquivo " + relative);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    private JsonObject lootOf(Path root, Path out) throws IOException {
        return JsonParser.parseString(assembleAndRead(root, out,
                "data/bestiary/loot_table/entities/stone_guardian.json")).getAsJsonObject();
    }

    @Test
    void herdaOsDropsDaBaseSemCopiaLos(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{ "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie" }]""");

        JsonObject table = lootOf(root, out);
        assertEquals("minecraft:entity", table.get("type").getAsString());

        JsonArray pools = table.getAsJsonArray("pools");
        assertEquals(1, pools.size(), "so a heranca, ja que nada proprio foi declarado");

        JsonObject entry = pools.get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        // Referencia, e nao copia: copiar congelaria os drops na versao em que o mod foi escrito,
        // e uma mudanca no zumbi deixaria de valer para tudo que descende dele.
        assertEquals("minecraft:loot_table", entry.get("type").getAsString());
        assertEquals("minecraft:entities/zombie", entry.get("value").getAsString());
    }

    @Test
    void tabelaDeclaradaSubstituiADaBase(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie",
                  "loot": { "table": "minecraft:entities/wither_skeleton" }
                }]""");

        JsonArray pools = lootOf(root, out).getAsJsonArray("pools");
        assertEquals(1, pools.size());
        assertEquals("minecraft:entities/wither_skeleton", pools.get(0).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject().get("value").getAsString());
    }

    @Test
    void dropProprioSomaAoDaBase(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie",
                  "loot": { "drops": [ { "item": "minecraft:emerald" } ] }
                }]""");

        JsonArray pools = lootOf(root, out).getAsJsonArray("pools");
        // Soma, e nao substitui: declarar um drop proprio nao deveria apagar em silencio tudo que
        // a base derrubava, e a perda so apareceria matando o bicho.
        assertEquals(2, pools.size(), "a heranca e o drop proprio");

        JsonObject own = pools.get(1).getAsJsonObject()
                .getAsJsonArray("entries").get(0).getAsJsonObject();
        assertEquals("minecraft:item", own.get("type").getAsString());
        assertEquals("minecraft:emerald", own.get("name").getAsString());
        assertFalse(own.has("functions"), "quantidade fixa de um nao precisa de funcao");
    }

    @Test
    void faixaChanceEMorteDeJogadorViramCondicoes(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie",
                  "loot": { "drops": [ {
                    "item": "minecraft:diamond", "min": 2, "max": 5,
                    "chance": 0.25, "requires_player_kill": true
                  } ] }
                }]""");

        JsonObject pool = lootOf(root, out).getAsJsonArray("pools").get(1).getAsJsonObject();
        JsonObject entry = pool.getAsJsonArray("entries").get(0).getAsJsonObject();

        JsonObject count = entry.getAsJsonArray("functions").get(0).getAsJsonObject()
                .getAsJsonObject("count");
        assertEquals(2, count.get("min").getAsInt());
        assertEquals(5, count.get("max").getAsInt());

        JsonArray conditions = pool.getAsJsonArray("conditions");
        assertEquals(2, conditions.size());
        assertEquals("minecraft:random_chance",
                conditions.get(0).getAsJsonObject().get("condition").getAsString());
        // 0.25 e nao "0,25": o separador decimal do sistema ja quebrou JSON gerado antes.
        assertEquals(0.25, conditions.get(0).getAsJsonObject().get("chance").getAsDouble());
        assertEquals("minecraft:killed_by_player",
                conditions.get(1).getAsJsonObject().get("condition").getAsString());
    }

    @Test
    void nomeDaEspecieEDoOvoEntramNaTraducao(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao de Pedra", "base": "minecraft:zombie",
                  "spawn_egg": { "name": "Ovo de Guardiao" }
                }]""");

        JsonObject lang = JsonParser.parseString(
                assembleAndRead(root, out, "assets/bestiary/lang/en_us.json")).getAsJsonObject();

        assertEquals("Guardiao de Pedra", lang.get("entity.bestiary.stone_guardian").getAsString());
        // Sem a chave do ovo, o criativo mostra o identificador cru no lugar do nome.
        assertEquals("Ovo de Guardiao", lang.get("item.bestiary.stone_guardian_spawn_egg")
                .getAsString());
    }

    @Test
    void ovoSemNomeGanhaUmDerivado(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao de Pedra", "base": "minecraft:zombie",
                  "spawn_egg": {}
                }]""");

        JsonObject lang = JsonParser.parseString(
                assembleAndRead(root, out, "assets/bestiary/lang/en_us.json")).getAsJsonObject();
        assertEquals("Guardiao de Pedra Spawn Egg",
                lang.get("item.bestiary.stone_guardian_spawn_egg").getAsString());
    }

    @Test
    void tagDeEspecieViraArquivoDeTag(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie",
                  "tags": ["minecraft:undead", "bestiary:guardians"]
                }]""");

        // A pasta e "entity_type", e nao "entity": o plural errado produz um arquivo que o jogo nao
        // le, e a tag some sem erro nenhum.
        JsonObject undead = JsonParser.parseString(assembleAndRead(root, out,
                "data/minecraft/tags/entity_type/undead.json")).getAsJsonObject();

        // "replace": false preserva o que o jogo ja pos na tag; true apagaria todos os mortos-vivos
        // do jogo e deixaria so a especie do mod.
        assertFalse(undead.get("replace").getAsBoolean());
        assertEquals("bestiary:stone_guardian",
                undead.getAsJsonArray("values").get(0).getAsString());

        JsonObject own = JsonParser.parseString(assembleAndRead(root, out,
                "data/bestiary/tags/entity_type/guardians.json")).getAsJsonObject();
        assertEquals("bestiary:stone_guardian",
                own.getAsJsonArray("values").get(0).getAsString());
    }

    @Test
    void especieSemTagNaoGeraArquivo(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{ "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie" }]""");
        assembleAndRead(root, out, "data/bestiary/loot_table/entities/stone_guardian.json");

        assertTrue(Files.notExists(out.resolve("pack/data/minecraft/tags/entity_type")),
                "sem tag declarada nao deveria sobrar pasta de tag");
    }

    @Test
    void ovoDeCriacaoGanhaModeloDeItem(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{
                  "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie",
                  "spawn_egg": { "primary_color": 12379391, "secondary_color": 3355647 }
                }]""");

        // Sem o modelo o ovo e o cubo roxo e preto -- e o servidor nao reclama de nada: o item
        // existe, entra na aba do criativo e funciona ao ser usado. So a tela denuncia.
        JsonObject model = JsonParser.parseString(assembleAndRead(root, out,
                "assets/bestiary/models/item/stone_guardian_spawn_egg.json")).getAsJsonObject();

        // O molde do jogo, e nao um desenho proprio: e ele que pinta as duas cores declaradas.
        // Um desenho aqui sairia igual para toda especie, ignorando primary e secondary_color.
        assertEquals("minecraft:item/template_spawn_egg", model.get("parent").getAsString());
    }

    @Test
    void especieSemOvoNaoGeraModelo(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, """
                [{ "id": "stone_guardian", "name": "Guardiao", "base": "minecraft:zombie" }]""");
        assembleAndRead(root, out, "data/bestiary/loot_table/entities/stone_guardian.json");

        assertTrue(Files.notExists(out.resolve(
                        "pack/assets/bestiary/models/item/stone_guardian_spawn_egg.json")),
                "sem ovo declarado nao deveria sobrar modelo");
    }
}
