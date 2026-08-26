package dev.lualoader.minecraft;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.mob.CreeperEntity;
import net.minecraft.entity.mob.SkeletonEntity;
import net.minecraft.entity.mob.SpiderEntity;
import net.minecraft.entity.mob.ZombieEntity;
import net.minecraft.entity.passive.ChickenEntity;
import net.minecraft.entity.passive.CowEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.PigEntity;
import net.minecraft.entity.passive.SheepEntity;
import net.minecraft.entity.passive.WolfEntity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * As espécies do jogo de que uma espécie declarada pode descender.
 *
 * <p><b>Uma tabela explícita, e não o registro do jogo inteiro.</b> Registrar um tipo novo exige a
 * função que constrói a entidade — {@code ZombieEntity::new} — e o registro só entrega o tipo, não
 * a função. Derivar de um tipo qualquer produziria uma entidade que se declara como o zumbi
 * original em vez da espécie nova, e ela se perderia ao salvar o mundo.
 *
 * <p>Então uma base fora desta lista é <b>recusada</b>, e não aproximada. Registrar mesmo assim
 * daria um bicho sem modelo nem comportamento, e um mob invisível não se parece com "essa base
 * ainda não é suportada" para quem escreveu o mod.
 *
 * <p>A lista cresce acrescentando uma linha aqui e o desenhista correspondente no cliente. O
 * cliente confere a cobertura ao subir, justamente para as duas metades não divergirem em silêncio.
 */
public final class EntityBases {
    private EntityBases() {
    }

    /**
     * Uma base suportada.
     *
     * @param vanilla    o tipo do jogo, de onde vêm tamanho, categoria e alcance padrão
     * @param factory    como construir a entidade para o tipo novo
     * @param attributes vida, velocidade e o mais que a espécie precisa ter antes de nascer
     */
    public record Base(EntityType<? extends LivingEntity> vanilla,
                       EntityType.EntityFactory<? extends LivingEntity> factory,
                       java.util.function.Supplier<DefaultAttributeContainer.Builder> attributes) {
    }

    private static final Map<String, Base> BASES = new LinkedHashMap<>();

    private static void put(String id, EntityType<? extends LivingEntity> vanilla,
                            EntityType.EntityFactory<? extends LivingEntity> factory,
                            java.util.function.Supplier<DefaultAttributeContainer.Builder> attributes) {
        BASES.put(id, new Base(vanilla, factory, attributes));
    }

    static {
        // Hostis
        put("minecraft:zombie", EntityType.ZOMBIE,
                (EntityType.EntityFactory<ZombieEntity>) ZombieEntity::new,
                ZombieEntity::createZombieAttributes);
        put("minecraft:skeleton", EntityType.SKELETON,
                (EntityType.EntityFactory<SkeletonEntity>) SkeletonEntity::new,
                SkeletonEntity::createAbstractSkeletonAttributes);
        put("minecraft:creeper", EntityType.CREEPER,
                (EntityType.EntityFactory<CreeperEntity>) CreeperEntity::new,
                CreeperEntity::createCreeperAttributes);
        put("minecraft:spider", EntityType.SPIDER,
                (EntityType.EntityFactory<SpiderEntity>) SpiderEntity::new,
                SpiderEntity::createSpiderAttributes);

        // Pacíficos
        put("minecraft:pig", EntityType.PIG,
                (EntityType.EntityFactory<PigEntity>) PigEntity::new,
                PigEntity::createPigAttributes);
        put("minecraft:cow", EntityType.COW,
                (EntityType.EntityFactory<CowEntity>) CowEntity::new,
                CowEntity::createCowAttributes);
        put("minecraft:sheep", EntityType.SHEEP,
                (EntityType.EntityFactory<SheepEntity>) SheepEntity::new,
                SheepEntity::createSheepAttributes);
        put("minecraft:chicken", EntityType.CHICKEN,
                (EntityType.EntityFactory<ChickenEntity>) ChickenEntity::new,
                ChickenEntity::createChickenAttributes);
        put("minecraft:wolf", EntityType.WOLF,
                (EntityType.EntityFactory<WolfEntity>) WolfEntity::new,
                WolfEntity::createWolfAttributes);
        put("minecraft:iron_golem", EntityType.IRON_GOLEM,
                (EntityType.EntityFactory<IronGolemEntity>) IronGolemEntity::new,
                IronGolemEntity::createIronGolemAttributes);
    }

    /** A base pedida, ou {@code null} se o loader não sabe derivar dela. */
    public static Base get(String baseId) {
        return baseId == null ? null : BASES.get(baseId);
    }

    /** Os ids suportados, para a mensagem de recusa dizer o que usar no lugar. */
    public static Set<String> supported() {
        return java.util.Collections.unmodifiableSet(BASES.keySet());
    }

    /**
     * A categoria do jogo para o nome declarado no manifesto.
     *
     * <p>O núcleo já recusa um nome fora do conjunto, então aqui um desconhecido só pode ser um
     * valor novo que o núcleo aceita e o adaptador ainda não traduz: nesse caso vale a da base, e
     * não uma escolha arbitrária.
     */
    public static SpawnGroup categoryOf(String declared, EntityType<?> base) {
        if (declared == null || declared.isBlank()) return base.getSpawnGroup();
        return switch (declared.toLowerCase(java.util.Locale.ROOT)) {
            case "monster" -> SpawnGroup.MONSTER;
            case "creature" -> SpawnGroup.CREATURE;
            case "ambient" -> SpawnGroup.AMBIENT;
            case "axolotls" -> SpawnGroup.AXOLOTLS;
            case "water_creature" -> SpawnGroup.WATER_CREATURE;
            case "water_ambient" -> SpawnGroup.WATER_AMBIENT;
            case "underground_water_creature" -> SpawnGroup.UNDERGROUND_WATER_CREATURE;
            case "misc" -> SpawnGroup.MISC;
            default -> base.getSpawnGroup();
        };
    }
}
