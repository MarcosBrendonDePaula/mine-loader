package dev.lualoader.minecraft;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.BlockEventData;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.item.BlockItem;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Traduz cliques e batidas em blocos declarativos nos eventos {@code block_used} e
 * {@code block_attacked} do núcleo.
 *
 * <p>Toda a lógica específica do Fabric fica aqui: o núcleo recebe apenas um
 * {@link BlockEventData} neutro.
 */
public final class BlockInteractionEvents {
    private final LuaRuntime runtime;
    private final BlockRegistrar registrar;

    public BlockInteractionEvents(LuaRuntime runtime, BlockRegistrar registrar) {
        this.runtime = runtime;
        this.registrar = registrar;
    }

    public void register() {
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            // O callback e disparado uma vez por mao; sem este filtro cada clique
            // contaria duas vezes e um ciclo de duas variantes voltaria ao inicio.
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            // Clicar segurando um bloco significa posicionar, nao usar o bloco alvo.
            if (player.getStackInHand(hand).getItem() instanceof BlockItem) return ActionResult.PASS;
            // Um script que devolve false impede a acao padrao do jogo para este clique.
            return dispatch("block_used", world, hit.getBlockPos(), player)
                    ? ActionResult.FAIL
                    : ActionResult.PASS;
        });

        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (hand != Hand.MAIN_HAND) return ActionResult.PASS;
            return dispatch("block_attacked", world, pos, player)
                    ? ActionResult.FAIL
                    : ActionResult.PASS;
        });
    }

    /** @return {@code true} se um script pediu para cancelar a acao padrao */
    private boolean dispatch(String event, World world, BlockPos pos, net.minecraft.entity.player.PlayerEntity player) {
        // Interações também chegam no cliente; o loader só reage no lado servidor.
        if (world.isClient()) return false;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;

        BlockState state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DeclarativeBlock declarativeBlock)) return false;

        Identifier id = Registries.BLOCK.getId(declarativeBlock);
        if (id == null) return false;

        BlockEventData data = new BlockEventData(
                id.toString(),
                pos.getX(),
                pos.getY(),
                pos.getZ(),
                state.get(DeclarativeBlock.LUA_VARIANT),
                registrar.variantCount(id)
        );
        // O evento carrega a dimensao em que aconteceu, para o script atuar no lugar certo.
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
}
