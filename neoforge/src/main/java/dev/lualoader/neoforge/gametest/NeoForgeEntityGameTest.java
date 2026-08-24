package dev.lualoader.neoforge.gametest;

import dev.lualoader.neoforge.NeoForgeLuaLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * A espécie declarada, dentro de um servidor NeoForge de verdade.
 *
 * <p>O irmão de {@code EntityRegistrationGameTest} do Fabric, e o par existe pelo motivo que
 * {@code NeoForgeBlockGameTest} registra: enquanto os GameTests rodaram só de um lado, seis
 * divergências entre os adaptadores se acumularam sem quebrar nada. Aqui a pergunta é a mesma nas
 * duas plataformas — mesmo manifesto, mesma criatura —, e uma delas responder diferente é o
 * resultado que o par existe para produzir.
 */
@GameTestHolder("lua_loader")
@PrefixGameTestTemplate(false)
public class NeoForgeEntityGameTest {
    private static final ResourceLocation GUARDIAO =
            ResourceLocation.fromNamespaceAndPath("crystal_world", "crystal_guardian");
    private static final String EMPTY = "empty";

    private static EntityType<?> requireType() {
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(GUARDIAO);
        // O registro devolve o porco para um id desconhecido, em vez de nulo: sem esta comparacao
        // um tipo que nunca foi registrado passaria no teste como se estivesse la.
        if (type == null || type == EntityType.PIG) {
            throw new AssertionError("especie declarada nao foi registrada: " + GUARDIAO);
        }
        return type;
    }

    /** A espécie declarada no manifesto chegou ao registro, e não virou a própria base. */
    @GameTest(template = EMPTY)
    public static void especieDeclaradaEstaRegistrada(GameTestHelper helper) {
        EntityType<?> type = requireType();

        // Derivar da base entrega modelo e IA, mas a entidade tem que se declarar como a especie
        // do mod: se ela reportar o tipo do golem, se perde ao salvar o mundo.
        if (type == EntityType.IRON_GOLEM) {
            throw new AssertionError("a especie declarada virou a propria base");
        }
        helper.succeed();
    }

