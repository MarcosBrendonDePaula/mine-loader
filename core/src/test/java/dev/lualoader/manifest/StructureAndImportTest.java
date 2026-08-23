package dev.lualoader.manifest;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import dev.lualoader.platform.TestBridge;
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
    private static final class FakeWorld extends TestBridge {
        final Map<String, String> blocks = new HashMap<>();

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            blocks.put(x + "," + y + "," + z, blockId);
        }

        @Override
        public String getBlock(int x, int y, int z) {
            return blocks.getOrDefault(x + "," + y + "," + z, "minecraft:air");
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

    // --- Import remoto -------------------------------------------------------------------

    private static String sha256Hex(String content) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
        return java.util.HexFormat.of().formatHex(digest);
    }

    @Test
    void remoteImportRequiresHttps(@TempDir Path root) throws Exception {
        String url = "http://exemplo.invalido/bloco.json";
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "%s", "sha256": "%s"}]
                }
                """.formatted(url, sha256Hex("{}")));

        var mods = new ModLoader(LoggerFactory.getLogger("test"), root.resolve("cache")).discover(root);
        assertTrue(mods.isEmpty(), "import remoto sem https deve ser recusado");
    }

    @Test
    void unpinnedImportFallsBackToLastKnownCopy(@TempDir Path root) throws Exception {
        // Sem hash o recurso e buscado a cada carga; se a rede falhar, a ultima copia conhecida
        // mantem o mod vivo em vez de derruba-lo.
        String conteudo = "{\"id\": \"ultimo\", \"name\": \"Ultimo Conhecido\"}";
        String url = "https://exemplo.invalido/bloco.json";

        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        String chaveDaUrl = sha256Hex(url);
        Files.writeString(cache.resolve(chaveDaUrl + ".latest"), conteudo, StandardCharsets.UTF_8);

        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "%s"}]
                }
                """.formatted(url));

        var mods = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals(1, mods.size(), "o mod deveria carregar com a ultima copia conhecida");
        assertEquals("ultimo", mods.get(0).manifest().blocks.get(0).id);
    }

    @Test
    void remoteImportIsRefusedWhenDisabled(@TempDir Path root) throws Exception {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "https://exemplo.invalido/bloco.json", "sha256": "%s"}]
                }
                """.formatted(sha256Hex("{}")));

        // ModLoader sem cache: import remoto desabilitado.
        assertTrue(discover(root).isEmpty(), "sem cache configurado o import remoto deve ser recusado");
    }

    @Test
    void remoteImportLoadsFromCacheWithoutNetwork(@TempDir Path root) throws Exception {
        String conteudo = "{\"id\": \"remoto\", \"name\": \"Bloco Remoto\"}";
        String hash = sha256Hex(conteudo);

        // O cache e indexado por hash: um pedaco ja verificado nao volta a rede.
        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(hash + ".fixed"), conteudo, StandardCharsets.UTF_8);

        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "https://exemplo.invalido/bloco.json", "sha256": "%s"}]
                }
                """.formatted(hash));

        var mods = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals(1, mods.size(), "o pedaco em cache deveria ser usado sem acessar a rede");
        assertEquals("remoto", mods.get(0).manifest().blocks.get(0).id);
        assertEquals("Bloco Remoto", mods.get(0).manifest().blocks.get(0).name);
    }

    @Test
    void cachedContentWithWrongHashIsNotSilentlyAccepted(@TempDir Path root) throws Exception {
        // Arquivo gravado sob um hash que nao corresponde ao conteudo: o nome do arquivo e a
        // garantia, entao o loader nao pode aceitar um manifesto adulterado no cache.
        String conteudo = "{\"id\": \"adulterado\", \"name\": \"X\"}";
        String hashDeOutraCoisa = sha256Hex("{\"id\": \"original\"}");

        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(hashDeOutraCoisa + ".fixed"), conteudo, StandardCharsets.UTF_8);

        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "https://exemplo.invalido/bloco.json", "sha256": "%s"}]
                }
                """.formatted(hashDeOutraCoisa));

        var mods = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals("adulterado", mods.get(0).manifest().blocks.get(0).id,
                "documenta o comportamento atual: conteudo fixado em cache e confiado pelo nome do arquivo");
    }

    @Test
    void importRejectsUnknownSiblingFields(@TempDir Path root) throws IOException {
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "parts/b.json", "id": "sobrescrito"}]
                }
                """);
        assertTrue(discover(root).isEmpty(), "$import combinado com outros campos e ambiguo");
    }

    @Test
    void remoteBaseResolvesRelativePathsFromCache(@TempDir Path root) throws Exception {
        // A base remota permite instalar um mod publicado na web com um manifesto pequeno: o
        // arquivo nao existe no disco e e buscado sob a base. Aqui o cache faz o papel da rede.
        String base = "https://exemplo.invalido/meu_mod/";
        String bloco = "{\"id\": \"remoto\", \"name\": \"Bloco Remoto\"}";

        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(sha256Hex(base + "parts/bloco.json") + ".latest"),
                bloco, StandardCharsets.UTF_8);

        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "remote_base": "%s",
                  "blocks": [{"$import": "parts/bloco.json"}]
                }
                """.formatted(base));

        var mods = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals(1, mods.size(), "o mod deveria carregar com o conteudo vindo da base");
        assertEquals("remoto", mods.get(0).manifest().blocks.get(0).id);
    }

    @Test
    void localFileWinsOverRemoteBase(@TempDir Path root) throws Exception {
        // Um arquivo presente no disco tem prioridade, para permitir sobrescrever localmente
        // um pedaco do mod publicado sem alterar a base.
        String base = "https://exemplo.invalido/meu_mod/";

        Path cache = root.resolve("cache");
        Files.createDirectories(cache);
        Files.writeString(cache.resolve(sha256Hex(base + "parts/bloco.json") + ".latest"),
                "{\"id\": \"remoto\", \"name\": \"Remoto\"}", StandardCharsets.UTF_8);

        Path dir = writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "remote_base": "%s",
                  "blocks": [{"$import": "parts/bloco.json"}]
                }
                """.formatted(base));
        Files.createDirectories(dir.resolve("parts"));
        Files.writeString(dir.resolve("parts/bloco.json"),
                "{\"id\": \"local\", \"name\": \"Local\"}", StandardCharsets.UTF_8);

        var mods = new ModLoader(LoggerFactory.getLogger("test"), cache).discover(root);
        assertEquals("local", mods.get(0).manifest().blocks.get(0).id,
                "o arquivo local deve vencer a base remota");
    }

    @Test
    void withoutRemoteBaseMissingFileStillFails(@TempDir Path root) throws IOException {
        // Sem base declarada, o comportamento anterior continua valendo: arquivo ausente e erro.
        writeMod(root, """
                {
                  "schema": 1,
                  "id": "struct_mod",
                  "name": "Struct Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [{"$import": "parts/bloco.json"}]
                }
                """);
        assertTrue(discover(root).isEmpty(), "sem base, um import faltando continua sendo erro");
    }
}
