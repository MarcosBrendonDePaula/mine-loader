package dev.lualoader.neoforge;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.DispenserMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

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
public class NeoForgeGhost3x3Menu extends DispenserMenu {
    /** Quantos slots do começo são fantasma. Os seguintes são o inventário do jogador. */
    private static final int GHOST_SLOTS = 9;

    public NeoForgeGhost3x3Menu(int containerId, Inventory playerInventory, Container container) {
        super(containerId, playerInventory, container);
    }

    private static boolean isGhost(int slotIndex) {
        return slotIndex >= 0 && slotIndex < GHOST_SLOTS;
    }

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!isGhost(slotId)) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        Slot slot = slots.get(slotId);
        ItemStack cursor = getCarried();

        int quantos = dev.lualoader.ui.GhostSlotRule.countAfterClick(cursor.getCount(), button == 1);
        if (quantos <= 0) {
            slot.set(ItemStack.EMPTY);
        } else {
            ItemStack desenho = cursor.copy();
            desenho.setCount(quantos);
            slot.set(desenho);
        }

        // O conteúdo mudou sem passar pelo caminho normal do jogo, então o cliente precisa ser
        // avisado à mão -- senão ele continua desenhando o slot como estava até algo mais o
        // sincronizar, e o jogador vê o clique "não funcionar".
        slot.setChanged();
        broadcastChanges();
    }

    /** Shift-clique não move nada: o slot fantasma não tirou de lugar nenhum o que mostra. */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !isGhost(slots.indexOf(slot)) && super.canDragTo(slot);
    }
}
