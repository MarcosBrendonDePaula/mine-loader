package dev.lualoader.client;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.content.EntityModelSpec;
import dev.lualoader.minecraft.EntityBases;
import dev.lualoader.resources.GeneratedResourcePackProvider;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.minecraft.client.model.ModelPart;
import net.minecraft.client.render.entity.ChickenEntityRenderer;
import net.minecraft.client.render.entity.CowEntityRenderer;
import net.minecraft.client.render.entity.CreeperEntityRenderer;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.EntityRendererFactory;
import net.minecraft.client.render.entity.IronGolemEntityRenderer;
import net.minecraft.client.render.entity.PigEntityRenderer;
import net.minecraft.client.render.entity.SheepEntityRenderer;
import net.minecraft.client.render.entity.SkeletonEntityRenderer;
import net.minecraft.client.render.entity.SpiderEntityRenderer;
import net.minecraft.client.render.entity.WolfEntityRenderer;
import net.minecraft.client.render.entity.ZombieEntityRenderer;
import net.minecraft.client.render.entity.model.ChickenEntityModel;
import net.minecraft.client.render.entity.model.CowEntityModel;
import net.minecraft.client.render.entity.model.CreeperEntityModel;
import net.minecraft.client.render.entity.model.IronGolemEntityModel;
import net.minecraft.client.render.entity.model.PigEntityModel;
import net.minecraft.client.render.entity.model.SheepEntityModel;
import net.minecraft.client.render.entity.model.SkeletonEntityModel;
import net.minecraft.client.render.entity.model.SpiderEntityModel;
import net.minecraft.client.render.entity.model.WolfEntityModel;
import net.minecraft.client.render.entity.model.ZombieEntityModel;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.AbstractSkeletonEntity;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.util.Identifier;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Liga cada espécie declarada ao desenhista da base, com a forma e a pele que ela declarou.
 *
 * <p>Uma espécie registrada sem desenhista <b>não dá erro</b>: ela existe, anda, ataca e morre, e
 * simplesmente não aparece na tela. É o pior desfecho possível — o log fica verde, o servidor
 * concorda que o bicho está ali, e só quem está olhando percebe que não há nada. Por isso a
 * cobertura das bases é conferida em voz alta ao subir.
 *
 * <p><b>A geometria declarada é entregue à classe de modelo da própria base.</b> Ela recebe uma
 * raiz e procura os filhos por nome — {@code head}, {@code body}, {@code right_arm} — para girá-los
 * a cada quadro. Trocando só a raiz, a animação do golem passa a mover as caixas novas sem saber
 * que elas mudaram: é forma própria sem escrever uma linha de animação.
 */
public final class EntityRenderers {
    private EntityRenderers() {
    }

    /**
     * Como cada base constrói um desenhista, já sabendo a forma e a pele da espécie.
     *
     * <p>Um desenhista por espécie, e não por base: duas espécies derivadas do mesmo golem podem
     * ter geometrias diferentes, e um desenhista compartilhado só conseguiria desenhar uma delas.
     *
     * @param custom  raiz da geometria declarada, ou {@code null} para a forma da base
     * @param texture pele declarada, ou {@code null} para a da base
     */
    private interface Skin {
        EntityRenderer<?> create(EntityRendererFactory.Context context,
                                 ModelPart custom, Identifier texture);
    }

    private static final Map<String, Skin> RENDERERS = new LinkedHashMap<>();

    private static void put(String base, Skin skin) {
        RENDERERS.put(base, skin);
    }

