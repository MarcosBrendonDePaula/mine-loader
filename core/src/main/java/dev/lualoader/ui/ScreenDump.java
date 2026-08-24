package dev.lualoader.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Diz onde cada elemento de uma tela vai parar, e o que está errado com isso.
 *
 * <p><b>Por que existe.</b> Quando uma tela sai errada, o log responde a pergunta errada: ele diz
 * que a descrição foi enviada, e ela foi. O que ninguém consegue ver é a conta que transforma
 * {@code x = 6, anchor = "gui_top_right", group = "area"} numa posição na janela — e é lá que os
 * defeitos moram. Duas telas desta sessão foram ao jogo com elementos por cima de outros, e as duas
 * vezes a descoberta foi olhando o jogo.
 *
 * <p>A conta reproduzida aqui é a <b>mesma</b> que o cliente faz, porque vem de
 * {@link ScreenLayout} — o mesmo código, não uma cópia. Uma cópia divergiria no primeiro ajuste, e
 * um diagnóstico que mente é pior que nenhum.
 *
 * <p><b>O que este dump não alcança:</b> a rolagem. O deslocamento de um viewport vive no cliente,
 * para rolar não custar uma ida e volta pela rede. Os elementos de um grupo aparecem aqui na
 * posição de rolagem zero, que é onde eles começam.
 */
public final class ScreenDump {
    private ScreenDump() {
    }

    /** Uma medida de texto qualquer, suficiente para posicionar: o dump não desenha nada. */
    private static final ScreenLayout.TextMetrics METRICS = new ScreenLayout.TextMetrics() {
        @Override
        public int width(String text) {
            // Seis pixels por caractere e a largura media da fonte do jogo. O dump posiciona, nao
            // desenha: uma medida aproximada acerta a faixa em que o texto cai, que e o que se quer.
            return text == null ? 0 : text.length() * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }
    };

    /**
     * O relatório de uma tela, pronto para ir ao log.
     *
     * @param screenWidth  largura da janela do jogador, de {@code screen_size}
     * @param screenHeight altura da janela do jogador
     */
    public static String of(String descriptionJson, int screenWidth, int screenHeight) {
        ScreenModel model;
        try {
            model = ScreenModel.parse(descriptionJson);
        } catch (RuntimeException error) {
            return "a descricao nao pode ser lida: " + error.getMessage();
        }

        int width = model.width() > 0 ? model.width() : 256;
        int height = model.height() > 0 ? model.height() : 166;

        // A janela desenhada nasce centrada, como o cliente faz.
        ScreenLayout.Bounds surface = new ScreenLayout.Bounds(
                (screenWidth - width) / 2, (screenHeight - height) / 2, width, height);

        List<String> lines = new ArrayList<>();
        lines.add(String.format("tela %dx%d na janela %dx%d, %d elemento(s)",
                width, height, screenWidth, screenHeight, model.elements().size()));

        Map<String, ScreenModel.Element> viewports = new LinkedHashMap<>();
        for (ScreenModel.Element element : model.elements()) {
            if (element.type().equals("viewport")) viewports.put(element.id(), element);
        }

        List<String> problems = new ArrayList<>();

        for (int index = 0; index < model.elements().size(); index++) {
            ScreenModel.Element element = model.elements().get(index);
            int[] size = ScreenLayout.measure(element, METRICS);

            String where;
            if (element.group().isBlank()) {
                int[] position = ScreenLayout.resolve(element, surface, surface, METRICS);
                where = String.format("(%d,%d) %dx%d", position[0], position[1], size[0], size[1]);

                // Fora da janela declarada: o cliente desenha assim mesmo, e o elemento aparece
                // por cima da borda ou some conforme a superficie.
                int relativeX = position[0] - surface.x();
                int relativeY = position[1] - surface.y();
                if (relativeX < 0 || relativeY < 0
                        || relativeX + size[0] > width || relativeY + size[1] > height) {
                    problems.add(describe(index, element) + " sai da janela");
                }
            } else {
                where = String.format("(%d,%d)+viewport %dx%d",
                        element.x(), element.y(), size[0], size[1]);

                ScreenModel.Element viewport = viewports.get(element.group());
                if (viewport == null) {
                    problems.add(describe(index, element)
                            + " aponta para o viewport '" + element.group() + "', que nao existe");
                } else if (element.x() + size[0] > viewport.w()) {
                    problems.add(describe(index, element) + " e mais largo que o viewport");
                }
            }

            lines.add(String.format("  %2d %-9s %-16s %s", index, element.type(),
                    element.id().isBlank() ? "-" : element.id(), where));
        }

        collectOverlaps(model, surface, problems);

        if (problems.isEmpty()) {
            lines.add("nenhum problema de posicao encontrado");
        } else {
            lines.add(problems.size() + " problema(s):");
            for (String problem : problems) lines.add("  ! " + problem);
        }
        return String.join("\n", lines);
    }

    /**
     * Texto por cima de botão, que é a colisão que de fato atrapalha.
     *
     * <p>Painel por baixo de tudo é o que um painel é, e rótulo encostando em rótulo não prejudica
     * ninguém. Reprovar essas duas encheria o relatório de ruído e faria quem lê parar de ler.
     */
    private static void collectOverlaps(ScreenModel model, ScreenLayout.Bounds surface,
                                        List<String> problems) {
        List<ScreenModel.Element> elements = model.elements();

        for (int i = 0; i < elements.size(); i++) {
            ScreenModel.Element label = elements.get(i);
            if (!label.type().equals("label") || !label.group().isBlank()) continue;

            int[] labelAt = ScreenLayout.resolve(label, surface, surface, METRICS);
            int[] labelSize = ScreenLayout.measure(label, METRICS);

            for (int j = 0; j < elements.size(); j++) {
                ScreenModel.Element button = elements.get(j);
                if (!button.type().equals("button") || !button.group().isBlank()) continue;
                // Um botao sem texto e superficie de clique: rotulo por cima dele e o desenho
                // pretendido, e nao colisao.
                if (button.text().isBlank()) continue;

                int[] buttonAt = ScreenLayout.resolve(button, surface, surface, METRICS);
                int[] buttonSize = ScreenLayout.measure(button, METRICS);

                boolean hits = labelAt[0] < buttonAt[0] + buttonSize[0]
                        && buttonAt[0] < labelAt[0] + Math.max(1, labelSize[0])
                        && labelAt[1] < buttonAt[1] + buttonSize[1]
                        && buttonAt[1] < labelAt[1] + Math.max(9, labelSize[1]);

                if (hits) {
                    problems.add(describe(i, label) + " cai por cima de " + describe(j, button));
                }
            }
        }
    }

    private static String describe(int index, ScreenModel.Element element) {
        return "#" + index + " " + element.type()
                + (element.id().isBlank() ? "" : "[" + element.id() + "]");
    }
}
