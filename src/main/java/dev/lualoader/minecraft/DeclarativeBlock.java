package dev.lualoader.minecraft;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.state.StateManager;
import net.minecraft.state.property.IntProperty;

/** Bloco base do loader: estados visuais e alguns getters físicos podem ser alterados pelo Lua. */
public class DeclarativeBlock extends Block {
    public static final IntProperty LUA_VARIANT = IntProperty.of("lua_variant", 0, 15);
    public static final IntProperty LUA_LUMINANCE = IntProperty.of("lua_luminance", 0, 15);

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
    }

    @Override
    protected void appendProperties(StateManager.Builder<Block, BlockState> builder) {
        builder.add(LUA_VARIANT, LUA_LUMINANCE);
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
