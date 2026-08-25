package dev.lualoader.minecraft;

import dev.lualoader.content.BlockShapes;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Um bloco que cresce braços em direção aos vizinhos: cano, cerca, muro, vidraça.
 *
 * <p>Era a lacuna mais estruturante que a migração do Logistic Pipes encontrou. Antes disto,
 * {@code shape} era declarado uma vez e valia para todos os estados: uma rede de canos ficava
 * sendo peças soltas encostadas, e a diferença para o mod original era gritante.
 *
 * <p><b>A colisão acompanha o desenho.</b> É a parte que não pode ser esquecida: uma forma que fica
 * só no visual deixa o jogador ver o braço e atravessá-lo, e este repositório já registra esse tipo
 * de divergência como o pior defeito de forma declarada — parece o jogo quebrado, e não um recurso
 * faltando.
 */
public class ConnectedBlock extends DeclarativeBlock {
    private final BlockShapes.Box core;
    private final BlockShapes.Box arm;

    /** Ids de bloco a que este se conecta. */
    private final Set<Identifier> blockIds;

    /** Tags de bloco a que este se conecta, já sem o {@code #}. */
    private final Set<TagKey<Block>> tags;

    /**
     * A forma pronta de cada combinação de lados.
     *
     * <p>Calculada uma vez por estado e guardada: {@code getOutlineShape} é chamado a cada quadro
     * para cada bloco visível, e montar a caixa ali dentro faria a rede inteira pagar aritmética
     * sessenta vezes por segundo.
     */
    private final Map<String, VoxelShape> shapeCache = new java.util.concurrent.ConcurrentHashMap<>();

    public ConnectedBlock(Settings settings,
                          float hardness, float resistance, float slipperiness,
                          float velocityMultiplier, float jumpVelocityMultiplier,
                          BlockShapes.Box core, BlockShapes.Box arm, List<String> connectsTo) {
        super(settings, hardness, resistance, slipperiness,
                velocityMultiplier, jumpVelocityMultiplier);

        this.core = core;
        this.arm = arm;

        Set<Identifier> ids = new LinkedHashSet<>();
        Set<TagKey<Block>> tagKeys = new LinkedHashSet<>();
        for (String entry : connectsTo == null ? List.<String>of() : connectsTo) {
            if (entry == null || entry.isBlank()) continue;

            if (entry.startsWith("#")) {
                Identifier parsed = Identifier.tryParse(entry.substring(1));
                if (parsed != null) tagKeys.add(TagKey.of(RegistryKeys.BLOCK, parsed));
            } else {
                Identifier parsed = Identifier.tryParse(entry);
                if (parsed != null) ids.add(parsed);
            }
        }
        this.blockIds = Set.copyOf(ids);
        this.tags = Set.copyOf(tagKeys);

        setDefaultState(withAllDisconnected(getDefaultState()));
    }

    /** As propriedades booleanas, por direção, resolvidas uma vez. */
    private static final Map<Direction, String> SIDE_OF = new EnumMap<>(Map.of(
            Direction.NORTH, "north", Direction.SOUTH, "south",
            Direction.WEST, "west", Direction.EAST, "east",
            Direction.UP, "up", Direction.DOWN, "down"));

    private BooleanProperty propertyOf(String side) {
        return (BooleanProperty) declaredProperties().get(side);
    }

    private BlockState withAllDisconnected(BlockState state) {
        BlockState result = state;
        for (String side : BlockShapes.SIDES) {
            BooleanProperty property = propertyOf(side);
            if (property != null) result = result.with(property, false);
        }
        return result;
    }

    /**
     * Calcula as conexões no momento em que o bloco é colocado.
     *
     * <p>Sem isto, um cano colocado no meio de uma linha pronta nasceria solto: os vizinhos seriam
     * avisados e se ligariam a ele, e ele continuaria sem braço nenhum — a rede pareceria ligada de
     * um lado só.
     */
    @Override
    public BlockState getPlacementState(ItemPlacementContext context) {
        BlockState state = super.getPlacementState(context);
        if (state == null) return null;

        BlockPos pos = context.getBlockPos();
        for (Direction direction : Direction.values()) {
            BooleanProperty property = propertyOf(SIDE_OF.get(direction));
            if (property == null) continue;

            state = state.with(property,
                    connectsTo(context.getWorld().getBlockState(pos.offset(direction))));
        }
        return state;
    }

    /**
     * Refaz uma conexão quando o vizinho daquele lado muda.
     *
     * <p>Só o lado que mudou, e não os seis: o jogo avisa uma direção por vez, e recalcular tudo
     * multiplicaria por seis o custo de quebrar um bloco no meio de uma rede grande.
     */
    @Override
    protected BlockState getStateForNeighborUpdate(BlockState state, Direction direction,
                                                   BlockState neighborState, WorldAccess world,
                                                   BlockPos pos, BlockPos neighborPos) {
        BooleanProperty property = propertyOf(SIDE_OF.get(direction));
        if (property == null) {
            return super.getStateForNeighborUpdate(state, direction, neighborState,
                    world, pos, neighborPos);
        }
        return state.with(property, connectsTo(neighborState));
    }

    /** Se aquele vizinho é do tipo a que este bloco se liga. */
    private boolean connectsTo(BlockState neighbor) {
        if (neighbor == null || neighbor.isAir()) return false;

        Identifier id = Registries.BLOCK.getId(neighbor.getBlock());
        if (blockIds.contains(id)) return true;

        for (TagKey<Block> tag : tags) {
            if (neighbor.isIn(tag)) return true;
        }
        return false;
    }

    @Override
    protected VoxelShape getOutlineShape(BlockState state, BlockView world, BlockPos pos,
                                         net.minecraft.block.ShapeContext context) {
        return shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos,
                                           net.minecraft.block.ShapeContext context) {
        // A mesma forma do contorno, e nao uma aproximacao: ver o braco e atravessa-lo e o
        // defeito que esta classe existe para nao produzir.
        return shapeOf(state);
    }

    private VoxelShape shapeOf(BlockState state) {
        Set<String> connected = new LinkedHashSet<>();
        StringBuilder key = new StringBuilder();

        for (String side : BlockShapes.SIDES) {
            BooleanProperty property = propertyOf(side);
            boolean on = property != null && state.get(property);
            if (on) connected.add(side);
            key.append(on ? '1' : '0');
        }

        return shapeCache.computeIfAbsent(key.toString(), ignored ->
                DeclarativeShapes.fromBoxes(BlockShapes.connected(core, arm, connected)));
    }
}
