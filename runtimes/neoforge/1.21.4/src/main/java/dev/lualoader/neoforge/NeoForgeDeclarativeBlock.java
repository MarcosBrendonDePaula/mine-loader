package dev.lualoader.neoforge;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;

/**
 * Bloco declarado por manifesto, com a variante visual que o resource pack espera.
 *
 * <p>O pack gerado descreve o bloco por variante — {@code lua_variant=0} até {@code lua_variant=15}
 * — e um bloco sem essa propriedade não casa com nenhuma delas: o jogo procura a variante sem
 * propriedades, não encontra, e desenha o cubo de textura ausente.
 *
 * <p>A faixa é fixa em 16 porque é o que o montador do pack sempre escreve, mesmo quando o mod
 * declara uma textura só — as variantes sobrando apontam todas para o primeiro modelo. Uma faixa
 * que acompanhasse a contagem declarada teria dois problemas: não casaria com o blockstate gerado,
 * e com uma textura só produziria uma propriedade de um valor único, que o jogo recusa.
 */
public class NeoForgeDeclarativeBlock extends Block {
    /** Quantas variantes o pack descreve, e portanto quantas o bloco precisa aceitar. */
    public static final int VARIANT_COUNT = 16;

    /** A variante visual, na mesma faixa que o resource pack gera. */
    public static final IntegerProperty VARIANT =
            IntegerProperty.create("lua_variant", 0, VARIANT_COUNT - 1);

    /**
     * A luminância, guardada no estado e não nas propriedades do bloco.
     *
     * <p>As propriedades do bloco são únicas e imutáveis: mudá-las acenderia todos os blocos
     * daquele tipo no mundo inteiro. Um script que acende <em>um</em> altar precisa que o valor
     * viva na posição, e é o estado que dá isso.
     */
    public static final IntegerProperty LUMINANCE = IntegerProperty.create("lua_luminance", 0, 15);

    /**
     * Propriedades declaradas no manifesto para o bloco que esta sendo construido.
     *
     * <p>O Minecraft chama {@link #createBlockStateDefinition} de dentro do construtor de
     * {@code Block}, antes de qualquer campo de instancia existir, entao as propriedades precisam
     * ser publicadas por fora. O registro e sequencial e o valor e limpo logo apos o construtor.
     */
    private static final ThreadLocal<NeoForgeStateProperties> PENDING = new ThreadLocal<>();

    /** Publica as propriedades declaradas antes de instanciar o bloco. */
    public static void beginConstruction(NeoForgeStateProperties declared) {
        PENDING.set(declared);
    }

    /** Limpa a publicacao feita por {@link #beginConstruction}. */
    public static void endConstruction() {
        PENDING.remove();
    }

    private final java.util.Map<String, net.minecraft.world.level.block.state.properties.Property<?>>
            declaredProperties = new java.util.LinkedHashMap<>();

    // Propriedades físicas que um script troca em tempo de execução. Diferente da luminância, estas
    // valem para o bloco todo: sao caracteristicas do material, nao do exemplar no mundo. NaN
    // significa "nao sobrescrito", e o valor declarado no manifesto continua valendo.
    private float dynamicHardness = Float.NaN;
    private float dynamicBlastResistance = Float.NaN;
    private float dynamicFriction = Float.NaN;
    private float dynamicSpeedFactor = Float.NaN;
    private float dynamicJumpFactor = Float.NaN;

    /** Contorno e colisao declarados; nulos quando o bloco e um cubo inteiro. */
    private final net.minecraft.world.phys.shapes.VoxelShape outline;
    private final net.minecraft.world.phys.shapes.VoxelShape collision;

    /**
     * A luz de cada estado: do proprio estado quando o bloco declara que ela muda, senao o valor fixo.
     *
     * <p>O {@code hasProperty} nao e defensividade: um bloco que nao declara luminosidade dinamica
     * nao recebe a propriedade -- ela custa dezesseis valores e quase nenhum bloco a usa --, e
     * pedi-la ali estouraria em todo calculo de luz do jogo.
     */
    private static java.util.function.ToIntFunction<BlockState> luzDe(int declaredLuminance) {
        int fixa = Math.max(0, Math.min(15, declaredLuminance));
        return state -> state.hasProperty(LUMINANCE) ? state.getValue(LUMINANCE) : fixa;
    }

    public NeoForgeDeclarativeBlock(BlockBehaviour.Properties properties, int declaredLuminance) {
        this(properties, declaredLuminance, null, null);
    }

