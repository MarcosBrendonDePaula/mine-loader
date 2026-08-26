package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * Traduz o material de ferramenta declarado no manifesto para o que o jogo entende.
 *
 * <p>Na 1.21.4 {@code ToolMaterial} deixou de ser uma interface com getters e passou a ser um
 * record de dados. A mudança fica isolada nesta fábrica; o manifesto e a API do loader permanecem
 * inalterados.
 */
public final class DeclarativeToolMaterial {
    private DeclarativeToolMaterial() {
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

    /** Tag de itens que repara o material padrão daquele nível. */
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

    /** Cria o material de ferramenta da declaração. */
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

    /**
     * Cria o item de ferramenta da classe declarada.
     *
     * <p>Os construtores 1.21.4 recebem diretamente dano, velocidade de ataque e settings. O
     * comportamento exposto pelo manifesto é o mesmo; só a tradução do adaptador mudou.
     */
    public static Item create(ModManifest.ToolDefinition definition, Item.Settings settings) {
        ToolMaterial material = materialOf(definition);
        float damage = (float) definition.damage;
        float speed = switch (typeOf(definition)) {
            case "axe", "shovel" -> -3.0f;
            case "hoe" -> -1.0f;
            case "sword" -> -2.4f;
            default -> -2.8f;
        };

        return switch (typeOf(definition)) {
            case "axe" -> new net.minecraft.item.AxeItem(material, damage, speed, settings);
            case "shovel" -> new net.minecraft.item.ShovelItem(material, damage, speed, settings);
            case "hoe" -> new net.minecraft.item.HoeItem(material, damage, speed, settings);
            case "sword" -> new net.minecraft.item.SwordItem(material, damage, speed, settings);
            default -> new net.minecraft.item.PickaxeItem(material, damage, speed, settings);
        };
    }
}
