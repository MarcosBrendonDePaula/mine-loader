package dev.lualoader.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.manifest.ModLoader;
import dev.lualoader.platform.TestBridge;
import dev.lualoader.platform.TestPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Nenhum elemento de tela pode cair por cima de outro nem sair da janela.
 *
 * <p><b>Por que este arquivo existe.</b> A tela do gerenciador foi ao jogo com a última linha da
 * lista desenhada por baixo dos botões do rodapé. Nada acusou: o servidor monta uma descrição, o
 * cliente desenha, e ninguém no meio compara posições. O log dizia que a tela tinha sido enviada, e
 * estava certo — só que "enviada" não quer dizer "legível".
 *
 * <p>A geometria mora no núcleo justamente para isto poder ser conferido sem cliente. Um erro de
 * alinhamento vira um teste aqui, e não dois defeitos independentes descobertos por quem joga.
 *
 * <p>A regra conferida é estreita de propósito: <b>texto e botão não se sobrepõem</b>, e nada passa
 * da altura declarada. Painéis podem ficar por baixo de tudo — é o que eles são — e um rótulo pode
 * encostar noutro rótulo sem prejuízo. Uma regra mais larga reprovaria telas boas.
 */
class ScreenOverlapTest {
    /** Quantas linhas o gerenciador mostra por pagina; a lista de teste precisa passar disso. */
    private static final int POR_PAGINA = 6;

    /** Um elemento posicionado, do jeito que a descrição o entrega. */
    private record Box(String type, String id, String text, String group,
                       int x, int y, int w, int h) {
        boolean intersects(Box other) {
            return x < other.x + other.w && other.x < x + w
                    && y < other.y + other.h && other.y < y + h;
        }

        String describe() {
            return type + (id.isEmpty() ? "" : "#" + id)
                    + " em (" + x + "," + y + ") " + w + "x" + h;
        }
    }

    @Test
    void oGerenciadorNaoDesenhaTextoPorBaixoDeBotao(@TempDir Path root) throws IOException {
        TestPlayer player = openManager(root);

        assertNotNull(player.screenJson, "o gerenciador precisa abrir uma tela");
        assertNoOverlap(player.screenJson, "lista");
    }

    @Test
    void oGerenciadorNaoDesenhaForaDaJanela(@TempDir Path root) throws IOException {
        TestPlayer player = openManager(root);
        assertInsideBounds(player.screenJson, "lista");
    }

    @Test
    void aTelaDeInstalarTambemRespeitaOsLimites(@TempDir Path root) throws IOException {
        TestPlayer player = openManager(root);
        LuaRuntime runtime = lastRuntime;

        // O botao "instalar" leva a tela de instalacao; ela e a mais densa das tres, porque lista
        // as permissoes do mod que se pretende instalar.
        runtime.triggerScreenEvent("gerenciador:lista", "instalar", "click", "", player);

        assertNoOverlap(player.screenJson, "instalar");
        assertInsideBounds(player.screenJson, "instalar");
    }

    @Test
    void oDetalheDeUmModRespeitaOsLimites(@TempDir Path root) throws IOException {
        TestPlayer player = openManager(root);
        LuaRuntime runtime = lastRuntime;

        // O detalhe do proprio gerenciador: e o mod com mais permissoes declaradas entre os que o
        // teste carrega, entao e o caso que mais empurra o texto para baixo.
        runtime.triggerScreenEvent("gerenciador:lista", "abrir:gerenciador", "click", "", player);

        assertNoOverlap(player.screenJson, "detalhe");
        assertInsideBounds(player.screenJson, "detalhe");
    }

    /**
     * O painel do autoteste, que e a tela mais densa do repositorio.
     *
     * <p>Uma linha por caso, com marca, nome e o motivo da falha quando ha uma -- e a lista cresce
     * a cada teste novo. E justamente a tela em que uma colisao tem mais chance de aparecer sem
     * ninguem notar, porque ninguem reconta os elementos ao acrescentar um caso.
     */
    @Test
    void oPainelDoAutotesteRespeitaOsLimites(@TempDir Path root) throws IOException {
        Path origin = Path.of("..", "examples", "autoteste");
        Path target = root.resolve("autoteste");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        var bridge = new TestBridge() {
        };
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        runtime.load(new ModLoader(LoggerFactory.getLogger("test")).discover(root).get(0));

        TestPlayer player = new TestPlayer();
        runtime.runCommand("autoteste", player, "");

        assertNotNull(player.screenJson, "o autoteste precisa abrir o painel para um jogador");
        assertNoOverlap(player.screenJson, "painel");

        // O conteudo do viewport passa da altura da janela de proposito -- e o que rola. Conferir
        // limites aqui reprovaria a rolagem, entao a checagem e so a de sobreposicao.
    }

    // --- apoio ---------------------------------------------------------------------------

    private LuaRuntime lastRuntime;

