package dev.lualoader.minecraft;

import dev.lualoader.content.EntityAi;
import dev.lualoader.mixin.MobEntityAccessor;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.EscapeDangerGoal;
import net.minecraft.entity.ai.goal.FleeEntityGoal;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.LookAtEntityGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.SwimGoal;
import net.minecraft.entity.ai.goal.TemptGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Traduz o comportamento declarado para as metas do jogo.
 *
 * <p>O mod diz "foge de"; qual classe do Minecraft faz isso nesta versão é problema deste arquivo.
 * É a mesma regra do protocolo de telas — vocabulário fechado nos dois sentidos —, e existe porque
 * as classes de meta já mudaram de nome mais de uma vez: um manifesto que as nomeasse pararia de
 * funcionar sem ter mudado nada.
 *
 * <p>As metas são aplicadas quando a criatura entra no mundo, no mesmo ponto em que os padrões
 * declarados são aplicados. Não dá para fazer isso no registro: os seletores de meta pertencem à
 * instância, e no registro só existe o tipo.
 */
public final class DeclaredGoals {
    private DeclaredGoals() {
    }

    /**
     * Aplica o comportamento declarado a uma criatura recém-chegada ao mundo.
     *
     * <p>Uma meta que a base não suporta é dita e pulada, e não recusada: metade das metas do jogo
     * exige uma entidade que saiba andar por caminho, e uma espécie derivada de algo que não sabe
     * simplesmente não pode ter aquele comportamento. Recusar tudo por causa de uma faria o mod
     * perder o resto do que declarou.
     */
    public static void apply(Logger logger, MobEntity mob, EntityAi ai) {
        if (ai == null) return;

        var goals = ((MobEntityAccessor) mob).lua_loader$goalSelector();
        var targets = ((MobEntityAccessor) mob).lua_loader$targetSelector();

        if (ai.clear) {
            // As duas listas: limpar so as metas deixaria a criatura ainda caçando, sem saber
            // perseguir -- ela ficaria parada encarando o alvo.
            goals.clear(goal -> true);
            targets.clear(goal -> true);
        }

        int fallback = 0;
        for (EntityAi.Goal declared : ai.goals) {
            int priority = declared.priority == null ? fallback : declared.priority;
            fallback++;

            Goal goal = translateGoal(logger, mob, declared);
            if (goal != null) goals.add(priority, goal);
        }

        fallback = 0;
        for (EntityAi.Target declared : ai.targets) {
            int priority = declared.priority == null ? fallback : declared.priority;
            fallback++;

            Goal goal = translateTarget(logger, mob, declared);
            if (goal != null) targets.add(priority, goal);
        }
    }

    private static Goal translateGoal(Logger logger, MobEntity mob, EntityAi.Goal declared) {
        String type = EntityAi.normalized(declared.type);

        switch (type) {
            case "float":
                return new SwimGoal(mob);
            case "look_around":
                return new LookAroundGoal(mob);
            case "look_at_player":
                return new LookAtEntityGoal(mob, PlayerEntity.class, (float) declared.range);
            default:
                break;
        }

        // As metas que andam exigem uma criatura que saiba seguir caminho. Um morcego nao sabe, e
        // pedir a ele que vagueie derrubaria o jogo em vez de nao acontecer nada.
        if (!(mob instanceof PathAwareEntity walker)) {
            logger.warn("A meta {} exige uma criatura que ande por caminho; {} nao anda",
                    type, Registries.ENTITY_TYPE.getId(mob.getType()));
            return null;
        }

        return switch (type) {
            case "wander" -> new WanderAroundFarGoal(walker, declared.speed);
            case "panic" -> new EscapeDangerGoal(walker, declared.speed);
            case "melee_attack" -> new MeleeAttackGoal(walker, declared.speed, false);
            case "follow_item" -> temptGoal(logger, walker, declared);
            case "avoid" -> avoidGoal(logger, walker, declared);
            default -> null;
        };
    }

    /** Ir atrás de quem segura um dos itens declarados. */
    private static Goal temptGoal(Logger logger, PathAwareEntity walker, EntityAi.Goal declared) {
        List<net.minecraft.item.Item> items = new ArrayList<>();
        for (String id : declared.items) {
            Identifier parsed = Identifier.tryParse(id);
            if (parsed == null || !Registries.ITEM.containsId(parsed)) {
                logger.warn("Item desconhecido numa meta follow_item: {}", id);
                continue;
            }
            items.add(Registries.ITEM.get(parsed));
        }
        if (items.isEmpty()) return null;

        return new TemptGoal(walker, declared.speed,
                Ingredient.ofItems(items.toArray(new net.minecraft.item.Item[0])), false);
    }

