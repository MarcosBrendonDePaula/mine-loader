package dev.lualoader.minecraft;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.platform.BlockEventData;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
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

    @Override
    public void onPlaced(World world, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.onPlaced(world, pos, state, placer, stack);
        notifyLoader("block_placed", world, pos, state,
                placer instanceof ServerPlayerEntity player ? player : null);
    }

    @Override
    public void onStateReplaced(BlockState state, World world, BlockPos pos, BlockState newState, boolean moved) {
        // Trocar de variante tambem substitui o estado; so avisa quando o bloco deixa de existir.
        if (!moved && !newState.isOf(this)) {
            notifyLoader("block_broken", world, pos, state, null);
        }
        super.onStateReplaced(state, world, pos, newState, moved);
    }

    @Override
    protected void randomTick(BlockState state, ServerWorld world, BlockPos pos, Random random) {
        notifyLoader("block_random_tick", world, pos, state, null);
    }

    @Override
    protected void neighborUpdate(BlockState state, World world, BlockPos pos, Block sourceBlock,
                                  BlockPos sourcePos, boolean notify) {
        super.neighborUpdate(state, world, pos, sourceBlock, sourcePos, notify);
        notifyLoader("block_neighbor_update", world, pos, state, null);
    }

    /** Entrega ao runtime um evento originado pelo proprio bloco. */
    private void notifyLoader(String event, World world, BlockPos pos, BlockState state,
                              ServerPlayerEntity player) {
        if (world == null || world.isClient()) return;
        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return;

        var id = Registries.BLOCK.getId(this);
        if (id == null) return;

        int variant = state.contains(LUA_VARIANT) ? state.get(LUA_VARIANT) : 0;
        int variantCount = LuaLoaderMod.blockRegistrar() == null
                ? 1
                : LuaLoaderMod.blockRegistrar().variantCount(id);

        runtime.triggerBlock(event,
                player == null ? null : new FabricPlayerHandle(player),
                new BlockEventData(id.toString(), pos.getX(), pos.getY(), pos.getZ(), variant, variantCount));
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