    static {
        // Cada base entrega o desenhista dela, com dois desvios: a raiz declarada substitui o
        // modelo, e a pele declarada substitui a textura. Sem nenhuma das duas, o desenhista se
        // comporta exatamente como o do jogo.
        put("minecraft:zombie", (context, custom, texture) -> new ZombieEntityRenderer(context) {
            {
                if (custom != null) this.model = new ZombieEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(ZombieEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:skeleton", (context, custom, texture) ->
                new SkeletonEntityRenderer(context) {
                    {
                        if (custom != null) this.model = new SkeletonEntityModel<>(custom);
                    }

                    @Override
                    public Identifier getTexture(AbstractSkeletonEntity entity) {
                        return texture == null ? super.getTexture(entity) : texture;
                    }
                });
        put("minecraft:creeper", (context, custom, texture) -> new CreeperEntityRenderer(context) {
            {
                if (custom != null) this.model = new CreeperEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(CreeperEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:spider", (context, custom, texture) ->
                new SpiderEntityRenderer<SpiderEntity>(context) {
                    {
                        if (custom != null) this.model = new SpiderEntityModel<>(custom);
                    }

                    @Override
                    public Identifier getTexture(SpiderEntity entity) {
                        return texture == null ? super.getTexture(entity) : texture;
                    }
                });
        put("minecraft:pig", (context, custom, texture) -> new PigEntityRenderer(context) {
            {
                if (custom != null) this.model = new PigEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(PigEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:cow", (context, custom, texture) -> new CowEntityRenderer(context) {
            {
                if (custom != null) this.model = new CowEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(CowEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:sheep", (context, custom, texture) -> new SheepEntityRenderer(context) {
            {
                if (custom != null) this.model = new SheepEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(SheepEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:chicken", (context, custom, texture) -> new ChickenEntityRenderer(context) {
            {
                if (custom != null) this.model = new ChickenEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(ChickenEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:wolf", (context, custom, texture) -> new WolfEntityRenderer(context) {
            {
                if (custom != null) this.model = new WolfEntityModel<>(custom);
            }

            @Override
            public Identifier getTexture(WolfEntity entity) {
                return texture == null ? super.getTexture(entity) : texture;
            }
        });
        put("minecraft:iron_golem", (context, custom, texture) ->
                new IronGolemEntityRenderer(context) {
                    {
                        if (custom != null) this.model = new IronGolemEntityModel<>(custom);
                    }

                    @Override
                    public Identifier getTexture(IronGolemEntity entity) {
                        return texture == null ? super.getTexture(entity) : texture;
                    }
                });
    }

    /** Registra o desenhista de cada espécie e denuncia qualquer base sem um. */
    public static void register() {
        var registrar = LuaLoaderMod.entityRegistrar();
        if (registrar == null) return;

        reportUncoveredBases();

        Map<Identifier, EntityType<?>> types = registrar.registeredEntities();
        registrar.basesInUse().forEach((id, base) -> {
            Skin skin = RENDERERS.get(base);
            EntityType<?> type = types.get(id);
            if (type == null) return;

            if (skin == null) {
                // Nao ha desenhista generico a que recorrer: o jogo precisa de um modelo concreto.
                // Entao o unico caminho honesto e dizer, e alto.
                LuaLoaderClient.LOGGER.error(
                        "Entidade {} nao tem desenhista para a base {}; ela sera invisivel",
                        id, base);
                return;
            }

            var definition = registrar.declaredEntity(id);
            Identifier texture = definition != null && definition.texture != null
                    && !definition.texture.isBlank()
                    ? Identifier.of(id.getNamespace(), "textures/entity/" + id.getPath() + ".png")
                    : null;
            ModelPart custom = customRoot(id, definition == null ? null : definition.model);

            EntityRendererRegistry.register(cast(type),
                    context -> skin.create(context, custom, texture));
        });
    }

    /**
     * Monta a raiz da geometria declarada, lendo o modelo do pacote gerado.
     *
     * <p>Lê do disco, e não do gerenciador de recursos do jogo: os desenhistas são registrados
     * antes da primeira carga de recursos, e pedir o arquivo por lá nesta altura não devolveria
     * nada — a espécie sairia com a forma da base e ninguém saberia por quê.
     *
     * <p>Um modelo que falha aqui vira aviso e a espécie usa a forma da base. É feio e visível, que
     * é melhor que uma criatura deformada sem uma linha de log.
     */
    private static ModelPart customRoot(Identifier id, String declared) {
        if (declared == null || declared.isBlank()) return null;

        Path root = GeneratedResourcePackProvider.root();
        if (root == null) return null;

        Path file = root.resolve("assets").resolve(id.getNamespace())
                .resolve("models/entity").resolve(id.getPath() + ".json");
        if (!Files.isRegularFile(file)) {
            LuaLoaderClient.LOGGER.warn("Modelo de {} nao foi gerado; usando a forma da base", id);
            return null;
        }

        try {
            EntityModelSpec spec =
                    EntityModelSpec.parse(Files.readString(file, StandardCharsets.UTF_8));
            LuaLoaderClient.LOGGER.info("Entidade {} desenhada com forma propria: {} osso(s)",
                    id, spec.bones.size());
            return DeclaredEntityModels.rootOf(spec);
        } catch (Exception error) {
            LuaLoaderClient.LOGGER.warn("Modelo de {} recusado; usando a forma da base: {}",
                    id, error.getMessage());
            return null;
        }
    }

    /**
     * Avisa sobre base suportada no servidor e sem desenhista aqui.
     *
     * <p>Antes de qualquer mod usá-la: descobrir a falta quando alguém já declarou a espécie é
     * tarde, porque a essa altura o bicho invisível já está no mundo de alguém.
     */
    private static void reportUncoveredBases() {
        for (String base : EntityBases.supported()) {
            if (RENDERERS.containsKey(base)) continue;
            LuaLoaderClient.LOGGER.error("Base {} e suportada no servidor e nao tem desenhista no"
                    + " cliente; toda especie derivada dela sera invisivel", base);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T extends net.minecraft.entity.Entity> EntityType<T> cast(EntityType<?> type) {
        return (EntityType) type;
    }
}