    public NeoForgeDeclarativeBlock(BlockBehaviour.Properties properties, int declaredLuminance,
                                    net.minecraft.world.phys.shapes.VoxelShape outline,
                                    net.minecraft.world.phys.shapes.VoxelShape collision) {
        // A luz sai do estado quando o bloco declara que ela muda; senao vale o valor fixo.
        //
        // O `hasProperty` nao e defensividade: um bloco que nao declara luminosidade dinamica nao
        // recebe a propriedade, e pedi-la ali estouraria em todo calculo de luz do jogo.
        // O valor fixo e calculado na propria chamada: `super` precisa ser a primeira instrucao, e
        // uma variavel local antes dela nao compila.
        super(properties.lightLevel(luzDe(declaredLuminance)));

        int fixa = Math.max(0, Math.min(15, declaredLuminance));
        BlockState base = getStateDefinition().any();
        if (base.hasProperty(LUMINANCE)) base = base.setValue(LUMINANCE, fixa);
        if (base.hasProperty(VARIANT)) base = base.setValue(VARIANT, 0);

        NeoForgeStateProperties declared = PENDING.get();
        if (declared != null) {
            this.declaredProperties.putAll(declared.properties());
            base = applyDefaults(base, declared);
        }
        registerDefaultState(base);

        this.outline = outline;
        this.collision = collision;
    }

    private BlockState applyDefaults(BlockState state, NeoForgeStateProperties declared) {
        BlockState result = state;
        for (java.util.Map.Entry<String, String> entry : declared.defaults().entrySet()) {
            var property = declaredProperties.get(entry.getKey());
            if (property == null) continue;
            result = withParsed(result, property, entry.getValue());
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState withParsed(
            BlockState state,
            net.minecraft.world.level.block.state.properties.Property<T> property,
            String rawValue) {
        return property.getValue(rawValue).map(value -> state.setValue(property, value))
                .orElse(state);
    }

    /** Propriedades declaradas no manifesto, por nome. */
    public java.util.Map<String, net.minecraft.world.level.block.state.properties.Property<?>>
            declaredProperties() {
        return java.util.Map.copyOf(declaredProperties);
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        return outline == null ? super.getShape(state, level, pos, context) : outline;
    }

    @Override
    protected net.minecraft.world.phys.shapes.VoxelShape getCollisionShape(
            BlockState state, BlockGetter level, BlockPos pos,
            net.minecraft.world.phys.shapes.CollisionContext context) {
        // Uma forma sem colisao, como uma planta, e representada por uma caixa vazia.
        return collision == null ? super.getCollisionShape(state, level, pos, context) : collision;
    }

    /**
     * O tique aleatório do jogo, entregue ao script.
     *
     * <p>Estes dois eventos nascem no próprio bloco, e não numa interação do jogador: não há quem
     * os tenha causado, e por isso não passam pelo caminho de {@code NeoForgeInteractionEvents},
     * que exige um jogador. É a mesma divisão do adaptador Fabric.
     *
     * <p>O tique só chega aqui quando {@code settings.random_ticks} foi declarado — enquanto a
     * propriedade não era aplicada nesta plataforma, o evento parecia faltar e o que faltava era o
     * bloco pedir para ser tiqueado.
     */
    @Override
    protected void randomTick(BlockState state, net.minecraft.server.level.ServerLevel level,
                              BlockPos pos, net.minecraft.util.RandomSource random) {
        notifyLoader("block_random_tick", level, pos, state);
    }

    /**
     * O tique que o script pediu, chegando na posicao em que foi pedido.
     *
     * <p>Nao se repete sozinho: cada tique vale uma vez, e continuar significa o script agendar o
     * proximo. Igual ao par no Fabric -- um lado que repetisse sozinho faria o mesmo manifesto
     * andar em velocidades diferentes nas duas plataformas.
     */
    @Override
    protected void tick(BlockState state, net.minecraft.server.level.ServerLevel level,
                        BlockPos pos, net.minecraft.util.RandomSource random) {
        notifyLoader("block_scheduled", level, pos, state);
    }

    @Override
    protected void neighborChanged(BlockState state, net.minecraft.world.level.Level level,
                                   BlockPos pos, Block sourceBlock,
                                   net.minecraft.world.level.redstone.Orientation orientation,
                                   boolean movedByPiston) {
        super.neighborChanged(state, level, pos, sourceBlock, orientation, movedByPiston);
        notifyLoader("block_neighbor_update", level, pos, state);
    }

    /** Entrega ao runtime um evento originado pelo proprio bloco, sem jogador. */
    private void notifyLoader(String event, net.minecraft.world.level.Level level, BlockPos pos,
                              BlockState state) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        var runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return;

        var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(this);
        if (id == null) return;

        var registrar = NeoForgeLuaLoader.contentRegistrar();
        var data = new dev.lualoader.platform.BlockEventData(
                id.toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                state.hasProperty(VARIANT) ? state.getValue(VARIANT) : 0,
                registrar == null ? 1 : registrar.variantCount(id));

        // A ponte precisa saber em que dimensao o evento aconteceu, e precisa esquecer depois: um
        // mundo que sobra faria a proxima chamada sem contexto agir na dimensao errada.
        var bridge = NeoForgeLuaLoader.gameBridge();
        if (bridge != null) bridge.setCurrentLevel(serverLevel);
        try {
            runtime.triggerBlock(event, null, data);
        } finally {
            if (bridge != null) bridge.setCurrentLevel(null);
        }
    }

    /**
     * Escolhe a direcao no momento em que o bloco e colocado.
     *
     * <p>Sem isto, a propriedade existiria no blockstate e nunca mudaria: o bloco ficaria sempre
     * apontando para o norte, e o campo declarado no manifesto pareceria ignorado.
     */
    @Override
    public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext ctx) {
        BlockState state = super.getStateForPlacement(ctx);
        if (state == null) return null;

        var declared = declaredProperties.get("facing");
        if (declared == net.minecraft.world.level.block.state.properties
                .BlockStateProperties.FACING) {
            return state.setValue(net.minecraft.world.level.block.state.properties
                    .BlockStateProperties.FACING, ctx.getClickedFace().getOpposite());
        }
        if (declared == net.minecraft.world.level.block.state.properties
                .BlockStateProperties.HORIZONTAL_FACING) {
            // Oposto de para onde o jogador olha: colocar um bloco de frente e o gesto de virar
            // ele para si, e nao de empurra-lo para longe.
            return state.setValue(net.minecraft.world.level.block.state.properties
                            .BlockStateProperties.HORIZONTAL_FACING,
                    ctx.getHorizontalDirection().getOpposite());
        }
        return state;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        NeoForgeStateProperties declared = PENDING.get();

        // A luminosidade so entra quando o bloco a declara -- estatica ou por script. Ela custa
        // dezesseis valores, e era registrada em todo bloco declarativo: quinze mods de exemplo
        // criavam 128 mil blockstates, contra os cerca de 26 mil do Minecraft inteiro.
        if (declared == null || declared.hasLuminance()) {
            builder.add(LUMINANCE);
        }

        // A variante so entra quando o bloco declara mais de uma textura. Ela custa dezesseis
        // valores, e um bloco de textura unica nunca sai da variante zero -- registra-la ali
        // multiplicava por dezesseis todos os outros estados do bloco, sem nada em troca.
        if (declared == null || declared.hasVariant()) {
            builder.add(VARIANT);
        }
        if (declared == null) return;
        for (var property : declared.properties().values()) {
            builder.add(property);
        }
    }