    /** Nasce com a vida declarada no manifesto, e não com a da base. */
    @GameTest(template = EMPTY)
    public static void nasceComOsAtributosDeclarados(GameTestHelper helper) {
        EntityType<?> type = requireType();
        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));

        Entity entity = type.spawn(helper.getLevel(), position, MobSpawnType.COMMAND);
        if (entity == null) {
            throw new AssertionError("a especie declarada nao pode nascer");
        }
        if (!(entity instanceof LivingEntity living)) {
            throw new AssertionError("a especie deveria ser viva, veio " + entity);
        }

        // Sessenta e o que o manifesto do exemplo declara; o golem tem cem. O mesmo numero e
        // conferido no Fabric: e a pergunta que denuncia uma plataforma aplicando diferente.
        if (living.getMaxHealth() != 60.0f) {
            throw new AssertionError("vida maxima deveria ser 60, veio " + living.getMaxHealth());
        }
        if (living.getHealth() != 60.0f) {
            throw new AssertionError("deveria nascer com a vida cheia declarada, veio "
                    + living.getHealth());
        }
        entity.discard();
        helper.succeed();
    }

    /** O ovo de criação existe como item, e não como ar. */
    @GameTest(template = EMPTY)
    public static void ovoDeCriacaoExisteComoItem(GameTestHelper helper) {
        ResourceLocation eggId =
                ResourceLocation.fromNamespaceAndPath("crystal_world", "crystal_guardian_spawn_egg");

        if (BuiltInRegistries.ITEM.get(eggId) == Items.AIR) {
            throw new AssertionError("ovo de criacao nao foi registrado: " + eggId);
        }
        helper.succeed();
    }

    /** Com o mundo no ar, o registro já fechou. */
    @GameTest(template = EMPTY)
    public static void registroFechaDepoisDaCarga(GameTestHelper helper) {
        var registrar = NeoForgeLuaLoader.entityRegistrar();
        if (registrar == null) {
            throw new AssertionError("o registrador de especies nao existe");
        }
        // Aceitar uma especie aqui produziria um tipo que nao entra em lugar nenhum, e o mod so
        // descobriria ao nao achar o bicho.
        if (registrar.isOpen()) {
            throw new AssertionError("o registro de especies deveria ter fechado apos a carga");
        }
        helper.succeed();
    }

    /**
     * Uma espécie que descende da espécie declarada por outro mod.
     *
     * <p>A mesma pergunta do lado Fabric, e é o par que importa: este é o caminho que substitui um
     * {@code register_entity} em tempo de execução, justamente porque aquele funcionaria lá e
     * falharia sempre aqui — o Lua do NeoForge carrega depois de o jogo congelar os registros.
     */
    @GameTest(template = EMPTY)
    public static void especieHerdaDeOutroMod(GameTestHelper helper) {
        ResourceLocation eliteId =
                ResourceLocation.fromNamespaceAndPath("bestiario", "elite_guardian");

        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(eliteId);
        if (type == null || type == EntityType.PIG) {
            throw new AssertionError("especie herdada de outro mod nao foi registrada: " + eliteId);
        }

        var definition = NeoForgeLuaLoader.entityRegistrar().declaredEntity(eliteId);
        if (definition == null) {
            throw new AssertionError("a especie herdada nao aparece como declarada");
        }
        if (!"minecraft:iron_golem".equals(definition.base)) {
            throw new AssertionError("a base efetiva deveria ser a do ancestral, veio "
                    + definition.base);
        }

        BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
        Entity entity = type.spawn(helper.getLevel(), position, MobSpawnType.COMMAND);
        if (!(entity instanceof LivingEntity living)) {
            throw new AssertionError("a especie herdada deveria ser viva, veio " + entity);
        }
        if (living.getMaxHealth() != 120.0f) {
            throw new AssertionError("vida maxima deveria ser 120, veio " + living.getMaxHealth());
        }
        entity.discard();
        helper.succeed();
    }

    /**
     * A tag declarada chegou ao jogo, e não só ao disco.
     *
     * <p>O par do lado Fabric. Um arquivo de tag escrito na pasta errada é lido por ninguém e some
     * sem erro nenhum, então conferir que o arquivo existe não prova nada — só o jogo responde.
     */
    @GameTest(template = EMPTY)
    public static void tagDeclaradaValeNoJogo(GameTestHelper helper) {
        var tag = net.minecraft.tags.TagKey.create(
                net.minecraft.core.registries.Registries.ENTITY_TYPE,
                ResourceLocation.fromNamespaceAndPath("crystal_world", "guardians"));

        if (!requireType().is(tag)) {
            throw new AssertionError("a especie deveria estar na tag crystal_world:guardians");
        }
        helper.succeed();
    }

    /**
     * Espécies criadas por script, na fase de registro.
     *
     * <p><b>Este é o teste que justifica a fase existir.</b> Aqui o Lua carrega quando o servidor
     * sobe, muito depois de o jogo congelar os registros: sem um momento próprio, nada que um
     * script declarasse chegaria ao registro, e a operação valeria só no Fabric. Se este caso
     * passar e o par do Fabric também, as duas plataformas respondem igual ao mesmo script.
     */
    @GameTest(template = EMPTY)
    public static void especiesGeradasPorScriptExistem(GameTestHelper helper) {
        String[] niveis = {"bronze", "prata", "ouro"};
        float[] vidas = {40.0f, 80.0f, 160.0f};

        for (int index = 0; index < niveis.length; index++) {
            ResourceLocation id =
                    ResourceLocation.fromNamespaceAndPath("bestiario", "guardiao_" + niveis[index]);

            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(id);
            if (type == null || type == EntityType.PIG) {
                throw new AssertionError("especie gerada por script nao registrou: " + id);
            }

            BlockPos position = helper.absolutePos(new BlockPos(1, 2, 1));
            Entity entity = type.spawn(helper.getLevel(), position, MobSpawnType.COMMAND);
            if (!(entity instanceof LivingEntity living)) {
                throw new AssertionError("a especie gerada deveria ser viva: " + id);
            }
            if (living.getMaxHealth() != vidas[index]) {
                throw new AssertionError(id + " deveria ter " + vidas[index] + " de vida, veio "
                        + living.getMaxHealth());
            }
            entity.discard();
        }

        if (BuiltInRegistries.ITEM.get(ResourceLocation.fromNamespaceAndPath(
                "bestiario", "guardiao_ouro_spawn_egg")) == Items.AIR) {
            throw new AssertionError("a especie gerada deveria ter ovo de criacao");
        }
        helper.succeed();
    }
}
