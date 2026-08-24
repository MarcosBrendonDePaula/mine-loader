package dev.lualoader.resources;

import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O modelo desenhado de um bloco, conforme a forma declarada.
 *
 * <p>Antes o montador escrevia {@code cube_all} para tudo, e a forma só mudava a colisão. O
 * resultado era um bloco incoerente — uma laje com colisão de laje e aparência de cubo inteiro, em
 * que o jogador via um bloco cheio e atravessava a metade de cima. Isso passava por qualquer teste
 * porque nada olhava o modelo gerado.
 */
class BlockModelTest {

    private Path writeMod(Path root, String blockJson) throws IOException {
        Path dir = root.resolve("shape_mod");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "shape_mod",
                  "name": "Shape Mod",
                  "version": "0.1.0",
                  "entrypoint": "main.lua",
                  "blocks": [%s]
                }
                """.formatted(blockJson), StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("main.lua"), "return {}\n", StandardCharsets.UTF_8);
        return dir;
    }

    /** Monta o pack e devolve o modelo gerado da variante zero. */
    private String modelOf(Path root, Path out, String blockJson) throws IOException {
        writeMod(root, blockJson);

        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path model = out.resolve("pack/assets/shape_mod/models/block/crate_v0.json");
        assertTrue(Files.isRegularFile(model), "o modelo deveria ter sido gerado em " + model);
        return Files.readString(model, StandardCharsets.UTF_8);
    }

    @Test
    void cuboInteiroContinuaUsandoOModeloDoJogo(@TempDir Path root, @TempDir Path out)
            throws IOException {
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate"}""");

        // Desenhar o cubo por caixas daria o mesmo resultado, mais caro e sem o sombreamento de
        // face que o cube_all traz de graca.
        assertTrue(model.contains("minecraft:block/cube_all"),
                "cubo inteiro deveria usar cube_all: " + model);
        assertFalse(model.contains("elements"), "cubo inteiro nao precisa de elements");
    }

    @Test
    void lajeVirouCaixaDeMeiaAltura(@TempDir Path root, @TempDir Path out) throws IOException {
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate", "shape": {"outline": "slab", "collision": "slab"}}""");

        assertFalse(model.contains("cube_all"), "uma laje nao pode sair como cubo: " + model);
        assertTrue(model.contains("\"elements\""), "deveria ter elements: " + model);
        assertTrue(model.contains("\"from\": [0, 0, 0]"), "deveria comecar na base: " + model);
        // Oito de dezesseis: meia altura, que e o que faz a laje parecer uma laje.
        assertTrue(model.contains("\"to\": [16, 8, 16]"), "deveria ter meia altura: " + model);
    }

    @Test
    void formaVisualHerdaOContornoQuandoNaoDeclarada(@TempDir Path root, @TempDir Path out)
            throws IOException {
        // O caso que motivou tudo: o altar do exemplo declara so collision e outline, e era
        // desenhado como cubo macico. Exigir repetir o nome em visual so multiplicaria a chance de
        // os dois divergirem.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate", "shape": {"outline": "table", "collision": "table"}}""");

        assertFalse(model.contains("cube_all"), "deveria herdar a forma do contorno: " + model);
        // A mesa sao duas caixas: o tampo e o pe.
        assertTrue(model.contains("\"to\": [16, 16, 16]"), "faltou o tampo: " + model);
        assertTrue(model.contains("\"from\": [2, 0, 2]"), "faltou o pe: " + model);
    }

    @Test
    void visualDeclaradoGanhaDoContorno(@TempDir Path root, @TempDir Path out) throws IOException {
        // Um bloco pode ser solido para andar e fino para ver -- um painel de vidro num caixilho,
        // por exemplo. Quando os dois sao declarados, o desenho segue o visual.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate",
                 "shape": {"collision": "full_cube", "outline": "full_cube", "visual": "carpet"}}""");

        assertTrue(model.contains("\"to\": [16, 1, 16]"), "deveria seguir o visual: " + model);
    }

    @Test
    void caixasPropriasGanhamDoNome(@TempDir Path root, @TempDir Path out) throws IOException {
        // O campo boxes existia no manifesto e nunca era lido -- aceito, validado e ignorado.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate",
                 "shape": {"outline": "slab", "boxes": [[3, 0, 3, 13, 5, 13]]}}""");

        assertTrue(model.contains("\"from\": [3, 0, 3]"), "deveria usar a caixa declarada: " + model);
        assertTrue(model.contains("\"to\": [13, 5, 13]"), "deveria usar a caixa declarada: " + model);
        assertFalse(model.contains("\"to\": [16, 8, 16]"), "a caixa propria deveria vencer o nome");
    }

    @Test
    void faceInternaNaoEcortadaPeloVizinho(@TempDir Path root, @TempDir Path out)
            throws IOException {
        // cullface manda esconder a face quando o vizinho e solido daquele lado. Faz sentido para
        // uma face na borda do bloco; numa caixa interna faria a peca ficar oca assim que houvesse
        // qualquer bloco ao lado -- um defeito que aparece longe da causa.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate", "shape": {"boxes": [[4, 4, 4, 12, 12, 12]]}}""");

        assertFalse(model.contains("cullface"),
                "uma caixa que nao encosta em borda nenhuma nao pode ter cullface: " + model);
    }

    @Test
    void faceNaBordaEcortadaPeloVizinho(@TempDir Path root, @TempDir Path out) throws IOException {
        // A laje encosta embaixo e nos quatro lados; so o topo fica livre. Sem o corte, as faces
        // escondidas continuariam sendo desenhadas e custariam de graca.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate", "shape": {"outline": "slab"}}""");

        assertTrue(model.contains("\"down\": {\"texture\": \"#all\", \"cullface\": \"down\"}"),
                "a base da laje encosta e deveria ser cortada: " + model);
        assertTrue(model.contains("\"up\": {\"texture\": \"#all\"}"),
                "o topo da laje nao encosta e nao pode ser cortado: " + model);
    }

    @Test
    void modeloNaoCubicoDeclaraAParticula(@TempDir Path root, @TempDir Path out) throws IOException {
        // Sem particle o jogo usa a textura de bloco ausente, e a poeira ao quebrar sai roxa.
        String model = modelOf(root, out, """
                {"id": "crate", "name": "Crate", "shape": {"outline": "post"}}""");

        assertTrue(model.contains("\"particle\""), "faltou a textura de particula: " + model);
    }
}
