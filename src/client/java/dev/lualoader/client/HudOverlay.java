package dev.lualoader.client;

import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * Elementos fixos na tela do jogador.
 *
 * <p>Diferente de uma tela: fica sobre o jogo, não captura o mouse e não pausa nada. Por isso usa o
 * gancho de renderização em vez de uma {@code Screen}, e não aceita botão nem campo de texto — não
 * haveria como o jogador interagir sem tirar o controle da câmera.
 */
public final class HudOverlay {
    private static volatile ScreenModel atual;

    private HudOverlay() {
    }

    /** Substitui o que está sendo desenhado. Uma descrição sem elementos limpa o HUD. */
    public static void set(String description) {
        ScreenModel model = ScreenModel.parse(description);
        atual = model == null || model.elements().isEmpty() ? null : model;
    }

    public static void register() {
        HudRenderCallback.EVENT.register((context, tickCounter) -> {
            ScreenModel model = atual;
            if (model == null) return;

            var client = MinecraftClient.getInstance();
            // Com uma tela aberta o HUD sairia por cima dela; o jogo esconde o próprio HUD aí.
            if (client.currentScreen != null || client.options.hudHidden) return;

            int largura = client.getWindow().getScaledWidth();
            int altura = client.getWindow().getScaledHeight();

            for (ScreenModel.Element element : model.elements()) {
                // Sem ancora, o HUD parte do canto superior esquerdo: e o canto natural para um
                // elemento fixo, e o que alguem espera ao escrever x = 4, y = 4.
                int x = element.x();
                int y = element.y();

                switch (element.anchor()) {
                    case "top" -> x = largura / 2 - element.w() / 2 + element.x();
                    case "top_right" -> x = largura - element.w() + element.x();
                    case "left" -> y = altura / 2 - element.h() / 2 + element.y();
                    case "right" -> {
                        x = largura - element.w() + element.x();
                        y = altura / 2 - element.h() / 2 + element.y();
                    }
                    case "bottom_left" -> y = altura - element.h() + element.y();
                    case "bottom" -> {
                        x = largura / 2 - element.w() / 2 + element.x();
                        y = altura - element.h() + element.y();
                    }
                    case "bottom_right" -> {
                        x = largura - element.w() + element.x();
                        y = altura - element.h() + element.y();
                    }
                    case "center" -> {
                        x = largura / 2 - element.w() / 2 + element.x();
                        y = altura / 2 - element.h() / 2 + element.y();
                    }
                    default -> { }
                }

                switch (element.type()) {
                    case "panel" -> context.fill(x, y, x + element.w(), y + element.h(), element.color());
                    case "label" -> context.drawTextWithShadow(client.textRenderer,
                            Text.literal(element.text()), x, y, element.color());
                    case "progress" -> {
                        context.fill(x, y, x + element.w(), y + element.h(), 0xA0303030);
                        int cheio = (int) (element.w() * Math.max(0, Math.min(1, element.progress())));
                        context.fill(x, y, x + cheio, y + element.h(), element.color());
                    }
                    case "item" -> {
                        Identifier id = Identifier.tryParse(element.item());
                        if (id != null && Registries.ITEM.containsId(id)) {
                            ItemStack stack = new ItemStack(Registries.ITEM.get(id), element.count());
                            context.drawItem(stack, x, y);
                            context.drawItemInSlot(client.textRenderer, stack, x, y);
                        }
                    }
                    default -> {
                        // Botao, campo e tipo desconhecido nao sao desenhados no HUD.
                    }
                }
            }
        });
    }
}
