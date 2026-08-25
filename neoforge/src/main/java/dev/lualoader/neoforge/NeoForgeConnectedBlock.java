package dev.lualoader.neoforge;

import dev.lualoader.content.BlockShapes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Um bloco que cresce braços em direção aos vizinhos, no NeoForge.
 *
 * <p>O par de {@code ConnectedBlock} do Fabric. A aritmética das caixas vem do núcleo, e é a mesma
 * nos dois lados: um braço girado diferente em cada plataforma daria o mesmo manifesto produzindo
 * canos que se encontram de um lado e não do outro.
 *
 * <p><b>A colisão acompanha o desenho.</b> Uma forma que fica só no visual deixa o jogador ver o
 * braço e atravessá-lo.
 */
public class NeoForgeConnectedBlock extends NeoForgeDeclarativeDataBlock {
    /**
     * Se este cano guarda dados na propria posicao.
     *
     * <p>Conectar e guardar dados sao independentes, e o registrador escolhia um so: aqui a
     * condicao era literalmente {@code connects(definition) && !withData}, entao um cano que
     * pedisse {@code block_data} perdia a conexao inteira sem aviso. O porte do Logistic Pipes
     * precisou das duas ao mesmo tempo, porque a carga em viagem mora na posicao do cano.
     */
    private final boolean withData;

    private final BlockShapes.Box core;
    private final BlockShapes.Box arm;

    private final Set<ResourceLocation> blockIds;
    private final Set<TagKey<Block>> tags;

    /**
     * A forma pronta de cada combinação de lados.
     *
     * <p>Guardada porque {@code getShape} é chamado a cada quadro para cada bloco visível: montar a
     * caixa ali dentro faria a rede inteira pagar aritmética sessenta vezes por segundo.
     */
    private final Map<String, VoxelShape> shapeCache = new ConcurrentHashMap<>();

    public NeoForgeConnectedBlock(Properties properties, int luminance,
                                  BlockShapes.Box core, BlockShapes.Box arm,
                                  List<String> connectsTo, boolean withData) {
        super(properties, luminance, null, null);

        this.withData = withData;

        this.core = core;
        this.arm = arm;

        Set<ResourceLocation> ids = new LinkedHashSet<>();
        Set<TagKey<Block>> tagKeys = new LinkedHashSet<>();
        for (String entry : connectsTo == null ? List.<String>of() : connectsTo) {
            if (entry == null || entry.isBlank()) continue;

            if (entry.startsWith("#")) {
                ResourceLocation parsed = ResourceLocation.tryParse(entry.substring(1));
                if (parsed != null) tagKeys.add(TagKey.create(BuiltInRegistries.BLOCK.key(), parsed));
            } else {
                ResourceLocation parsed = ResourceLocation.tryParse(entry);
                if (parsed != null) ids.add(parsed);
            }
        }
        this.blockIds = Set.copyOf(ids);
        this.tags = Set.copyOf(tagKeys);

        registerDefaultState(withAllDisconnected(defaultBlockState()));
    }

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
            if (property != null) result = result.setValue(property, false);
        }
        return result;
    }

    /**
     * Calcula as conexões no momento em que o bloco é colocado.
     *
     * <p>Sem isto, um cano colocado no meio de uma linha pronta nasceria solto: os vizinhos se
     * ligariam a ele, e ele continuaria sem braço — a rede pareceria ligada de um lado só.
     */
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = super.getStateForPlacement(context);
        if (state == null) return null;

        BlockPos pos = context.getClickedPos();
        for (Direction direction : Direction.values()) {
            BooleanProperty property = propertyOf(SIDE_OF.get(direction));
            if (property == null) continue;

            state = state.setValue(property,
                    connectsTo(context.getLevel().getBlockState(pos.relative(direction))));
        }
        return state;
    }

    /**
     * Refaz uma conexão quando o vizinho daquele lado muda.
     *
     * <p>Só o lado que mudou: o jogo avisa uma direção por vez, e recalcular os seis multiplicaria
     * o custo de quebrar um bloco no meio de uma rede grande.
     */
    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BooleanProperty property = propertyOf(SIDE_OF.get(direction));
        if (property == null) {
            return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
        }
        return state.setValue(property, connectsTo(neighbor));
    }

    private boolean connectsTo(BlockState neighbor) {
        if (neighbor == null || neighbor.isAir()) return false;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(neighbor.getBlock());
        if (blockIds.contains(id)) return true;

        for (TagKey<Block> tag : tags) {
            if (neighbor.is(tag)) return true;
        }
        return false;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return shapeOf(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos,
                                           CollisionContext context) {
        // A mesma forma do contorno, e nao uma aproximacao: ver o braco e atravessa-lo e o defeito
        // que esta classe existe para nao produzir.
        return shapeOf(state);
    }

    private VoxelShape shapeOf(BlockState state) {
        Set<String> connected = new LinkedHashSet<>();
        StringBuilder key = new StringBuilder();

        for (String side : BlockShapes.SIDES) {
            BooleanProperty property = propertyOf(side);
            boolean on = property != null && state.getValue(property);
            if (on) connected.add(side);
            key.append(on ? '1' : '0');
        }

        return shapeCache.computeIfAbsent(key.toString(), ignored ->
                NeoForgeShapes.fromBoxes(BlockShapes.connected(core, arm, connected)));
    }

    @Override
    public net.minecraft.world.level.block.entity.BlockEntity newBlockEntity(BlockPos pos,
                                                                            BlockState state) {
        if (!withData) return null;
        return super.newBlockEntity(pos, state);
    }
}
