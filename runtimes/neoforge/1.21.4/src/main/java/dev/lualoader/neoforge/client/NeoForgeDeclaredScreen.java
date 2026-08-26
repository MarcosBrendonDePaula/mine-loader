package dev.lualoader.neoforge.client;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.neoforge.NeoForgeDeclaredMenu;
import dev.lualoader.neoforge.NeoForgeDeclaredMenus;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;

/**
 * A tela de uma janela declarada: a arte que o mod trouxe, os slots do jogo e os botões do manifesto.
 *
 * <p><b>Uma tela para todos os blocos.</b> O que muda é o manifesto que ela lê — a mesma ideia da
 * tela genérica do loader, que interpreta dados em vez de existir uma por mod. Um mod novo não exige
 * código de cliente novo.
 *
 * <p>Herdar de {@link AbstractContainerScreen} é o ponto: os slots, o arrastar, o shift-clique e o
 * item no cursor são do jogo, e não imitações. A camada de tela do loader desenha dados muito bem e
 * <b>não mexe em itens</b> — tentar o contrário foi o que produziu uma tela que o jogador chamou de
 * inutilizável.
 */
public class NeoForgeDeclaredScreen extends AbstractContainerScreen<NeoForgeDeclaredMenu> {
    /** O desenho declarado, achado pelo bloco que a janela abriu. */
    private ModManifest.LayoutDefinition layout;

    /** A folha de fundo, quando o mod declarou uma. */
    private ResourceLocation folha;

    public NeoForgeDeclaredScreen(NeoForgeDeclaredMenu menu, Inventory playerInventory,
                                  Component title) {
        super(menu, playerInventory, title);
    }

    @Override
    protected void init() {
        ModManifest.InventoryDefinition inventory = minecraft == null || minecraft.level == null
                ? null
                : NeoForgeDeclaredMenus.inventoryOf(minecraft.level, menu.pos());

        layout = inventory == null ? null : inventory.layout;
        if (layout != null) {
            imageWidth = layout.width;
            imageHeight = layout.height;

            // O rótulo do inventário do jogador vai onde os slots dele estão, e não onde a tela do
            // baú o coloca: a posição é declarada, e um rótulo fixo cairia no meio dos slots.
            if (layout.player != null) {
                inventoryLabelY = layout.player.y - 12;
            }

            String caminho = NeoForgeDeclaredMenus.textureOf(menu.pos(), minecraft.level);
            if (caminho != null) folha = ResourceLocation.tryParse(caminho);
        }

        // `init` do pai calcula a posição da janela com o tamanho acima, então ele vem depois.
        super.init();

        if (layout == null) return;

        for (ModManifest.ButtonDefinition botao : layout.buttons) {
            if (botao == null || botao.id == null) continue;

            String id = botao.id;
            Button widget = Button.builder(Component.literal(botao.text == null ? id : botao.text),
                            pressed -> NeoForgeDeclaredScreenEvents.send(menu.pos(), id))
                    .bounds(leftPos + botao.x, topPos + botao.y, botao.w, botao.h)
                    .build();
            if (botao.tooltip != null && !botao.tooltip.isBlank()) {
                widget.setTooltip(net.minecraft.client.gui.components.Tooltip
                        .create(Component.literal(botao.tooltip)));
            }
            addRenderableWidget(widget);
        }
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float delta, int mouseX, int mouseY) {
        if (folha != null) {
            graphics.blit(net.minecraft.client.renderer.RenderType::guiTextured,
                    folha, leftPos, topPos, 0, 0, imageWidth, imageHeight,
                    imageWidth, imageHeight);
            return;
        }

        // Sem folha declarada, a janela do jogo desenhada por regra -- o mesmo painel que o resto do
        // loader usa. Um mod que não trouxe arte ainda tem uma tela apresentável.
        NeoForgeScreenRenderer.vanillaPanel(graphics, leftPos, topPos, imageWidth, imageHeight);

        // E o encaixe de cada slot, para o item não ficar solto no ar.
        if (layout != null) {
            for (ModManifest.SlotDefinition slot : layout.slots) {
                if (slot == null) continue;
                NeoForgeScreenRenderer.slotWell(graphics, leftPos + slot.x - 1,
                        topPos + slot.y - 1, 18, 18);
            }
            if (layout.player != null) {
                for (int linha = 0; linha < 3; linha++) {
                    for (int coluna = 0; coluna < 9; coluna++) {
                        NeoForgeScreenRenderer.slotWell(graphics,
                                leftPos + layout.player.x + coluna * 18 - 1,
                                topPos + layout.player.y + linha * 18 - 1, 18, 18);
                    }
                }
                for (int coluna = 0; coluna < 9; coluna++) {
                    NeoForgeScreenRenderer.slotWell(graphics,
                            leftPos + layout.player.x + coluna * 18 - 1,
                            topPos + layout.player.y + 58 - 1, 18, 18);
                }
            }
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float delta) {
        renderBackground(graphics, mouseX, mouseY, delta);
        super.render(graphics, mouseX, mouseY, delta);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
