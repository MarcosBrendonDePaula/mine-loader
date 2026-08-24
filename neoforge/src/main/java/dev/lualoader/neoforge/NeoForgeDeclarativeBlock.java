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

    // Propriedades físicas que um script troca em tempo de execução. Diferente da luminância, estas
    // valem para o bloco todo: sao caracteristicas do material, nao do exemplar no mundo. NaN
    // significa "nao sobrescrito", e o valor declarado no manifesto continua valendo.
    private float dynamicHardness = Float.NaN;
    private float dynamicBlastResistance = Float.NaN;
    private float dynamicFriction = Float.NaN;
    private float dynamicSpeedFactor = Float.NaN;
    private float dynamicJumpFactor = Float.NaN;

    public NeoForgeDeclarativeBlock(BlockBehaviour.Properties properties, int declaredLuminance) {
        // A luz sai do estado, e nao do valor fixo: e o que permite um script acender um bloco so.
        super(properties.lightLevel(state -> state.getValue(LUMINANCE)));
        registerDefaultState(getStateDefinition().any()
                .setValue(VARIANT, 0)
                .setValue(LUMINANCE, Math.max(0, Math.min(15, declaredLuminance))));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(VARIANT, LUMINANCE);
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
