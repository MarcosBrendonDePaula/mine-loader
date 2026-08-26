package dev.lualoader.minecraft;

import dev.lualoader.content.BlockShapes;
import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;


/**
 * Formas de colisão e contorno para blocos que não são cubos inteiros.
 *
 * <p>Até aqui todo bloco declarado ocupava o cubo inteiro, o que impedia lajes, tapetes, painéis e
 * plantas. Em vez de derivar uma classe do jogo por formato, cada forma é uma caixa: isso cobre as
 * silhuetas comuns sem multiplicar subclasses, e mantém o formato como dado no manifesto.
 *
 * <p>Formatos que dependem de estado direcional, como escadas e cercas conectáveis, continuam fora:
 * eles exigem propriedades de rotação e conexão que o loader ainda não declara.
 */
public final class DeclarativeShapes {
    private DeclarativeShapes() {
    }

    /**
     * Devolve a forma correspondente ao nome declarado, ou {@code null} para cubo inteiro.
     *
     * <p>As caixas vem do nucleo, e nao daqui: a mesma definicao alimenta o modelo desenhado no
     * resource pack e a colisao das duas plataformas. Enquanto cada lado tinha a propria lista, uma
     * laje podia ter colisao de laje e aparencia de cubo inteiro -- e tinha.
     */
    public static VoxelShape byName(String name) {
        return fromBoxes(BlockShapes.byName(name));
    }

    /** Converte caixas do nucleo na forma do jogo. */
    public static VoxelShape fromBoxes(java.util.List<BlockShapes.Box> boxes) {
        if (boxes == null || BlockShapes.isFullCube(boxes)) return null;

        VoxelShape combined = null;
        for (BlockShapes.Box box : boxes) {
            VoxelShape part = Block.createCuboidShape(
                    box.fromX(), box.fromY(), box.fromZ(), box.toX(), box.toY(), box.toZ());
            combined = combined == null
                    ? part
                    : VoxelShapes.combineAndSimplify(combined, part, BooleanBiFunction.OR);
        }
        return combined;
    }

    /** A forma declarada de um bloco, ja considerando caixas proprias. */
    public static VoxelShape declared(ModManifest.ShapeDefinition shape, String name) {
        if (shape == null) return null;

        java.util.List<BlockShapes.Box> proprias = BlockShapes.fromNumbers(shape.boxes);
        return proprias != null ? fromBoxes(proprias) : byName(name);
    }

    /** Indica se o nome descreve uma forma conhecida diferente do cubo inteiro. */
    public static boolean isKnown(String name) {
        return BlockShapes.isKnown(name);
    }

    /** Bloco declarativo com forma própria. */
    public static class ShapedBlock extends DeclarativeBlock {
        private final VoxelShape shape;
        private final VoxelShape collision;

        public ShapedBlock(Settings settings,
                           float hardness,
                           float blastResistance,
                           float slipperiness,
                           float velocityMultiplier,
                           float jumpVelocityMultiplier,
                           VoxelShape outline,
                           VoxelShape collision) {
            super(settings, hardness, blastResistance, slipperiness, velocityMultiplier,
                    jumpVelocityMultiplier);
            this.shape = outline;
            this.collision = collision;
        }

        @Override
        protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                             ShapeContext context) {
            return shape == null ? super.getOutlineShape(state, world, pos, context) : shape;
        }

        @Override
        protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos,
                                               ShapeContext context) {
            // Uma forma sem colisao, como uma planta, e representada por uma caixa vazia.
            if (collision == null) return super.getCollisionShape(state, world, pos, context);
            return collision;
        }
    }
}
