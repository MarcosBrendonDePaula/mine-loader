package dev.lualoader.neoforge;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.PlayerHandle;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Set;

/**
 * O mesmo contrato de jogador do adaptador Fabric, respondido com as APIs do NeoForge.
 *
 * <p>Cobertura parcial e deliberada: mensagens, inventário, posição e vida primeiro, porque são o
 * que a maioria dos mods usa. A camada de interface — telas, HUD, sobreposição — depende de um
 * protocolo de rede e de um renderizador de cliente que este adaptador ainda não tem, e por isso
 * recusa com o nome da operação em vez de fingir que abriu.
 */
public class NeoForgePlayerHandle implements PlayerHandle {
    private final ServerPlayer player;

    public NeoForgePlayerHandle(ServerPlayer player) {
        this.player = player;
    }

    private static BridgeException pending(String operation) {
        return new BridgeException(operation + " ainda nao existe no adaptador NeoForge");
    }

    private static Item requireItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    // ------------------------------------------------------------------ identidade e mensagens

    @Override
    public String name() {
        return player.getName().getString();
    }

    @Override
    public String uuid() {
        return player.getUUID().toString();
    }

    @Override
    public void sendMessage(String message) {
        player.sendSystemMessage(Component.literal(message));
    }

    @Override
    public void sendActionBar(String message) {
        player.displayClientMessage(Component.literal(message), true);
    }

    // ------------------------------------------------------------------ inventario

    @Override
    public String heldItem() {
        ItemStack stack = player.getMainHandItem();
        return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
    }

    @Override
    public int countItem(String itemId) {
        Item item = requireItem(itemId);
        int total = 0;

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }

    @Override
    public int giveItem(String itemId, int count) {
        ItemStack stack = new ItemStack(requireItem(itemId), count);
        boolean coube = player.getInventory().add(stack);

        // O que nao coube vai para o chao, e o retorno diz quanto foi: um script que checa o
        // retorno precisa saber que o item existe em algum lugar, e nao que sumiu.
        if (!coube && !stack.isEmpty()) {
            player.drop(stack, false);
            return stack.getCount();
        }
        return 0;
    }

    @Override
    public int takeItem(String itemId, int count) {
        Item item = requireItem(itemId);
        int removed = 0;

        for (int slot = 0; slot < player.getInventory().getContainerSize() && removed < count; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (!stack.is(item)) continue;

            int taken = Math.min(stack.getCount(), count - removed);
            stack.shrink(taken);
            removed += taken;
        }
        return removed;
    }

    // ------------------------------------------------------------------ corpo

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
        player.teleportTo(x, y, z);
    }

    // ------------------------------------------------------------------ interface, ainda nao

    @Override
    public void openMenu(String menuId, String title, int rows, List<String> items) {
        throw pending("open_menu");
    }

    @Override
    public boolean updateMenu(List<String> items) {
        throw pending("update_menu");
    }

    @Override
    public String openMenuId() {
        return null;
    }

    @Override
    public void closeMenu() {
        player.closeContainer();
    }

    @Override
    public boolean supportsScreens() {
        // Nao ha protocolo de tela neste adaptador. Responder false, e nao lancar, e o certo: o
        // contrato existe justamente para o mod poder escolher um caminho alternativo.
        return false;
    }

    @Override
    public boolean openScreen(String screenId, String descriptionJson) {
        return false;
    }

    @Override
    public boolean updateScreen(String descriptionJson) {
        return false;
    }

    @Override
    public void closeScreen() {
    }

    @Override
    public String openScreenId() {
        return null;
    }

    @Override
    public void setHud(String descriptionJson) {
    }

    @Override
    public boolean setOverlay(String overlayId, String descriptionJson) {
        return false;
    }

    @Override
    public boolean clearOverlay(String overlayId) {
        return false;
    }

    @Override
    public int[] screenSize() {
        return null;
    }
}