    /** Manter distância de uma espécie. */
    private static Goal avoidGoal(Logger logger, PathAwareEntity walker, EntityAi.Goal declared) {
        Class<? extends LivingEntity> target = livingClassOf(logger, declared.entity);
        if (target == null) return null;

        // A segunda velocidade e a de quando o perigo esta perto: fugir devagar de longe e correr
        // de perto e o que faz a fuga parecer reacao, e nao trajeto.
        return new FleeEntityGoal<>(walker, cast(target), (float) declared.range,
                declared.speed, declared.speed * 1.4);
    }

    private static Goal translateTarget(Logger logger, MobEntity mob, EntityAi.Target declared) {
        String type = EntityAi.normalized(declared.type);

        // Revidar exige perseguir quem feriu, e perseguir exige andar por caminho.
        if ("hurt_by".equals(type)) {
            if (!(mob instanceof PathAwareEntity walker)) {
                logger.warn("O alvo hurt_by exige uma criatura que ande por caminho; {} nao anda",
                        Registries.ENTITY_TYPE.getId(mob.getType()));
                return null;
            }
            return new RevengeGoal(walker);
        }

        return switch (type) {
            case "attack_player" -> new ActiveTargetGoal<>(mob, PlayerEntity.class, true);
            case "attack_entity" -> attackEntity(logger, mob, declared);
            default -> null;
        };
    }

    private static Goal attackEntity(Logger logger, MobEntity mob, EntityAi.Target declared) {
        Class<? extends LivingEntity> target = livingClassOf(logger, declared.entity);
        if (target == null) return null;
        return new ActiveTargetGoal<>(mob, cast(target), true);
    }

    /**
     * Descobre a classe de uma espécie a partir do id.
     *
     * <p>Pela classe, e não pelo tipo, porque é o que as metas do jogo pedem. Criar uma instância
     * só para perguntar a classe dela é feio e é o único caminho: o tipo não a expõe. A criatura
     * nasce solta e é descartada sem nunca entrar no mundo.
     */
    private static Class<? extends LivingEntity> livingClassOf(Logger logger, String id) {
        Identifier parsed = id == null ? null : Identifier.tryParse(id);
        if (parsed == null || !Registries.ENTITY_TYPE.containsId(parsed)) {
            logger.warn("Especie desconhecida numa meta declarada: {}", id);
            return null;
        }

        EntityType<?> type = Registries.ENTITY_TYPE.get(parsed);
        Class<?> resolved = CLASSES.get(parsed.toString());
        if (resolved != null) return resolved.asSubclass(LivingEntity.class);

        logger.warn("A especie {} nao esta na lista de alvos suportados", id);
        return null;
    }

    /**
     * As espécies que podem ser alvo de uma meta.
     *
     * <p>Explícita pelo mesmo motivo da lista de bases: descobrir a classe de um tipo exigiria
     * criar uma entidade só para perguntar, e uma lista pequena é mais honesta que um truque que
     * funciona por acidente. Cresce acrescentando uma linha.
     */
    private static final java.util.Map<String, Class<? extends LivingEntity>> CLASSES =
            java.util.Map.of(
                    "minecraft:player", PlayerEntity.class,
                    "minecraft:zombie", net.minecraft.entity.mob.ZombieEntity.class,
                    "minecraft:skeleton", net.minecraft.entity.mob.SkeletonEntity.class,
                    "minecraft:creeper", net.minecraft.entity.mob.CreeperEntity.class,
                    "minecraft:spider", net.minecraft.entity.mob.SpiderEntity.class,
                    "minecraft:wolf", net.minecraft.entity.passive.WolfEntity.class,
                    "minecraft:pig", net.minecraft.entity.passive.PigEntity.class,
                    "minecraft:cow", net.minecraft.entity.passive.CowEntity.class,
                    "minecraft:sheep", net.minecraft.entity.passive.SheepEntity.class,
                    "minecraft:villager", net.minecraft.entity.passive.VillagerEntity.class);

    @SuppressWarnings("unchecked")
    private static <T extends LivingEntity> Class<T> cast(Class<? extends LivingEntity> type) {
        return (Class<T>) type;
    }
}
