package dev.lualoader.neoforge;

import dev.lualoader.content.EntityAi;
import dev.lualoader.neoforge.mixin.MobAccessor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.PanicGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.TemptGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Traduz o comportamento declarado para as metas do jogo, no NeoForge.
 *
 * <p>O par de {@code DeclaredGoals} do Fabric, e o par existe porque as duas plataformas nomeiam
 * essas classes de formas diferentes — {@code WanderAroundFarGoal} de um lado,
 * {@code WaterAvoidingRandomStrollGoal} do outro, para a mesma coisa. É exatamente o motivo de o
 * manifesto não nomear classe nenhuma: o vocabulário é o mesmo nos dois lados, e a tradução muda.
 *
 * <p>O que <b>não</b> pode divergir é o que a criatura faz. Uma meta declarada precisa produzir o
 * mesmo comportamento nas duas plataformas, e é o que o GameTest de cada lado confere.
 */
public final class NeoForgeDeclaredGoals {
    private NeoForgeDeclaredGoals() {
    }

    /**
     * Aplica o comportamento declarado a uma criatura recém-chegada ao mundo.
     *
     * <p>Uma meta que a base não suporta é dita e pulada, e não recusada: metade das metas do jogo
     * exige uma entidade que saiba andar por caminho, e recusar tudo por causa de uma faria o mod
     * perder o resto do que declarou.
     */
    public static void apply(Logger logger, Mob mob, EntityAi ai) {
        if (ai == null) return;

        var goals = ((MobAccessor) mob).lua_loader$goalSelector();
        var targets = ((MobAccessor) mob).lua_loader$targetSelector();

        if (ai.clear) {
            // As duas listas: limpar so as metas deixaria a criatura ainda cacando, sem saber
            // perseguir -- ela ficaria parada encarando o alvo.
            goals.removeAllGoals(goal -> true);
            targets.removeAllGoals(goal -> true);
        }

        int fallback = 0;
        for (EntityAi.Goal declared : ai.goals) {
            int priority = declared.priority == null ? fallback : declared.priority;
            fallback++;

            Goal goal = translateGoal(logger, mob, declared);
            if (goal != null) goals.addGoal(priority, goal);
        }

        fallback = 0;
        for (EntityAi.Target declared : ai.targets) {
            int priority = declared.priority == null ? fallback : declared.priority;
            fallback++;

            Goal goal = translateTarget(logger, mob, declared);
            if (goal != null) targets.addGoal(priority, goal);
        }
    }

