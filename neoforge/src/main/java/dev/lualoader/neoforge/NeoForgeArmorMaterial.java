package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;

/**
 * Traduz a armadura declarada no manifesto para o material que o jogo entende.
 *
 * <p>É o par de {@code DeclarativeArmorMaterial} do adaptador Fabric. Cada peça é declarada por si:
 * um mod pode ter só um capacete, sem obrigar quem escreve a inventar o conjunto inteiro. O jogo,
 * por outro lado, espera um material com a proteção de todas as peças — então cada peça declarada
 * vira um material que protege apenas no próprio slot.
 */
public final class NeoForgeArmorMaterial {
    private NeoForgeArmorMaterial() {
    }

    /** Onde a peça veste, normalizado. */
    public static ArmorItem.Type slotOf(ModManifest.ArmorDefinition definition) {
        String slot = definition.slot == null
                ? "chestplate"
                : definition.slot.trim().toLowerCase(Locale.ROOT);

        return switch (slot) {
            case "helmet" -> ArmorItem.Type.HELMET;
            case "leggings" -> ArmorItem.Type.LEGGINGS;
            case "boots" -> ArmorItem.Type.BOOTS;
            default -> ArmorItem.Type.CHESTPLATE;
        };
    }

    /**
     * Durabilidade da peça.
     *
     * <p>O jogo multiplica um número base conforme a peça — um capacete dura menos que uma calça do
     * mesmo material — e o manifesto declara o resultado que quer. Sem declaração, usa o base do
     * couro: um número baixo e reconhecível, que não faz a armadura parecer melhor do que quem
     * escreveu pediu.
     */
    private static int durabilityOf(ModManifest.ArmorDefinition definition, ArmorItem.Type type) {
        return definition.durability > 0 ? definition.durability : type.getDurability(5);
    }

    /**
     * Cria o item de armadura da peça declarada.
     *
     * <p>Um mod que queira um conjunto declara quatro peças, e cada uma responde pela própria
     * proteção — que é como o manifesto já descreve.
     */
    public static Item create(ModManifest.ArmorDefinition definition, Item.Properties properties) {
        ArmorItem.Type type = slotOf(definition);

        EnumMap<ArmorItem.Type, Integer> defense = new EnumMap<>(ArmorItem.Type.class);
        for (ArmorItem.Type each : ArmorItem.Type.values()) {
            defense.put(each, each == type ? definition.protection : 0);
        }

        ArmorMaterial material = new ArmorMaterial(
                defense,
                definition.enchantability,
                SoundEvents.ARMOR_EQUIP_GENERIC,
                () -> repairIngredient(definition),
                // Sem textura própria, a peça veste a de couro: aparecer com a textura errada é
                // melhor que o jogador ficar invisivelmente sem armadura, e a textura própria
                // depende do resource pack gerado.
                List.of(new ArmorMaterial.Layer(ResourceLocation.withDefaultNamespace("leather"))),
                (float) definition.toughness,
                (float) definition.knockbackResistance);

        return new ArmorItem(Holder.direct(material), type,
                properties.durability(durabilityOf(definition, type)));
    }

    private static Ingredient repairIngredient(ModManifest.ArmorDefinition definition) {
        if (definition.repairItem == null || definition.repairItem.isBlank()) {
            return Ingredient.EMPTY;
        }

        ResourceLocation id = ResourceLocation.tryParse(definition.repairItem);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return Ingredient.EMPTY;

        return Ingredient.of(BuiltInRegistries.ITEM.get(id));
    }
}
