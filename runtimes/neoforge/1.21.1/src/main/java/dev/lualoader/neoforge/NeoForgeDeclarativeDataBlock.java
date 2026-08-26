package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Bloco declarativo que guarda dados e itens na própria posição.
 *
 * <p>Existe como classe separada porque {@link EntityBlock} obriga o bloco a criar uma entidade
 * sempre: um bloco comum não deve pagar esse custo só porque outro bloco do mesmo mod precisa
 * de dados.
 */
public class NeoForgeDeclarativeDataBlock extends NeoForgeDeclarativeBlock implements EntityBlock {
    public NeoForgeDeclarativeDataBlock(BlockBehaviour.Properties properties, int declaredLuminance,
                                       net.minecraft.world.phys.shapes.VoxelShape outline,
                                       net.minecraft.world.phys.shapes.VoxelShape collision) {
        super(properties, declaredLuminance, outline, collision);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        // Antes do registro do tipo, criar a entidade lançaria; nesse ponto o bloco funciona
        // como um bloco comum, sem dados.
        if (!NeoForgeBlockEntities.isRegistered()) return null;
        return new NeoForgeDeclarativeBlockEntity(pos, state);
    }

    /**
     * Abre o inventário do bloco.
     *
     * <p>Só quando o manifesto pede. Um mod que prefere reagir ao clique no script — mostrar um
     * menu montado, cobrar algo antes de abrir — desliga {@code open_on_use} e trata o
     * {@code block_used} como qualquer outro bloco.
     */
    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide()) return InteractionResult.SUCCESS;

        if (!(level.getBlockEntity(pos) instanceof NeoForgeDeclarativeBlockEntity entity)
                || !entity.hasInventory()) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }

        ModManifest.InventoryDefinition declared = entity.inventoryDefinition();
        if (declared == null || !declared.openOnUse) {
            return super.useWithoutItem(state, level, pos, player, hit);
        }

        // Com janela declarada, a posicao vai junto: e o unico dado que o cliente precisa para
        // achar o desenho no manifesto que ele ja tem.
        if (declared.layout != null && player instanceof net.minecraft.server.level.ServerPlayer) {
            player.openMenu(entity, buffer -> buffer.writeBlockPos(pos));
        } else {
            player.openMenu(entity);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Derrama o conteúdo quando o bloco é quebrado.
     *
     * <p>Sem isto, quebrar uma máquina cheia apagaria o que estava dentro — o pior desfecho
     * possível, porque é silencioso e o jogador só descobre depois. O baú do jogo faz o mesmo.
     */
    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState,
                            boolean moved) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof NeoForgeDeclarativeBlockEntity entity
                && entity.hasInventory()) {

            ModManifest.InventoryDefinition declared = entity.inventoryDefinition();
            if (declared != null && declared.dropOnBreak && entity.contents() != null) {
                Containers.dropContents(level, pos, entity.contents());
                level.updateNeighbourForOutputSignal(pos, this);
            }
        }
        super.onRemove(state, level, pos, newState, moved);
    }
}
