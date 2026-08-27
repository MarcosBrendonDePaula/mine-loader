package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.fabricmc.fabric.api.registry.FuelRegistry;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroup;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registra itens declarados no manifesto e a aba do mod no inventário criativo.
 *
 * <p>Sem uma aba, o conteúdo do mod só era obtenível por comando: nada aparecia no
 * inventário criativo. Cada mod que declara {@code creative_tab} recebe uma aba própria
 * contendo seus blocos e itens, na ordem em que foram declarados.
 */
public final class ContentRegistrar {
    private final Logger logger;
    private final Map<Identifier, Item> items = new LinkedHashMap<>();
    private final Map<String, RegistryKey<ItemGroup>> tabs = new LinkedHashMap<>();

    public ContentRegistrar(Logger logger) {
        this.logger = logger;
    }

    /** Registra os itens que não pertencem a um bloco. */
    public void registerItems(ModManifest manifest) {
        if (manifest.items == null) return;

        for (ModManifest.ItemEntryDefinition definition : manifest.items) {
            Identifier id = Identifier.of(manifest.id, definition.id);
            if (items.containsKey(id) || Registries.ITEM.containsId(id)) {
                throw new IllegalStateException("Item já registrado: " + id);
            }

            Item.Settings settings = new Item.Settings()
                    .maxCount(Math.max(1, Math.min(definition.maxStackSize, 64)))
                    .rarity(rarity(definition.rarity));
            if (definition.maxDamage > 0) {
                // maxDamage implica stack unitário; a validação do manifesto já garante isso.
                settings = settings.maxDamage(definition.maxDamage);
            }
            if (definition.fireResistant) {
                settings = settings.fireproof();
            }
            if (definition.food != null) {
                FoodComponent.Builder food = new FoodComponent.Builder()
                        .nutrition(definition.food.nutrition)
                        .saturationModifier((float) definition.food.saturation);
                if (definition.food.alwaysEdible) food.alwaysEdible();
                settings = settings.food(food.build());
            }

            // Ferramenta e armadura sao itens de classes proprias do jogo: uma picareta precisa
            // ser PickaxeItem para quebrar pedra rapido, e nao um item com dano declarado. Por isso
            // elas nao passam por DeclarativeItem -- e o preco e que os eventos on_use declarados
            // no manifesto nao valem para elas, o que esta documentado.
            Item item;
            if (definition.tool != null) {
                item = DeclarativeToolMaterial.create(definition.tool, settings);
            } else if (definition.armor != null) {
                item = DeclarativeArmorMaterial.create(definition.armor, settings);
            } else {
                // DeclarativeItem entrega ao runtime os eventos declarados no manifesto.
                item = new DeclarativeItem(settings);
            }
            Registry.register(Registries.ITEM, id, item);
            if (definition.fuelBurnTime > 0) {
                FuelRegistry.INSTANCE.add(item, definition.fuelBurnTime);
            }
            items.put(id, item);
            logger.info("Lua Loader registrou item {} ({})", id, definition.name);
        }
    }

    /**
     * Cria a aba do mod no inventário criativo com o conteúdo declarado.
     *
     * @param blockItems itens de bloco já registrados para este mod, na ordem de declaração
     */
    public void registerCreativeTab(ModManifest manifest, List<Identifier> blockItems) {
        ModManifest.CreativeTabDefinition definition = manifest.creativeTab;
        if (definition == null || !definition.register) return;

        List<Identifier> contents = new ArrayList<>(blockItems);
        if (manifest.items != null) {
            for (ModManifest.ItemEntryDefinition item : manifest.items) {
                contents.add(Identifier.of(manifest.id, item.id));
            }
        }
        if (contents.isEmpty()) {
            logger.warn("Mod {} declara creative_tab sem blocos nem itens; aba não registrada", manifest.id);
            return;
        }

        Identifier tabId = Identifier.of(manifest.id, definition.id == null ? "main" : definition.id);
        RegistryKey<ItemGroup> key = RegistryKey.of(RegistryKeys.ITEM_GROUP, tabId);

        Identifier iconId = resolveIcon(definition, contents);
        String title = definition.name == null || definition.name.isBlank() ? manifest.name : definition.name;

        ItemGroup group = ItemGroup.create(ItemGroup.Row.TOP, 0)
                .displayName(Text.literal(title))
                .icon(() -> new ItemStack(Registries.ITEM.get(iconId)))
                .entries((context, entries) -> {
                    for (Identifier id : contents) {
                        Item item = Registries.ITEM.get(id);
                        if (item == null || item == net.minecraft.item.Items.AIR) {
                            logger.warn("Conteúdo {} não encontrado ao montar a aba {}", id, tabId);
                            continue;
                        }
                        entries.add(item);
                    }
                })
                .build();

        Registry.register(Registries.ITEM_GROUP, key, group);
        tabs.put(manifest.id, key);
        logger.info("Lua Loader registrou aba criativa {} com {} entrada(s)", tabId, contents.size());
    }

    private Identifier resolveIcon(ModManifest.CreativeTabDefinition definition, List<Identifier> contents) {
        if (definition.icon != null && !definition.icon.isBlank()) {
            int separator = definition.icon.indexOf(':');
            if (separator > 0 && separator < definition.icon.length() - 1) {
                Identifier icon = Identifier.of(
                        definition.icon.substring(0, separator),
                        definition.icon.substring(separator + 1));
                if (Registries.ITEM.containsId(icon)) return icon;
                logger.warn("Ícone {} da aba não existe; usando o primeiro conteúdo", definition.icon);
            }
        }
        return contents.get(0);
    }

    private static Rarity rarity(String value) {
        String key = value == null || value.isBlank() ? "common" : value.trim().toLowerCase(Locale.ROOT);
        return switch (key) {
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> Rarity.COMMON;
        };
    }

    public Item get(Identifier id) {
        return items.get(id);
    }

    public Map<Identifier, Item> registeredItems() {
        return Map.copyOf(items);
    }
}