    private static Goal translateGoal(Logger logger, Mob mob, EntityAi.Goal declared) {
        String type = EntityAi.normalized(declared.type);

        switch (type) {
            case "float":
                return new FloatGoal(mob);
            case "look_around":
                return new RandomLookAroundGoal(mob);
            case "look_at_player":
                return new LookAtPlayerGoal(mob, Player.class, (float) declared.range);
            default:
                break;
        }

        // As metas que andam exigem uma criatura que saiba seguir caminho. Um morcego nao sabe, e
        // pedir a ele que vagueie derrubaria o jogo em vez de nao acontecer nada.
        if (!(mob instanceof PathfinderMob walker)) {
            logger.warn("A meta {} exige uma criatura que ande por caminho; {} nao anda",
                    type, BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
            return null;
        }

        return switch (type) {
            case "wander" -> new WaterAvoidingRandomStrollGoal(walker, declared.speed);
            case "panic" -> new PanicGoal(walker, declared.speed);
            case "melee_attack" -> new MeleeAttackGoal(walker, declared.speed, false);
            case "follow_item" -> temptGoal(logger, walker, declared);
            case "avoid" -> avoidGoal(logger, walker, declared);
            default -> null;
        };
    }

    /** Ir atrás de quem segura um dos itens declarados. */
    private static Goal temptGoal(Logger logger, PathfinderMob walker, EntityAi.Goal declared) {
        List<Item> items = new ArrayList<>();
        for (String id : declared.items) {
            ResourceLocation parsed = ResourceLocation.tryParse(id);
            if (parsed == null || !BuiltInRegistries.ITEM.containsKey(parsed)) {
                logger.warn("Item desconhecido numa meta follow_item: {}", id);
                continue;
            }
            BuiltInRegistries.ITEM.getOptional(parsed).ifPresent(items::add);
        }
        if (items.isEmpty()) return null;

        return new TemptGoal(walker, declared.speed,
                Ingredient.of(items.toArray(new Item[0])), false);
    }

    /** Manter distância de uma espécie. */
    private static Goal avoidGoal(Logger logger, PathfinderMob walker, EntityAi.Goal declared) {
        Class<? extends LivingEntity> target = livingClassOf(logger, declared.entity);
        if (target == null) return null;

        // A segunda velocidade e a de quando o perigo esta perto: fugir devagar de longe e correr
        // de perto e o que faz a fuga parecer reacao, e nao trajeto.
        return new AvoidEntityGoal<>(walker, cast(target), (float) declared.range,
                declared.speed, declared.speed * 1.4);
    }

    private static Goal translateTarget(Logger logger, Mob mob, EntityAi.Target declared) {
        String type = EntityAi.normalized(declared.type);

        // Revidar exige perseguir quem feriu, e perseguir exige andar por caminho.
        if ("hurt_by".equals(type)) {
            if (!(mob instanceof PathfinderMob walker)) {
                logger.warn("O alvo hurt_by exige uma criatura que ande por caminho; {} nao anda",
                        BuiltInRegistries.ENTITY_TYPE.getKey(mob.getType()));
                return null;
            }
            return new HurtByTargetGoal(walker);
        }

        return switch (type) {
            case "attack_player" -> new NearestAttackableTargetGoal<>(mob, Player.class, true);
            case "attack_entity" -> attackEntity(logger, mob, declared);
            default -> null;
        };
    }

    private static Goal attackEntity(Logger logger, Mob mob, EntityAi.Target declared) {
        Class<? extends LivingEntity> target = livingClassOf(logger, declared.entity);
        if (target == null) return null;
        return new NearestAttackableTargetGoal<>(mob, cast(target), true);
    }

    /**
     * Descobre a classe de uma espécie a partir do id.
     *
     * <p>Explícita pelo mesmo motivo da lista de bases: o registro entrega o tipo, não a classe, e
     * uma lista pequena é mais honesta que um truque que funciona por acidente. Precisa dizer o
     * mesmo que a lista do Fabric — uma espécie alvo de um lado só seria a divergência que este
     * projeto mais teme.
     */
    private static Class<? extends LivingEntity> livingClassOf(Logger logger, String id) {
        Class<? extends LivingEntity> resolved = id == null ? null : CLASSES.get(id);
        if (resolved == null) {
            logger.warn("A especie {} nao esta na lista de alvos suportados", id);
        }
        return resolved;
    }

    private static final Map<String, Class<? extends LivingEntity>> CLASSES = Map.of(
            "minecraft:player", Player.class,
            "minecraft:zombie", net.minecraft.world.entity.monster.Zombie.class,
            "minecraft:skeleton", net.minecraft.world.entity.monster.Skeleton.class,
            "minecraft:creeper", net.minecraft.world.entity.monster.Creeper.class,
            "minecraft:spider", net.minecraft.world.entity.monster.Spider.class,
            "minecraft:wolf", net.minecraft.world.entity.animal.Wolf.class,
            "minecraft:pig", net.minecraft.world.entity.animal.Pig.class,
            "minecraft:cow", net.minecraft.world.entity.animal.Cow.class,
            "minecraft:sheep", net.minecraft.world.entity.animal.Sheep.class,
            "minecraft:villager", net.minecraft.world.entity.npc.Villager.class);

    @SuppressWarnings("unchecked")
    private static <T extends LivingEntity> Class<T> cast(Class<? extends LivingEntity> type) {
        return (Class<T>) type;
    }
}
