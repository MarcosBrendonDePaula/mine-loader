package dev.lualoader.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Onde cada elemento vai parar, verificado sem abrir o jogo.
 *
 * <p>Estes testes existem porque o cálculo saiu do cliente. Antes ele morava junto do desenho, e a
 * única forma de conferir um alinhamento era rodar o Minecraft e olhar — que foi como os erros de
 * ancoragem passados apareceram, tarde e por acaso. Cada caso aqui é uma pergunta que antes só a
 * tela respondia.
 *
 * <p>A fonte é falsa de propósito: seis pixels por caractere e nove de altura. O valor não importa,
 * a proporção sim — o que se verifica é que a medida entra na conta, não quanto ela vale num
 * cliente de verdade.
 */
class ScreenLayoutTest {

    /** Fonte de largura fixa, para as contas darem números previsíveis. */
    private static final ScreenLayout.TextMetrics METRICS = new ScreenLayout.TextMetrics() {
        @Override
        public int width(String text) {
            return text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }
    };

    private static ScreenModel.Element element(String json) {
        ScreenModel model = ScreenModel.parse("{\"elements\":[" + json + "]}");
        assertNotNull(model, "descricao de teste invalida");
        assertEquals(1, model.elements().size());
        return model.elements().get(0);
    }

    // ------------------------------------------------------------------ medida

    @Test
    void itemMedeUmSlot() {
        int[] size = ScreenLayout.measure(element("{\"type\":\"item\",\"item\":\"minecraft:stone\"}"),
                METRICS);
        assertEquals(16, size[0]);
        assertEquals(16, size[1]);
    }

    @Test
    void rotuloMedePeloTexto() {
        int[] size = ScreenLayout.measure(element("{\"type\":\"label\",\"text\":\"abcd\"}"), METRICS);
        assertEquals(24, size[0]);
        assertEquals(9, size[1]);
    }

    @Test
    void escalaMultiplicaAMedidaDoRotulo() {
        int[] size = ScreenLayout.measure(
                element("{\"type\":\"label\",\"text\":\"abcd\",\"scale\":2}"), METRICS);
        assertEquals(48, size[0]);
        assertEquals(18, size[1]);
    }

    @Test
    void gradeMedePelasCelulas() {
        // Cinco celulas em tres colunas ocupam duas linhas: a segunda fica pela metade, e a altura
        // conta a linha inteira mesmo assim. Arredondar para baixo cortaria a ultima fileira.
        int[] size = ScreenLayout.measure(element(
                "{\"type\":\"grid\",\"columns\":3,\"cell\":18,\"cells\":["
                        + "{\"item\":\"minecraft:stone\"},{\"item\":\"minecraft:stone\"},"
                        + "{\"item\":\"minecraft:stone\"},{\"item\":\"minecraft:stone\"},"
                        + "{\"item\":\"minecraft:stone\"}]}"), METRICS);
        assertEquals(54, size[0]);
        assertEquals(36, size[1]);
    }

    // ------------------------------------------------------------------ ancoragem

    private static final ScreenLayout.Bounds SURFACE = new ScreenLayout.Bounds(0, 0, 200, 100);
    private static final ScreenLayout.Bounds GUI = new ScreenLayout.Bounds(50, 20, 100, 60);