    private TestPlayer openManager(Path root) throws IOException {
        Path origin = Path.of("..", "examples", "gerenciador");
        Path target = root.resolve("gerenciador");
        Files.createDirectories(target);
        for (Path file : List.of(Path.of("mod.json"), Path.of("main.lua"))) {
            Files.copy(origin.resolve(file), target.resolve(file));
        }

        // A pagina precisa encher.
        //
        // Com um mod so na lista, a primeira versao deste teste passava mesmo com a altura errada:
        // uma linha nunca chega perto do rodape, e o defeito real -- a sexta linha por baixo dos
        // botoes -- so existe com a pagina cheia. Um teste que nao consegue falhar nao e
        // verificacao, entao a lista aqui e maior que uma pagina de proposito.
        for (int i = 1; i <= POR_PAGINA + 2; i++) {
            String id = "vizinho_" + i;
            Path other = root.resolve(id);
            Files.createDirectories(other);
            Files.writeString(other.resolve("mod.json"), """
                    {
                      "schema": 1,
                      "id": "%s",
                      "name": "Mod Vizinho De Nome Longo %d",
                      "version": "1.0.0"
                    }
                    """.formatted(id, i));
        }

        var bridge = new TestBridge() {
        };
        LuaRuntime runtime = new LuaRuntime(LoggerFactory.getLogger("test"));
        runtime.attach(bridge);
        for (var mod : new ModLoader(LoggerFactory.getLogger("test")).discover(root)) {
            runtime.load(mod);
        }
        lastRuntime = runtime;

        TestPlayer player = new TestPlayer();
        runtime.runCommand("gerenciador", player, "");
        return player;
    }

    private static List<Box> boxesOf(String json) {
        JsonObject screen = JsonParser.parseString(json).getAsJsonObject();
        JsonArray elements = screen.getAsJsonArray("elements");

        List<Box> boxes = new ArrayList<>();
        if (elements == null) return boxes;

        for (var element : elements) {
            JsonObject object = element.getAsJsonObject();
            boxes.add(new Box(
                    object.has("type") ? object.get("type").getAsString() : "",
                    object.has("id") ? object.get("id").getAsString() : "",
                    object.has("text") ? object.get("text").getAsString() : "",
                    object.has("group") ? object.get("group").getAsString() : "",
                    object.has("x") ? object.get("x").getAsInt() : 0,
                    object.has("y") ? object.get("y").getAsInt() : 0,
                    object.has("w") ? object.get("w").getAsInt() : 0,
                    object.has("h") ? object.get("h").getAsInt() : 0));
        }
        return boxes;
    }

    /**
     * Rótulo sobre botão é o defeito que este arquivo existe para pegar.
     *
     * <p>Um rótulo não declara largura nem altura: ele é medido pelo texto na hora de desenhar. A
     * conferência usa a altura de uma linha de fonte, que é o que basta para detectar uma linha
     * caindo dentro da faixa de um botão -- que foi exatamente o caso real.
     */
    private static void assertNoOverlap(String json, String tela) {
        List<Box> boxes = boxesOf(json);

        for (Box label : boxes) {
            if (!label.type().equals("label")) continue;

            // Um elemento com group vive dentro de um viewport: as coordenadas dele sao relativas
            // aquele recorte, e o cliente nao desenha nada que caia fora. Compara-lo com o que
            // esta fora do viewport seria comparar dois sistemas de coordenadas diferentes -- foi
            // o que esta linha passou a evitar, depois de o teste reprovar uma tela correta.
            if (!label.group().isEmpty()) continue;

            // Altura de uma linha de texto do jogo. A largura nao entra na conta: o rotulo comeca
            // a esquerda, e o que importa e a faixa vertical em que ele cai.
            Box linha = new Box(label.type(), label.id(), label.text(), label.group(),
                    label.x(), label.y(), 1, 9);

            for (Box button : boxes) {
                if (!button.type().equals("button")) continue;
                if (!button.group().isEmpty()) continue;

                // Um botao sem texto e uma superficie de clique, e nao um botao desenhado: a lista
                // usa um por linha justamente para clicar em qualquer ponto dela abrir o detalhe.
                // Rotulo por cima dele e o desenho pretendido, e nao a colisao que se procura.
                if (button.text().isEmpty()) continue;

                if (!linha.intersects(button)) continue;

                fail("na tela " + tela + ", " + label.describe()
                        + " cai por cima de " + button.describe());
            }
        }
    }

    private static void assertInsideBounds(String json, String tela) {
        JsonObject screen = JsonParser.parseString(json).getAsJsonObject();
        int width = screen.has("width") ? screen.get("width").getAsInt() : 256;
        int height = screen.has("height") ? screen.get("height").getAsInt() : 166;

        for (Box box : boxesOf(json)) {
            if (!box.group().isEmpty()) continue;
            assertTrue(box.x() + box.w() <= width,
                    "na tela " + tela + ", " + box.describe() + " passa da largura " + width);
            assertTrue(box.y() + box.h() <= height,
                    "na tela " + tela + ", " + box.describe() + " passa da altura " + height);
        }
    }
}
