package dev.lualoader.minecraft;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * A janela de um inventário <b>fantasma</b>: os slots mostram um item sem guardar item nenhum.
 *
 * <p>Serve para desenhar uma intenção — um padrão de receita, um filtro, uma lista de itens
 * desejados. Clicar com um item no cursor copia a <i>identidade</i> dele para o slot e devolve o
 * cursor intacto; mão vazia limpa. <b>Nada é consumido, nunca.</b>
 *
 * <p>A regra é a do {@code DummySlot} do Logistic Pipes, que é onde esta lacuna apareceu: ele monta
 * o padrão de fabricação com slots que recusam {@code canTakeStack} e têm limite de pilha zero, e
 * reescreve o clique para copiar em vez de mover. Sem isso um mod declarativo tem duas saídas ruins
 * — exigir que o jogador <i>gaste</i> um item de cada tipo para desenhar a receita, ou inventar um
 * gesto próprio numa tela sem slot.
 *
 * <p><b>Por que herdar da janela de baú em vez de registrar um tipo próprio.</b> O tipo é o que o
 * cliente usa para escolher a tela que desenha; um tipo novo exigiria uma tela nova em cada
 * plataforma e não funcionaria em cliente sem o mod. Herdando, o desenho continua sendo o do jogo e
 * só o <i>comportamento</i> do clique muda — que é a parte que o servidor decide sozinho.
 */
public class GhostContainerScreenHandler extends GenericContainerScreenHandler {
    /** Quantos slots do começo são fantasma. Os seguintes são o inventário do jogador. */
    private final int ghostSlots;

    public GhostContainerScreenHandler(ScreenHandlerType<GenericContainerScreenHandler> type,
                                       int syncId, PlayerInventory playerInventory,
                                       Inventory inventory, int rows) {
        super(type, syncId, playerInventory, inventory, rows);
        this.ghostSlots = rows * 9;
    }

    private boolean isGhost(int slotIndex) {
        return slotIndex >= 0 && slotIndex < ghostSlots;
    }

    /**
     * O clique num slot fantasma copia, e não move.
     *
     * <p>As regras são as do mod original, e cada uma existe por um gesto que o jogador espera:
     *
     * <ul>
     *   <li>com item no cursor, botão esquerdo desenha uma pilha e o direito desenha <b>um</b>;
     *   <li>mão vazia limpa o slot — é o gesto de tirar da bancada;
     *   <li>o cursor volta intacto em todos os casos, porque nada saiu dele.
     * </ul>
     *
     * <p>O clique fora da janela ({@code slotIndex} negativo) segue para o jogo: é o gesto de jogar
     * fora o que está no cursor, e ele não tem nada a ver com o slot fantasma.
     */
    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                            PlayerEntity player) {
        if (!isGhost(slotIndex)) {
            super.onSlotClick(slotIndex, button, actionType, player);
            return;
        }

        Slot slot = slots.get(slotIndex);
        ItemStack cursor = getCursorStack();

        int quantos = dev.lualoader.ui.GhostSlotRule.countAfterClick(cursor.getCount(), button == 1);
        if (quantos <= 0) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            ItemStack desenho = cursor.copy();
            desenho.setCount(quantos);
            slot.setStack(desenho);
        }

        // O conteúdo mudou sem passar pelo caminho normal do jogo, então o cliente precisa ser
        // avisado à mão -- senão ele continua desenhando o slot como estava até algo mais o
        // sincronizar, e o jogador vê o clique "não funcionar".
        slot.markDirty();
        sendContentUpdates();
    }

    /**
     * Shift-clique não move nada, nos dois sentidos.
     *
     * <p>Sem isto o gesto mais natural do jogo esvaziaria o desenho para o inventário do jogador —
     * ou pior, <b>criaria</b> itens, porque o slot fantasma não tirou de lugar nenhum o que mostra.
     */
    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    /**
     * Nenhum slot fantasma aceita item arrastado.
     *
     * <p>O arrastar do jogo divide uma pilha entre vários slots, e não faz sentido aqui: o slot não
     * guarda quantidade real. Recusar deixa o gesto simplesmente não acontecer, em vez de espalhar
     * itens de verdade por slots que não os guardam.
     */
    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return !isGhost(slots.indexOf(slot)) && super.canInsertIntoSlot(stack, slot);
    }
}
