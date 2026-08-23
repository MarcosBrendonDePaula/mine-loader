package dev.lualoader.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.function.BooleanBiFunction;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;

import java.util.Locale;

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

    /** Devolve a forma correspondente ao nome declarado, ou {@code null} para cubo inteiro. */
    public static VoxelShape byName(String name) {
        if (name == null || name.isBlank()) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "full_cube" -> null;
            case "slab", "slab_bottom" -> Block.createCuboidShape(0, 0, 0, 16, 8, 16);
            case "slab_top" -> Block.createCuboidShape(0, 8, 0, 16, 16, 16);
            case "carpet", "layer" -> Block.createCuboidShape(0, 0, 0, 16, 1, 16);
            case "pane", "panel" -> Block.createCuboidShape(0, 0, 7, 16, 16, 9);
            case "post", "pillar" -> Block.createCuboidShape(6, 0, 6, 10, 16, 10);
            case "plate" -> Block.createCuboidShape(1, 0, 1, 15, 1, 15);
            case "cross", "plant" -> Block.createCuboidShape(2, 0, 2, 14, 14, 14);
            case "small" -> Block.createCuboidShape(4, 0, 4, 12, 12, 12);
            case "table" -> VoxelShapes.combineAndSimplify(
                    Block.createCuboidShape(0, 12, 0, 16, 16, 16),
                    Block.createCuboidShape(2, 0, 2, 14, 12, 14),
                    BooleanBiFunction.OR);
            default -> null;
        };
    }

    /** Indica se o nome descreve uma forma conhecida diferente do cubo inteiro. */
    public static boolean isKnown(String name) {
        if (name == null || name.isBlank()) return true;
        String key = name.trim().toLowerCase(Locale.ROOT);
        return key.equals("full_cube") || byName(key) != null;
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
