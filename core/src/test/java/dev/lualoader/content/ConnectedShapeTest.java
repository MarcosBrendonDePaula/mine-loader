package dev.lualoader.content;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A forma de um bloco que conecta: núcleo mais um braço por lado ligado.
 *
 * <p>É aritmética sobre o cubo de dezesseis, e por isso mora no núcleo — as duas plataformas
 * precisam da mesma resposta, e duas implementações concordariam até o primeiro caso torto.
 *
 * <p>O caso torto aqui é caro: um braço girado para o lado errado aponta para o vizinho errado, e
 * o sintoma é um cano que parece conectado a quem não está. Pior ainda se a colisão discordar do
 * desenho — o jogador vê o braço e atravessa.
 */
class ConnectedShapeTest {

    /** Um braço de cano: 4×4 no meio da face, indo da parede norte até o núcleo. */
    private static final BlockShapes.Box BRACO = new BlockShapes.Box(6, 6, 0, 10, 10, 6);
    private static final BlockShapes.Box NUCLEO = new BlockShapes.Box(6, 6, 6, 10, 10, 10);

    @Test
    void oBracoDoNorteNaoGira() {
        // Norte é a referência do formato do jogo: um braço declarado já aponta para lá.
        assertEquals(BRACO, BlockShapes.rotate(BRACO, "north"));
    }

    @Test
    void oBracoDoSulEOEspelhoDoNorte() {
        BlockShapes.Box sul = BlockShapes.rotate(BRACO, "south");

        // Meia volta: o que ia de z=0 a z=6 passa a ir de z=10 a z=16, e x continua centrado.
        assertEquals(10.0, sul.fromZ());
        assertEquals(16.0, sul.toZ());
        assertEquals(6.0, sul.fromX());
        assertEquals(10.0, sul.toX());
        // A altura não muda numa volta no eixo vertical.
        assertEquals(6.0, sul.fromY());
        assertEquals(10.0, sul.toY());
    }

    @Test
    void osBracosLateraisTrocamXPorZ() {
        BlockShapes.Box oeste = BlockShapes.rotate(BRACO, "west");
        BlockShapes.Box leste = BlockShapes.rotate(BRACO, "east");

        // Um quarto de volta para cada lado: o comprimento sai no eixo x, e a espessura no z.
        assertEquals(0.0, oeste.fromX());
        assertEquals(6.0, oeste.toX());
        assertEquals(10.0, leste.fromX());
        assertEquals(16.0, leste.toX());

        // Os dois continuam centrados em z, senão o braço sairia torto da parede.
        assertEquals(6.0, oeste.fromZ());
        assertEquals(10.0, oeste.toZ());
        assertEquals(6.0, leste.fromZ());
        assertEquals(10.0, leste.toZ());
    }

    @Test
    void osBracosVerticaisTrocamYPorZ() {
        BlockShapes.Box cima = BlockShapes.rotate(BRACO, "up");
        BlockShapes.Box baixo = BlockShapes.rotate(BRACO, "down");

        assertEquals(10.0, cima.fromY());
        assertEquals(16.0, cima.toY());
        assertEquals(0.0, baixo.fromY());
        assertEquals(6.0, baixo.toY());

        // E continuam centrados nos outros dois eixos.
        assertEquals(6.0, cima.fromX());
        assertEquals(10.0, cima.toX());
        assertEquals(6.0, baixo.fromZ());
        assertEquals(10.0, baixo.toZ());
    }

    @Test
    void todaRotacaoFicaDentroDoCubo() {
        for (String lado : BlockShapes.SIDES) {
            BlockShapes.Box girado = BlockShapes.rotate(BRACO, lado);

            // Um giro que sai do cubo produz colisão fora do bloco, e o jogador esbarra no ar.
            assertTrue(girado.fromX() >= 0 && girado.toX() <= 16, lado + " saiu em x: " + girado);
            assertTrue(girado.fromY() >= 0 && girado.toY() <= 16, lado + " saiu em y: " + girado);
            assertTrue(girado.fromZ() >= 0 && girado.toZ() <= 16, lado + " saiu em z: " + girado);

            // E continua sendo uma caixa: mínimo antes do máximo nos três eixos.
            assertTrue(girado.fromX() < girado.toX(), lado + " inverteu x");
            assertTrue(girado.fromY() < girado.toY(), lado + " inverteu y");
            assertTrue(girado.fromZ() < girado.toZ(), lado + " inverteu z");
        }
    }

