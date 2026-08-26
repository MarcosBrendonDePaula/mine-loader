package dev.lualoader.resources;

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
 * Um modelo OBJ indo para o pacote gerado.
 *
 * <p>O arquivo vai inteiro, e nao convertido: o formato de modelo do jogo descreve caixas, e uma
 * malha nao e uniao de caixas. Quem transforma a malha em faces e o cliente.
 *
 * <p>O que se prende aqui e o que o cliente depende para funcionar -- o arquivo no lugar certo, a
 * reserva ao lado dele com a mesma textura, e o blockstate apontando para o modelo em vez de para o
 * multipart.
 */
class ObjPackTest {

    private static final byte[] PIXEL_PNG = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    /** Uma piramide de quatro faces: pequena, e mesmo assim impossivel de descrever com caixas. */
    private static final String OBJ = """
            v 0 0 0
            v 16 0 0
            v 16 0 16
            v 0 0 16
            v 8 16 8
            f 1 2 5
            f 2 3 5
            f 3 4 5
            f 4 1 5
            """;

    private Path writeMod(Path root, String extra) throws IOException {
        Path dir = root.resolve("tenda");
        Files.createDirectories(dir.resolve("assets"));
        Files.createDirectories(dir.resolve("models"));
        Files.write(dir.resolve("assets/lona.png"), PIXEL_PNG);
        Files.writeString(dir.resolve("models/tenda.obj"), OBJ, StandardCharsets.UTF_8);

        Files.writeString(dir.resolve("mod.json"), """
                {
                  "schema": 1,
                  "id": "tenda",
                  "name": "Tenda",
                  "version": "0.1.0",
                  "resources": {
                    "lona": { "type": "image", "from": "assets/lona.png" },
                    "forma": { "type": "model", "from": "models/tenda.obj" }
                  },
                  "blocks": [
                    {
                      "id": "tenda",
                      "name": "Tenda",
                      "render": { "model": "@forma", "texture": { "ref": "lona" } }
                      %s
                    }
                  ]
                }
                """.formatted(extra), StandardCharsets.UTF_8);
        return dir;
    }

