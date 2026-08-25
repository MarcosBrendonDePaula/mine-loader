package dev.lualoader.content;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A leitura de um modelo OBJ.
 *
 * <p>Existe porque nem todo mod desenha com caixas: o cano do Logistic Pipes tem a geometria num
 * OBJ de milhares de vértices, e sem ler esse formato portar o mod esbarra no desenho.
 *
 * <p>O leitor mora no núcleo de propósito — o NeoForge tem um leitor próprio e o Fabric não, e usar
 * o de lá num lado daria o mesmo arquivo produzindo modelos diferentes em cada plataforma. Estes
 * casos são o contrato que as duas seguem.
 */
class ObjModelTest {

    private static ObjModel ler(String texto) throws IOException {
        return ObjModel.read(new StringReader(texto));
    }

    /** Um quadrado no plano, o menor OBJ que ainda é um modelo. */
    private static final String QUADRADO = """
            # um comentario, que deve ser ignorado
            v 0 0 0
            v 1 0 0
            v 1 1 0
            v 0 1 0
            vt 0 0
            vt 1 0
            vt 1 1
            vt 0 1
            f 1/1 2/2 3/3 4/4
            """;

    @Test
    void leUmaFaceComPosicaoETextura() throws IOException {
        ObjModel modelo = ler(QUADRADO);

        assertEquals(1, modelo.faces().size());
        assertEquals(4, modelo.faces().get(0).vertices().size());

        ObjModel.Vertex primeiro = modelo.faces().get(0).vertices().get(0);
        assertEquals(0, primeiro.x());
        assertEquals(0, primeiro.y());

        // O OBJ conta a textura de baixo para cima e o jogo de cima para baixo. Sem esta volta o
        // desenho sai espelhado na vertical, e so aparece com uma textura que nao e simetrica.
        assertEquals(1, primeiro.v(), "a coordenada vertical deveria ter sido invertida");
        assertEquals(0, modelo.faces().get(0).vertices().get(3).v());
    }

