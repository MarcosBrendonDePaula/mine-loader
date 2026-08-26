package dev.lualoader.neoforge.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Desenha uma lista de elementos com recorte, rolagem e clique de célula.
 *
 * <p>Existe porque as três coisas andam juntas. Um {@code viewport} recorta; os elementos com
 * {@code group} apontando para ele rolam dentro daquele recorte; e uma {@code grid} dentro dele
 * precisa saber qual célula está sob o cursor depois do deslocamento. Espalhar isso entre a tela
 * própria e a sobreposição faria as duas divergirem no primeiro ajuste.
 *
 * <p>O deslocamento vive aqui, no cliente, e não no servidor: rolar uma lista não deve custar uma
 * ida e volta pela rede a cada entalhe da roda.
 */
public final class NeoForgeScreenSurface {
    /** Deslocamento vertical de cada viewport, por identificador. */
    private final Map<String, Integer> offsets = new HashMap<>();

    /** Área de cada viewport na última vez que foi desenhado. */
    private final Map<String, ScreenLayout.Bounds> viewports = new LinkedHashMap<>();

    /** Descarta o que rolou. Usado quando a descrição muda de forma. */
    public void reset() {
        offsets.clear();
        viewports.clear();
    }

    /**
     * Resolve a posição de um elemento, já considerando o viewport a que pertence.
     *
     * <p>Dentro de um grupo, {@code x} e {@code y} passam a ser relativos ao canto do viewport, e
     * não à superfície: é o que permite montar a lista uma vez e deixá-la rolar sem recalcular
     * coordenada nenhuma.
     */
    private int[] positionOf(ScreenModel.Element element, List<ScreenModel.Element> elements,
                             ScreenLayout.Bounds surface, ScreenLayout.Bounds gui) {
        if (element.group().isBlank()) {
            return NeoForgeScreenRenderer.resolve(element, surface, gui);
        }

        ScreenLayout.Bounds area = viewportBounds(element.group(), elements, surface, gui);
        if (area == null) return NeoForgeScreenRenderer.resolve(element, surface, gui);

        return new int[]{
                area.x() + element.x(),
                area.y() + element.y() - offsets.getOrDefault(element.group(), 0)};
    }

    private ScreenLayout.Bounds viewportBounds(String id, List<ScreenModel.Element> elements,
                                               ScreenLayout.Bounds surface,
                                               ScreenLayout.Bounds gui) {
        for (ScreenModel.Element element : elements) {
            if (!element.type().equals("viewport") || !element.id().equals(id)) continue;

            int[] position = NeoForgeScreenRenderer.resolve(element, surface, gui);
            return new ScreenLayout.Bounds(position[0], position[1], element.w(), element.h());
        }
        return null;
    }

    /**
     * Desenha todos os elementos e devolve o texto de ajuda sob o cursor, se houver.
     *
     * @param skipWidgets quando os tipos {@code button} e {@code input} já existem como widgets do
     *                    jogo e seriam pintados duas vezes
     */
    public String draw(GuiGraphics graphics, Font font,
                       List<ScreenModel.Element> elements,
                       ScreenLayout.Bounds surface, ScreenLayout.Bounds gui,
                       int mouseX, int mouseY, boolean skipWidgets) {
        viewports.clear();
        for (ScreenModel.Element element : elements) {
            if (!element.type().equals("viewport") || element.id().isBlank()) continue;
            int[] position = NeoForgeScreenRenderer.resolve(element, surface, gui);
            viewports.put(element.id(),
                    new ScreenLayout.Bounds(position[0], position[1], element.w(), element.h()));
        }

        String tooltip = null;
        for (ScreenModel.Element element : elements) {
            if (element.type().equals("viewport")) continue;
            if (skipWidgets && (element.type().equals("button") || element.type().equals("input"))) {
                continue;
            }

            int[] position = positionOf(element, elements, surface, gui);
            ScreenLayout.Bounds clip = viewports.get(element.group());

            if (clip != null) {
                // Fora do recorte o elemento nem e desenhado: o scissor ja cortaria o desenho, mas
                // pular cedo evita percorrer as celulas de uma grade inteira que esta fora de vista.
                graphics.enableScissor(clip.x(), clip.y(),
                        clip.x() + clip.w(), clip.y() + clip.h());
            }
            NeoForgeScreenRenderer.draw(graphics, font, element, position[0], position[1]);
            if (clip != null) graphics.disableScissor();

            // Um elemento recortado so responde pelo cursor quando o cursor esta dentro do recorte.
            if (clip != null && !inside(clip, mouseX, mouseY)) continue;

            String found =
                    NeoForgeScreenRenderer.tooltipAt(element, position[0], position[1], mouseX, mouseY);
            if (found != null) tooltip = found;
        }
        return tooltip;
    }

