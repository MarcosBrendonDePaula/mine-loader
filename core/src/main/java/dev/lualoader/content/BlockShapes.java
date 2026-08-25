package dev.lualoader.content;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * As formas que um bloco declarado pode ter, como caixas.
 *
 * <p>Uma caixa é aritmética sobre o cubo de dezesseis unidades do jogo, e não depende de plataforma
 * nenhuma. Fica no núcleo porque a mesma definição alimenta três coisas que antes não conversavam:
 * o modelo desenhado no resource pack, a colisão do adaptador Fabric e a do NeoForge.
 *
 * <p>Era essa desconexão que produzia o pior defeito da forma declarada: uma laje com colisão de
 * laje e aparência de cubo inteiro. O jogador via um bloco cheio e atravessava a metade de cima —
 * o tipo de coisa que parece o jogo quebrado, e não um recurso faltando.
 *
 * <p>Formatos que dependem de estado direcional, como escadas e cercas conectáveis, continuam fora:
 * exigem propriedades de rotação e conexão que o loader ainda não declara.
 */
public final class BlockShapes {
    private BlockShapes() {
    }

    /**
     * Uma caixa, em unidades de bloco — de 0 a 16.
     *
     * <p>É a mesma escala que o formato de modelo do Minecraft usa, de propósito: o montador do
     * pack escreve estes números direto, sem conversão que pudesse introduzir erro de meio pixel.
     */
    public record Box(double fromX, double fromY, double fromZ,
                      double toX, double toY, double toZ) {

        /** Se a caixa cobre o cubo inteiro, caso em que o jogo tem um modelo pronto e melhor. */
        public boolean isFullCube() {
            return fromX == 0 && fromY == 0 && fromZ == 0 && toX == 16 && toY == 16 && toZ == 16;
        }
    }

    /** O cubo inteiro, que é o padrão quando nada é declarado. */
    public static final List<Box> FULL_CUBE = List.of(new Box(0, 0, 0, 16, 16, 16));

    /**
     * As formas nomeadas.
     *
     * <p>Um nome cobre as silhuetas comuns sem o mod precisar desenhar caixa por caixa. Quem
     * precisar de algo fora desta lista declara as caixas diretamente.
     */
    private static final Map<String, List<Box>> NAMED = Map.ofEntries(
            Map.entry("full_cube", FULL_CUBE),
            Map.entry("slab", List.of(new Box(0, 0, 0, 16, 8, 16))),
            Map.entry("slab_bottom", List.of(new Box(0, 0, 0, 16, 8, 16))),
            Map.entry("slab_top", List.of(new Box(0, 8, 0, 16, 16, 16))),
            Map.entry("carpet", List.of(new Box(0, 0, 0, 16, 1, 16))),
            Map.entry("layer", List.of(new Box(0, 0, 0, 16, 1, 16))),
            Map.entry("pane", List.of(new Box(0, 0, 7, 16, 16, 9))),
            Map.entry("panel", List.of(new Box(0, 0, 7, 16, 16, 9))),
            Map.entry("post", List.of(new Box(6, 0, 6, 10, 16, 10))),
            Map.entry("pillar", List.of(new Box(6, 0, 6, 10, 16, 10))),
            Map.entry("plate", List.of(new Box(1, 0, 1, 15, 1, 15))),
            Map.entry("cross", List.of(new Box(2, 0, 2, 14, 14, 14))),
            Map.entry("plant", List.of(new Box(2, 0, 2, 14, 14, 14))),
            Map.entry("small", List.of(new Box(4, 0, 4, 12, 12, 12))),
            // Duas caixas: o tampo e o pe. E a forma composta mais util, e a que mostra por que a
            // definicao precisa ser uma lista e nao uma caixa so.
            Map.entry("table", List.of(
                    new Box(0, 12, 0, 16, 16, 16),
                    new Box(2, 0, 2, 14, 12, 14))));

    /**
     * As caixas de uma forma nomeada, ou {@code null} se o nome não for conhecido.
     *
     * <p>Devolve {@code null} em vez do cubo inteiro para o chamador poder distinguir "o mod pediu
     * cubo" de "o mod pediu algo que não existe" — a segunda merece aviso.
     */
    public static List<Box> byName(String name) {
        if (name == null || name.isBlank()) return FULL_CUBE;
        return NAMED.get(name.trim().toLowerCase(Locale.ROOT));
    }