    @Test
    void oIndiceNegativoContaDoFim() throws IOException {
        // O formato permite, e ferramenta de export usa. Recusar rejeitaria arquivo legitimo com
        // uma mensagem que ninguem liga a causa.
        ObjModel modelo = ler("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                f -3 -2 -1
                """);

        List<ObjModel.Vertex> vertices = modelo.faces().get(0).vertices();
        assertEquals(3, vertices.size());
        assertEquals(0, vertices.get(0).x());
        assertEquals(1, vertices.get(2).y());
    }

    @Test
    void faceComMaisDeQuatroLadosViraTriangulos() throws IOException {
        // O jogo desenha quads. Um pentagono sem essa quebra sairia com um pedaco faltando.
        ObjModel modelo = ler("""
                v 0 0 0
                v 2 0 0
                v 3 1 0
                v 1 2 0
                v -1 1 0
                f 1 2 3 4 5
                """);

        assertEquals(3, modelo.faces().size(), "um pentagono da tres triangulos");
        for (ObjModel.Face face : modelo.faces()) {
            assertEquals(3, face.vertices().size());
            // Leque a partir do primeiro vertice: todos os triangulos o compartilham.
            assertEquals(0, face.vertices().get(0).x());
            assertEquals(0, face.vertices().get(0).y());
        }
    }

    @Test
    void oGrupoAcompanhaAsFaces() throws IOException {
        ObjModel modelo = ler("""
                v 0 0 0
                v 1 0 0
                v 1 1 0
                g corpo
                f 1 2 3
                usemtl vidro
                f 1 2 3
                """);

        assertEquals("corpo", modelo.faces().get(0).group());
        // usemtl tambem nomeia: o material nao muda o desenho, mas diz que parte e aquela -- e e o
        // que permite ao mod dizer "esta parte usa aquela textura" sem cortar o arquivo.
        assertEquals("vidro", modelo.faces().get(1).group());
    }

    @Test
    void oQueOLeitorNaoEntendeNaoDerrubaOArquivo() throws IOException {
        // vn, s e mtllib nao mudam a geometria. Recusar por causa deles rejeitaria quase todo
        // arquivo de verdade, porque toda ferramenta escreve alguma coisa a mais.
        ObjModel modelo = ler("""
                mtllib cano.mtl
                s off
                vn 0 0 1
                v 0 0 0
                v 1 0 0
                v 1 1 0
                f 1//1 2//1 3//1
                """);

        assertEquals(1, modelo.faces().size());
        assertEquals(3, modelo.faces().get(0).vertices().size());
    }

    @Test
    void arquivoSemFaceERecusado() {
        // Um OBJ so com vertices desenha nada. Aceitar daria um bloco invisivel, e quem escreveu o
        // mod procuraria o defeito na textura.
        IOException erro = assertThrows(IOException.class, () -> ler("v 0 0 0\nv 1 0 0\n"));
        assertTrue(erro.getMessage().contains("sem face"), erro.getMessage());
    }

    @Test
    void arquivoAbsurdoERecusadoAntesDeTravarOJogo() {
        // Sem teto, um export descuidado trava o carregamento e o sintoma e a tela de "carregando"
        // para sempre, sem uma linha explicando.
        StringBuilder gigante = new StringBuilder("v 0 0 0\nv 1 0 0\nv 1 1 0\n");
        for (int i = 0; i <= ObjModel.MAX_FACES; i++) gigante.append("f 1 2 3\n");

        IOException erro = assertThrows(IOException.class, () -> ler(gigante.toString()));
        assertTrue(erro.getMessage().contains("faces"), erro.getMessage());
    }

    // ------------------------------------------------------------------ escala

    @Test
    void oModeloNormalizadoCabeNoBloco() throws IOException {
        // Um OBJ vem na escala em que foi desenhado -- o do Logistic Pipes vem de 0 a 100 -- e o
        // jogo desenha em dezesseis avos. Exigir que o mod acerte isso a mao seria pedir que ele
        // adivinhe a unidade de quem exportou.
        ObjModel modelo = ler("""
                v 0 0 0
                v 100 0 0
                v 100 100 0
                v 0 100 0
                f 1 2 3 4
                """).normalized();

        assertEquals(0, modelo.minY(), 0.001, "o chao deveria ficar em zero");
        assertEquals(16, modelo.maxY(), 0.001, "e o topo em dezesseis");
        assertTrue(modelo.minX() >= -0.001 && modelo.maxX() <= 16.001,
                "deveria caber no bloco: " + modelo.minX() + " a " + modelo.maxX());
    }

    @Test
    void aEscalaEAMesmaNosTresEixos() throws IOException {
        // Escalar cada eixo pelo proprio tamanho esticaria o desenho ate encher a caixa, e um cano
        // fino ficaria achatado. O maior lado manda, e os outros ficam proporcionais.
        ObjModel modelo = ler("""
                v 0 0 0
                v 100 0 0
                v 100 10 0
                v 0 10 0
                f 1 2 3 4
                """).normalized();

        assertEquals(16, modelo.maxX() - modelo.minX(), 0.001, "o maior lado vira dezesseis");
        assertEquals(1.6, modelo.maxY() - modelo.minY(), 0.001, "e o menor mantem a proporcao");

        // E fica centrado no eixo horizontal, em vez de encostado num canto.
        assertEquals(8, (modelo.minX() + modelo.maxX()) / 2, 0.001);
    }

    @Test
    void aTexturaNaoMudaComAEscala() throws IOException {
        ObjModel cru = ler(QUADRADO);
        ObjModel escalado = cru.normalized();

        // A coordenada de textura e uma fracao da imagem, e nao uma posicao no mundo: mexer nela
        // junto com a escala esticaria a textura sem ninguem pedir.
        assertEquals(cru.faces().get(0).vertices().get(2).u(),
                escalado.faces().get(0).vertices().get(2).u());
        assertEquals(cru.faces().get(0).vertices().get(2).v(),
                escalado.faces().get(0).vertices().get(2).v());
        assertNotEquals(cru.faces().get(0).vertices().get(2).x(),
                escalado.faces().get(0).vertices().get(2).x());
    }

    @Test
    void oNumeroDeVerticesEOQueOArquivoTraz() throws IOException {
        assertEquals(4, ler(QUADRADO).vertexCount());
    }

    @Test
    void reconheceOCaminhoDeUmObj() {
        assertTrue(ObjModel.isObj("models/cano.obj"));
        assertTrue(ObjModel.isObj("MODELS/CANO.OBJ"));
        assertTrue(!ObjModel.isObj("models/cano.json"));
        assertTrue(!ObjModel.isObj(null));
    }
}
