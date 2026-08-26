package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

import java.util.Locale;

/**
 * Traduz o material de ferramenta declarado no manifesto para o que o jogo entende.
 *
 * <p>É o par de {@code DeclarativeToolMaterial} do adaptador Fabric, e existe pelo mesmo motivo: um
 * item com durabilidade e textura não é uma ferramenta. Sem nível de colheita ela não derruba
 * minério, sem velocidade leva o mesmo tempo que a mão, e sem dano é enfeite.
 *
 * <p>Enquanto esta classe não existiu, uma picareta declarada era registrada aqui como
 * {@code Item} comum — o manifesto dizia picareta e o jogo entregava um enfeite empilhável, sem
 * erro no log. O nome dos métodos muda entre as duas plataformas; as medidas e a escala de nível,
 * não, porque quem escreve o mod declara uma vez só.
 */
public final class NeoForgeToolMaterial implements Tier {
    private final ModManifest.ToolDefinition definition;

    public NeoForgeToolMaterial(ModManifest.ToolDefinition definition) {
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
    public int getUses() {
        return definition.durability > 0
                ? definition.durability
                : defaultDurability(definition.level);
    }

    @Override
    public float getSpeed() {
        return (float) definition.speed;
    }

    @Override
    public float getAttackDamageBonus() {
        return (float) definition.damage;
    }

    @Override
    public TagKey<Block> getIncorrectBlocksForDrops() {
        return incorrectFor(definition.level);
    }

    @Override
    public int getEnchantmentValue() {
        return definition.enchantability;
    }

    @Override
    public Ingredient getRepairIngredient() {
        // Sem item de conserto declarado, a ferramenta simplesmente não é consertável na bigorna --
        // que é melhor que escolher um item arbitrário e surpreender quem joga.
        if (definition.repairItem == null || definition.repairItem.isBlank()) {
            return Ingredient.EMPTY;
        }

        ResourceLocation id = ResourceLocation.tryParse(definition.repairItem);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return Ingredient.EMPTY;

        return Ingredient.of(BuiltInRegistries.ITEM.get(id));
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
    public static Item create(ModManifest.ToolDefinition definition, Item.Properties properties) {
        Tier tier = new NeoForgeToolMaterial(definition);
        float damage = (float) definition.damage;

        // Desde a 1.21 o dano e a velocidade de ataque vivem nos atributos do item, e nao no
        // construtor: cada classe traz o proprio conjunto, e o manifesto entra somando dano. Os
        // numeros de velocidade sao os mesmos do adaptador Fabric de proposito -- uma espada que
        // golpeia mais rapido numa plataforma seria a mesma arma com dois equilibrios.
        String type = typeOf(definition);
        Item.Properties comAtributos = properties.attributes(switch (type) {
            case "axe" -> net.minecraft.world.item.AxeItem.createAttributes(tier, damage, -3.0f);
            case "shovel" ->
                    net.minecraft.world.item.ShovelItem.createAttributes(tier, damage, -3.0f);
            case "hoe" ->
                    net.minecraft.world.item.HoeItem.createAttributes(tier, (int) damage, -1.0f);
            case "sword" ->
                    net.minecraft.world.item.SwordItem.createAttributes(tier, (int) damage, -2.4f);
            default ->
                    net.minecraft.world.item.PickaxeItem.createAttributes(tier, damage, -2.8f);
        });

        return switch (type) {
            case "axe" -> new net.minecraft.world.item.AxeItem(tier, comAtributos);
            case "shovel" -> new net.minecraft.world.item.ShovelItem(tier, comAtributos);
            case "hoe" -> new net.minecraft.world.item.HoeItem(tier, comAtributos);
            case "sword" -> new net.minecraft.world.item.SwordItem(tier, comAtributos);
            default -> new net.minecraft.world.item.PickaxeItem(tier, comAtributos);
        };
    }
}