    @Test
    void semAncoraAOrigemEOCantoDaSuperficie() {
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"x\":4,\"y\":6,\"w\":10,\"h\":10}"),
                new ScreenLayout.Bounds(30, 40, 200, 100), GUI, METRICS);
        assertEquals(34, position[0]);
        assertEquals(46, position[1]);
    }

    @Test
    void centroDescontaMetadeDoTamanho() {
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"center\",\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(90, position[0]);
        assertEquals(45, position[1]);
    }

    @Test
    void cantoInferiorDireitoEncostaNaBorda() {
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"bottom_right\",\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(180, position[0]);
        assertEquals(90, position[1]);
    }

    @Test
    void ancoraDeJanelaUsaAGuiENaoASuperficie() {
        // gui_top_right cola o elemento na borda direita da janela do jogo. E a ancora que motiva o
        // recurso: e onde cabe um painel lateral sem cobrir os slots do inventario.
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"gui_top_right\",\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(150, position[0]);
        assertEquals(20, position[1]);
    }

    @Test
    void ancoraDeJanelaAEsquerdaDescontaALargura() {
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"gui_left\",\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(30, position[0]);
        assertEquals(45, position[1]);
    }

    @Test
    void deslocamentoSomaDepoisDaAncora() {
        // x e y continuam valendo com ancora: sao um ajuste sobre o ponto ancorado, e nao uma
        // posicao absoluta que a ancora substituiria.
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"top_right\",\"x\":-5,\"y\":3,"
                        + "\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(175, position[0]);
        assertEquals(3, position[1]);
    }

    @Test
    void ancoraDesconhecidaCaiNoCanto() {
        // Um cliente antigo diante de uma ancora nova nao pode sumir com o elemento.
        int[] position = ScreenLayout.resolve(
                element("{\"type\":\"panel\",\"anchor\":\"nao_existe\",\"w\":20,\"h\":10}"),
                SURFACE, GUI, METRICS);
        assertEquals(0, position[0]);
        assertEquals(0, position[1]);
    }

    // ------------------------------------------------------------------ cursor

    private static final String GRADE =
            "{\"type\":\"grid\",\"id\":\"lista\",\"columns\":3,\"cell\":18,\"cells\":["
                    + "{\"item\":\"minecraft:stone\"},{\"item\":\"minecraft:dirt\"},"
                    + "{\"item\":\"minecraft:sand\"},{\"item\":\"minecraft:gravel\"},"
                    + "{\"item\":\"minecraft:clay\"}]}";

    @Test
    void celulaContaAPartirDeUm() {
        // Zero significa "nenhuma", entao a primeira celula precisa ser 1: e o mesmo numero que
        // chega ao script no valor do evento.
        assertEquals(1, ScreenLayout.cellAt(element(GRADE), 0, 0, 5, 5, METRICS));
    }

    @Test
    void celulaSegueColunaELinha() {
        // Segunda coluna da segunda linha: a quinta celula.
        assertEquals(5, ScreenLayout.cellAt(element(GRADE), 0, 0, 20, 20, METRICS));
    }

    @Test
    void celulaVaziaDepoisDaUltimaNaoResponde() {
        // A sexta posicao existe na grade mas nao tem item: clicar ali nao e clicar em nada.
        assertEquals(0, ScreenLayout.cellAt(element(GRADE), 0, 0, 40, 20, METRICS));
    }

    @Test
    void foraDaGradeNaoResponde() {
        assertEquals(0, ScreenLayout.cellAt(element(GRADE), 0, 0, 100, 100, METRICS));
    }

    @Test
    void celulaRespeitaODeslocamentoDaGrade() {
        // A grade desenhada em (30, 40) responde as mesmas celulas, deslocadas.
        assertEquals(1, ScreenLayout.cellAt(element(GRADE), 30, 40, 35, 45, METRICS));
        assertEquals(0, ScreenLayout.cellAt(element(GRADE), 30, 40, 5, 5, METRICS));
    }

    @Test
    void contemUsaOTamanhoMedido() {
        // O rotulo nao declara largura: quem responde pelo cursor e a medida do texto.
        ScreenModel.Element label = element("{\"type\":\"label\",\"text\":\"abcd\"}");

        assertTrue(ScreenLayout.contains(label, 10, 10, 12, 12, METRICS));
        // 10 + 24 = 34 e o primeiro pixel de fora.
        assertFalse(ScreenLayout.contains(label, 10, 10, 34, 12, METRICS));
        assertFalse(ScreenLayout.contains(label, 10, 10, 12, 19, METRICS));
    }

    @Test
    void bordaEsquerdaEntraEDireitaNaoEntra() {
        // Meio pixel de diferenca aqui e a diferenca entre dois slots vizinhos responderem ao mesmo
        // clique e um deles nunca responder.
        ScreenModel.Element panel = element("{\"type\":\"panel\",\"w\":10,\"h\":10}");

        assertTrue(ScreenLayout.contains(panel, 0, 0, 0, 0, METRICS));
        assertTrue(ScreenLayout.contains(panel, 0, 0, 9, 9, METRICS));
        assertFalse(ScreenLayout.contains(panel, 0, 0, 10, 5, METRICS));
        assertFalse(ScreenLayout.contains(panel, 0, 0, 5, 10, METRICS));
    }
}
