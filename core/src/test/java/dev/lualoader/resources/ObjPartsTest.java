package dev.lualoader.resources;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.content.ObjModel;
import dev.lualoader.manifest.ModLoader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O caminho inteiro de um modelo de malha: manifesto, pacote e o recorte que o cliente vai desenhar.
 *
 * <p><b>Por que este teste existe.</b> Cada defeito desta parte custou subir o cliente, esperar dois
 * minutos e olhar a tela — e três deles eram invisíveis numa captura parada. O que o jogo desenha é
 * decidido pelo pacote, e o pacote dá para conferir aqui: o blockstate escolhe a peça, a peça aponta
 * a malha e o recorte, e o recorte tem um resultado que dá para medir.
 *
 * <p>Ele roda contra {@code examples/tubos}, que existe para isto. O arquivo de modelo de lá imita a
 * estrutura de um export de verdade — catálogo de peças, linha {@code g} com vários nomes, e grupos
 * <b>sem direção no nome</b>, que só a região separa.
 *
 * <p>A ordem das operações é a mesma do cliente: escala, recorte por nome, recorte por região,
 * inflar. Testar noutra ordem daria números que não são os que aparecem na tela.
 */
class ObjPartsTest {

    private Path assemble(Path out) throws IOException {
        List<ModLoader.LoadedMod> mods = new ModLoader(LoggerFactory.getLogger("test"))
                .discoverIn(List.of(Path.of("..", "examples", "tubos")));
        assertEquals(1, mods.size(), "o mod de tubos deveria ter carregado");

        new ResourcePackAssembler(LoggerFactory.getLogger("test"), out.resolve("cache"))
                .assemble(mods, out.resolve("pack"));
        return out.resolve("pack");
    }

