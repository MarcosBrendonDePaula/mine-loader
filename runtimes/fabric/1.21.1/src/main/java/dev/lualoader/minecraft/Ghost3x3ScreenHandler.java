package dev.lualoader.minecraft;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;

/**
 * A janela 3x3 de um inventário <b>fantasma</b>.
 *
 * <p>Mesma regra da janela de fileiras, outro formato: aqui os nove slots aparecem em três por três,
 * que é a forma que dá sentido a um padrão de bancada. Nove slots numa fileira única não dizem "a
 * espada é duas barras em cima de um graveto" — o jogador olha e não tem como desenhar.
 *
 * <p><b>Por que uma classe separada.</b> O tipo de janela do jogo é uma classe diferente para cada
 * formato, e não dá para herdar as duas. A decisão que elas compartilham — o que o clique faz —
 * mora no núcleo, em {@code GhostSlotRule}, justamente para as quatro implementações não
 * divergirem.
 */
public class Ghost3x3ScreenHandler extends Generic3x3ContainerScreenHandler {
    /** Quantos slots do começo são fantasma. Os seguintes são o inventário do jogador. */
    private static final int GHOST_SLOTS = 9;

    public Ghost3x3ScreenHandler(int syncId, PlayerInventory playerInventory, Inventory inventory) {
        super(syncId, playerInventory, inventory);
    }

    private static boolean isGhost(int slotIndex) {
        return slotIndex >= 0 && slotIndex < GHOST_SLOTS;
    }

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

    /** Shift-clique não move nada: o slot fantasma não tirou de lugar nenhum o que mostra. */
    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return !isGhost(slots.indexOf(slot)) && super.canInsertIntoSlot(stack, slot);
    }
}
