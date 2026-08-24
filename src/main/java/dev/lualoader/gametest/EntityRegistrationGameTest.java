package dev.lualoader.gametest;

import dev.lualoader.LuaLoaderMod;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * A espécie declarada, dentro de um servidor de verdade.
 *
 * <p>O núcleo verifica a declaração e a tabela de saque; isto verifica o que só existe no jogo — o
 * tipo entrando no registro, a criatura nascendo com a vida declarada em vez da da base, e o ovo
 * de criação existindo como item. Nenhuma das três falha de um jeito visível: uma espécie que não
 * registrou some do {@code /summon} sem erro, e uma que nasce com a vida errada parece só um bicho
 * comum.
 */
public class EntityRegistrationGameTest implements FabricGameTest {
    private static final Identifier GUARDIAO = Identifier.of("crystal_world", "crystal_guardian");

    private static EntityType<?> requireType() {
        if (!Registries.ENTITY_TYPE.containsId(GUARDIAO)) {
            throw new AssertionError("especie declarada nao foi registrada: " + GUARDIAO);
        }
        return Registries.ENTITY_TYPE.get(GUARDIAO);
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void especieDeclaradaEntraNoRegistro(TestContext context) {
        EntityType<?> type = requireType();

        // O tipo precisa ser o novo, e nao o do golem: derivar da base entrega modelo e IA, mas a
        // entidade tem que se declarar como a especie do mod, senao ela se perde ao salvar o mundo.
        if (type == EntityType.IRON_GOLEM) {
            throw new AssertionError("a especie declarada virou a propria base");
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void nasceComOsAtributosDeclarados(TestContext context) {
        EntityType<?> type = requireType();
        BlockPos position = context.getAbsolutePos(new BlockPos(1, 2, 1));

        Entity entity = type.spawn(context.getWorld(), position, SpawnReason.COMMAND);
        if (entity == null) {
            throw new AssertionError("a especie declarada nao pode nascer");
        }
        if (!(entity instanceof LivingEntity living)) {
            throw new AssertionError("a especie deveria ser viva, veio " + entity);
        }

        // Sessenta e o que o manifesto do exemplo declara; o golem tem cem. O numero sai da
        // declaracao, e nao da base -- e isso que faz declarar valer alguma coisa.
        if (living.getMaxHealth() != 60.0f) {
            throw new AssertionError("vida maxima deveria ser 60, veio " + living.getMaxHealth());
        }
        // Vem do contentor de atributos do tipo, e nao de um ajuste depois do nascimento: aplicado
        // depois, a criatura viveria um instante com a vida da base.
        if (living.getHealth() != 60.0f) {
            throw new AssertionError("deveria nascer com a vida cheia declarada, veio "
                    + living.getHealth());
        }
        entity.discard();
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void ovoDeCriacaoExisteComoItem(TestContext context) {
        Identifier eggId = Identifier.of("crystal_world", "crystal_guardian_spawn_egg");
        if (!Registries.ITEM.containsId(eggId)) {
            throw new AssertionError("ovo de criacao nao foi registrado: " + eggId);
        }
        if (Registries.ITEM.get(eggId) == Items.AIR) {
            // containsId responde para o id reservado; o ar e o que volta quando nada foi posto la.
            throw new AssertionError("ovo de criacao registrado como ar: " + eggId);
        }
        context.complete();
    }

    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void registroFechaDepoisDaCarga(TestContext context) {
        var registrar = LuaLoaderMod.entityRegistrar();
        if (registrar == null) {
            throw new AssertionError("o registrador de especies nao existe");
        }
        // Com o mundo no ar, o jogo ja congelou os registros. Aceitar uma especie aqui produziria
        // um tipo que nao entra em lugar nenhum, e o mod so descobriria ao nao achar o bicho.
        if (registrar.isOpen()) {
            throw new AssertionError("o registro de especies deveria ter fechado apos a carga");
        }
        context.complete();
    }

    /**
     * Uma espécie que descende da espécie declarada por outro mod.
     *
     * <p>É o registro externo que vale nas duas plataformas: {@code bestiario} não conhece o golem,
     * só o guardião do {@code crystal_world}, e mesmo assim nasce com modelo e comportamento. É o
     * que substitui um {@code register_entity} chamado por script, que funcionaria no Fabric — onde
     * o Lua carrega antes do congelamento — e falharia sempre no NeoForge.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void especieHerdaDeOutroMod(TestContext context) {
        Identifier eliteId = Identifier.of("bestiario", "elite_guardian");
        if (!Registries.ENTITY_TYPE.containsId(eliteId)) {
            throw new AssertionError("especie herdada de outro mod nao foi registrada: " + eliteId);
        }

        var registrar = LuaLoaderMod.entityRegistrar();
        var definition = registrar.declaredEntity(eliteId);
        if (definition == null) {
            throw new AssertionError("a especie herdada nao aparece como declarada");
        }
        // A base efetiva e a do ancestral do jogo, e nao "crystal_world:crystal_guardian": e dela
        // que vem modelo e comportamento, e o adaptador precisa dela para achar os dois.
        if (!"minecraft:iron_golem".equals(definition.base)) {
            throw new AssertionError("a base efetiva deveria ser a do ancestral, veio "
                    + definition.base);
        }

        BlockPos position = context.getAbsolutePos(new BlockPos(1, 2, 1));
        Entity entity = Registries.ENTITY_TYPE.get(eliteId)
                .spawn(context.getWorld(), position, SpawnReason.COMMAND);
        if (!(entity instanceof LivingEntity living)) {
            throw new AssertionError("a especie herdada deveria ser viva, veio " + entity);
        }
        // Cento e vinte e o que o filho declarou; o pai declarou sessenta. O filho vence no que
        // declarou, e herda o resto -- e o que deixa um mod de dificuldade ter tres linhas.
        if (living.getMaxHealth() != 120.0f) {
            throw new AssertionError("vida maxima deveria ser 120, veio " + living.getMaxHealth());
        }
        entity.discard();
        context.complete();
    }

    /**
     * A tag declarada chegou ao jogo, e não só ao disco.
     *
     * <p>Tag era o único campo de espécie que o manifesto aceitava e o loader ignorava. Um arquivo
     * escrito na pasta errada — {@code entity} em vez de {@code entity_type} — é lido por ninguém
     * e some sem erro, então conferir que o arquivo existe não prova nada. Só o jogo responde.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void tagDeclaradaValeNoJogo(TestContext context) {
        var tag = net.minecraft.registry.tag.TagKey.of(
                net.minecraft.registry.RegistryKeys.ENTITY_TYPE,
                Identifier.of("crystal_world", "guardians"));

        if (!Registries.ENTITY_TYPE.getEntry(requireType()).isIn(tag)) {
            throw new AssertionError("a especie deveria estar na tag crystal_world:guardians");
        }
        context.complete();
    }

    /**
     * Espécies criadas por script, na fase de registro.
     *
     * <p>É a divergência estrutural fechada. O Lua do Fabric carrega antes de o jogo congelar os
     * registros e o do NeoForge depois, então registrar por script valeria só aqui — a menos que
     * cada adaptador ganhasse um momento próprio para isso. Ganhou, e este teste tem um par
     * idêntico do outro lado justamente para provar que os dois respondem igual.
     *
     * <p>O que o script faz e o manifesto não faria: gerar. As três saem de um laço.
     */
    @GameTest(templateName = FabricGameTest.EMPTY_STRUCTURE)
    public void especiesGeradasPorScriptExistem(TestContext context) {
        String[] niveis = {"bronze", "prata", "ouro"};
        float[] vidas = {40.0f, 80.0f, 160.0f};

        for (int index = 0; index < niveis.length; index++) {
            Identifier id = Identifier.of("bestiario", "guardiao_" + niveis[index]);
            if (!Registries.ENTITY_TYPE.containsId(id)) {
                throw new AssertionError("especie gerada por script nao registrou: " + id);
            }

            BlockPos position = context.getAbsolutePos(new BlockPos(1, 2, 1));
            Entity entity = Registries.ENTITY_TYPE.get(id)
                    .spawn(context.getWorld(), position, SpawnReason.COMMAND);
            if (!(entity instanceof LivingEntity living)) {
                throw new AssertionError("a especie gerada deveria ser viva: " + id);
            }
            // O numero sai do laco do script, e nao de um padrao: e o que prova que o que foi
            // declarado por codigo chegou inteiro ao jogo.
            if (living.getMaxHealth() != vidas[index]) {
                throw new AssertionError(id + " deveria ter " + vidas[index] + " de vida, veio "
                        + living.getMaxHealth());
            }
            entity.discard();
        }

        // O ovo tambem: uma especie gerada nao e cidada de segunda classe.
        if (!Registries.ITEM.containsId(Identifier.of("bestiario", "guardiao_ouro_spawn_egg"))) {
            throw new AssertionError("a especie gerada deveria ter ovo de criacao");
        }
        context.complete();
    }
}
