package dev.lualoader.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Property;

import java.util.LinkedHashMap;
import java.util.Map;

/** Bloco base do loader: estados visuais e alguns getters físicos podem ser alterados pelo Lua. */
public class DeclarativeBlock extends Block {
    public static final IntProperty LUA_VARIANT = IntProperty.of("lua_variant", 0, 15);
    public static final IntProperty LUA_LUMINANCE = IntProperty.of("lua_luminance", 0, 15);

    /**
     * Propriedades declaradas no manifesto para o bloco que esta sendo construido.
     *
     * <p>O Minecraft chama {@link #appendProperties} de dentro do construtor de {@code Block},
     * antes de qualquer campo de instancia existir, entao as propriedades precisam ser
     * publicadas por fora. O registro e sequencial e o valor e limpo logo apos o construtor.
     */
    private static final ThreadLocal<DeclarativeStateProperties> PENDING = new ThreadLocal<>();

    /** Publica as propriedades declaradas antes de instanciar o bloco. */
    public static void beginConstruction(DeclarativeStateProperties declared) {
        PENDING.set(declared);
    }

    /** Limpa a publicacao feita por {@link #beginConstruction}. */
    public static void endConstruction() {
        PENDING.remove();
    }

    private final Map<String, Property<?>> declaredProperties = new LinkedHashMap<>();

    private volatile float dynamicHardness;
    private volatile float dynamicBlastResistance;
    private volatile float dynamicSlipperiness;
    private volatile float dynamicVelocityMultiplier;
    private volatile float dynamicJumpVelocityMultiplier;

    public DeclarativeBlock(Settings settings,
                            float hardness,
                            float blastResistance,
                            float slipperiness,
                            float velocityMultiplier,
                            float jumpVelocityMultiplier) {
        super(settings);
        this.dynamicHardness = hardness;
        this.dynamicBlastResistance = blastResistance;
        this.dynamicSlipperiness = slipperiness;
        this.dynamicVelocityMultiplier = velocityMultiplier;
        this.dynamicJumpVelocityMultiplier = jumpVelocityMultiplier;

        DeclarativeStateProperties declared = PENDING.get();
        if (declared != null) {
            this.declaredProperties.putAll(declared.properties());
            setDefaultState(applyDefaults(getDefaultState(), declared));
        }
    }

    private BlockState applyDefaults(BlockState state, DeclarativeStateProperties declared) {
        BlockState result = state;
        for (Map.Entry<String, String> entry : declared.defaults().entrySet()) {
            Property<?> property = declaredProperties.get(entry.getKey());
            if (property == null) continue;
            result = withParsed(result, property, entry.getValue());
        }
        return result;
    }

    private static <T extends Comparable<T>> BlockState withParsed(BlockState state,
                                                                   Property<T> property,
                                                                   String rawValue) {
        return property.parse(rawValue).map(value -> state.with(property, value)).orElse(state);
    }

    /** Propriedades declaradas no manifesto, por nome. */
    public Map<String, Property<?>> declaredProperties() {
        return Map.copyOf(declaredProperties);
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LUA_VARIANT, LUA_LUMINANCE);

        DeclarativeStateProperties declared = PENDING.get();
        if (declared == null) return;
        for (Property<?> property : declared.properties().values()) {
            builder.add(property);
        }
    }

    @Override
    public float getHardness() {
        return dynamicHardness;
    }

    @Override
    public float getBlastResistance() {
        return dynamicBlastResistance;
    }

    @Override
    public float getSlipperiness() {
        return dynamicSlipperiness;
    }

    @Override
    public float getVelocityMultiplier() {
        return dynamicVelocityMultiplier;
    }

    @Override
    public float getJumpVelocityMultiplier() {
        return dynamicJumpVelocityMultiplier;
    }

    public void setDynamicProperty(String property, float value) {
        switch (property) {
            case "hardness" -> dynamicHardness = value;
            case "resistance", "blast_resistance" -> dynamicBlastResistance = value;
            case "slipperiness" -> dynamicSlipperiness = value;
            case "velocity_multiplier" -> dynamicVelocityMultiplier = value;
            case "jump_velocity_multiplier" -> dynamicJumpVelocityMultiplier = value;
            default -> throw new IllegalArgumentException("propriedade física dinâmica não suportada: " + property);
        }
    }
}
