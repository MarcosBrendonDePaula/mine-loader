package dev.lualoader.content;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Um modelo no formato Wavefront OBJ, lido sem depender do jogo.
 *
 * <p><b>Por que o loader precisa disto.</b> Nem todo mod desenha com caixas. O cano do Logistic
 * Pipes, por exemplo, tem a geometria num OBJ de milhares de vértices carregado por código — não há
 * JSON para copiar, e reproduzir a mesma silhueta com caixas seria trabalho manual sem fim e ainda
 * assim aproximado. Sem OBJ, portar um mod de verdade esbarra no desenho.
 *
 * <p><b>Por que mora no núcleo.</b> O NeoForge tem um leitor de OBJ próprio e o Fabric não. Usar o
 * de lá num lado e escrever o outro daria o mesmo arquivo produzindo modelos diferentes em cada
 * plataforma — exatamente o tipo de divergência que este repositório existe para não ter. Aqui o
 * arquivo vira a mesma lista de faces nos dois, e cada adaptador só a converte para os quads dele.
 *
 * <p><b>O que este leitor entende:</b> {@code v}, {@code vt}, {@code vn}, {@code f} e {@code g}.
 * Material ({@code mtllib}, {@code usemtl}) é lido como nome de grupo e nada mais — quem declara o
 * mod diz qual textura vale, porque o pack do loader já é montado por declaração e um {@code .mtl}
 * apontando para caminho de disco não teria como ser resolvido dentro do jogo.
 */
public final class ObjModel {

    /** Um vértice de face: posição, e a coordenada de textura quando o arquivo traz uma. */
    public record Vertex(double x, double y, double z, double u, double v) {
    }

    /**
     * Uma face do modelo, já triangulada em três ou quatro vértices.
     *
     * <p>O grupo vem do {@code g} ou do {@code usemtl} mais recente. Ele não muda o desenho: serve
     * para o mod poder dizer "esta parte usa aquela textura" sem precisar cortar o arquivo.
     */
    public record Face(List<Vertex> vertices, String group) {
    }

    private final List<Face> faces;
    private final double minX, minY, minZ, maxX, maxY, maxZ;

    private ObjModel(List<Face> faces,
                     double minX, double minY, double minZ,
                     double maxX, double maxY, double maxZ) {
        this.faces = List.copyOf(faces);
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public List<Face> faces() {
        return faces;
    }

    /** Quantos vértices o modelo tem ao todo, contando repetidos entre faces. */
    public int vertexCount() {
        int total = 0;
        for (Face face : faces) total += face.vertices().size();
        return total;
    }

    public double minX() { return minX; }
    public double minY() { return minY; }
    public double minZ() { return minZ; }
    public double maxX() { return maxX; }
    public double maxY() { return maxY; }
    public double maxZ() { return maxZ; }

    /**
     * O maior lado da caixa que envolve o modelo.
     *
     * <p>É o número que {@link #normalized()} usa para caber o desenho num bloco.
     */
    public double largestSide() {
        return Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
    }

    /**
     * Limite de faces por arquivo.
     *
     * <p>Um OBJ é texto, e nada impede um arquivo de trazer um milhão de faces. Sem teto, um mod
     * mal-intencionado -- ou um export descuidado do Blender -- travaria o carregamento do jogo, e
     * o sintoma seria a tela de "carregando" para sempre, sem uma linha explicando.
     *
     * <p>Cinquenta mil é folgado para um bloco: o cano do Logistic Pipes, que é um dos modelos mais
     * detalhados que um mod desses tem, fica em menos de mil.
     */
    public static final int MAX_FACES = 50_000;

    /**
     * Lê um OBJ.
     *
     * <p>Índice negativo é aceito porque o formato permite: {@code -1} é o último vértice lido. É
     * comum em arquivo gerado por ferramenta, e recusá-lo faria o loader rejeitar um export
     * legítimo com uma mensagem que ninguém liga à causa.
     *
     * <p>Face com mais de quatro vértices é triangulada em leque. O jogo desenha quads, e um
     * polígono de cinco lados sem essa quebra sairia com um pedaço faltando.
     */
    public static ObjModel read(Reader source) throws IOException {
        List<double[]> positions = new ArrayList<>();
        List<double[]> uvs = new ArrayList<>();
        List<Face> faces = new ArrayList<>();
        String group = "";

        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE, minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;

        try (BufferedReader reader = new BufferedReader(source)) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') continue;

                String[] parts = line.split("\\s+");
                switch (parts[0]) {
                    case "v" -> {
                        if (parts.length < 4) break;
                        double x = number(parts[1]);
                        double y = number(parts[2]);
                        double z = number(parts[3]);
                        positions.add(new double[]{x, y, z});

                        minX = Math.min(minX, x);
                        minY = Math.min(minY, y);
                        minZ = Math.min(minZ, z);
                        maxX = Math.max(maxX, x);
                        maxY = Math.max(maxY, y);
                        maxZ = Math.max(maxZ, z);
                    }
                    case "vt" -> {
                        if (parts.length < 3) break;
                        uvs.add(new double[]{number(parts[1]), number(parts[2])});
                    }
                    // O grupo e o material dao nome a parte; o desenho nao muda por causa deles.
                    case "g", "o", "usemtl" -> group = parts.length > 1 ? parts[1] : "";
                    case "f" -> {
                        List<Vertex> vertices = new ArrayList<>();
                        for (int index = 1; index < parts.length; index++) {
                            Vertex vertex = vertexOf(parts[index], positions, uvs);
                            if (vertex != null) vertices.add(vertex);
                        }
                        if (vertices.size() < 3) break;

                        if (vertices.size() <= 4) {
                            faces.add(new Face(List.copyOf(vertices), group));
                        } else {
                            // Leque a partir do primeiro vertice: e a triangulacao que preserva a
                            // forma de um poligono convexo, que e o caso de todo export de modelo.
                            for (int index = 1; index + 1 < vertices.size(); index++) {
                                faces.add(new Face(List.of(vertices.get(0),
                                        vertices.get(index), vertices.get(index + 1)), group));
                            }
                        }

                        if (faces.size() > MAX_FACES) {
                            throw new IOException("modelo OBJ com mais de " + MAX_FACES
                                    + " faces; provavelmente nao e um modelo de bloco");
                        }
                    }
                    default -> {
                        // vn, s, mtllib e o que mais vier sao ignorados de proposito: nao mudam a
                        // geometria, e recusar o arquivo por causa deles rejeitaria export legitimo.
                    }
                }
            }
        }