    /**
     * Desenha o ícone dos botões que declaram um item.
     *
     * <p>Precisa acontecer depois dos widgets: o botão é desenhado pelo jogo, e um ícone pintado
     * antes dele ficaria escondido atrás. Por isso não fica no {@link #draw}, que roda antes.
     *
     * <p>O botão continua sendo o mesmo elemento; o ícone é um enfeite. Um botão com texto e ícone
     * mostra os dois, com o ícone à esquerda.
     */
    public void drawButtonIcons(GuiGraphics graphics, Font font,
                                List<ScreenModel.Element> elements,
                                ScreenLayout.Bounds surface, ScreenLayout.Bounds gui) {
        for (ScreenModel.Element element : elements) {
            if (!element.type().equals("button") || element.item().isBlank()) continue;

            int[] position = positionOf(element, elements, surface, gui);

            // Centralizado quando o botão não tem texto, encostado à esquerda quando tem.
            int x = element.text().isBlank()
                    ? position[0] + (element.w() - 16) / 2
                    : position[0] + 3;
            int y = position[1] + (element.h() - 16) / 2;

            NeoForgeScreenRenderer.drawItemIcon(graphics, font, element.item(), x, y);
        }
    }

    /**
     * Trata um clique. Devolve a grade clicada e a célula, ou {@code null}.
     *
     * @return array com o id da grade e o índice da célula, a partir de 1
     */
    public Object[] clickedCell(List<ScreenModel.Element> elements, ScreenLayout.Bounds surface,
                                ScreenLayout.Bounds gui, int mouseX, int mouseY) {
        // De trás para a frente: o último declarado é o que está por cima, e é o que o jogador
        // entende ter clicado.
        for (int index = elements.size() - 1; index >= 0; index--) {
            ScreenModel.Element element = elements.get(index);
            if (!element.type().equals("grid")) continue;

            ScreenLayout.Bounds clip = viewports.get(element.group());
            if (clip != null && !inside(clip, mouseX, mouseY)) continue;

            int[] position = positionOf(element, elements, surface, gui);
            int cell = NeoForgeScreenRenderer.cellAt(element, position[0], position[1], mouseX, mouseY);
            if (cell > 0) return new Object[]{element.id(), cell};
        }
        return null;
    }

    /**
     * Rola o viewport sob o cursor.
     *
     * <p>O limite vem do {@code content} declarado pelo mod: o cliente não mede o conteúdo, porque
     * parte dele pode nem estar nesta descrição. Sem {@code content}, não há o que rolar.
     *
     * @return {@code true} quando algo rolou, para o chamador consumir o evento
     */
    public boolean scroll(List<ScreenModel.Element> elements, ScreenLayout.Bounds surface,
                          ScreenLayout.Bounds gui, int mouseX, int mouseY, double amount) {
        for (ScreenModel.Element element : elements) {
            if (!element.type().equals("viewport") || element.id().isBlank()) continue;

            int[] position = NeoForgeScreenRenderer.resolve(element, surface, gui);
            ScreenLayout.Bounds area =
                    new ScreenLayout.Bounds(position[0], position[1], element.w(), element.h());
            if (!inside(area, mouseX, mouseY)) continue;

            int maximum = Math.max(0, element.content() - element.h());
            if (maximum == 0) return false;

            // Um entalhe da roda anda uma celula de inventario, que e o passo que a maioria das
            // listas usa; sem isso a rolagem fica lenta demais em lista longa.
            int step = 18;
            int current = offsets.getOrDefault(element.id(), 0);
            int updated = Math.max(0, Math.min(maximum, current - (int) Math.round(amount) * step));

            offsets.put(element.id(), updated);
            return true;
        }
        return false;
    }

    private static boolean inside(ScreenLayout.Bounds area, int x, int y) {
        return x >= area.x() && x < area.x() + area.w()
                && y >= area.y() && y < area.y() + area.h();
    }
}
