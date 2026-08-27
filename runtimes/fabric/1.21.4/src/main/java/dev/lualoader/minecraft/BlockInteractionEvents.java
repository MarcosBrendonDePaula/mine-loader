package dev.lualoader.minecraft;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.authorization.AuthorizationEventData;
import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.BlockEventData;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** Traduz interações Fabric para eventos declarativos e autorizações globais. */
public final class BlockInteractionEvents {
    private final LuaRuntime runtime;
    private final BlockRegistrar registrar;

    public BlockInteractionEvents(LuaRuntime runtime, BlockRegistrar registrar) {
        this.runtime = runtime;
        this.registrar = registrar;
    }

    public void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            ItemStack held = player.getStackInHand(hand);
            if (held.getItem() instanceof BlockItem blockItem) {
                BlockPos placementPos = hit.getBlockPos().offset(hit.getSide());
                String placedId = idOf(Registries.BLOCK.getId(blockItem.getBlock()));
                return authorize("block.place", world, placementPos, placedId, player,
                        hit.getSide().getName()) ? ActionResult.FAIL : ActionResult.PASS;
            }
            if (held.getItem() instanceof DeclarativeItem) return ActionResult.PASS;
            if (authorize("block.use", world, hit.getBlockPos(), null, player,
                    hit.getSide().getName())) return ActionResult.FAIL;
            return dispatch("block_used", world, hit.getBlockPos(), player)
                    ? ActionResult.FAIL : ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            return dispatch("block_attacked", world, pos, player)
                    ? ActionResult.FAIL : ActionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            boolean denied = authorize("block.break", world, pos,
                    idOf(Registries.BLOCK.getId(state.getBlock())), player, null);
            boolean legacyDenied = dispatch("block_broken", world, pos, player);
            return !(denied || legacyDenied);
        });
    }

    /** @return {@code true} se um script pediu para cancelar a autorização global */
    private boolean authorize(String action, World world, BlockPos pos, String targetId,
                              net.minecraft.entity.player.PlayerEntity player, String face) {
        if (world.isClient() || !(player instanceof ServerPlayerEntity serverPlayer)) return false;
        Identifier dimension = world.getRegistryKey().getValue();
        AuthorizationEventData data = new AuthorizationEventData(
                action,
                dimension.toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                targetId,
                serverPlayer.getUuid().toString(),
                serverPlayer.getName().getString(),
                "player",
                face);
        var bridge = LuaLoaderMod.gameBridge();
        if (bridge != null && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            bridge.setCurrentWorld(serverWorld);
        }
        try {
            return runtime.triggerAuthorization(data, new FabricPlayerHandle(serverPlayer));
        } finally {
            if (bridge != null) bridge.setCurrentWorld(null);
        }
    }

    /** @return {@code true} se um callback de conteúdo pediu para cancelar a ação */
    private boolean dispatch(String event, World world, BlockPos pos,
                             net.minecraft.entity.player.PlayerEntity player) {
        if (world.isClient()) return false;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;

        BlockState state = world.getBlockState(pos);
        DeclarativeBlock declarativeBlock = state.getBlock() instanceof DeclarativeBlock block
                ? block : null;
        if (!"block_broken".equals(event) && declarativeBlock == null) return false;

        Identifier id = Registries.BLOCK.getId(state.getBlock());
        if (id == null) return false;

        BlockEventData data = new BlockEventData(
                id.toString(), pos.getX(), pos.getY(), pos.getZ(),
                state.contains(DeclarativeBlock.LUA_VARIANT)
                        ? state.get(DeclarativeBlock.LUA_VARIANT) : 0,
                registrar.variantCount(id));
        var bridge = LuaLoaderMod.gameBridge();
        if (bridge != null && world instanceof net.minecraft.server.world.ServerWorld serverWorld) {
            bridge.setCurrentWorld(serverWorld);
        }
        try {
            return runtime.triggerBlock(event, new FabricPlayerHandle(serverPlayer), data);
        } finally {
            if (bridge != null) bridge.setCurrentWorld(null);
        }
    }

    private static String idOf(Identifier id) {
        return id == null ? null : id.toString();
    }
}