        if (faces.isEmpty()) throw new IOException("modelo OBJ sem face nenhuma");

        return new ObjModel(faces, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Um vértice da forma {@code v}, {@code v/vt} ou {@code v/vt/vn}. */
    private static Vertex vertexOf(String token, List<double[]> positions, List<double[]> uvs) {
        String[] parts = token.split("/");
        if (parts.length == 0 || parts[0].isEmpty()) return null;

        double[] position = at(positions, parts[0]);
        if (position == null) return null;

        double u = 0;
        double v = 0;
        if (parts.length > 1 && !parts[1].isEmpty()) {
            double[] uv = at(uvs, parts[1]);
            if (uv != null) {
                u = uv[0];
                // O OBJ conta a textura de baixo para cima, e o jogo de cima para baixo. Sem esta
                // volta o desenho sai espelhado na vertical, e o defeito so aparece com uma textura
                // que nao e simetrica -- ou seja, tarde.
                v = 1 - uv[1];
            }
        }
        return new Vertex(position[0], position[1], position[2], u, v);
    }

    /** Resolve um índice do OBJ, que começa em 1 e aceita negativo contando do fim. */
    private static double[] at(List<double[]> values, String raw) {
        int index;
        try {
            index = Integer.parseInt(raw);
        } catch (NumberFormatException error) {
            return null;
        }

        if (index > 0 && index <= values.size()) return values.get(index - 1);
        if (index < 0 && -index <= values.size()) return values.get(values.size() + index);
        return null;
    }

    private static double number(String raw) {
        return Double.parseDouble(raw.replace(",", "."));
    }

    /**
     * O mesmo modelo cabendo num bloco, com o chão em zero e centrado nos outros dois eixos.
     *
     * <p>Um OBJ vem na escala em que foi desenhado -- o do Logistic Pipes vem de 0 a 100, outros
     * vêm de -0,5 a 0,5 --, e o jogo desenha em dezesseis avos. Exigir que o mod acerte a escala à
     * mão seria pedir que ele adivinhe a unidade de quem exportou.
     *
     * <p>A escala é a mesma nos três eixos, e é essa a razão de existir {@link #largestSide()}:
     * escalar cada eixo pelo próprio tamanho esticaria o desenho até a caixa, e um cano ficaria
     * achatado.
     */
    public ObjModel normalized() {
        double side = largestSide();
        if (side <= 0) return this;

        double scale = 16.0 / side;
        double offsetX = -minX * scale + (16 - (maxX - minX) * scale) / 2;
        double offsetY = -minY * scale;
        double offsetZ = -minZ * scale + (16 - (maxZ - minZ) * scale) / 2;

        return transformed(scale, offsetX, offsetY, offsetZ);
    }

    /** O mesmo modelo com escala e deslocamento próprios, em dezesseis avos. */
    public ObjModel transformed(double scale, double offsetX, double offsetY, double offsetZ) {
        List<Face> moved = new ArrayList<>(faces.size());
        for (Face face : faces) {
            List<Vertex> vertices = new ArrayList<>(face.vertices().size());
            for (Vertex vertex : face.vertices()) {
                vertices.add(new Vertex(
                        vertex.x() * scale + offsetX,
                        vertex.y() * scale + offsetY,
                        vertex.z() * scale + offsetZ,
                        vertex.u(), vertex.v()));
            }
            moved.add(new Face(List.copyOf(vertices), face.group()));
        }

        return new ObjModel(moved,
                minX * scale + offsetX, minY * scale + offsetY, minZ * scale + offsetZ,
                maxX * scale + offsetX, maxY * scale + offsetY, maxZ * scale + offsetZ);
    }

    /** Se o caminho declarado aponta para um OBJ. */
    public static boolean isObj(String path) {
        return path != null && path.toLowerCase(Locale.ROOT).endsWith(".obj");
    }
}
