package dev.lualoader.client;

import dev.lualoader.ui.ScreenLayout;
import dev.lualoader.ui.ScreenModel;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;

/**
 * Elementos fixos na tela do jogador.
 *
 * <p>Diferente de uma tela: fica sobre o jogo, não captura o mouse e não pausa nada. Por isso usa o
 * gancho de renderização em vez de uma {@code Screen}, e não aceita botão nem campo de texto — não
 * haveria como o jogador interagir sem tirar o controle da câmera.
 */
public final class HudOverlay {
    private static volatile ScreenModel current;

    private HudOverlay() {
    }

    /** Substitui o que está sendo desenhado. Uma descrição sem elementos limpa o HUD. */
    public static void set(String description) {
        ScreenModel model = ScreenModel.parse(description);
        current = model == null || model.elements().isEmpty() ? null : model;
    }

    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            ScreenModel model = current;
            if (model == null) return;

            MinecraftClient client = MinecraftClient.getInstance();
            // Com uma tela aberta o HUD sairia por cima dela; o jogo esconde o próprio HUD aí.
            if (client.currentScreen != null || client.options.hudHidden) return;

            // A superfície é a tela inteira, e sem âncora a origem é o canto superior esquerdo: é o
            // canto natural para um elemento fixo, e o que alguém espera ao escrever x = 4, y = 4.
            ScreenLayout.Bounds surface = new ScreenLayout.Bounds(0, 0,
                    client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight());

            for (ScreenModel.Element element : model.elements()) {
                // Botão e campo não existem no HUD: sem cursor, não haveria como acioná-los. A
                // caixa de ajuda também fica de fora, pelo mesmo motivo.
                if (element.type().equals("button") || element.type().equals("input")) continue;

                int[] position = ScreenRenderer.resolve(element, surface, surface);
                ScreenRenderer.draw(context, client.textRenderer, element, position[0], position[1]);
            }
        });
    }
}
