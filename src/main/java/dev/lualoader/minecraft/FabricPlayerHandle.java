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
        int remaining = count;
        int dropped = 0;

        while (remaining > 0) {
            int batch = Math.min(remaining, item.getMaxCount());
            ItemStack stack = new ItemStack(item, batch);

            player.getInventory().insertStack(stack);
            if (!stack.isEmpty()) {
                // O que nao coube cai no mundo, para o item nao sumir em silencio, e e reportado
                // ao script, que pode querer avisar o jogador ou desfazer a operacao.
                dropped += stack.getCount();
                player.dropItem(stack, false);
            }
            remaining -= batch;
        }
        return dropped;
    }

    @Override
    public int takeItem(String itemId, int count) {
        Item item = resolveItem(itemId);
        int remaining = count;
        var inventory = player.getInventory();

        for (int slot = 0; slot < inventory.size() && remaining > 0; slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack.getItem() != item) continue;

            int removed = Math.min(remaining, stack.getCount());
            stack.decrement(removed);
            remaining -= removed;
            if (stack.isEmpty()) inventory.setStack(slot, ItemStack.EMPTY);
        }
        return count - remaining;
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
    public void openMenu(String menuId, String title, int rows, java.util.List<String> items) {
        int lines = Math.max(1, Math.min(6, rows));
        var content = LuaMenu.build(items, lines);
        String modId = menuId.contains(":") ? menuId.substring(0, menuId.indexOf(':')) : menuId;

        player.openHandledScreen(new net.minecraft.screen.SimpleNamedScreenHandlerFactory(
                (syncId, playerInventory, ignored) ->
                        new LuaMenu(syncId, playerInventory, content, lines, menuId, modId),
                Text.literal(title)));
    }

    @Override
    public boolean updateMenu(java.util.List<String> items) {
        if (!(player.currentScreenHandler instanceof LuaMenu menu)) return false;
        menu.replaceContents(items);
        return true;
    }

    @Override
    public String openMenuId() {
        return player.currentScreenHandler instanceof LuaMenu menu ? menu.menuId() : null;
    }

    @Override
    public void closeMenu() {
        player.closeHandledScreen();
    }

    // --- Telas desenhadas ------------------------------------------------------------------

    /** Tela do loader aberta por jogador. O servidor precisa saber o que cada um esta vendo. */
    private static final java.util.Map<java.util.UUID, String> TELAS_ABERTAS =
            new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public boolean supportsScreens() {
        return dev.lualoader.network.ScreenNetwork.supports(player);
    }

    @Override
    public boolean openScreen(String screenId, String descriptionJson) {
        if (!supportsScreens()) {
            dev.lualoader.LuaLoaderMod.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; {} nao foi aberta",
                    player.getName().getString(), screenId);
            return false;
        }
        dev.lualoader.LuaLoaderMod.LOGGER.info("Enviando tela {} para {} ({} caracteres)",
                screenId, player.getName().getString(), descriptionJson.length());

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.OpenScreen(
                        dev.lualoader.ui.ScreenProtocol.VERSION, screenId, descriptionJson));
        TELAS_ABERTAS.put(player.getUuid(), screenId);
        return true;
    }

    @Override
    public boolean updateScreen(String descriptionJson) {
        if (!supportsScreens() || TELAS_ABERTAS.get(player.getUuid()) == null) return false;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.UpdateScreen(descriptionJson));
        return true;
    }

    @Override
    public void closeScreen() {
        TELAS_ABERTAS.remove(player.getUuid());
        if (!supportsScreens()) return;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.CloseScreen(""));
    }

    @Override
    public String openScreenId() {
        return TELAS_ABERTAS.get(player.getUuid());
    }

    /** Chamado quando o cliente avisa que fechou a tela. */
    public static void forgetScreen(java.util.UUID playerId) {
        TELAS_ABERTAS.remove(playerId);
    }

    @Override
    public void setHud(String descriptionJson) {
        if (!supportsScreens()) return;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.SetHud(descriptionJson));
    }

    @Override
    public boolean setOverlay(String overlayId, String descriptionJson) {
        if (!supportsScreens()) {
            dev.lualoader.LuaLoaderMod.LOGGER.warn(
                    "Cliente de {} nao registrou o canal de telas; a sobreposicao {} nao foi enviada",
                    player.getName().getString(), overlayId);
            return false;
        }
        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.SetOverlay(
                        dev.lualoader.ui.ScreenProtocol.VERSION, overlayId, descriptionJson));
        return true;
    }

    @Override
    public boolean clearOverlay(String overlayId) {
        if (!supportsScreens()) return false;

        net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking.send(player,
                new dev.lualoader.network.ScreenPayloads.ClearOverlay(overlayId));
        return true;
    }

    /** Tamanho de tela informado por cada cliente, em unidades de interface. */
    private static final java.util.Map<java.util.UUID, int[]> TAMANHOS_DE_TELA =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Chamado quando o cliente informa o tamanho, ao entrar e a cada mudanca de escala. */
    public static void rememberScreenSize(java.util.UUID playerId, int width, int height) {
        TAMANHOS_DE_TELA.put(playerId, new int[]{width, height});
    }

    public static void forgetScreenSize(java.util.UUID playerId) {
        TAMANHOS_DE_TELA.remove(playerId);
    }

    @Override
    public int[] screenSize() {
        int[] size = TAMANHOS_DE_TELA.get(player.getUuid());
        return size == null ? null : size.clone();
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
