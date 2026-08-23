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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carga simultânea de vários mods.
 *
 * <p>Cada mod é um namespace próprio: os identificadores de bloco e item usam o id do mod, não
 * o id do loader. Mods distintos podem declarar o mesmo id local sem colidir, e uma falha em um
 * mod não pode impedir a carga dos demais.
 */
class MultiModTest {

    private void writeMod(Path root, String modId, String localId) throws IOException {
        Path dir = root.resolve(modId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "%s",
                  "name": "%s",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "creative_tab": {"id": "main", "name": "%s"},
                  "items": [{"id": "shard", "name": "Shard"}],
                  "blocks": [
                    {
                      "id": "%s",
                      "name": "Block of %s",
                      "tags": ["minecraft:mineable/pickaxe"]
                    }
                  ]
                }
                """.formatted(modId, modId, modId, localId, modId), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    @Test
    void manyModsLoadTogether(@TempDir Path root) throws IOException {
        writeMod(root, "alpha_mod", "core_block");
        writeMod(root, "beta_mod", "core_block");
        writeMod(root, "gamma_mod", "core_block");

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(3, mods.size(), "os tres mods deveriam carregar juntos");

        // O mesmo id local em mods diferentes nao colide: o namespace e o id do mod.
        List<String> ids = mods.stream()
                .map(mod -> mod.manifest().id + ":" + mod.manifest().blocks.get(0).id)
                .toList();
        assertEquals(List.of(
                "alpha_mod:core_block",
                "beta_mod:core_block",
                "gamma_mod:core_block"
        ), ids);
    }

    @Test
    void oneBrokenModDoesNotStopTheOthers(@TempDir Path root) throws IOException {
        writeMod(root, "alpha_mod", "core_block");
        writeMod(root, "beta_mod", "core_block");

        // Manifesto invalido: o diretorio nao corresponde ao id declarado.
        Path broken = root.resolve("broken_mod");
        Files.createDirectories(broken);
        Files.writeString(broken.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "outro_id",
                  "name": "Broken",
                  "version": "0.1.0",
                  "entrypoint": "main.lua"
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(broken.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);

        List<ModLoader.LoadedMod> mods = discover(root);
        assertEquals(2, mods.size(), "o mod invalido nao pode impedir os validos");
        assertTrue(mods.stream().noneMatch(mod -> mod.manifest().id.equals("outro_id")));
    }

    @Test
    void tagsFromDifferentModsAreMerged(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "alpha_mod", "core_block");
        writeMod(root, "beta_mod", "core_block");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(discover(root), out.resolve("pack"));

        String tag = Files.readString(
                out.resolve("pack/data/minecraft/tags/block/mineable/pickaxe.json"),
                StandardCharsets.UTF_8);

        // Uma unica tag precisa conter os blocos dos dois mods, sem um sobrescrever o outro.
        assertTrue(tag.contains("alpha_mod:core_block"), "faltou o bloco do alpha_mod");
        assertTrue(tag.contains("beta_mod:core_block"), "faltou o bloco do beta_mod");
    }

    @Test
    void eachModGetsItsOwnNamespacedAssets(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "alpha_mod", "core_block");
        writeMod(root, "beta_mod", "core_block");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(discover(root), out.resolve("pack"));

        for (String modId : List.of("alpha_mod", "beta_mod")) {
            assertTrue(Files.isRegularFile(out.resolve("pack/assets/" + modId + "/blockstates/core_block.json")),
                    "blockstate deveria estar no namespace de " + modId);
            assertTrue(Files.isRegularFile(out.resolve("pack/assets/" + modId + "/lang/en_us.json")),
                    "traducao deveria estar no namespace de " + modId);
            assertTrue(Files.isRegularFile(out.resolve("pack/data/" + modId + "/loot_table/blocks/core_block.json")),
                    "loot table deveria estar no namespace de " + modId);
        }
    }

    @Test
    void translationsUseTheDeclaredNames(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "alpha_mod", "core_block");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(discover(root), out.resolve("pack"));

        String lang = Files.readString(
                out.resolve("pack/assets/alpha_mod/lang/en_us.json"), StandardCharsets.UTF_8);

        // Sem estas chaves o jogo exibiria "block.alpha_mod.core_block" na tela.
        assertTrue(lang.contains("\"block.alpha_mod.core_block\": \"Block of alpha_mod\""), lang);
        assertTrue(lang.contains("\"item.alpha_mod.shard\": \"Shard\""), lang);
        assertTrue(lang.contains("\"itemGroup.alpha_mod.main\": \"alpha_mod\""), lang);
    }
}
