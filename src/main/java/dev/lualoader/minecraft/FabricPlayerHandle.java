package dev.lualoader.minecraft;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.PlayerHandle;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Embrulha a entidade de jogador do Fabric na referência neutra usada pelo núcleo. */
public record FabricPlayerHandle(ServerPlayerEntity player) implements PlayerHandle {
    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public String uuid() {
        return player.getUuidAsString();
    }

    @Override
    public void sendMessage(String message) {
        player.sendMessage(Text.literal(message), false);
    }

    @Override
    public void sendActionBar(String message) {
        player.sendMessage(Text.literal(message), true);
    }

    @Override
    public String heldItem() {
        ItemStack stack = player.getMainHandStack();
        if (stack.isEmpty()) return "minecraft:air";
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    @Override
    public int countItem(String itemId) {
        Item item = resolveItem(itemId);
        int total = 0;
        var inventory = player.getInventory();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() == item) total += stack.getCount();
        }
        return total;
    }

    @Override
    public int giveItem(String itemId, int count) {
        Item item = resolveItem(itemId);
        int restante = count;
        int derrubados = 0;

        while (restante > 0) {
            int lote = Math.min(restante, item.getMaxCount());
            ItemStack stack = new ItemStack(item, lote);

            player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) {
                // O que nao coube cai no mundo, para o item nao sumir em silencio, e e reportado
                // ao script, que pode querer avisar o jogador ou desfazer a operacao.
                derrubados += stack.getCount();
                player.dropItem(stack, false);
            }
            restante -= lote;
        }
        return derrubados;
    }

    @Override
    public int takeItem(String itemId, int count) {
        Item item = resolveItem(itemId);
        int restante = count;
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.size() && restante > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() != item) continue;

            int retirado = Math.min(restante, stack.getCount());
            stack.decrement(retirado);
            restante -= retirado;
            if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
        }
        return count - restante;
    }

    @Override
    public int[] position() {
        return new int[]{player.getBlockX(), player.getBlockY(), player.getBlockZ()};
    }

    @Override
    public float[] health() {
        return new float[]{player.getHealth(), player.getMaxHealth()};
    }

    @Override
    public void teleport(double x, double y, double z) {
        player.teleport(player.getServerWorld(), x, y, z,
                java.util.Set.of(), player.getYaw(), player.getPitch());
    }

    @Override
    public void openMenu(String title, int rows, java.util.List<String> items) {
        int linhas = Math.max(1, Math.min(6, rows));
        var inventory = ReadOnlyMenu.buildInventory(items, linhas);

        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) ->
                        new ReadOnlyMenu(syncId, playerInventory, inventory, linhas),
                Text.literal(title)));
    }

    @Override
    public void closeMenu() {
        player.closeHandledScreen();
    }

    private static Item resolveItem(String itemId) {
        int separator = itemId.indexOf(':');
        if (separator <= 0 || separator == itemId.length() - 1) {
            throw new BridgeException("identificador de item invalido: " + itemId);
        }
        Identifier id = Identifier.of(itemId.substring(0, separator), itemId.substring(separator + 1));
        if (!Registries.ITEM.containsId(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return Registries.ITEM.get(id);
    }
}
