package dev.lualoader.neoforge.client;

import dev.lualoader.content.EntityModelSpec;
import dev.lualoader.neoforge.NeoForgeEntityBases;
import dev.lualoader.neoforge.NeoForgeLuaLoader;
import net.minecraft.client.model.ChickenModel;
import net.minecraft.client.model.CowModel;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.IronGolemModel;
import net.minecraft.client.model.PigModel;
import net.minecraft.client.model.SheepModel;
import net.minecraft.client.model.SkeletonModel;
import net.minecraft.client.model.SpiderModel;
import net.minecraft.client.model.WolfModel;
import net.minecraft.client.model.ZombieModel;
import net.minecraft.client.model.geom.ModelPart;
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
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.AbstractSkeleton;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liga cada espécie declarada ao desenhista da base, com a forma e a pele que ela declarou.
 *
 * <p>O par de {@code EntityRenderers} do Fabric. Os dois existem porque as plataformas nomeiam as
 * mesmas classes do jogo de formas diferentes; o que não pode divergir é a criatura que sai na
 * tela.
 *
 * <p>Uma espécie registrada sem desenhista <b>não dá erro</b>: ela existe, anda e morre, e
 * simplesmente não aparece. Por isso a cobertura das bases é conferida em voz alta ao subir.
 */
public final class NeoForgeEntityRenderers {
    private NeoForgeEntityRenderers() {
    }

    /**
     * Como cada base constrói um desenhista, já sabendo a forma e a pele da espécie.
     *
     * @param custom  raiz da geometria declarada, ou {@code null} para a forma da base
     * @param texture pele declarada, ou {@code null} para a da base
     */
    private interface Skin {
        EntityRenderer<?> create(EntityRendererProvider.Context context,
                                 ModelPart custom, ResourceLocation texture);
    }

    private static final Map<String, Skin> RENDERERS = new LinkedHashMap<>();

    private static void put(String base, Skin skin) {
        RENDERERS.put(base, skin);
    }

    static {
        // Cada base entrega o desenhista dela, com dois desvios: a raiz declarada substitui o
        // modelo, e a pele declarada substitui a textura. Sem nenhuma das duas, o desenhista se
        // comporta exatamente como o do jogo.
        put("minecraft:zombie", (context, custom, texture) -> new ZombieRenderer(context) {
            {
                if (custom != null) this.model = new ZombieModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Zombie entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:skeleton", (context, custom, texture) -> new SkeletonRenderer(context) {
            {
                if (custom != null) this.model = new SkeletonModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(AbstractSkeleton entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:creeper", (context, custom, texture) -> new CreeperRenderer(context) {
            {
                if (custom != null) this.model = new CreeperModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Creeper entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:spider", (context, custom, texture) -> new SpiderRenderer<Spider>(context) {
            {
                if (custom != null) this.model = new SpiderModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Spider entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:pig", (context, custom, texture) -> new PigRenderer(context) {
            {
                if (custom != null) this.model = new PigModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Pig entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:cow", (context, custom, texture) -> new CowRenderer(context) {
            {
                if (custom != null) this.model = new CowModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Cow entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:sheep", (context, custom, texture) -> new SheepRenderer(context) {
            {
                if (custom != null) this.model = new SheepModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Sheep entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:chicken", (context, custom, texture) -> new ChickenRenderer(context) {
            {
                if (custom != null) this.model = new ChickenModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Chicken entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:wolf", (context, custom, texture) -> new WolfRenderer(context) {
            {
                if (custom != null) this.model = new WolfModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(Wolf entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
        put("minecraft:iron_golem", (context, custom, texture) -> new IronGolemRenderer(context) {
            {
                if (custom != null) this.model = new IronGolemModel<>(custom);
            }

            @Override
            public ResourceLocation getTextureLocation(IronGolem entity) {
                return texture == null ? super.getTextureLocation(entity) : texture;
            }
        });
    }

    /** Liga o registro ao evento em que o jogo pede os desenhistas. */
    public static void install(IEventBus modBus) {
        modBus.addListener(NeoForgeEntityRenderers::onRegisterRenderers);
    }

    private static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        var registrar = NeoForgeLuaLoader.entityRegistrar();
        if (registrar == null) return;

        reportUncoveredBases();

        Map<ResourceLocation, EntityType<?>> types = registrar.registeredEntities();
        registrar.basesInUse().forEach((id, base) -> {
            Skin skin = RENDERERS.get(base);
            EntityType<?> type = types.get(id);
            if (type == null) return;

            if (skin == null) {
                // Nao ha desenhista generico a que recorrer: o jogo precisa de um modelo concreto.
                NeoForgeLuaLoader.LOGGER.error(
                        "Entidade {} nao tem desenhista para a base {}; ela sera invisivel",
                        id, base);
                return;
            }

            var definition = registrar.declaredEntity(id);
            ResourceLocation texture = definition != null && definition.texture != null
                    && !definition.texture.isBlank()
                    ? ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                            "textures/entity/" + id.getPath() + ".png")
                    : null;
            ModelPart custom = customRoot(id, definition == null ? null : definition.model);

            event.registerEntityRenderer(cast(type),
                    context -> skin.create(context, custom, texture));
        });
    }

    /**
     * Monta a raiz da geometria declarada, lendo o modelo do pacote gerado.
     *
     * <p>Lê do disco, e não do gerenciador de recursos do jogo: os desenhistas são registrados
     * antes da primeira carga de recursos, e pedir o arquivo por lá nesta altura não devolveria
     * nada — a espécie sairia com a forma da base e ninguém saberia por quê.
     */
    private static ModelPart customRoot(ResourceLocation id, String declared) {
        if (declared == null || declared.isBlank()) return null;

        Path file = net.neoforged.fml.loading.FMLPaths.GAMEDIR.get()
                .resolve("lua-loader").resolve("generated-pack")
                .resolve("assets").resolve(id.getNamespace())
                .resolve("models/entity").resolve(id.getPath() + ".json");
        if (!Files.isRegularFile(file)) {
            NeoForgeLuaLoader.LOGGER.warn("Modelo de {} nao foi gerado; usando a forma da base", id);
            return null;
        }

        try {
            EntityModelSpec spec =
                    EntityModelSpec.parse(Files.readString(file, StandardCharsets.UTF_8));
            NeoForgeLuaLoader.LOGGER.info("Entidade {} desenhada com forma propria: {} osso(s)",
                    id, spec.bones.size());
            return NeoForgeDeclaredModels.rootOf(spec);
        } catch (Exception error) {
            NeoForgeLuaLoader.LOGGER.warn("Modelo de {} recusado; usando a forma da base: {}",
                    id, error.getMessage());
            return null;
        }
    }

    /** Avisa sobre base suportada no servidor e sem desenhista aqui. */
    private static void reportUncoveredBases() {
        for (String base : NeoForgeEntityBases.supported()) {
            if (RENDERERS.containsKey(base)) continue;
            NeoForgeLuaLoader.LOGGER.error("Base {} e suportada no servidor e nao tem desenhista no"
                    + " cliente; toda especie derivada dela sera invisivel", base);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends Entity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType) type;
    }
}
