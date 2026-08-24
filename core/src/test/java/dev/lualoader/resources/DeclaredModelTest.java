package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Modelos desenhados fora do loader, como os que o Blockbench exporta.
 *
 * <p>A forma declarada por caixas cobre silhuetas simples, mas não alcança textura por face, uv nem
 * rotação de elemento — e é exatamente isso que se desenha numa ferramenta visual. Aceitar o
 * arquivo pronto é o que liga as duas coisas: o mod desenha onde é confortável, e o loader só
 * precisa ligar os nomes de textura aos recursos declarados.
 */
class DeclaredModelTest {

    /** Um PNG de um pixel, suficiente para o montador ter o que copiar. */
    private static final byte[] PIXEL_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Um modelo como o Blockbench exporta: nomes de textura proprios e elementos com uv. */
    private static final String BLOCKBENCH_MODEL = """
            {
              "credit": "Made with Blockbench",
              "textures": {
                "tampo": "bloco/tampo",
                "pe": "bloco/pe"
              },
              "elements": [
                {
                  "from": [0, 12, 0],
                  "to": [16, 16, 16],
                  "faces": {
                    "up": {"uv": [0, 0, 16, 16], "texture": "#tampo"},
                    "down": {"uv": [0, 0, 16, 16], "texture": "#tampo"}
                  }
                },
                {
                  "from": [6, 0, 6],
                  "to": [10, 12, 10],
                  "faces": {
                    "north": {"uv": [0, 0, 4, 12], "texture": "#pe"}
                  }
                }
              ]
            }
            """;

    private Path writeMod(Path root) throws IOException {
        Path dir = root.resolve("bb_mod");
        Files.createDirectories(dir.resolve("assets"));
        Files.createDirectories(dir.resolve("models"));

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "bb_mod",
                  "name": "Blockbench Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "resources": {
                    "mesa": {"type": "model", "from": "models/mesa.json"},
                    "madeira": {"type": "image", "from": "assets/madeira.png"},
                    "ferro": {"type": "image", "from": "assets/ferro.png"}
                  },
                  "blocks": [{
                    "id": "mesa",
                    "name": "Mesa",
                    "render": {
                      "model": "@mesa",
                      "textures": {"tampo": "@madeira", "pe": "@ferro"}
                    }
                  }]
                }
                """, StandardCharsets.UTF_8);

        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("models/mesa.json"), BLOCKBENCH_MODEL, StandardCharsets.UTF_8);
        Files.write(dir.resolve("assets/madeira.png"), PIXEL_PNG);
        Files.write(dir.resolve("assets/ferro.png"), PIXEL_PNG);
        return dir;
    }

    private String assembleAndRead(Path root, Path out, String relative) throws IOException {
        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path file = out.resolve("pack").resolve(relative);
        assertTrue(Files.isRegularFile(file), "faltou o arquivo " + relative);
        return Files.readString(file, StandardCharsets.UTF_8);
    }

    @Test
    void oDesenhoDoModelosobrevive(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root);
        String model = assembleAndRead(root, out, "assets/bb_mod/models/block/mesa.json");

        // O que o loader nao entende passa intacto: uv e rotacao de elemento sao o motivo de
        // alguem desenhar numa ferramenta em vez de declarar caixas.
        assertTrue(model.contains("\"uv\""), "o uv do desenho deveria sobreviver: " + model);
        assertTrue(model.contains("[0,12,0]") || model.contains("[0, 12, 0]"),
                "os elementos deveriam sobreviver: " + model);
    }

    @Test
    void nomesDeTexturaApontamParaOPack(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root);
        String model = assembleAndRead(root, out, "assets/bb_mod/models/block/mesa.json");

        // O Blockbench grava caminhos que so fazem sentido no projeto dele. Sem a reescrita, o
        // jogo procuraria "bloco/tampo" e desenharia o cubo roxo.
        assertTrue(model.contains("bb_mod:block/mesa_tampo"), "tampo nao foi religado: " + model);
        assertTrue(model.contains("bb_mod:block/mesa_pe"), "pe nao foi religado: " + model);
        assertFalse(model.contains("\"bloco/tampo\""),
                "o caminho do projeto do Blockbench nao deveria sobrar: " + model);
    }

    @Test
    void asTexturasDeCadaNomeSaoCopiadas(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root);
        assembleAndRead(root, out, "assets/bb_mod/models/block/mesa.json");

        assertTrue(Files.isRegularFile(
                out.resolve("pack/assets/bb_mod/textures/block/mesa_tampo.png")));
        assertTrue(Files.isRegularFile(
                out.resolve("pack/assets/bb_mod/textures/block/mesa_pe.png")));
    }

    @Test
    void oModeloRecebeParticula(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root);
        String model = assembleAndRead(root, out, "assets/bb_mod/models/block/mesa.json");

        // Sem particle a poeira ao quebrar sai roxa, e um modelo do Blockbench raramente a traz.
        assertTrue(model.contains("particle"), "faltou a particula: " + model);
    }

    @Test
    void oBlockstateApontaParaOModeloDeclarado(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root);
        String blockstate = assembleAndRead(root, out, "assets/bb_mod/blockstates/mesa.json");

        assertTrue(blockstate.contains("bb_mod:block/mesa"),
                "o blockstate deveria apontar para o modelo declarado: " + blockstate);
        // Todas as variantes apontam para ele: a variante troca textura, e o modelo declarado ja
        // traz as suas.
        assertTrue(blockstate.contains("lua_variant=15"), "faltou variante: " + blockstate);
    }
}