    @Test
    void oVolumeSobreviveAoGiro() {
        double esperado = volume(BRACO);
        for (String lado : BlockShapes.SIDES) {
            // Girar não encolhe nem estica: se o volume mudou, a fórmula trocou dois eixos sem
            // trocar os limites junto.
            assertEquals(esperado, volume(BlockShapes.rotate(BRACO, lado)), 0.0001, lado);
        }
    }

    private static double volume(BlockShapes.Box box) {
        return (box.toX() - box.fromX()) * (box.toY() - box.fromY()) * (box.toZ() - box.fromZ());
    }

    // ------------------------------------------------------------------ a forma montada

    @Test
    void semConexaoSobraSoONucleo() {
        List<BlockShapes.Box> forma = BlockShapes.connected(NUCLEO, BRACO, Set.of());

        // Um cano isolado é só o miolo. Desenhar braços sem vizinho daria a impressão de rede onde
        // não há nenhuma.
        assertEquals(1, forma.size());
        assertEquals(NUCLEO, forma.get(0));
    }

    @Test
    void cadaLadoLigadoAcrescentaUmBraco() {
        List<BlockShapes.Box> forma =
                BlockShapes.connected(NUCLEO, BRACO, Set.of("north", "east", "up"));

        assertEquals(4, forma.size(), "o nucleo mais tres bracos");
        assertEquals(NUCLEO, forma.get(0), "o nucleo vem sempre primeiro");
    }

    @Test
    void aOrdemEADosLadosENaoADoConjunto() {
        // A ordem do conjunto de entrada não deve vazar para a saída: um modelo gerado em ordem
        // instável mudaria de arquivo a cada carga, sem nada ter mudado de verdade.
        List<BlockShapes.Box> a = BlockShapes.connected(NUCLEO, BRACO, Set.of("up", "north"));
        List<BlockShapes.Box> b = BlockShapes.connected(NUCLEO, BRACO, Set.of("north", "up"));
        assertEquals(a, b);
    }

    @Test
    void semBracoDeclaradoSoONucleoAparece() {
        // Um bloco que declara núcleo e não declara braço é legítimo — é um poste. Ele não deveria
        // ganhar braços invisíveis por causa dos vizinhos.
        List<BlockShapes.Box> forma =
                BlockShapes.connected(NUCLEO, null, Set.of("north", "south"));
        assertEquals(List.of(NUCLEO), forma);
    }

    @Test
    void seisNumerosViramCaixaEORestoNao() {
        assertEquals(NUCLEO, BlockShapes.boxOf(List.of(6f, 6f, 6f, 10f, 10f, 10f)));

        // Menos que seis não é caixa. Aceitar completando com zero daria uma forma que ninguém
        // declarou, e o defeito só apareceria como colisão estranha.
        assertNull(BlockShapes.boxOf(List.of(6f, 6f, 6f)));
        assertNull(BlockShapes.boxOf(null));
    }

    @Test
    void oDeslocamentoDeCadaLadoBateComOJogo() {
        // Norte é -z no Minecraft. Trocar o sinal aqui faria o cano procurar o vizinho errado, e o
        // sintoma seria um braço apontando para o nada.
        assertEquals(-1, BlockShapes.offsetOf("north")[2]);
        assertEquals(1, BlockShapes.offsetOf("south")[2]);
        assertEquals(-1, BlockShapes.offsetOf("west")[0]);
        assertEquals(1, BlockShapes.offsetOf("east")[0]);
        assertEquals(1, BlockShapes.offsetOf("up")[1]);
        assertEquals(-1, BlockShapes.offsetOf("down")[1]);
    }
}
