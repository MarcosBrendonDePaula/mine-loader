package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ToolMaterial;
import net.minecraft.recipe.Ingredient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.util.Identifier;

import java.util.Locale;

/**
 * Traduz o material de ferramenta declarado no manifesto para o que o jogo entende.
 *
 * <p>Um item com durabilidade e textura não é uma ferramenta: sem nível de colheita ela não derruba
 * minério, sem velocidade leva o mesmo tempo que a mão, e sem dano é enfeite. Estas quatro medidas,
 * mais o que conserta, são o que o jogo chama de material.
 *
 * <p>O nível segue a escala do jogo — madeira, pedra, ferro, diamante, netherita — porque uma escala
 * própria obrigaria quem escreve o mod a traduzir mentalmente a cada bloco que quisesse minerar.
 */
public final class DeclarativeToolMaterial implements ToolMaterial {
    private final ModManifest.ToolDefinition definition;

    public DeclarativeToolMaterial(ModManifest.ToolDefinition definition) {
        this.definition = definition;
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

    @Override
    public int getDurability() {
        return definition.durability > 0
                ? definition.durability
                : defaultDurability(definition.level);
    }

    @Override
    public float getMiningSpeedMultiplier() {
        return (float) definition.speed;
    }

    @Override
    public float getAttackDamage() {
        return (float) definition.damage;
    }

    @Override
    public TagKey<Block> getInverseTag() {
        return incorrectFor(definition.level);
    }

    @Override
    public int getEnchantability() {
        return definition.enchantability;
    }

    @Override
    public Ingredient getRepairIngredient() {
        // Sem item de conserto declarado, a ferramenta simplesmente não é consertável na bigorna --
        // que é melhor que escolher um item arbitrário e surpreender quem joga.
        if (definition.repairItem == null || definition.repairItem.isBlank()) {
            return Ingredient.empty();
        }

        Identifier id = Identifier.tryParse(definition.repairItem);
        if (id == null || !Registries.ITEM.containsId(id)) return Ingredient.empty();

        return Ingredient.ofItems(Registries.ITEM.get(id));
    }

    /** Nome da classe de ferramenta, normalizado. */
    public static String typeOf(ModManifest.ToolDefinition definition) {
        return definition.type == null
                ? "pickaxe"
                : definition.type.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Cria o item de ferramenta da classe declarada.
     *
     * <p>Cada classe é uma subclasse própria no jogo, e não um parâmetro: é o que faz a picareta
     * quebrar pedra rápido e o machado quebrar madeira, com a mesma ferramenta declarada.
     */
    public static Item create(ModManifest.ToolDefinition definition, Item.Settings settings) {
        ToolMaterial material = new DeclarativeToolMaterial(definition);
        float damage = (float) definition.damage;

        // Desde a 1.21 o dano e a velocidade de ataque vivem nos atributos do item, e nao no
        // construtor: cada classe traz o proprio conjunto, e o manifesto entra somando dano.
        String type = typeOf(definition);
        Item.Settings comAtributos = settings.attributeModifiers(switch (type) {
            case "axe" -> net.minecraft.item.AxeItem.createAttributeModifiers(material, damage, -3.0f);
            case "shovel" ->
                    net.minecraft.item.ShovelItem.createAttributeModifiers(material, damage, -3.0f);
            case "hoe" ->
                    net.minecraft.item.HoeItem.createAttributeModifiers(material, (int) damage, -1.0f);
            case "sword" ->
                    net.minecraft.item.SwordItem.createAttributeModifiers(material, (int) damage, -2.4f);
            default ->
                    net.minecraft.item.PickaxeItem.createAttributeModifiers(material, damage, -2.8f);
        });

        return switch (type) {
            case "axe" -> new net.minecraft.item.AxeItem(material, comAtributos);
            case "shovel" -> new net.minecraft.item.ShovelItem(material, comAtributos);
            case "hoe" -> new net.minecraft.item.HoeItem(material, comAtributos);
            case "sword" -> new net.minecraft.item.SwordItem(material, comAtributos);
            default -> new net.minecraft.item.PickaxeItem(material, comAtributos);
        };
    }
}
