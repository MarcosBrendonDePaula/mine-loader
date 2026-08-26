package dev.lualoader.minecraft;

import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

/**
 * Bloco declarativo que guarda dados na própria posição.
 *
 * <p>Existe como classe separada porque {@link BlockEntityProvider} obriga o bloco a criar uma
 * entidade sempre: um bloco comum não deve pagar esse custo só porque outro bloco do mesmo mod
 * precisa de dados.
 */
public class DeclarativeDataBlock extends DeclarativeBlock implements BlockEntityProvider {
    public DeclarativeDataBlock(Settings settings,
                                float hardness,
                                float blastResistance,
                                float slipperiness,
                                float velocityMultiplier,
                                float jumpVelocityMultiplier) {
        super(settings, hardness, blastResistance, slipperiness, velocityMultiplier, jumpVelocityMultiplier);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        // Antes do registro do tipo, criar a entidade lançaria; nesse ponto o bloco funciona
        // como um bloco comum, sem dados.
        if (!BlockEntityRegistrar.isRegistered()) return null;
        return new DeclarativeBlockEntity(pos, state);
    }

    /**
     * Abre o inventário do bloco.
     *
     * <p>Só quando o manifesto pede. Um mod que prefere reagir ao clique no script — mostrar um
     * menu montado, cobrar algo antes de abrir — desliga {@code open_on_use} e trata o
     * {@code block_used} como qualquer outro bloco.
     */
    @Override
    public net.minecraft.util.ActionResult onUse(BlockState state, net.minecraft.world.World world,
                                                 BlockPos pos,
                                                 net.minecraft.entity.player.PlayerEntity player,
                                                 net.minecraft.util.hit.BlockHitResult hit) {
        if (world.isClient()) return net.minecraft.util.ActionResult.SUCCESS;

        if (!(world.getBlockEntity(pos) instanceof DeclarativeBlockEntity entity)
                || !entity.hasInventory()) {
            return super.onUse(state, world, pos, player, hit);
        }

        var declared = entity.inventoryDefinition();
        if (declared == null || !declared.openOnUse) {
            return super.onUse(state, world, pos, player, hit);
        }

        player.openHandledScreen(entity);
        return net.minecraft.util.ActionResult.CONSUME;
    }

    /**
     * Derrama o conteúdo quando o bloco é quebrado.
     *
     * <p>Sem isto, quebrar uma máquina cheia apagaria o que estava dentro — o pior desfecho
     * possível, porque é silencioso e o jogador só descobre depois. O baú do jogo faz o mesmo.
     */
    @Override
    public void onStateReplaced(BlockState state, net.minecraft.world.World world, BlockPos pos,
                                BlockState newState, boolean moved) {
        if (!state.isOf(newState.getBlock())
                && world.getBlockEntity(pos) instanceof DeclarativeBlockEntity entity
                && entity.hasInventory()) {

            var declared = entity.inventoryDefinition();
            if (declared != null && declared.dropOnBreak) {
                net.minecraft.util.ItemScatterer.spawn(world, pos, entity.contents());
                world.updateComparators(pos, this);
            }
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }
}