    private Path assemble(Path root, Path out) throws IOException {
        List<ModLoader.LoadedMod> mods =
                new ModLoader(LoggerFactory.getLogger("test")).discover(root);
        assertFalse(mods.isEmpty(), "o mod do teste deveria ter carregado");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));
        return out.resolve("pack");
    }

    @Test
    void oArquivoObjVaiInteiroParaOPacote(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "");
        Path pack = assemble(root, out);

        Path obj = pack.resolve("assets/tenda/models/block/tenda.obj");
        assertTrue(Files.isRegularFile(obj), "o OBJ deveria estar no pacote");

        // Inteiro, e nao reescrito: o cliente le a malha, e converter aqui perderia informacao que
        // o formato do jogo nao sabe representar.
        assertEquals(OBJ, Files.readString(obj, StandardCharsets.UTF_8));
    }

    @Test
    void aReservaUsaAMesmaTexturaDaMalha(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "");
        Path pack = assemble(root, out);

        // A reserva existe para o bloco virar um cubo texturizado se o cliente nao desenhar a
        // malha. Um mod que perde o desenho e um problema; um que vira cubo roxo parece o jogo
        // quebrado.
        JsonObject reserva = JsonParser.parseString(Files.readString(
                pack.resolve("assets/tenda/models/block/tenda.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();

        String textura = reserva.getAsJsonObject("textures").get("all").getAsString();
        assertEquals("tenda:block/tenda_v0", textura);

        // E a imagem existe: e dela que o cliente tira a textura da malha tambem.
        assertTrue(Files.isRegularFile(pack.resolve("assets/tenda/textures/block/tenda_v0.png")),
                "a textura da malha precisa existir no pacote");
        assertTrue(reserva.getAsJsonObject("textures").has("particle"),
                "sem particle a poeira ao quebrar sai roxa");
    }

    @Test
    void oBlockstateApontaParaOModeloENaoParaOMultipart(@TempDir Path root, @TempDir Path out)
            throws IOException {
        // Um bloco pode declarar modelo proprio e connects_to ao mesmo tempo. O modelo vence: sem
        // esta decisao o multipart ignorava o modelo em silencio, e o mod parecia nao ter sido
        // aplicado.
        writeMod(root, """
                ,
                      "shape": {
                        "core": [5, 5, 5, 11, 11, 11],
                        "arm": [5, 5, 0, 11, 11, 5],
                        "connects_to": ["tenda:tenda"]
                      }""");

        Path pack = assemble(root, out);
        JsonObject blockstate = JsonParser.parseString(Files.readString(
                pack.resolve("assets/tenda/blockstates/tenda.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();

        assertTrue(blockstate.has("variants"), "deveria ser variantes, veio " + blockstate);
        assertFalse(blockstate.has("multipart"), "o multipart ignoraria o modelo declarado");
        // A chave e vazia porque o bloco nao declara variante nem direcao: escrever
        // "lua_variant=0" ali citaria uma propriedade que o bloco nao tem, e o jogo recusa a
        // definicao inteira -- deixando o bloco sem modelo.
        assertEquals("tenda:block/tenda", blockstate.getAsJsonObject("variants")
                .getAsJsonObject("").get("model").getAsString());
    }

    @Test
    void oItemDesenhaAMalhaEmVezDeUmCubo(@TempDir Path root, @TempDir Path out) throws IOException {
        writeMod(root, "");
        Path pack = assemble(root, out);

        JsonObject item = JsonParser.parseString(Files.readString(
                pack.resolve("assets/tenda/models/item/tenda.json"),
                StandardCharsets.UTF_8)).getAsJsonObject();

        // O item aponta a malha direto. HERDAR dela derrubava o cliente inteiro -- o jogo exige que
        // o pai de um modelo JSON seja outro JSON, e a mensagem "BlockModel parent has to be a
        // block model" nao e um bloco feio: e o cliente que nao abre. Apontar nao e herdar.
        assertEquals("tenda:block/tenda.obj", item.get("lua_obj").getAsString());
        assertFalse(item.get("parent").getAsString().equals("tenda:block/tenda"),
                "o item nao pode herdar do modelo do bloco");

        // O parent de cubo continua ali como reserva, para o item nao sumir se a malha falhar.
        assertEquals("minecraft:block/cube_all", item.get("parent").getAsString());

        // Sem recorte: um item nao tem vizinho, entao nao tem estado de conexao para escolher peca.
        assertEquals(0, item.getAsJsonArray("lua_obj_groups").size());
    }

    @Test
    void umObjQuebradoERecusadoNaMontagem(@TempDir Path root, @TempDir Path out) throws IOException {
        Path dir = writeMod(root, "");
        // Sem face nenhuma o modelo desenha nada. Recusar aqui, na montagem, e o que transforma
        // isso em mensagem para quem escreveu o mod -- o cliente e onde ninguem le o log.
        Files.writeString(dir.resolve("models/tenda.obj"), "v 0 0 0\nv 1 0 0\n",
                StandardCharsets.UTF_8);

        Path pack = assemble(root, out);
        assertFalse(Files.exists(pack.resolve("assets/tenda/models/block/tenda.obj")),
                "um OBJ sem face nao deveria ir para o pacote");

        // E o bloco continua existindo, com a reserva. Deixar a excecao subir derrubava a montagem
        // inteira -- um OBJ torto num mod apagava o pacote de todos os outros.
        assertTrue(Files.isRegularFile(pack.resolve("assets/tenda/models/block/tenda.json")),
                "a reserva precisa existir mesmo com a malha recusada");
        assertTrue(Files.isRegularFile(pack.resolve("assets/tenda/blockstates/tenda.json")),
                "e o blockstate tambem");
    }
}
