package dev.lualoader.neoforge.client;

import dev.lualoader.neoforge.NeoForgeEntityBases;
import dev.lualoader.neoforge.NeoForgeLuaLoader;
import net.minecraft.client.renderer.entity.ChickenRenderer;
import net.minecraft.client.renderer.entity.CowRenderer;
import net.minecraft.client.renderer.entity.CreeperRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.IronGolemRenderer;
import net.minecraft.client.renderer.entity.PigRenderer;
import net.minecraft.client.renderer.entity.SheepRenderer;
import net.minecraft.client.renderer.entity.SkeletonRenderer;
import net.minecraft.client.renderer.entity.SpiderRenderer;
import net.minecraft.client.renderer.entity.WolfRenderer;
import net.minecraft.client.renderer.entity.ZombieRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Registra os renderers de entidades declaradas no runtime NeoForge 1.21.4.
 *
 * <p>A 1.21.4 mudou para render states e deixou incompatível o renderer personalizado antigo. Este
 * primeiro bridge mantém a entidade visível usando o renderer vanilla da sua base. O manifesto e o
 * núcleo continuam iguais; modelos/skins personalizados ficam como capacidade futura até serem
 * portados para o novo contrato de estados.
 */
public final class NeoForgeEntityRenderers {
    private NeoForgeEntityRenderers() {
    }

    private interface Factory {
        EntityRenderer<?, ?> create(EntityRendererProvider.Context context);
    }

    private static final Map<String, Factory> RENDERERS = new LinkedHashMap<>();

    private static void put(String base, Factory factory) {
        RENDERERS.put(base, factory);
    }

    static {
        put("minecraft:zombie", ZombieRenderer::new);
        put("minecraft:skeleton", SkeletonRenderer::new);
        put("minecraft:creeper", CreeperRenderer::new);
        put("minecraft:spider", SpiderRenderer::new);
        put("minecraft:pig", PigRenderer::new);
        put("minecraft:cow", CowRenderer::new);
        put("minecraft:sheep", SheepRenderer::new);
        put("minecraft:chicken", ChickenRenderer::new);
        put("minecraft:wolf", WolfRenderer::new);
        put("minecraft:iron_golem", IronGolemRenderer::new);
    }

    public static void install(IEventBus modBus) {
        modBus.addListener(NeoForgeEntityRenderers::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        var registrar = NeoForgeLuaLoader.entityRegistrar();
        if (registrar == null) return;

        reportUncoveredBases();
        Map<ResourceLocation, EntityType<?>> types = registrar.registeredEntities();
        registrar.basesInUse().forEach((id, base) -> {
            Factory factory = RENDERERS.get(base);
            EntityType<?> type = types.get(id);
            if (type == null || factory == null) return;

            event.registerEntityRenderer(cast(type), context -> createRenderer(factory, context));
        });
    }

    private static <T extends Entity> EntityRenderer<T, ?> createRenderer(
            Factory factory, EntityRendererProvider.Context context) {
        return (EntityRenderer<T, ?>) factory.create(context);
    }

    private static void reportUncoveredBases() {
        for (String base : NeoForgeEntityBases.supported()) {
            if (!RENDERERS.containsKey(base)) {
                NeoForgeLuaLoader.LOGGER.warn(
                        "Base {} não tem renderer no runtime NeoForge 1.21.4", base);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Entity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType) type;
    }
}
