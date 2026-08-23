package dev.lualoader.minecraft;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;

/**
 * Menu de itens somente leitura, montado sobre a tela de container do jogo.
 *
 * <p>Reaproveitar a tela existente evita exigir um renderizador novo no cliente: o menu funciona em
 * qualquer cliente vanilla com o loader instalado. Em troca, o conteúdo é apenas exibido — o
 * jogador não retira nem insere itens, e a interação é reportada ao mod pelo índice do slot.
 */
public class ReadOnlyMenu extends GenericContainerScreenHandler {
    public ReadOnlyMenu(int syncId, PlayerInventory playerInventory, Inventory inventory, int rows) {
        super(typeFor(rows), syncId, playerInventory, inventory, rows);
    }

    private static ScreenHandlerType<GenericContainerScreenHandler> typeFor(int rows) {
        return switch (rows) {
            case 1 -> ScreenHandlerType.GENERIC_9X1;
            case 2 -> ScreenHandlerType.GENERIC_9X2;
            case 3 -> ScreenHandlerType.GENERIC_9X3;
            case 4 -> ScreenHandlerType.GENERIC_9X4;
            case 5 -> ScreenHandlerType.GENERIC_9X5;
            default -> ScreenHandlerType.GENERIC_9X6;
        };
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        // Shift-clique moveria o item exibido para o inventario; o menu e apenas visual.
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return false;
    }

    /** Constrói um inventário simples a partir das linhas {@code item;quantidade}. */
    public static Inventory buildInventory(java.util.List<String> items, int rows) {
        SimpleInventory inventory = new SimpleInventory(rows * 9);
        int index = 0;
        for (String linha : items) {
            if (index >= inventory.size()) break;
            String[] partes = linha.split(";");
            if (partes.length == 0 || partes[0].isBlank()) {
                index++;
                continue;
            }
            var id = net.minecraft.util.Identifier.tryParse(partes[0]);
            if (id == null || !net.minecraft.registry.Registries.ITEM.containsId(id)) {
                index++;
                continue;
            }
            int count = 1;
            if (partes.length > 1) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(partes[1].trim())));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }
            inventory.setStack(index++, new ItemStack(net.minecraft.registry.Registries.ITEM.get(id), count));
        }
        return inventory;
    }
}
