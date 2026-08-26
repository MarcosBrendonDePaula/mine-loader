package dev.lualoader.minecraft;

import dev.lualoader.content.BlockShapes;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.BlockView;
import net.minecraft.world.WorldAccess;
import net.minecraft.world.WorldView;

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
public class ConnectedBlock extends DeclarativeDataBlock {
    /**
     * Se este cano guarda dados na propria posicao.
     *
     * <p>Conectar e guardar dados sao capacidades independentes, e o registrador escolhia uma so:
     * um cano que declarasse {@code block_data} virava bloco de dados e perdia a conexao inteira --
     * em silencio, com as seis propriedades no blockstate e nenhuma mudando nunca. Foi o porte do
     * Logistic Pipes que precisou das duas ao mesmo tempo, porque a carga em viagem mora na posicao.
     *
     * <p>Herdar de {@link DeclarativeDataBlock} e devolver {@code null} aqui e o que deixa um cano
     * sem dados nao pagar o custo da entidade.
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

    /** Ids de bloco a que este se conecta. */
    private final Set<Identifier> blockIds;

    /** Tags de bloco a que este se conecta, já sem o {@code #}. */
    private final Set<TagKey<Block>> tags;

    /**
     * Se este bloco cresce braço em direção a qualquer coisa que guarde item.
     *
     * <p>Declarado com {@code "@items"} em {@code connects_to}. É o critério que a própria rede de
     * um mod de logística usa — capability, e não id —, e tê-lo aqui é o que faz o desenho e a rede
     * concordarem sobre onde há ligação.
     */
    private final boolean toInventory;

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
                          java.util.List<BlockShapes.Box> core, java.util.List<BlockShapes.Box> arm, List<String> connectsTo,
                          boolean withData) {
        super(settings, hardness, resistance, slipperiness,
                velocityMultiplier, jumpVelocityMultiplier);

        this.withData = withData;

        this.core = core == null ? java.util.List.of() : java.util.List.copyOf(core);
        this.arm = arm == null ? java.util.List.of() : java.util.List.copyOf(arm);

        Set<Identifier> ids = new LinkedHashSet<>();
        Set<TagKey<Block>> tagKeys = new LinkedHashSet<>();
        boolean inventario = false;
        for (String entry : connectsTo == null ? List.<String>of() : connectsTo) {
            if (entry == null || entry.isBlank()) continue;

            if (BlockShapes.INVENTORY_TOKEN.equals(entry.trim())) {
                inventario = true;
            } else if (entry.startsWith("#")) {
                Identifier parsed = Identifier.tryParse(entry.substring(1));
                if (parsed != null) tagKeys.add(TagKey.of(RegistryKeys.BLOCK, parsed));
            } else {
                Identifier parsed = Identifier.tryParse(entry);
                if (parsed != null) ids.add(parsed);
            }
        }
        this.blockIds = Set.copyOf(ids);
        this.tags = Set.copyOf(tagKeys);
        this.toInventory = inventario;

        setDefaultState(withAllDisconnected(getDefaultState()));
    }

    /** As propriedades booleanas, por direção, resolvidas uma vez. */
    private static final Map<Direction, String> SIDE_OF = new EnumMap<>(Map.of(
            Direction.NORTH, "north", Direction.SOUTH, "south",
            Direction.WEST, "west", Direction.EAST, "east",
            Direction.UP, "up", Direction.DOWN, "down"));

    @SuppressWarnings("unchecked")
    private net.minecraft.state.property.Property<String> propertyOf(String side) {
        return (net.minecraft.state.property.Property<String>) declaredProperties().get(side);
    }

    private BlockState withAllDisconnected(BlockState state) {
        BlockState result = state;
        for (String side : BlockShapes.SIDES) {
            var property = propertyOf(side);
            if (property != null) result = result.with(property, BlockShapes.LINK_NONE);
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
            var property = propertyOf(SIDE_OF.get(direction));
            if (property == null) continue;

            BlockPos neighbor = pos.offset(direction);
            state = state.with(property,
                    linkTo(context.getWorld().getBlockState(neighbor), context.getWorld(), neighbor));
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
    protected BlockState getStateForNeighborUpdate(BlockState state, WorldView world,
                                                   net.minecraft.world.tick.ScheduledTickView tickView,
                                                   BlockPos pos, Direction direction,
                                                   BlockPos neighborPos, BlockState neighborState,
                                                   net.minecraft.util.math.random.Random random) {
        var property = propertyOf(SIDE_OF.get(direction));
        if (property == null) {
            return super.getStateForNeighborUpdate(state, world, tickView, pos, direction,
                    neighborPos, neighborState, random);
        }
        return state.with(property, linkTo(neighborState, world, neighborPos));
    }

    /**
     * Que tipo de ligação este bloco faz com aquele vizinho.
     *
     * <p>A lista declarada vem primeiro: um baú que também esteja nomeado por id conta como bloco,
     * e não como inventário. É o que permite a um mod tratar um caso especial sem perder a regra
     * geral.
     */
    private String linkTo(BlockState neighbor, WorldView world, BlockPos pos) {
        if (neighbor == null || neighbor.isAir()) return BlockShapes.LINK_NONE;

        Identifier id = Registries.BLOCK.getId(neighbor.getBlock());
        if (blockIds.contains(id)) return BlockShapes.LINK_BLOCK;

        for (TagKey<Block> tag : tags) {
            if (neighbor.isIn(tag)) return BlockShapes.LINK_BLOCK;
        }

        if (toInventory && hasItems(world, pos)) return BlockShapes.LINK_INVENTORY;
        return BlockShapes.LINK_NONE;
    }

    /**
     * Se há inventário naquela posição.
     *
     * <p>A capability primeiro, porque é o que a ponte do loader responde a um mod — e o desenho
     * discordar da rede é justamente o defeito que este caminho existe para fechar. Ela precisa de
     * um {@link net.minecraft.world.World} de verdade, e a atualização de vizinho às vezes chega com
     * uma região de geração; nesse caso vale o inventário do bloco, que cobre baú, barril e forno.
     */
    private boolean hasItems(WorldView world, BlockPos pos) {
        if (world instanceof net.minecraft.world.World real
                && net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED
                        .find(real, pos, null) != null) {
            return true;
        }
        return world.getBlockEntity(pos) instanceof net.minecraft.inventory.Inventory;
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
            var property = propertyOf(side);
            String link = property == null ? BlockShapes.LINK_NONE : state.get(property);

            // A colisao nao distingue os dois tipos de braco: os dois ocupam o mesmo espaco, e a
            // diferenca entre eles e so de desenho. Distinguir aqui multiplicaria a cache de formas
            // por nada.
            boolean on = !BlockShapes.LINK_NONE.equals(link);
            if (on) connected.add(side);
            key.append(on ? '1' : '0');
        }

        return shapeCache.computeIfAbsent(key.toString(), ignored ->
                DeclarativeShapes.fromBoxes(BlockShapes.connected(core, arm, connected)));
    }

    @Override
    public net.minecraft.block.entity.BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        if (!withData) return null;
        return super.createBlockEntity(pos, state);
    }
}