    private JsonObject json(Path pack, String relativo) throws IOException {
        Path arquivo = pack.resolve(relativo);
        assertTrue(Files.isRegularFile(arquivo), "faltou " + relativo);
        return JsonParser.parseString(Files.readString(arquivo, StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    /** A malha de uma peça, do jeito que o cliente vai montá-la. */
    private ObjModel malhaDe(Path pack, JsonObject peca) throws IOException {
        String objRef = peca.get("lua_obj").getAsString();
        String caminho = objRef.substring(objRef.indexOf(':') + 1);

        Path arquivo = pack.resolve("assets/tubos/models").resolve(caminho);
        assertTrue(Files.isRegularFile(arquivo), "faltou a malha " + arquivo);

        ObjModel model = ObjModel.read(new StringReader(
                Files.readString(arquivo, StandardCharsets.UTF_8))).normalized();

        List<String> grupos = new ArrayList<>();
        peca.getAsJsonArray("lua_obj_groups").forEach(e -> grupos.add(e.getAsString()));
        model = model.filtered(grupos);

        if (peca.has("lua_obj_clip")) {
            JsonArray caixa = peca.getAsJsonArray("lua_obj_clip");
            model = model.clipped(caixa.get(0).getAsDouble(), caixa.get(1).getAsDouble(),
                    caixa.get(2).getAsDouble(), caixa.get(3).getAsDouble(),
                    caixa.get(4).getAsDouble(), caixa.get(5).getAsDouble());
        }
        if (peca.has("lua_obj_expand")) {
            model = model.expanded(peca.get("lua_obj_expand").getAsDouble());
        }
        return model;
    }

    /** A caixa que a malha ocupa, como {x1,y1,z1,x2,y2,z2}. */
    private static double[] caixa(ObjModel model) {
        double[] b = {99, 99, 99, -99, -99, -99};
        for (ObjModel.Face face : model.faces()) {
            for (ObjModel.Vertex v : face.vertices()) {
                b[0] = Math.min(b[0], v.x());
                b[1] = Math.min(b[1], v.y());
                b[2] = Math.min(b[2], v.z());
                b[3] = Math.max(b[3], v.x());
                b[4] = Math.max(b[4], v.y());
                b[5] = Math.max(b[5], v.z());
            }
        }
        return b;
    }

    @Test
    void oBlockstateEscolheAPecaPorConexao(@TempDir Path out) throws IOException {
        JsonObject blockstate = json(assemble(out), "assets/tubos/blockstates/tubo.json");

        assertTrue(blockstate.has("multipart"), "deveria ser multipart, veio " + blockstate);
        JsonArray partes = blockstate.getAsJsonArray("multipart");

        // O miolo mais, por lado, uma peca de ligado e duas de livre.
        assertEquals(1 + 6 * 3, partes.size(), "uma entrada por peca de cada estado");

        boolean achouLigado = false;
        boolean achouLivre = false;
        for (int i = 0; i < partes.size(); i++) {
            JsonObject parte = partes.get(i).getAsJsonObject();
            if (!parte.has("when")) continue;

            JsonObject quando = parte.getAsJsonObject("when");
            if (!quando.has("north")) continue;

            // A condicao NEGATIVA e o que faz um bloco fechar a face que ninguem usa. Enquanto so a
            // positiva era escrita, o bloco so sabia crescer braco.
            if (quando.get("north").getAsString().equals("true")) achouLigado = true;
            else achouLivre = true;
        }
        assertTrue(achouLigado, "faltou a peca do lado ligado");
        assertTrue(achouLivre, "faltou a peca do lado livre -- a face ficaria aberta");
    }

    @Test
    void oNucleoEAMangaSaemNoLugar(@TempDir Path out) throws IOException {
        Path pack = assemble(out);

        ObjModel nucleo = malhaDe(pack, json(pack, "assets/tubos/models/block/tubo_core0.json"));
        double[] c = caixa(nucleo);
        assertEquals(4, c[0], 0.01, "o miolo comeca em 4");
        assertEquals(12, c[3], 0.01, "e termina em 12");

        // A manga vai do miolo ate a BORDA do bloco. Se parar antes, dois tubos vizinhos ficam com
        // um vao entre eles -- e o desenho vira dois blocos com pernas em vez de um tubo.
        ObjModel manga = malhaDe(pack, json(pack, "assets/tubos/models/block/tubo_on0_n.json"));
        double[] m = caixa(manga);
        assertEquals(0, m[2], 0.01, "a manga do norte encosta na borda");
        assertEquals(4, m[5], 0.01, "e encontra o miolo");

        // A do sul e o espelho, para as duas se encontrarem entre blocos vizinhos.
        double[] s = caixa(malhaDe(pack, json(pack, "assets/tubos/models/block/tubo_on0_s.json")));
        assertEquals(12, s[2], 0.01);
        assertEquals(16, s[5], 0.01);
    }

    @Test
    void aRegiaoSeparaOQueONomeNaoSepara(@TempDir Path out) throws IOException {
        Path pack = assemble(out);
        JsonObject peca = json(pack, "assets/tubos/models/block/tubo_off0_n.json");

        // As placas da parede se chamam "Wall_Plate" em todos os seis lados -- e assim num arquivo
        // de verdade, onde a ferramenta gera os nomes. So a regiao diz de que lado cada uma e.
        assertEquals("Wall_Plate", peca.getAsJsonArray("lua_obj_groups").get(0).getAsString());
        assertTrue(peca.has("lua_obj_clip"), "sem regiao, esta peca traria as placas dos seis lados");

        ObjModel parede = malhaDe(pack, peca);
        assertFalse(parede.faces().isEmpty(), "o recorte nao pode ficar vazio");

        double[] b = caixa(parede);
        assertEquals(4, b[2], 0.01, "tudo no plano da parede do miolo");
        assertEquals(4, b[5], 0.01, "e nada fora dele -- senao vira uma aba saliente");
        assertEquals(4, b[0], 0.01, "e cobre a parede inteira");
        assertEquals(12, b[3], 0.01);
    }

    @Test
    void aRegiaoGiraComOLado(@TempDir Path out) throws IOException {
        Path pack = assemble(out);

        // A regiao e declarada uma vez, apontando para o norte, e o loader gira para os outros
        // cinco. Sem isso o mod escreveria seis caixas para manter em sincronia.
        double[] norte = caixa(malhaDe(pack,
                json(pack, "assets/tubos/models/block/tubo_off0_n.json")));
        double[] cima = caixa(malhaDe(pack,
                json(pack, "assets/tubos/models/block/tubo_off0_u.json")));

        assertEquals(4, norte[2], 0.01, "no norte a parede fica num plano de z");
        assertEquals(4, norte[5], 0.01);
        assertEquals(12, cima[1], 0.01, "e em cima, num plano de y");
        assertEquals(12, cima[4], 0.01);
    }

    @Test
    void oDecalqueFicaNaFrenteDaParede(@TempDir Path out) throws IOException {
        Path pack = assemble(out);
        JsonObject peca = json(pack, "assets/tubos/models/block/tubo_off1_n.json");

        assertTrue(peca.has("lua_obj_expand"), "sem inflar, o decalque briga com a parede");

        ObjModel decalque = malhaDe(pack, peca);
        double[] d = caixa(decalque);

        // Duas superficies no mesmo plano brigam pelo pixel e cintilam conforme quem joga anda --
        // um defeito que nao aparece numa captura parada. Um milesimo a frente resolve.
        assertTrue(d[2] < 4.0, "o decalque deveria estar a frente da parede, esta em " + d[2]);
        assertTrue(d[2] > 3.98, "e so um milesimo, nao um degrau: " + d[2]);
    }

    @Test
    void aTexturaEPorPeca(@TempDir Path out) throws IOException {
        Path pack = assemble(out);

        // Cada peca aponta a propria imagem. Um modelo de malha costuma ter um atlas para o corpo,
        // com as coordenadas embutidas no arquivo, e usar a imagem que identifica o bloco so em
        // algumas faces -- pintar tudo igual faz o corpo virar uma mancha de cor.
        for (String nome : List.of("tubo_core0", "tubo_on0_n", "tubo_off0_n", "tubo_off1_n")) {
            JsonObject peca = json(pack, "assets/tubos/models/block/" + nome + ".json");
            String textura = peca.getAsJsonObject("textures").get("all").getAsString();

            assertNotNull(textura);
            assertTrue(textura.startsWith("tubos:block/"), nome + " usa " + textura);

            Path imagem = pack.resolve("assets/tubos/textures/block/"
                    + textura.substring(textura.lastIndexOf('/') + 1) + ".png");
            assertTrue(Files.isRegularFile(imagem),
                    "a textura de " + nome + " precisa existir no pacote: " + imagem);
        }
    }

    @Test
    void nenhumaPecaSaiVazia(@TempDir Path out) throws IOException {
        Path pack = assemble(out);
        JsonArray partes = json(pack, "assets/tubos/blockstates/tubo.json")
                .getAsJsonArray("multipart");

        // O defeito mais caro desta parte: um recorte que nao casa com grupo nenhum deixa o modelo
        // vazio, o bloco desenha a reserva, e o resultado parece um cubo comum -- sem erro no log.
        for (int i = 0; i < partes.size(); i++) {
            String modelo = partes.get(i).getAsJsonObject()
                    .getAsJsonObject("apply").get("model").getAsString();
            String nome = modelo.substring(modelo.lastIndexOf('/') + 1);

            ObjModel malha = malhaDe(pack, json(pack, "assets/tubos/models/block/" + nome + ".json"));
            assertFalse(malha.faces().isEmpty(),
                    "a peca " + nome + " nao casou com grupo nenhum e desenharia nada");
        }
    }
}
