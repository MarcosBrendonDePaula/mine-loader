package dev.lualoader.neoforge;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * O mesmo contrato do adaptador Fabric, respondido com as APIs do NeoForge.
 *
 * <p>É aqui que a aposta do projeto se comprova ou não. O núcleo pede "os itens que este bloco
 * guarda" sem saber que no Fabric isso é {@code Storage<ItemVariant>} e aqui é
 * {@code IItemHandler}. Um mod Lua escrito para o loader roda nos dois sem mudar uma linha, porque
 * a diferença mora inteira neste arquivo.
 *
 * <p>Cobertura parcial, e de propósito: as operações centrais primeiro, para o caminho ser
 * verificável cedo. O que ainda não existe recusa com mensagem clara em vez de responder errado.
 */
public class NeoForgeGameBridge implements GameBridge {
    private MinecraftServer server;
    private ServerLevel currentLevel;

    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    /** Define em que dimensão as operações seguintes agem. */
    public void setCurrentLevel(ServerLevel level) {
        this.currentLevel = level;
    }

    private MinecraftServer requireServer() {
        if (server == null) throw new BridgeException("servidor ainda nao iniciou");
        return server;
    }

    private ServerLevel requireLevel() {
        if (currentLevel != null) return currentLevel;

        // Fora de um evento, o overworld: e o comportamento previsivel quando o script nao disse
        // onde atuar. A mesma escolha do adaptador Fabric, e pelo mesmo motivo.
        return requireServer().overworld();
    }

    private static ResourceLocation parse(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) throw new BridgeException("identificador invalido: " + id);
        return parsed;
    }

    // ------------------------------------------------------------------ servidor e mundo

    @Override
    public void broadcast(String message) {
        requireServer().getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    @Override
    public boolean isWorldAvailable() {
        return server != null;
    }

    @Override
    public List<String> onlinePlayers() {
        List<String> names = new ArrayList<>();
        for (var player : requireServer().getPlayerList().getPlayers()) {
            names.add(player.getName().getString());
        }
        return names;
    }

    @Override
    public long timeOfDay() {
        return requireLevel().getDayTime() % 24000L;
    }

    @Override
    public String worldName() {
        return requireLevel().dimension().location().toString();
    }

    @Override
    public String getBlock(int x, int y, int z) {
        BlockState state = requireLevel().getBlockState(new BlockPos(x, y, z));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    @Override
    public void setBlock(String blockId, int x, int y, int z) {
        Block block = requireBlock(blockId);
        requireLevel().setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState());
    }

    @Override
    public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
        Block block = requireBlock(blockId);
        ServerLevel level = requireLevel();
        int changed = 0;

        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState());
                    changed++;
                }
            }
        }
        return changed;
    }

    private static Block requireBlock(String blockId) {
        ResourceLocation id = parse(blockId);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new BridgeException("bloco desconhecido: " + blockId);
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    // ------------------------------------------------------------------ registro do jogo

    @Override
    public List<String> registeredItems(String namespace, String contains, int limit) {
        List<String> found = new ArrayList<>();

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (namespace != null && !id.getNamespace().equals(namespace)) continue;
            if (contains != null && !contains.isBlank() && !id.getPath().contains(contains)) continue;
            found.add(id.toString());
        }

        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    // ------------------------------------------------------------------ capacidades

    /**
     * O inventario de um bloco, pelo contrato do NeoForge.
     *
     * <p>Onde o Fabric publica {@code Storage<ItemVariant>}, aqui a mesma ideia se chama
     * {@code IItemHandler} e chega por capability. O nucleo nao vê nenhum dos dois nomes.
     */
    private IItemHandler itemHandlerAt(int x, int y, int z) {
        return requireLevel().getCapability(Capabilities.ItemHandler.BLOCK, new BlockPos(x, y, z), null);
    }

    @Override
    public Set<String> capabilitiesAt(int x, int y, int z) {
        Set<String> found = new LinkedHashSet<>();
        if (itemHandlerAt(x, y, z) != null) found.add("items");
        return found;
    }

    @Override
    public List<String> containerAt(int x, int y, int z) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        List<String> contents = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            contents.add(slot + ";"
                    + BuiltInRegistries.ITEM.getKey(stack.getItem()) + ";" + stack.getCount());
        }
        return contents;
    }

    @Override
    public int insertInto(int x, int y, int z, String itemId, int count) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        ItemStack remaining = new ItemStack(requireItem(itemId), count);
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
        }
        return remaining.getCount();
    }

    @Override
    public int extractFrom(int x, int y, int z, String itemId, int count) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        Item wanted = requireItem(itemId);
        int taken = 0;

        for (int slot = 0; slot < handler.getSlots() && taken < count; slot++) {
            if (!handler.getStackInSlot(slot).is(wanted)) continue;

            ItemStack removed = handler.extractItem(slot, count - taken, false);
            taken += removed.getCount();
        }
        return taken;
    }

    private static Item requireItem(String itemId) {
        ResourceLocation id = parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    // ------------------------------------------------------------------ ainda nao implementado

    /**
     * Operações que este adaptador ainda não cobre.
     *
     * <p>Recusam com o nome da operação em vez de devolver vazio. Um mod que dependa delas descobre
     * na primeira chamada, e não por um comportamento estranho meia hora depois.
     */
    private static BridgeException pending(String operation) {
        return new BridgeException(operation + " ainda nao existe no adaptador NeoForge");
    }

    @Override
    public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
        throw pending("set_block_variant");
    }

    @Override
    public void setBlockProperty(String blockId, String property, float value) {
        throw pending("set_block_property");
    }

    @Override
    public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
        throw pending("set_block_luminance");
    }

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
        throw pending("play_sound");
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread) {
        throw pending("spawn_particles");
    }

    @Override
    public String getBlockData(int x, int y, int z) {
        throw pending("get_block_data");
    }

    @Override
    public void setBlockData(int x, int y, int z, String json) {
        throw pending("set_block_data");
    }

    @Override
    public String spawnEntity(String entityId, double x, double y, double z) {
        throw pending("spawn_entity");
    }

    @Override
    public List<String> entitiesNear(double x, double y, double z, double radius) {
        throw pending("entities_near");
    }

    @Override
    public boolean removeEntity(String entityUuid) {
        throw pending("remove_entity");
    }

    @Override
    public boolean damageEntity(String entityUuid, float amount) {
        throw pending("damage_entity");
    }

    @Override
    public List<String> recipesFor(String itemId, int limit) {
        throw pending("recipes_for");
    }

    @Override
    public List<String> recipesUsing(String itemId, int limit) {
        throw pending("recipes_using");
    }

    @Override
    public List<String> dropsOf(String sourceId, int limit) {
        throw pending("drops_of");
    }

    @Override
    public List<String> droppedBy(String itemId, int limit) {
        throw pending("dropped_by");
    }
}
