package dev.lualoader.manifest;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import dev.lualoader.structure.StructurePlacer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Estruturas declaradas no manifesto e divisão do manifesto em vários arquivos. */
class StructureAndImportTest {

    /** Mundo simulado: registra o que foi escrito, sem Minecraft. */
    private static final class FakeWorld implements GameBridge {
        final Map<String, String> blocks = new HashMap<>();

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            blocks.put(x + "," + y + "," + z, blockId);
        }

        @Override
        public String getBlock(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
        }

        @Override
        public int fillBlocks(String id, int x1, int y1, int z1, int x2, int y2, int z2) {
            return 0;
        }

        @Override
        public void broadcast(String message) {
        }

        @Override
        public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
        }

        @Override
        public void setBlockProperty(String blockId, String property, float value) {
        }

        @Override
        public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
        }

        @Override
        public boolean isWorldAvailable() {
            return true;
        }
    }

    private Path writeMod(Path root, String manifest) throws IOException {
        Path dir = root.resolve("struct_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), manifest, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    private List<ModLoader.LoadedMod> discover(Path root) throws IOException {
        return new ModLoader(LoggerFactory.getLogger("test")).discover(root);
    }

    private static final String TOWER = """
            {
              "schema": 1,
              "id": "struct_mod",
              "name": "Struct Mod",
              "version": "0.1.0",
              "entrypoint": "main.lua",
              "structures": [
                {
                  "id": "hut",
                  "name": "Hut",
                  "origin": "corner",
                  "palette": {"S": "minecraft:stone", ".": "minecraft:air", " ": null},
                  "layers": [
                    ["SS", "SS"],
                    ["S.", " S"]
                  ]
                }
              ]
            }
            """;

    @Test
    void structureIsPlacedBlockByBlock(@TempDir Path root) throws IOException {
        writeMod(root, TOWER);
        var mods = discover(root);
        assertEquals(1, mods.size());

        FakeWorld world = new FakeWorld();
        var placement = new StructurePlacer(world)
                .place(mods.get(0).manifest().structures.get(0), 10, 20, 30);

        // Camada 0 preenchida (4) + camada 1 com dois S e um ar (3); o espaco nao e tocado.
        // O simbolo "." vale minecraft:air, entao conta como bloco colocado.
        assertEquals(7, placement.placed(), "blocos colocados");
        assertEquals(1, placement.skipped(), "o simbolo transparente nao deve escrever");

        assertEquals("minecraft:stone", world.getBlock(10, 20, 30));
        assertEquals("minecraft:stone", world.getBlock(11, 20, 31));
        assertEquals("minecraft:stone", world.getBlock(10, 21, 30));
        assertEquals("minecraft:air", world.getBlock(11, 21, 30));
        // Posicao do simbolo " ": nunca foi escrita.
        assertNull(world.blocks.get("10,21,31"));
    }

    @Test
    void bottomCenterOriginCentersTheDrawing(@TempDir Path root) throws IOException {
        writeMod(root, TOWER.replace("\"origin\": \"corner\"", "\"origin\": \"bottom_center\""));
        var mods = discover(root);

        FakeWorld world = new FakeWorld();
        new StructurePlacer(world).place(mods.get(0).manifest().structures.get(0), 10, 0, 10);

        // Desenho 2x2 centrado em (10,10) comeca em (9,9).
        assertEquals("minecraft:stone", world.getBlock(9, 0, 9));
        assertEquals("minecraft:stone", world.getBlock(10, 0, 10));
    }

    @Test
    void symbolOutsidePaletteIsRejectedAtLoad(@TempDir Path root) throws IOException {
        writeMod(root, TOWER.replace("[\"SS\", \"SS\"]", "[\"SX\", \"SS\"]"));
        assertTrue(discover(root).isEmpty(), "simbolo fora da paleta deve impedir a carga do mod");
    }

    @Test
    void manifestCanBeSplitAcrossFiles(@TempDir Path root) throws IOException {
        Path dir = writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "parts/block.json"}],
                  "structures": [{"$import": "parts/hut.json"}]
                }
                """);
        Files.createDirectories(dir.resolve("parts"));
        Files.writeString(dir.resolve("parts/block.json"), """
                {"id": "rock", "name": "Rock"}
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("parts/hut.json"), """
                {
                  "id": "hut",
                  "name": "Hut",
                  "palette": {"S": "minecraft:stone"},
                  "layers": [["S"]]
                }
                """, StandardCharsets.UTF_8);

        var mods = discover(root);
        assertEquals(1, mods.size(), "o mod dividido em arquivos deveria carregar");
        assertEquals("rock", mods.get(0).manifest().blocks.get(0).id);
        assertEquals("hut", mods.get(0).manifest().structures.get(0).id);
    }

    @Test
    void importedFileCanImportAnother(@TempDir Path root) throws IOException {
        Path dir = writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": {"$import": "parts/todos.json"}
                }
                """);
        Files.createDirectories(dir.resolve("parts"));
        // Um array inteiro vindo de import, com um item que tambem e importado.
        Files.writeString(dir.resolve("parts/todos.json"), """
                [{"$import": "parts/rock.json"}]
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("parts/rock.json"), """
                {"id": "rock", "name": "Rock"}
                """, StandardCharsets.UTF_8);

        var mods = discover(root);
        assertEquals(1, mods.size());
        assertEquals("rock", mods.get(0).manifest().blocks.get(0).id);
    }

    @Test
    void importCannotEscapeTheModFolder(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("segredo.json"), "{\"id\": \"x\"}", StandardCharsets.UTF_8);
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "../segredo.json"}]
                }
                """);

        assertTrue(discover(root).isEmpty(), "import fora da pasta do mod deve ser recusado");
    }

    @Test
    void missingImportIsReportedInsteadOfIgnored(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "parts/nao_existe.json"}]
                }
                """);

        assertTrue(discover(root).isEmpty(), "import inexistente nao pode passar em silencio");
    }

    @Test
    void oversizedStructureIsRefused() {
        ModManifest.StructureDefinition huge = new ModManifest.StructureDefinition();
        huge.id = "huge";
        huge.palette = Map.of("S", "minecraft:stone");
        huge.layers = new java.util.ArrayList<>();
        // 64 camadas de 64x64 = 262.144 blocos, acima do teto de 32.768.
        for (int y = 0; y < 64; y++) {
            List<String> layer = new java.util.ArrayList<>();
            for (int z = 0; z < 64; z++) layer.add("S".repeat(64));
            huge.layers.add(layer);
        }

        FakeWorld world = new FakeWorld();
        var placer = new StructurePlacer(world);
        try {
            placer.place(huge, 0, 0, 0);
            throw new AssertionError("deveria ter recusado a estrutura");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("excede o limite"), expected.getMessage());
        }
        assertTrue(world.blocks.isEmpty(), "nada pode ser escrito quando a estrutura e recusada");
    }

    @Test
    void detachedBridgeRefusesStructurePlacement() {
        ModManifest.StructureDefinition simple = new ModManifest.StructureDefinition();
        simple.id = "simple";
        simple.palette = Map.of("S", "minecraft:stone");
        simple.layers = List.of(List.of("S"));

        var placer = new StructurePlacer(GameBridge.DETACHED);
        try {
            placer.place(simple, 0, 0, 0);
            throw new AssertionError("sem plataforma nao ha onde posicionar");
        } catch (BridgeException expected) {
            assertTrue(expected.getMessage().contains("nenhuma plataforma"));
        }
    }
}
