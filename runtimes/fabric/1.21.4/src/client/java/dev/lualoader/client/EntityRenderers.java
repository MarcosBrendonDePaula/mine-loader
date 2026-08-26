package dev.lualoader.client;

import dev.lualoader.LuaLoaderMod;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.ChickenEntityRenderer;
import net.minecraft.client.render.entity.CowEntityRenderer;
import net.minecraft.client.render.entity.CreeperEntityRenderer;
import net.minecraft.client.render.entity.IronGolemEntityRenderer;
import net.minecraft.client.render.entity.PigEntityRenderer;
import net.minecraft.client.render.entity.SheepEntityRenderer;
import net.minecraft.client.render.entity.SkeletonEntityRenderer;
import net.minecraft.client.render.entity.SpiderEntityRenderer;
import net.minecraft.client.render.entity.WolfEntityRenderer;
import net.minecraft.client.render.entity.ZombieEntityRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.util.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registro de renderers do runtime Fabric 1.21.4.
 *
 * <p>A 1.21.4 mudou os renderers vanilla para render states. O bridge mantém as entidades visíveis
 * usando o renderer vanilla da base; modelos e texturas personalizados ficam explicitamente
 * pendentes até serem portados para o novo contrato, em vez de compilar com uma API antiga.
 */
public final class EntityRenderers {
    private EntityRenderers() {
    }

    private interface Factory {
        EntityRenderer<?, ?> create(EntityRendererFactory.Context context);
    }

    private static final Map<String, Factory> RENDERERS = new LinkedHashMap<>();

    private static void put(String base, Factory factory) {
        RENDERERS.put(base, factory);
    }

    static {
        put("minecraft:zombie", ZombieEntityRenderer::new);
        put("minecraft:skeleton", SkeletonEntityRenderer::new);
        put("minecraft:creeper", CreeperEntityRenderer::new);
        put("minecraft:spider", SpiderEntityRenderer::new);
        put("minecraft:pig", PigEntityRenderer::new);
        put("minecraft:cow", CowEntityRenderer::new);
        put("minecraft:sheep", SheepEntityRenderer::new);
        put("minecraft:chicken", ChickenEntityRenderer::new);
        put("minecraft:wolf", WolfEntityRenderer::new);
        put("minecraft:iron_golem", IronGolemEntityRenderer::new);
    }

    public static void register() {
        var registrar = LuaLoaderMod.entityRegistrar();
        if (registrar == null) return;

        reportUncoveredBases();
        Map<Identifier, EntityType<?>> types = registrar.registeredEntities();
        registrar.basesInUse().forEach((id, base) -> {
            Factory factory = RENDERERS.get(base);
            EntityType<?> type = types.get(id);
            if (type == null || factory == null) return;

            EntityRendererRegistry.register(cast(type), factory(factory));
        });
    }

    private static <T extends Entity> EntityRendererFactory<T> factory(Factory factory) {
        return context -> castRenderer(factory.create(context));
    }

    private static void reportUncoveredBases() {
        for (String base : dev.lualoader.minecraft.EntityBases.supported()) {
            if (!RENDERERS.containsKey(base)) {
                LuaLoaderClient.LOGGER.warn(
                        "Base {} não tem renderer no runtime Fabric 1.21.4", base);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Entity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType) type;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Entity> EntityRenderer<T, ?> castRenderer(EntityRenderer<?, ?> renderer) {
        return (EntityRenderer) renderer;
    }
}
