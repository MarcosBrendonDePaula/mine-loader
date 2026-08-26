package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ToolMaterial;
import net.minecraft.world.level.block.Block;

import java.util.Locale;

/**
 * Traduz o material de ferramenta declarado no manifesto para o Minecraft 1.21.4.
 *
 * <p>Na 1.21.4 o antigo {@code Tier} foi substituído por um record {@code ToolMaterial}; a mudança
 * fica isolada no runtime NeoForge e não altera o contrato do mod declarativo.
 */
public final class NeoForgeToolMaterial {
    private NeoForgeToolMaterial() {
    }

    /** Tag dos blocos que este nível não consegue colher. */
    private static TagKey<Block> incorrectFor(int level) {
        return switch (level) {
            case 0 -> BlockTags.INCORRECT_FOR_WOODEN_TOOL;
            case 1 -> BlockTags.INCORRECT_FOR_STONE_TOOL;
            case 2 -> BlockTags.INCORRECT_FOR_IRON_TOOL;
            case 3 -> BlockTags.INCORRECT_FOR_DIAMOND_TOOL;
            default -> BlockTags.INCORRECT_FOR_NETHERITE_TOOL;
        };
    }

    /** Tag de itens de reparação correspondente ao nível padrão. */
    private static TagKey<Item> repairItemsFor(int level) {
        return switch (level) {
            case 0 -> ItemTags.WOODEN_TOOL_MATERIALS;
            case 1 -> ItemTags.STONE_TOOL_MATERIALS;
            case 2 -> ItemTags.IRON_TOOL_MATERIALS;
            case 3 -> ItemTags.DIAMOND_TOOL_MATERIALS;
            default -> ItemTags.NETHERITE_TOOL_MATERIALS;
        };
    }

    /** Durabilidade padrão de cada nível, quando o manifesto não diz. */
    private static int defaultDurability(int level) {
        return switch (level) {
            case 0 -> 59;
            case 1 -> 131;
            case 2 -> 250;
            case 3 -> 1561;
            default -> 2031;
        };
    }

    /** Nome da classe de ferramenta, normalizado. */
    public static String typeOf(ModManifest.ToolDefinition definition) {
        return definition.type == null
                ? "pickaxe"
                : definition.type.trim().toLowerCase(Locale.ROOT);
    }

    private static ToolMaterial materialOf(ModManifest.ToolDefinition definition) {
        return new ToolMaterial(
                incorrectFor(definition.level),
                definition.durability > 0
                        ? definition.durability
                        : defaultDurability(definition.level),
                (float) definition.speed,
                (float) definition.damage,
                definition.enchantability,
                repairItemsFor(definition.level));
    }

    /** Cria o item de ferramenta da classe declarada. */
    public static Item create(ModManifest.ToolDefinition definition, Item.Properties properties) {
        ToolMaterial material = materialOf(definition);
        float damage = (float) definition.damage;
        float speed = switch (typeOf(definition)) {
            case "axe", "shovel" -> -3.0f;
            case "hoe" -> -1.0f;
            case "sword" -> -2.4f;
            default -> -2.8f;
        };

        return switch (typeOf(definition)) {
            case "axe" -> new net.minecraft.world.item.AxeItem(material, damage, speed, properties);
            case "shovel" -> new net.minecraft.world.item.ShovelItem(material, damage, speed, properties);
            case "hoe" -> new net.minecraft.world.item.HoeItem(material, damage, speed, properties);
            case "sword" -> new net.minecraft.world.item.SwordItem(material, damage, speed, properties);
            default -> new net.minecraft.world.item.PickaxeItem(material, damage, speed, properties);
        };
    }
}
