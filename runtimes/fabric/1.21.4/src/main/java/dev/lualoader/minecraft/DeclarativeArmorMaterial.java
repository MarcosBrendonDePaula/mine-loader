package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.equipment.ArmorMaterial;
import net.minecraft.item.equipment.EquipmentAssetKeys;
import net.minecraft.item.equipment.EquipmentType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Identifier;

import java.util.EnumMap;
import java.util.Locale;

/**
 * Traduz a armadura declarada no manifesto para o material que o jogo entende.
 *
 * <p>A API interna de armaduras mudou na 1.21.4: {@code ArmorMaterial} virou um record de dados e
 * {@code ArmorItem.Type} foi substituído por {@code EquipmentType}. O manifesto continua igual; esta
 * adaptação fica confinada ao bridge da versão do Minecraft.
 */
public final class DeclarativeArmorMaterial {
    private DeclarativeArmorMaterial() {
    }

    /** Onde a peça veste, normalizado. */
    public static EquipmentType slotOf(ModManifest.ArmorDefinition definition) {
        String slot = definition.slot == null
                ? "chestplate"
                : definition.slot.trim().toLowerCase(Locale.ROOT);

        return switch (slot) {
            case "helmet" -> EquipmentType.HELMET;
            case "leggings" -> EquipmentType.LEGGINGS;
            case "boots" -> EquipmentType.BOOTS;
            default -> EquipmentType.CHESTPLATE;
        };
    }

    /** Durabilidade da peça. */
    private static int durabilityOf(ModManifest.ArmorDefinition definition, EquipmentType type) {
        return definition.durability > 0 ? definition.durability : type.getMaxDamage(5);
    }

    /**
     * Cria o item de armadura da peça declarada.
     *
     * <p>Na 1.21.4 o material referencia uma tag de reparação e um asset de equipamento. A tag de
     * couro é o fallback estável para a declaração antiga por item; o contrato do manifesto não muda
     * e uma tag própria pode ser adicionada quando o resource pack versionado do loader a suportar.
     */
    public static Item create(ModManifest.ArmorDefinition definition, Item.Settings settings) {
        EquipmentType type = slotOf(definition);

        EnumMap<EquipmentType, Integer> defense = new EnumMap<>(EquipmentType.class);
        for (EquipmentType each : EquipmentType.values()) {
            defense.put(each, each == type ? definition.protection : 0);
        }

        ArmorMaterial material = new ArmorMaterial(
                durabilityOf(definition, type),
                defense,
                definition.enchantability,
                SoundEvents.ITEM_ARMOR_EQUIP_GENERIC,
                (float) definition.toughness,
                (float) definition.knockbackResistance,
                ItemTags.REPAIRS_LEATHER_ARMOR,
                EquipmentAssetKeys.LEATHER);

        return new ArmorItem(material, type, settings);
    }

    @SuppressWarnings("unused")
    private static IngredientRepair unusedRepairIngredient(ModManifest.ArmorDefinition definition) {
        return new IngredientRepair(definition.repairItem);
    }

    /** Pequeno marcador para deixar explícita a compatibilidade futura do campo repairItem. */
    private record IngredientRepair(String itemId) {
    }
}
