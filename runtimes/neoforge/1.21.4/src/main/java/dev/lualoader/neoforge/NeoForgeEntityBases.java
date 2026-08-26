package dev.lualoader.neoforge;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.animal.Wolf;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Spider;
import net.minecraft.world.entity.monster.Zombie;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * As espécies do jogo de que uma espécie declarada pode descender.
 *
 * <p>O espelho de {@code EntityBases} do adaptador Fabric, e a lista precisa ser <b>a mesma</b>. Uma
 * base suportada só de um lado é a divergência mais cara que este projeto já viu: o manifesto
 * carrega nas duas plataformas, e numa delas o mod perde a criatura inteira com um erro na carga
 * que quem escreveu nunca viu, porque testou na outra.
 *
 * <p>A tabela é explícita pelo mesmo motivo de lá: registrar um tipo novo exige a função que
 * constrói a entidade, e o registro do jogo só entrega o tipo. Derivar de um tipo qualquer daria
 * uma entidade que se declara como o original e se perderia ao salvar o mundo.
 */
public final class NeoForgeEntityBases {
    private NeoForgeEntityBases() {
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
                       Supplier<AttributeSupplier.Builder> attributes) {
    }

    private static final Map<String, Base> BASES = new LinkedHashMap<>();

    private static <T extends LivingEntity> void put(String id, EntityType<T> vanilla,
                                                     EntityType.EntityFactory<T> factory,
                                                     Supplier<AttributeSupplier.Builder> attributes) {
        BASES.put(id, new Base(vanilla, factory, attributes));
    }

    static {
        // Hostis
        put("minecraft:zombie", EntityType.ZOMBIE, Zombie::new, Zombie::createAttributes);
        put("minecraft:skeleton", EntityType.SKELETON, Skeleton::new,
                Skeleton::createAttributes);
        put("minecraft:creeper", EntityType.CREEPER, Creeper::new, Creeper::createAttributes);
        put("minecraft:spider", EntityType.SPIDER, Spider::new, Spider::createAttributes);

        // Pacíficos
        put("minecraft:pig", EntityType.PIG, Pig::new, Pig::createAttributes);
        put("minecraft:cow", EntityType.COW, Cow::new, Cow::createAttributes);
        put("minecraft:sheep", EntityType.SHEEP, Sheep::new, Sheep::createAttributes);
        put("minecraft:chicken", EntityType.CHICKEN, Chicken::new, Chicken::createAttributes);
        put("minecraft:wolf", EntityType.WOLF, Wolf::new, Wolf::createAttributes);
        put("minecraft:iron_golem", EntityType.IRON_GOLEM, IronGolem::new,
                IronGolem::createAttributes);
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
    public static MobCategory categoryOf(String declared, EntityType<?> base) {
        if (declared == null || declared.isBlank()) return base.getCategory();
        return switch (declared.toLowerCase(Locale.ROOT)) {
            case "monster" -> MobCategory.MONSTER;
            case "creature" -> MobCategory.CREATURE;
            case "ambient" -> MobCategory.AMBIENT;
            case "axolotls" -> MobCategory.AXOLOTLS;
            case "water_creature" -> MobCategory.WATER_CREATURE;
            case "water_ambient" -> MobCategory.WATER_AMBIENT;
            case "underground_water_creature" -> MobCategory.UNDERGROUND_WATER_CREATURE;
            case "misc" -> MobCategory.MISC;
            default -> base.getCategory();
        };
    }
}
