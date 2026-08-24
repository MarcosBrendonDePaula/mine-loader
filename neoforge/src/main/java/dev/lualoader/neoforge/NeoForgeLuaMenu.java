package dev.lualoader.neoforge;

import dev.lualoader.lua.LuaRuntime;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

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
 *
 * <p>É o par do {@code LuaMenu} do adaptador Fabric, e nasce da mesma decisão: como a tela é a do
 * baú, o menu de um mod atravessa a fronteira entre as plataformas sem nada do lado do cliente.
 * O formato de cada entrada — {@code item;quantidade;rotulo} — é o mesmo, porque quem o escreve é
 * o script, e o script é o mesmo nas duas.
 */
public class NeoForgeLuaMenu extends ChestMenu {
    private final String menuId;
    private final String modId;
    private final SimpleContainer contents;
    private final int rows;

    public NeoForgeLuaMenu(int containerId, Inventory playerInventory, SimpleContainer contents,
                           int rows, String menuId, String modId) {
        super(typeFor(rows), containerId, playerInventory, contents, rows);
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
        broadcastChanges();
    }

    private static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    @Override
    public void clicked(int slotIndex, int button, ClickType clickType, Player player) {
        // Cliques no inventário do jogador seguem o comportamento normal do jogo.
        int gridSlots = rows * 9;
        if (slotIndex < 0 || slotIndex >= gridSlots) {
            super.clicked(slotIndex, button, clickType, player);
            return;
        }

        if (!(player instanceof ServerPlayer serverPlayer)) return;
        LuaRuntime runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return;

        ItemStack clicked = contents.getItem(slotIndex);
        ResourceLocation id = clicked.isEmpty() ? null : BuiltInRegistries.ITEM.getKey(clicked.getItem());

        runtime.triggerMenuClick(
                modId,
                menuId,
                slotIndex,
                button,
                id == null ? "minecraft:air" : id.toString(),
                new NeoForgePlayerHandle(serverPlayer));

        // O clique não move item nenhum: a grade é um painel de botões.
    }

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, Slot slot) {
        return false;
    }

    /**
     * Monta o inventário exibido.
     *
     * <p>Cada entrada é {@code item;quantidade;rotulo}. O rótulo renomeia a peça, que é como um
     * botão ganha texto sem exigir uma tela desenhada.
     */
    public static SimpleContainer build(List<String> items, int rows) {
        SimpleContainer container = new SimpleContainer(rows * 9);
        fill(container, items, rows);
        return container;
    }

    private static void fill(SimpleContainer container, List<String> items, int rows) {
        container.clearContent();
        int index = 0;

        for (String line : items) {
            if (index >= rows * 9) break;
            if (line == null || line.isBlank()) {
                index++;
                continue;
            }

            String[] parts = line.split(";", 3);
            ResourceLocation id = ResourceLocation.tryParse(parts[0].trim());
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
                index++;
                continue;
            }

            int count = 1;
            if (parts.length > 1 && !parts[1].isBlank()) {
                try {
                    count = Math.max(1, Math.min(64, Integer.parseInt(parts[1].trim())));
                } catch (NumberFormatException ignored) {
                    count = 1;
                }
            }

            ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.get(id), count);
            if (parts.length > 2 && !parts[2].isBlank()) {
                stack.set(DataComponents.CUSTOM_NAME, Component.literal(parts[2].trim()));
            }
            container.setItem(index++, stack);
        }

        // Slots não preenchidos ficam vazios, e não com o item anterior.
        while (index < rows * 9) {
            container.setItem(index++, new ItemStack(Items.AIR));
        }
    }
}
