package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.equipment.ArmorMaterial;
import net.minecraft.world.item.equipment.ArmorType;
import net.minecraft.world.item.equipment.EquipmentAssets;
import net.minecraft.sounds.SoundEvents;

import java.util.EnumMap;
import java.util.Locale;

/**
 * Traduz a armadura declarada no manifesto para o que o Minecraft 1.21.4 entende.
 *
 * <p>O contrato do manifesto é comum aos runtimes. Apenas os tipos específicos do NeoForge ficam
 * nesta pasta versionada, permitindo atualizar a tradução sem alterar o mod Lua.
 */
public final class NeoForgeArmorMaterial {
    private NeoForgeArmorMaterial() {
    }

    /** Onde a peça veste, normalizado. */
    public static ArmorType slotOf(ModManifest.ArmorDefinition definition) {
        String slot = definition.slot == null
                ? "chestplate"
                : definition.slot.trim().toLowerCase(Locale.ROOT);

        return switch (slot) {
            case "helmet" -> ArmorType.HELMET;
            case "leggings" -> ArmorType.LEGGINGS;
            case "boots" -> ArmorType.BOOTS;
            default -> ArmorType.CHESTPLATE;
        };
    }

    /** Durabilidade da peça. */
    private static int durabilityOf(ModManifest.ArmorDefinition definition, ArmorType type) {
        return definition.durability > 0 ? definition.durability : type.getDurability(5);
    }

    /** Cria o item de armadura da peça declarada. */
    public static Item create(ModManifest.ArmorDefinition definition, Item.Properties properties) {
        ArmorType type = slotOf(definition);

        EnumMap<ArmorType, Integer> defense = new EnumMap<>(ArmorType.class);
        for (ArmorType each : ArmorType.values()) {
            defense.put(each, each == type ? definition.protection : 0);
        }

        ArmorMaterial material = new ArmorMaterial(
                durabilityOf(definition, type),
                defense,
                definition.enchantability,
                SoundEvents.ARMOR_EQUIP_GENERIC,
                (float) definition.toughness,
                (float) definition.knockbackResistance,
                ItemTags.REPAIRS_LEATHER_ARMOR,
                EquipmentAssets.LEATHER);

        return new ArmorItem(material, type, properties);
    }
}