    /** Se o nome descreve uma forma que o loader conhece. */
    public static boolean isKnown(String name) {
        return byName(name) != null;
    }

    /**
     * Converte caixas declaradas no manifesto, ou {@code null} se não houver nenhuma utilizável.
     *
     * <p>Entrada malformada é descartada em silêncio aqui e recusada na validação do manifesto,
     * que é onde a mensagem chega a quem escreveu o mod. Este método só precisa não quebrar.
     */
    public static List<Box> fromNumbers(List<? extends List<Float>> declared) {
        if (declared == null || declared.isEmpty()) return null;

        List<Box> boxes = new java.util.ArrayList<>();
        for (List<Float> numbers : declared) {
            if (numbers == null || numbers.size() != 6) continue;
            boxes.add(new Box(
                    numbers.get(0), numbers.get(1), numbers.get(2),
                    numbers.get(3), numbers.get(4), numbers.get(5)));
        }
        return boxes.isEmpty() ? null : List.copyOf(boxes);
    }

    /** Os nomes aceitos, para mensagens de erro que dizem o que se pode escrever. */
    public static List<String> names() {
        return NAMED.keySet().stream().sorted().toList();
    }

    /**
     * Se a forma é o cubo inteiro, caso em que nada precisa ser gerado.
     *
     * <p>Um cubo desenhado por caixas seria idêntico ao modelo pronto do jogo, mas mais caro de
     * montar e mais fácil de errar — e perderia o sombreamento de face que o {@code cube_all} traz.
     */
    public static boolean isFullCube(List<Box> boxes) {
        return boxes != null && boxes.size() == 1 && boxes.get(0).isFullCube();
    }

    // ------------------------------------------------------------------ blocos que conectam

    /**
     * As seis direcoes, na ordem em que o formato do jogo as nomeia.
     *
     * <p>No nucleo, e nao em cada adaptador: os dois precisam concordar sobre qual propriedade
     * corresponde a qual lado, e uma segunda lista divergiria na primeira reordenacao.
     */
    public static final List<String> SIDES =
            List.of("north", "south", "west", "east", "up", "down");

    /**
     * O deslocamento de cada direcao, em blocos.
     *
     * <p>Norte e {@code -z}, como o jogo define. Trocar o sinal aqui faria um cano conectar com o
     * vizinho errado -- e o defeito apareceria como braco apontando para o nada.
     */
    public static int[] offsetOf(String side) {
        return switch (side) {
            case "north" -> new int[]{0, 0, -1};
            case "south" -> new int[]{0, 0, 1};
            case "west" -> new int[]{-1, 0, 0};
            case "east" -> new int[]{1, 0, 0};
            case "up" -> new int[]{0, 1, 0};
            case "down" -> new int[]{0, -1, 0};
            default -> new int[]{0, 0, 0};
        };
    }

    /**
     * Gira uma caixa desenhada para o norte na direcao pedida.
     *
     * <p>Aritmetica sobre o cubo de dezesseis, e nao matriz: o giro e sempre de noventa graus em
     * torno do centro, e escrever os quatro casos e mais legivel que uma formula que ninguem
     * confere. Um erro aqui sai como braco atravessando o bloco vizinho.
     */
    public static Box rotate(Box box, String side) {
        double x1 = box.fromX(), y1 = box.fromY(), z1 = box.fromZ();
        double x2 = box.toX(),   y2 = box.toY(),   z2 = box.toZ();

        return switch (side) {
            case "north" -> box;
            // Meia volta no eixo vertical.
            case "south" -> new Box(16 - x2, y1, 16 - z2, 16 - x1, y2, 16 - z1);
            // Um quarto de volta, cada um para um lado.
            case "west" -> new Box(z1, y1, 16 - x2, z2, y2, 16 - x1);
            case "east" -> new Box(16 - z2, y1, x1, 16 - z1, y2, x2);
            // Cima e baixo giram no eixo horizontal, trocando altura por profundidade.
            case "up" -> new Box(x1, 16 - z2, y1, x2, 16 - z1, y2);
            case "down" -> new Box(x1, z1, 16 - y2, x2, z2, 16 - y1);
            default -> box;
        };
    }

    /**
     * A forma de um bloco que conecta, dado quais lados estao ligados.
     *
     * <p>O nucleo sempre, mais um braco por lado. E o que faz a colisao acompanhar o desenho: uma
     * forma que fica so no visual deixa o jogador ver o braco e atravessa-lo, e esse e o defeito
     * que este repositorio ja registra como o pior de forma declarada.
     */
    public static List<Box> connected(Box core, Box arm, Collection<String> connected) {
        return connected(core == null ? List.of() : List.of(core),
                arm == null ? List.of() : List.of(arm),
                connected);
    }

