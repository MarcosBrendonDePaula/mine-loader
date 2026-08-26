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

    /**
     * As caixas do nucleo e as do braco.
     *
     * <p>Listas, e nao uma caixa cada: o cano do Logistic Pipes tem placas nas faces alem do miolo,
     * e um colar na ponta de cada braco. As caixas de um braco giram juntas, entao o conjunto se
     * comporta como uma peca so.
     */
    private final java.util.List<BlockShapes.Box> core;
    private final java.util.List<BlockShapes.Box> arm;

    private final Set<ResourceLocation> blockIds;
    private final Set<TagKey<Block>> tags;

    /**
     * A forma pronta de cada combinação de lados.
     *
     * <p>Guardada porque {@code getShape} é chamado a cada quadro para cada bloco visível: montar a
     * caixa ali dentro faria a rede inteira pagar aritmética sessenta vezes por segundo.
     */
    private final Map<String, VoxelShape> shapeCache = new ConcurrentHashMap<>();

    /**
     * Se este bloco cresce braço em direção a qualquer coisa que guarde item.
     *
     * <p>Declarado com {@code "@items"} em {@code connects_to}. É o critério que a própria rede de
     * um mod de logística usa — capability, e não id —, e tê-lo aqui é o que faz o desenho e a rede
     * concordarem sobre onde há ligação.
     */
    private final boolean toInventory;

    public NeoForgeConnectedBlock(Properties properties, int luminance,
                                  java.util.List<BlockShapes.Box> core, java.util.List<BlockShapes.Box> arm,
                                  List<String> connectsTo, boolean withData) {
        super(properties, luminance, null, null);

        this.withData = withData;

        this.core = core == null ? java.util.List.of() : java.util.List.copyOf(core);
        this.arm = arm == null ? java.util.List.of() : java.util.List.copyOf(arm);

        Set<ResourceLocation> ids = new LinkedHashSet<>();
        Set<TagKey<Block>> tagKeys = new LinkedHashSet<>();
        boolean inventario = false;
        for (String entry : connectsTo == null ? List.<String>of() : connectsTo) {
            if (entry == null || entry.isBlank()) continue;

            if (BlockShapes.INVENTORY_TOKEN.equals(entry.trim())) {
                inventario = true;
            } else if (entry.startsWith("#")) {
                ResourceLocation parsed = ResourceLocation.tryParse(entry.substring(1));
                if (parsed != null) tagKeys.add(TagKey.create(BuiltInRegistries.BLOCK.key(), parsed));
            } else {
                ResourceLocation parsed = ResourceLocation.tryParse(entry);
                if (parsed != null) ids.add(parsed);
            }
        }
        this.blockIds = Set.copyOf(ids);
        this.tags = Set.copyOf(tagKeys);
        this.toInventory = inventario;

        registerDefaultState(withAllDisconnected(defaultBlockState()));
    }

    private static final Map<Direction, String> SIDE_OF = new EnumMap<>(Map.of(
            Direction.NORTH, "north", Direction.SOUTH, "south",
            Direction.WEST, "west", Direction.EAST, "east",
            Direction.UP, "up", Direction.DOWN, "down"));

    @SuppressWarnings("unchecked")
    private net.minecraft.world.level.block.state.properties.Property<String> propertyOf(String side) {
        return (net.minecraft.world.level.block.state.properties.Property<String>)
                declaredProperties().get(side);
    }

    private BlockState withAllDisconnected(BlockState state) {
        BlockState result = state;
        for (String side : BlockShapes.SIDES) {
            var property = propertyOf(side);
            if (property != null) result = result.setValue(property, BlockShapes.LINK_NONE);
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
            var property = propertyOf(SIDE_OF.get(direction));
            if (property == null) continue;

            BlockPos neighbor = pos.relative(direction);
            state = state.setValue(property,
                    linkTo(context.getLevel().getBlockState(neighbor), context.getLevel(), neighbor));
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
        var property = propertyOf(SIDE_OF.get(direction));
        if (property == null) {
            return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
        }
        return state.setValue(property, linkTo(neighbor, level, neighborPos));
    }

    /**
     * Que tipo de ligação este bloco faz com aquele vizinho.
     *
     * <p>A lista declarada vem primeiro: um baú que também esteja nomeado por id conta como bloco,
     * e não como inventário. É o que permite a um mod tratar um caso especial sem perder a regra
     * geral.
     */
    private String linkTo(BlockState neighbor, LevelAccessor level, BlockPos pos) {
        if (neighbor == null || neighbor.isAir()) return BlockShapes.LINK_NONE;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(neighbor.getBlock());
        if (blockIds.contains(id)) return BlockShapes.LINK_BLOCK;

        for (TagKey<Block> tag : tags) {
            if (neighbor.is(tag)) return BlockShapes.LINK_BLOCK;
        }

        if (toInventory && hasItems(level, pos)) return BlockShapes.LINK_INVENTORY;
        return BlockShapes.LINK_NONE;
    }

    /**
     * Se há inventário naquela posição.
     *
     * <p>A capability primeiro, porque é o que a ponte do loader responde a um mod — e o desenho
     * discordar da rede é justamente o defeito que este caminho existe para fechar. Ela precisa de
     * um {@link net.minecraft.world.level.Level} de verdade, e a atualização de vizinho às vezes
     * chega com uma região de geração; nesse caso vale o contêiner do bloco, que cobre baú, barril
     * e forno.
     */
    private boolean hasItems(LevelAccessor level, BlockPos pos) {
        if (level instanceof net.minecraft.world.level.Level real
                && real.getCapability(net.neoforged.neoforge.capabilities.Capabilities
                        .ItemHandler.BLOCK, pos, null) != null) {
            return true;
        }
        return level.getBlockEntity(pos) instanceof net.minecraft.world.Container;
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
            var property = propertyOf(side);
            String link = property == null ? BlockShapes.LINK_NONE : state.getValue(property);

            // A colisao nao distingue os dois tipos de braco: os dois ocupam o mesmo espaco, e a
            // diferenca entre eles e so de desenho. Distinguir aqui multiplicaria a cache de formas
            // por nada.
            boolean on = !BlockShapes.LINK_NONE.equals(link);
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
