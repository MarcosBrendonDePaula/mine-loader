package dev.lualoader.neoforge;

import dev.lualoader.authorization.AuthorizationEventData;
import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.ItemEventData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.function.Supplier;

/** Traduz interações NeoForge para eventos declarativos e autorizações globais. */
public final class NeoForgeInteractionEvents {
    private final Supplier<LuaRuntime> runtime;
    private final Supplier<NeoForgeGameBridge> bridge;
    private final NeoForgeContentRegistrar content;

    public NeoForgeInteractionEvents(Supplier<LuaRuntime> runtime,
                                     Supplier<NeoForgeGameBridge> bridge,
                                     NeoForgeContentRegistrar content) {
        this.runtime = runtime;
        this.bridge = bridge;
        this.content = content;
    }

    public void register(IEventBus gameBus) {
        gameBus.addListener(this::onRightClickBlock);
        gameBus.addListener(this::onLeftClickBlock);
        gameBus.addListener(this::onRightClickItem);
        gameBus.addListener(this::onPlace);
        gameBus.addListener(this::onBreak);
    }

    // ------------------------------------------------------------------ blocos

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack held = event.getItemStack();
        if (held.getItem() instanceof BlockItem blockItem) {
            BlockPos placementPos = event.getPos().relative(event.getFace());
            if (authorize("block.place", event.getLevel(), placementPos,
                    idOf(blockItem.getBlock()), event.getEntity(), event.getFace())) {
                event.setCanceled(true);
            }
            return;
        }

        // Clicar segurando um item declarado continua a disparar o callback próprio do item.
        if (isDeclaredItem(held)) {
            BlockPos pos = event.getPos();
            BlockState target = event.getLevel().getBlockState(pos);
            ItemEventData data = new ItemEventData(
                    idOf(held), idOf(target), pos.getX(), pos.getY(), pos.getZ(), true);
            if (dispatchItem("item_used_on_block", event.getLevel(), event.getEntity(), data)) {
                event.setCanceled(true);
            }
            return;
        }

        if (authorize("block.use", event.getLevel(), event.getPos(),
                idOf(event.getLevel().getBlockState(event.getPos())),
                event.getEntity(), event.getFace())) {
            event.setCanceled(true);
            return;
        }
        if (dispatchBlock("block_used", event.getLevel(), event.getPos(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (dispatchBlock("block_attacked", event.getLevel(), event.getPos(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        if (dispatchBlock("block_placed", event.getLevel(), event.getPos(), player)) {
            event.setCanceled(true);
        }
    }

    private void onBreak(BlockEvent.BreakEvent event) {
        boolean denied = authorize("block.break", event.getLevel(), event.getPos(),
                idOf(event.getState()), event.getPlayer(), null);
        boolean legacyDenied = dispatchBlock("block_broken", event.getLevel(),
                event.getPos(), event.getPlayer());
        if (denied || legacyDenied) event.setCanceled(true);
    }

    /** @return {@code true} se um autorizador Lua pediu para cancelar a ação */
    private boolean authorize(String action, LevelAccessor level, BlockPos pos, String targetId,
                              Player player, Direction face) {
        ServerLevel serverLevel = serverLevelOf(level);
        if (serverLevel == null || !(player instanceof ServerPlayer serverPlayer)) return false;
        AuthorizationEventData data = new AuthorizationEventData(
                action,
                serverLevel.dimension().location().toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                targetId,
                serverPlayer.getUUID().toString(),
                serverPlayer.getName().getString(),
                "player",
                face == null ? null : face.getName());
        return run(serverLevel,
                lua -> lua.triggerAuthorization(data, new NeoForgePlayerHandle(serverPlayer)));
    }

    /** @return {@code true} se um callback de conteúdo pediu para cancelar a ação */
    private boolean dispatchBlock(String event, LevelAccessor level, BlockPos pos, Player player) {
        ServerLevel serverLevel = serverLevelOf(level);
        if (serverLevel == null || !(player instanceof ServerPlayer serverPlayer)) return false;

        BlockState state = serverLevel.getBlockState(pos);
        boolean declarative = state.getBlock() instanceof NeoForgeDeclarativeBlock;
        if (!"block_broken".equals(event) && !declarative) return false;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;

        BlockEventData data = new BlockEventData(
                id.toString(), pos.getX(), pos.getY(), pos.getZ(),
                declarative && state.hasProperty(NeoForgeDeclarativeBlock.VARIANT)
                        ? state.getValue(NeoForgeDeclarativeBlock.VARIANT) : 0,
                content.variantCount(id));

        return run(serverLevel,
                lua -> lua.triggerBlock(event, new NeoForgePlayerHandle(serverPlayer), data));
    }

    // ------------------------------------------------------------------ itens

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack held = event.getItemStack();
        if (!isDeclaredItem(held)) return;
        ItemEventData data = new ItemEventData(idOf(held), null, 0, 0, 0, false);
        if (dispatchItem("item_used", event.getLevel(), event.getEntity(), data)) {
            event.setCanceled(true);
        }
    }

    private boolean dispatchItem(String event, LevelAccessor level, Player player, ItemEventData data) {
        ServerLevel serverLevel = serverLevelOf(level);
        if (serverLevel == null || !(player instanceof ServerPlayer serverPlayer)) return false;
        return run(serverLevel,
                lua -> lua.triggerItem(event, new NeoForgePlayerHandle(serverPlayer), data));
    }

    private boolean isDeclaredItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && content.isDeclaredItem(id);
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static String idOf(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : id.toString();
    }

    private static String idOf(net.minecraft.world.level.block.Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : id.toString();
    }

    // ------------------------------------------------------------------ execucao

    private interface Dispatch {
        boolean run(LuaRuntime runtime);
    }

    private boolean run(ServerLevel level, Dispatch dispatch) {
        LuaRuntime lua = runtime.get();
        if (lua == null) return false;
        NeoForgeGameBridge current = bridge.get();
        if (current != null) current.setCurrentLevel(level);
        try {
            return dispatch.run(lua);
        } finally {
            if (current != null) current.setCurrentLevel(null);
        }
    }

    private static ServerLevel serverLevelOf(LevelAccessor level) {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
