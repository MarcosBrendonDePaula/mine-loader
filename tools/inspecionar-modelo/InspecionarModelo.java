import dev.lualoader.content.ObjModel;

import java.io.FileReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diz o que o pacote gerado manda desenhar, sem abrir o jogo.
 *
 * <p><b>Por que existe.</b> Quando um bloco aparece errado na tela, a pergunta é qual peça o jogo
 * usou -- e o render é do cliente, então nem o servidor sabe responder. O que decide o desenho é o
 * pacote: o blockstate escolhe o modelo, e o modelo aponta a malha e o recorte. Isso dá para ler.
 *
 * <p>Foi escrito depois de três rodadas diagnosticando um cano por captura de tela. Cada uma
 * custava dois minutos de carregamento e uma descrição do que se via; aqui a resposta é imediata e
 * é o arquivo falando, não alguém interpretando pixels.
 *
 * <pre>
 *   tools/inspecionar-modelo.sh logistica:cano        o que cada estado desenha
 *   tools/inspecionar-modelo.sh logistica:cano.obj    os grupos do arquivo, com as caixas
 * </pre>
 */
public final class InspecionarModelo {

    private static Path pack = Path.of("run", "lua-loader", "generated-pack");

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            System.out.println("uso: InspecionarModelo <namespace:bloco> [caminho do pacote]");
            System.out.println("     InspecionarModelo <namespace:arquivo.obj> [caminho do pacote]");
            return;
        }
        if (args.length > 1) pack = Path.of(args[1]);

        String alvo = args[0];
        int dois = alvo.indexOf(':');
        if (dois <= 0) {
            System.out.println("id invalido: " + alvo + " (falta o namespace)");
            return;
        }

        String namespace = alvo.substring(0, dois);
        String nome = alvo.substring(dois + 1);

        if (nome.endsWith(".obj")) grupos(namespace, nome);
        else bloco(namespace, nome);
    }

    // ------------------------------------------------------------------ o bloco

    /** Percorre blockstate -> modelo -> malha, e diz o que cada estado desenha. */
    private static void bloco(String namespace, String id) throws IOException {
        Path blockstate = pack.resolve("assets").resolve(namespace)
                .resolve("blockstates").resolve(id + ".json");

        if (!Files.isRegularFile(blockstate)) {
            System.out.println("nao ha blockstate para " + namespace + ":" + id + " em " + pack);
            return;
        }

        String texto = Files.readString(blockstate, StandardCharsets.UTF_8);
        System.out.println(namespace + ":" + id
                + (texto.contains("\"multipart\"") ? "  (multipart)" : "  (variantes)"));
        System.out.println();

        for (Peca peca : pecasDe(texto)) {
            Path modelo = pack.resolve("assets").resolve(namespace)
                    .resolve("models/block").resolve(peca.modelo + ".json");

            if (!Files.isRegularFile(modelo)) {
                System.out.printf("  %-28s MODELO AUSENTE (%s)%n", peca.quando, peca.modelo);
                continue;
            }

            String json = Files.readString(modelo, StandardCharsets.UTF_8);
            String objRef = valor(json, "lua_obj");
            String textura = valor(json, "all");

            if (objRef == null) {
                System.out.printf("  %-28s %s  [sem malha: %s]%n",
                        peca.quando, peca.modelo, textura == null ? "?" : textura);
                continue;
            }

            List<String> recorte = lista(json, "lua_obj_groups");
            boolean doisLados = json.contains("\"lua_obj_double_sided\": true");

            ObjModel malha = malhaDe(namespace, objRef);
            List<String> regiao = lista(json, "lua_obj_clip");

            // A mesma ordem do cliente: escala, recorta por nome, recorta por regiao. Uma ordem
            // diferente aqui daria numeros que nao sao os que o jogo desenha -- e uma ferramenta de
            // diagnostico que mente e pior que nenhuma.
            ObjModel filtrada = null;
            if (malha != null) {
                filtrada = malha.normalized().filtered(recorte);
                if (regiao.size() == 6) {
                    filtrada = filtrada.clipped(
                            Double.parseDouble(regiao.get(0)), Double.parseDouble(regiao.get(1)),
                            Double.parseDouble(regiao.get(2)), Double.parseDouble(regiao.get(3)),
                            Double.parseDouble(regiao.get(4)), Double.parseDouble(regiao.get(5)));
                }
            }

            System.out.printf("  %-28s %s%n", peca.quando, peca.modelo);
            System.out.printf("      grupos   %s%n", recorte.isEmpty() ? "(todos)" : recorte);
            System.out.printf("      textura  %s%s%n", textura, doisLados ? "   dois lados" : "");

            // Duas pecas no mesmo plano brigam pelo pixel e cintilam. O aviso sai aqui porque e o
            // tipo de defeito que so aparece quando quem joga anda, e nao numa captura parada.
            String inflada = valorNumero(json, "lua_obj_expand");
            if (inflada != null && !inflada.equals("1")) {
                System.out.printf("      inflada  %s  (para nao brigar com a peca de tras)%n", inflada);
            }
            if (regiao.size() == 6) System.out.printf("      regiao   %s%n", regiao);

            if (filtrada == null) {
                System.out.println("      malha    ARQUIVO AUSENTE: " + objRef);
            } else if (filtrada.faces().isEmpty()) {
                // O caso que mais custou tempo: o recorte nao casa com grupo nenhum, o modelo sai
                // vazio, e o bloco desenha a reserva -- parecendo um cubo comum.
                System.out.println("      malha    VAZIA -- o recorte nao casou com grupo nenhum");
            } else {
                System.out.printf("      malha    %d face(s)   %s%n",
                        filtrada.faces().size(), caixaDe(filtrada));
            }
            System.out.println();
        }
    }

    /** Uma entrada do blockstate: quando ela vale, e que modelo aplica. */
    private record Peca(String quando, String modelo) {
    }

    /** Lê as entradas do blockstate sem depender de uma biblioteca de JSON. */
    private static List<Peca> pecasDe(String json) {
        List<Peca> pecas = new ArrayList<>();

        int busca = 0;
        while (true) {
            int modelo = json.indexOf("\"model\":", busca);
            if (modelo < 0) break;

            int abre = json.indexOf('"', modelo + 8);
            int fecha = json.indexOf('"', abre + 1);
            if (abre < 0 || fecha < 0) break;

            String id = json.substring(abre + 1, fecha);
            String nome = id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id;

            // A condicao e o "when" mais proximo antes deste modelo, quando ha um.
            String quando = "sempre";
            int when = json.lastIndexOf("\"when\"", modelo);
            int entrada = json.lastIndexOf("{ \"apply\"", modelo);
            if (when >= 0 && (entrada < 0 || when > entrada)) {
                int fimWhen = json.indexOf('}', when);
                if (fimWhen > 0) {
                    quando = json.substring(when + 6, fimWhen)
                            .replaceAll("[:{\"]", "").trim();
                }
            }

            pecas.add(new Peca(quando, nome));
            busca = fecha;
        }
        return pecas;
    }

    // ------------------------------------------------------------------ o arquivo

    /** Lista os grupos do arquivo, com quantas faces e que espaço cada um ocupa. */
    private static void grupos(String namespace, String arquivo) throws IOException {
        ObjModel malha = malhaDe(namespace, namespace + ":block/" + arquivo);
        if (malha == null) {
            System.out.println("nao achei " + arquivo + " em " + pack);
            return;
        }

        ObjModel escalada = malha.normalized();
        Map<String, List<ObjModel.Face>> porGrupo = new TreeMap<>();

        for (ObjModel.Face face : escalada.faces()) {
            for (String palavra : face.group().split("\\s+")) {
                // Os nomes que a ferramenta gera sozinha nao dizem nada sobre a peca.
                if (palavra.isEmpty() || palavra.startsWith("Mesh")) continue;
                porGrupo.computeIfAbsent(palavra, k -> new ArrayList<>()).add(face);
            }
        }

        System.out.println(arquivo + ": " + escalada.faces().size() + " face(s), "
                + porGrupo.size() + " grupo(s)");
        System.out.println();

        for (Map.Entry<String, List<ObjModel.Face>> entrada : porGrupo.entrySet()) {
            System.out.printf("  %-30s %4d face(s)   %s%n",
                    entrada.getKey(), entrada.getValue().size(), caixaDe(entrada.getValue()));
        }
    }

    // ------------------------------------------------------------------ apoio

    private static ObjModel malhaDe(String namespace, String objRef) throws IOException {
        String caminho = objRef.contains(":") ? objRef.substring(objRef.indexOf(':') + 1) : objRef;
        Path arquivo = pack.resolve("assets").resolve(namespace).resolve("models").resolve(caminho);
        if (!Files.isRegularFile(arquivo)) return null;

        try (var reader = new FileReader(arquivo.toFile(), StandardCharsets.UTF_8)) {
            return ObjModel.read(reader);
        }
    }

    private static String caixaDe(ObjModel malha) {
        return caixaDe(malha.faces());
    }

    private static String caixaDe(List<ObjModel.Face> faces) {
        double x1 = 99, y1 = 99, z1 = 99, x2 = -99, y2 = -99, z2 = -99;
        for (ObjModel.Face face : faces) {
            for (ObjModel.Vertex v : face.vertices()) {
                x1 = Math.min(x1, v.x());
                y1 = Math.min(y1, v.y());
                z1 = Math.min(z1, v.z());
                x2 = Math.max(x2, v.x());
                y2 = Math.max(y2, v.y());
                z2 = Math.max(z2, v.z());
            }
        }
        return String.format("x %5.2f..%5.2f  y %5.2f..%5.2f  z %5.2f..%5.2f", x1, x2, y1, y2, z1, z2);
    }

    /** Um numero solto do JSON, sem aspas em volta. */
    private static String valorNumero(String json, String campo) {
        int chave = json.indexOf("\"" + campo + "\"");
        if (chave < 0) return null;

        int dois = json.indexOf(':', chave);
        int fim = json.indexOf(',', dois);
        return dois < 0 || fim < 0 ? null : json.substring(dois + 1, fim).trim();
    }

    private static String valor(String json, String campo) {
        int chave = json.indexOf("\"" + campo + "\"");
        if (chave < 0) return null;

        int abre = json.indexOf('"', chave + campo.length() + 2);
        int fecha = json.indexOf('"', abre + 1);
        return abre < 0 || fecha < 0 ? null : json.substring(abre + 1, fecha);
    }

    private static List<String> lista(String json, String campo) {
        int chave = json.indexOf("\"" + campo + "\"");
        if (chave < 0) return List.of();

        int abre = json.indexOf('[', chave);
        int fecha = json.indexOf(']', abre);
        if (abre < 0 || fecha < 0) return List.of();

        List<String> valores = new ArrayList<>();
        for (String parte : json.substring(abre + 1, fecha).split(",")) {
            String limpo = parte.replace("\"", "").trim();
            if (!limpo.isEmpty()) valores.add(limpo);
        }
        return valores;
    }
}