    /**
     * A mesma forma, com nucleo e braco de varias caixas.
     *
     * <p>Uma caixa por peca cobre cano, cerca e muro, e nao cobre o cano do Logistic Pipes -- que
     * tem placas nas faces alem do miolo, e um colar na ponta de cada braco. As caixas de um braco
     * giram <b>juntas</b>, entao o conjunto se comporta como uma peca so; girar cada uma por conta
     * daria pedacos apontando para lados diferentes.
     */
    public static List<Box> connected(List<Box> core, List<Box> arm, Collection<String> connected) {
        List<Box> boxes = new ArrayList<>();
        if (core != null) {
            for (Box caixa : core) {
                if (caixa != null) boxes.add(caixa);
            }
        }

        if (arm != null && !arm.isEmpty()) {
            for (String side : SIDES) {
                if (!connected.contains(side)) continue;
                for (Box caixa : arm) {
                    if (caixa != null) boxes.add(rotate(caixa, side));
                }
            }
        }
        return List.copyOf(boxes);
    }

    /**
     * As caixas declaradas, ignorando as que nao tem seis numeros.
     *
     * <p>Ignorar em vez de recusar aqui e de proposito: a recusa com mensagem mora no validador do
     * manifesto, que sabe o id do bloco e consegue dizer onde esta o erro. Aqui, no meio da
     * aritmetica, so daria uma excecao sem contexto.
     */
    public static List<Box> boxesOf(List<List<Float>> declaradas) {
        if (declaradas == null || declaradas.isEmpty()) return List.of();

        List<Box> caixas = new ArrayList<>();
        for (List<Float> numeros : declaradas) {
            Box caixa = boxOf(numeros);
            if (caixa != null) caixas.add(caixa);
        }
        return List.copyOf(caixas);
    }

    /**
     * As caixas do nucleo de um bloco que conecta, ja com a regra {@code cores} vence {@code core}.
     */
    public static List<Box> coreBoxes(dev.lualoader.manifest.ModManifest.ShapeDefinition shape) {
        return shape == null ? List.of() : pick(shape.core, shape.cores);
    }

    /** As caixas do braco, ja com a regra {@code arms} vence {@code arm}. */
    public static List<Box> armBoxes(dev.lualoader.manifest.ModManifest.ShapeDefinition shape) {
        return shape == null ? List.of() : pick(shape.arm, shape.arms);
    }

    /**
     * Se o bloco declara nucleo e a quem se conectar.
     *
     * <p>Mora aqui porque a resposta precisa ser a mesma no montador do pacote e nos dois
     * adaptadores. Enquanto cada um tinha a sua copia, acrescentar {@code cores} significaria
     * lembrar de tres lugares -- e o que ficasse para tras registraria um bloco sem as propriedades
     * de conexao, que e o defeito silencioso de sempre.
     */
    public static boolean connects(dev.lualoader.manifest.ModManifest.BlockDefinition block) {
        if (block == null || block.shape == null) return false;

        return !coreBoxes(block.shape).isEmpty()
                && block.shape.connectsTo != null && !block.shape.connectsTo.isEmpty();
    }

    /**
     * Escolhe entre a caixa unica e a lista de caixas, na regra de sempre: a lista vence.
     *
     * <p>Existe para os tres consumidores -- o montador do pacote e os dois adaptadores -- nao
     * repetirem a escolha. Repetida, ela divergiria no primeiro ajuste, e o sintoma seria um bloco
     * desenhado de um jeito e com colisao de outro.
     */
    public static List<Box> pick(List<Float> uma, List<List<Float>> varias) {
        List<Box> lista = boxesOf(varias);
        if (!lista.isEmpty()) return lista;

        Box unica = boxOf(uma);
        return unica == null ? List.of() : List.of(unica);
    }

    /** Uma caixa a partir dos seis numeros declarados, ou {@code null} quando nao ha. */
    public static Box boxOf(List<Float> numbers) {
        if (numbers == null || numbers.size() != 6) return null;
        return new Box(numbers.get(0), numbers.get(1), numbers.get(2),
                numbers.get(3), numbers.get(4), numbers.get(5));
    }
}
