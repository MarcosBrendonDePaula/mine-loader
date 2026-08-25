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
 * O blockstate de um bloco que conecta, gerado no pacote.
 *
 * <p>O formato importa mais do que parece: um blockstate que o jogo não entende não dá erro — ele
 * desenha o cubo roxo e preto de modelo ausente, e quem escreveu o mod procura o problema na
 * textura. Por isso cada caso aqui confere o JSON de verdade, e não que o arquivo existe.
 *
 * <p>A escolha central que se verifica: <b>multipart, e não variantes</b>. Seis lados booleanos dão
 * sessenta e quatro combinações; listá-las daria sessenta e quatro entradas para manter em
 * sincronia, e o primeiro ajuste esqueceria uma.
 */
class ConnectedBlockstateTest {

    private static final byte[] PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private void writeMod(Path root, String shape) throws IOException {
        Path dir = root.resolve("tubos");
        Files.createDirectories(dir.resolve("assets"));
        Files.write(dir.resolve("assets/tubo.png"), PIXEL_PNG);

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "tubos",
                  "name": "Tubos",
                  "version": "0.1.0",
                  "resources": { "tubo": { "type": "image", "from": "assets/tubo.png" } },
                  "blocks": [
                    {
                      "id": "cano",
                      "name": "Cano",
                      "render": { "textures": { "all": "@tubo" } },
                      "shape": %s
                    }
                  ]
                }
                """.formatted(shape), StandardCharsets.UTF_8);
    }

    private JsonObject assemble(Path root, Path out, String relative) throws IOException {
        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertFalse(mods.isEmpty(), "o mod do teste deveria ter carregado");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));

        Path file = out.resolve("pack").resolve(relative);
        assertTrue(Files.isRegularFile(file), "faltou o arquivo " + relative);
        return JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static final String CANO = """
            {
              "core": [5, 5, 5, 11, 11, 11],
              "arm":  [5, 5, 0, 11, 11, 5],
              "connects_to": ["tubos:cano"]
            }""";

    @Test
    void umBlocoQueConectaUsaMultipart(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, CANO);
        JsonObject blockstate = assemble(root, out, "assets/tubos/blockstates/cano.json");

        // Multipart, e nao variants: e a diferenca entre sete pecas e sessenta e quatro entradas.
        assertTrue(blockstate.has("multipart"), "deveria ser multipart, veio " + blockstate);
        assertFalse(blockstate.has("variants"), "nao deveria ter variantes");

        JsonArray partes = blockstate.getAsJsonArray("multipart");
        assertEquals(7, partes.size(), "o nucleo mais seis bracos");
    }

    @Test
    void oNucleoNaoTemCondicao(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, CANO);
        JsonObject primeira = assemble(root, out, "assets/tubos/blockstates/cano.json")
                .getAsJsonArray("multipart").get(0).getAsJsonObject();

        // Sem "when": o nucleo e desenhado sempre. Condiciona-lo a algo faria um cano isolado
        // desaparecer da tela, e o bloco continuaria la para esbarrar.
        assertFalse(primeira.has("when"), "o nucleo nao deveria ter condicao");
        assertEquals("tubos:block/cano_core",
                primeira.getAsJsonObject("apply").get("model").getAsString());
    }

    @Test
    void cadaBracoDependeDaPropriaPropriedade(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root, CANO);
        JsonArray partes = assemble(root, out, "assets/tubos/blockstates/cano.json")
                .getAsJsonArray("multipart");

        // Um lado por peca, e o mesmo modelo em todas. Modelos separados por lado dariam seis
        // arquivos para manter em sincronia.
        for (String lado : List.of("north", "south", "west", "east", "up", "down")) {
            boolean achou = false;
            for (int index = 1; index < partes.size(); index++) {
                JsonObject parte = partes.get(index).getAsJsonObject();
                if (!parte.getAsJsonObject("when").has(lado)) continue;

                achou = true;
                assertEquals("true", parte.getAsJsonObject("when").get(lado).getAsString());
                assertEquals("tubos:block/cano_arm",
                        parte.getAsJsonObject("apply").get("model").getAsString());
            }
            assertTrue(achou, "faltou a peca do lado " + lado);
        }
    }

    @Test
    void osBracosGiramEDestravamAUv(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, CANO);
        JsonArray partes = assemble(root, out, "assets/tubos/blockstates/cano.json")
                .getAsJsonArray("multipart");

        for (int index = 1; index < partes.size(); index++) {
            JsonObject parte = partes.get(index).getAsJsonObject();
            JsonObject apply = parte.getAsJsonObject("apply");
            String lado = parte.getAsJsonObject("when").keySet().iterator().next();

            if ("north".equals(lado)) {
                // Norte e a referencia do formato: nao gira, e por isso nao precisa destravar nada.
                assertFalse(apply.has("y"), "o norte nao deveria girar");
                assertFalse(apply.has("x"), "o norte nao deveria girar");
                continue;
            }

            boolean gira = apply.has("y") || apply.has("x");
            assertTrue(gira, lado + " deveria girar");

            // uvlock mantem a textura de pe quando a peca gira. Sem ele, o braco de cima sai com a
            // textura deitada -- e o defeito so aparece olhando de perto.
            assertTrue(apply.has("uvlock") && apply.get("uvlock").getAsBoolean(),
                    lado + " deveria destravar a uv");
        }
    }

    @Test
    void oModeloDoBracoSaiNaMedidaDeclarada(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root, CANO);
        JsonObject modelo = assemble(root, out, "assets/tubos/models/block/cano_arm.json");

        JsonObject elemento = modelo.getAsJsonArray("elements").get(0).getAsJsonObject();
        JsonArray de = elemento.getAsJsonArray("from");
        JsonArray ate = elemento.getAsJsonArray("to");

        assertEquals(5, de.get(0).getAsInt());
        assertEquals(0, de.get(2).getAsInt(), "o braco comeca na parede norte");
        assertEquals(11, ate.get(0).getAsInt());
        assertEquals(5, ate.get(2).getAsInt(), "e termina no nucleo");

        // Sem particle, o jogo desenha a poeira de quebrar o bloco com a textura ausente.
        assertTrue(modelo.getAsJsonObject("textures").has("particle"));
    }

    @Test
    void aTexturaDoNucleoEDoBracoExisteNoPacote(@TempDir Path root, @TempDir Path out)
            throws IOException {
        writeMod(root, CANO);

        JsonObject nucleo = assemble(root, out, "assets/tubos/models/block/cano_core.json");
        String textura = nucleo.getAsJsonObject("textures").get("all").getAsString();

        // O defeito que este caso existe para nao deixar voltar: o montador passava o MODELO onde
        // esperava a TEXTURA, entao o nucleo nomeava "tubos:block/cano_v0" -- que e um modelo. O
        // jogo procura esse nome no atlas, nao acha, e desenha o cubo roxo. Todo bloco que conecta
        // saia assim, em qualquer manifesto, e os outros casos daqui passavam porque nenhum
        // perguntava se a imagem existe.
        assertTrue(textura.startsWith("tubos:block/"),
                "a textura deveria ser do proprio mod, veio " + textura);

        Path imagem = out.resolve("pack").resolve("assets/tubos/textures/block/"
                + textura.substring(textura.lastIndexOf('/') + 1) + ".png");
        assertTrue(Files.isRegularFile(imagem),
                "a textura nomeada pelo modelo precisa existir no pacote: " + imagem);

        // O braco usa a mesma, senao um cano teria nucleo de um jeito e braco de outro.
        JsonObject braco = JsonParser.parseString(Files.readString(
                        out.resolve("pack").resolve("assets/tubos/models/block/cano_arm.json"),
                        StandardCharsets.UTF_8)).getAsJsonObject();
        assertEquals(textura, braco.getAsJsonObject("textures").get("all").getAsString());
    }

    @Test
    void semBracoDeclaradoSoONucleoEDesenhado(@TempDir Path root, @TempDir Path out)
            throws IOException {
        // Um bloco que declara nucleo e nao declara braco e legitimo: e um poste.
        writeMod(root, """
                {
                  "core": [6, 0, 6, 10, 16, 10],
                  "connects_to": ["tubos:cano"]
                }""");

        JsonArray partes = assemble(root, out, "assets/tubos/blockstates/cano.json")
                .getAsJsonArray("multipart");
        assertEquals(1, partes.size(), "so o nucleo");
    }

    @Test
    void semConnectsToVoltaAoFormatoDeVariantes(@TempDir Path root, @TempDir Path out)
            throws IOException {
        // Sem a quem conectar, o bloco nao conecta -- e precisa continuar sendo desenhado pelo
        // caminho de sempre. Cair no multipart aqui deixaria o bloco sem as variantes visuais.
        writeMod(root, """
                { "core": [5, 5, 5, 11, 11, 11], "arm": [5, 5, 0, 11, 11, 5] }""");

        JsonObject blockstate = assemble(root, out, "assets/tubos/blockstates/cano.json");
        assertTrue(blockstate.has("variants"));
        assertFalse(blockstate.has("multipart"));
    }
}
