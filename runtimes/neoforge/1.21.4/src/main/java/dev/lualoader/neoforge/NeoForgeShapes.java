package dev.lualoader.neoforge;

import dev.lualoader.content.BlockShapes;
import dev.lualoader.manifest.ModManifest;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * Converte as formas declaradas no manifesto para as do jogo.
 *
 * <p>As caixas vêm do núcleo, e não daqui. É a mesma lista que alimenta o modelo desenhado no
 * resource pack e a colisão do adaptador Fabric — enquanto cada lado tinha a própria, uma laje
 * podia ter colisão de laje e aparência de cubo inteiro, e tinha.
 *
 * <p>Até agora este adaptador ignorava {@code shape} por completo: um bloco declarado como mesa
 * tinha colisão de cubo, e o jogador não conseguia andar por baixo dele. O núcleo já sabia a
 * resposta; faltava alguém perguntar.
 */
public final class NeoForgeShapes {
    private NeoForgeShapes() {
    }

    /** A forma declarada, ou {@code null} quando é o cubo inteiro e o padrão do jogo serve. */
    public static VoxelShape declared(ModManifest.ShapeDefinition shape, String name) {
        if (shape == null) return null;

        // Caixas proprias ganham do nome: quem as escreveu foi especifico de proposito.
        List<BlockShapes.Box> boxes = BlockShapes.fromNumbers(shape.boxes);
        if (boxes == null) boxes = BlockShapes.byName(name);

        return fromBoxes(boxes);
    }

    /** Converte caixas do núcleo na forma do jogo. */
    public static VoxelShape fromBoxes(List<BlockShapes.Box> boxes) {
        if (boxes == null || BlockShapes.isFullCube(boxes)) return null;

        VoxelShape combined = null;
        for (BlockShapes.Box box : boxes) {
            VoxelShape part = Block.box(
                    box.fromX(), box.fromY(), box.fromZ(), box.toX(), box.toY(), box.toZ());
            combined = combined == null
                    ? part
                    : Shapes.joinUnoptimized(combined, part, BooleanOp.OR);
        }
        return combined == null ? null : combined.optimize();
    }
}