    /**
     * Troca uma propriedade física do bloco.
     *
     * @throws IllegalArgumentException se a propriedade não for uma das suportadas
     */
    public void setDynamicProperty(String property, float value) {
        switch (property) {
            case "hardness" -> dynamicHardness = value;
            case "resistance", "blast_resistance" -> dynamicBlastResistance = value;
            case "slipperiness" -> dynamicFriction = value;
            case "velocity_multiplier" -> dynamicSpeedFactor = value;
            case "jump_velocity_multiplier" -> dynamicJumpFactor = value;
            default -> throw new IllegalArgumentException(
                    "propriedade física dinâmica não suportada: " + property);
        }
    }

    /**
     * Quanto do bloco se quebra por tique.
     *
     * <p>A dureza não é sobrescrevível diretamente — ela mora num campo do estado, preenchido no
     * registro e imutável depois. Este é o método que a consome, e por isso é aqui que a dureza
     * dinâmica entra: a conta é a mesma do jogo, com o valor do script no lugar do declarado.
     */
    @Override
    public float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (Float.isNaN(dynamicHardness)) return super.getDestroyProgress(state, player, level, pos);

        // Dureza negativa e o que o jogo usa para "inquebravel", como na bedrock.
        if (dynamicHardness < 0.0F) return 0.0F;

        int divisor = player.hasCorrectToolForDrops(state) ? 30 : 100;
        return player.getDestroySpeed(state) / dynamicHardness / divisor;
    }

    @Override
    public float getExplosionResistance() {
        return Float.isNaN(dynamicBlastResistance)
                ? super.getExplosionResistance()
                : dynamicBlastResistance;
    }

    @Override
    public float getFriction() {
        return Float.isNaN(dynamicFriction) ? super.getFriction() : dynamicFriction;
    }

    @Override
    public float getSpeedFactor() {
        return Float.isNaN(dynamicSpeedFactor) ? super.getSpeedFactor() : dynamicSpeedFactor;
    }

    @Override
    public float getJumpFactor() {
        return Float.isNaN(dynamicJumpFactor) ? super.getJumpFactor() : dynamicJumpFactor;
    }
}
