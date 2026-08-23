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
}
