package dev.lualoader.client;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.minecraft.DeclaredMenus;
import dev.lualoader.minecraft.DeclaredScreenHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/**
 * A tela de uma janela declarada: a arte que o mod trouxe, os slots do jogo e os botões do manifesto.
 *
 * <p><b>Uma tela para todos os blocos.</b> O que muda é o manifesto que ela lê — a mesma ideia da
 * tela genérica do loader, que interpreta dados em vez de existir uma por mod. Um mod novo não exige
 * código de cliente novo.
 *
 * <p>Herdar de {@link HandledScreen} é o ponto: os slots, o arrastar, o shift-clique e o item no
 * cursor são do jogo, e não imitações. A camada de tela do loader desenha dados muito bem e <b>não
 * mexe em itens</b> — tentar o contrário foi o que produziu uma tela que o jogador chamou de
 * inutilizável.
 */
public class DeclaredContainerScreen extends HandledScreen<DeclaredScreenHandler> {
    /** O desenho declarado, achado pelo bloco que a janela abriu. */
    private ModManifest.LayoutDefinition layout;

    /** A folha de fundo, quando o mod declarou uma. */
    private Identifier folha;

    public DeclaredContainerScreen(DeclaredScreenHandler handler, PlayerInventory inventory,
                                   Text title) {
        super(handler, inventory, title);
    }

    @Override
    protected void init() {
        ModManifest.InventoryDefinition declarado = client == null || client.world == null
                ? null
                : DeclaredMenus.inventoryOf(client.world, handler.pos());

        layout = declarado == null ? null : declarado.layout;
        if (layout != null) {
            backgroundWidth = layout.width;
            backgroundHeight = layout.height;

            // O rótulo do inventário do jogador vai onde os slots dele estão, e não onde a tela do
            // baú o coloca: a posição é declarada, e um rótulo fixo cairia no meio dos slots.
            if (layout.player != null) {
                playerInventoryTitleY = layout.player.y - 12;
            }

            String caminho = DeclaredMenus.textureOf(client.world, handler.pos());
            if (caminho != null) folha = Identifier.tryParse(caminho);
        }

        // `init` do pai calcula a posição da janela com o tamanho acima, então ele vem depois.
        super.init();

        if (layout == null) return;

        for (ModManifest.ButtonDefinition botao : layout.buttons) {
            if (botao == null || botao.id == null) continue;

            String id = botao.id;
            ButtonWidget widget = ButtonWidget
                    .builder(Text.literal(botao.text == null ? id : botao.text),
                            pressed -> DeclaredScreenEvents.send(handler.pos(), id))
                    .dimensions(x + botao.x, y + botao.y, botao.w, botao.h)
                    .build();
            if (botao.tooltip != null && !botao.tooltip.isBlank()) {
                widget.setTooltip(net.minecraft.client.gui.tooltip.Tooltip
                        .of(Text.literal(botao.tooltip)));
            }
            addDrawableChild(widget);
        }
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        if (folha != null) {
            context.drawTexture(folha, x, y, 0, 0, backgroundWidth, backgroundHeight,
                    backgroundWidth, backgroundHeight);
            return;
        }

        // Sem folha declarada, a janela do jogo desenhada por regra -- o mesmo painel que o resto do
        // loader usa. Um mod que não trouxe arte ainda tem uma tela apresentável.
        ScreenRenderer.vanillaPanel(context, x, y, backgroundWidth, backgroundHeight);

        // E o encaixe de cada slot, para o item não ficar solto no ar.
        if (layout == null) return;

        for (ModManifest.SlotDefinition slot : layout.slots) {
            if (slot == null) continue;
            ScreenRenderer.slotWell(context, x + slot.x - 1, y + slot.y - 1, 18, 18);
        }
        if (layout.player != null) {
            for (int linha = 0; linha < 3; linha++) {
                for (int coluna = 0; coluna < 9; coluna++) {
                    ScreenRenderer.slotWell(context, x + layout.player.x + coluna * 18 - 1,
                            y + layout.player.y + linha * 18 - 1, 18, 18);
                }
            }
            for (int coluna = 0; coluna < 9; coluna++) {
                ScreenRenderer.slotWell(context, x + layout.player.x + coluna * 18 - 1,
                        y + layout.player.y + 58 - 1, 18, 18);
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        drawMouseoverTooltip(context, mouseX, mouseY);
    }
}
