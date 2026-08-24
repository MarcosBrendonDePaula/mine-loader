package dev.lualoader.neoforge.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Elementos fixos na tela do jogador.
 *
 * <p>Diferente de uma tela: fica sobre o jogo, não captura o mouse e não pausa nada. Por isso é
 * desenhado no gancho de renderização em vez de uma {@code Screen}, e não aceita botão nem campo de
 * texto — não haveria como o jogador interagir sem tirar o controle da câmera.
 */
public final class NeoForgeHud {
    private static volatile ScreenModel current;

    private NeoForgeHud() {
    }

    /** Substitui o que está sendo desenhado. Uma descrição sem elementos limpa o HUD. */
    public static void set(String description) {
        ScreenModel model = ScreenModel.parse(description);
        current = model == null || model.elements().isEmpty() ? null : model;
    }

    /** Desenha o HUD corrente. Chamado a cada quadro pelo gancho de interface. */
    public static void render(GuiGraphics graphics) {
        ScreenModel model = current;
        if (model == null) return;

        Minecraft client = Minecraft.getInstance();
        // Com uma tela aberta o HUD sairia por cima dela; o jogo esconde o próprio HUD aí.
        if (client.screen != null || client.options.hideGui) return;

        // A superfície é a tela inteira, e sem âncora a origem é o canto superior esquerdo: é o
        // canto natural para um elemento fixo, e o que alguém espera ao escrever x = 4, y = 4.
        ScreenLayout.Bounds surface = new ScreenLayout.Bounds(0, 0,
                client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight());

        for (ScreenModel.Element element : model.elements()) {
            // Botão e campo não existem no HUD: sem cursor, não haveria como acioná-los. A
            // caixa de ajuda também fica de fora, pelo mesmo motivo.
            if (element.type().equals("button") || element.type().equals("input")) continue;

            int[] position = NeoForgeScreenRenderer.resolve(element, surface, surface);
            NeoForgeScreenRenderer.draw(graphics, client.font, element, position[0], position[1]);
        }
    }
}
