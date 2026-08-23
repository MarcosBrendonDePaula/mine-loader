package dev.lualoader.minecraft;

import dev.lualoader.LuaLoaderMod;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.List;

/**
 * Janela de um mod, montada sobre a tela de container do jogo.
 *
 * <p>Reaproveitar a tela existente evita exigir um renderizador no cliente: a janela funciona em
 * qualquer cliente vanilla com o loader instalado. Os slots não são armazenamento — são botões: o
 * clique vira um evento para o script, e nada sai nem entra na grade.
 *
 * <p>É essa inversão que permite acoplar lógica a uma interface. O mod desenha o estado como itens,
 * recebe o clique com o índice do slot, decide o que fazer e redesenha.
 */
public class LuaMenu extends GenericContainerScreenHandler {
    private final String menuId;
    private final String modId;
    private final SimpleInventory contents;
    private final int rows;

    public LuaMenu(int syncId, PlayerInventory playerInventory, SimpleInventory contents,
                   int rows, String menuId, String modId) {
        super(typeFor(rows), syncId, playerInventory, contents, rows);
        this.menuId = menuId;
        this.modId = modId;
        this.contents = contents;
        this.rows = rows;
    }

    public String menuId() {
        return menuId;
    }

    /** Troca o conteúdo exibido sem fechar a tela. */
    public void replaceContents(List<String> items) {
        fill(contents, items, rows);
        // Sem isto o cliente continuaria mostrando o conteúdo anterior.
        sendContentUpdates();
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
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity player) {
        // Cliques no inventário do jogador seguem o comportamento normal do jogo.
        int gradeSlots = rows * 9;
        if (slotIndex < 0 || slotIndex >= gradeSlots) {
            super.onSlotClick(slotIndex, button, actionType, player);
            return;
        }

        if (!(player instanceof ServerPlayerEntity serverPlayer)) return;
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return;

        ItemStack clicado = contents.getStack(slotIndex);
        Identifier id = clicado.isEmpty() ? null : Registries.ITEM.getId(clicado.getItem());

        runtime.triggerMenuClick(
                modId,
                menuId,
                slotIndex,
                button,
                id == null ? "minecraft:air" : id.toString(),
                new FabricPlayerHandle(serverPlayer));

        // O clique não move item nenhum: a grade é um painel de botões.
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return false;
    }

    /**
     * Monta o inventário exibido.
     *
     * <p>Cada entrada é {@code item;quantidade;rotulo}. O rótulo renomeia a peça, que é como um
     * botão ganha texto sem exigir uma tela desenhada.
     */
    public static SimpleInventory build(List<String> items, int rows) {
        SimpleInventory inventory = new SimpleInventory(rows * 9);
        fill(inventory, items, rows);
        return inventory;
    }

    private static void fill(SimpleInventory inventory, List<String> items, int rows) {
        inventory.clear();
        int index = 0;

        for (String linha : items) {
            if (index >= rows * 9) break;
            if (linha == null || linha.isBlank()) {
                index++;
                continue;
            }

            String[] partes = linha.split(";", 3);
            Identifier id = Identifier.tryParse(partes[0].trim());
            if (id == null || !Registries.ITEM.containsId(id)) {
                index++;
                continue;
            }

            int count = 1;
            if (partes.length > 1 && !partes[1].isBlank()) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(partes[1].trim())));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }

            ItemStack stack = new ItemStack(Registries.ITEM.get(id), count);
            if (partes.length > 2 && !partes[2].isBlank()) {
                stack.set(net.minecraft.component.DataComponentTypes.CUSTOM_NAME,
                        Text.literal(partes[2].trim()));
            }
            inventory.setStack(index++, stack);
        }

        // Slots não preenchidos ficam vazios, e não com o item anterior.
        while (index < rows * 9) {
            inventory.setStack(index++, new ItemStack(Items.AIR));
        }
    }
}
