package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Registra no jogo os blocos e itens declarados nos manifestos.
 *
 * <p>Precisa acontecer muito antes do resto: o registro do Minecraft fecha durante a inicialização,
 * e um bloco declarado depois disso simplesmente não existe. Por isso a descoberta dos mods é feita
 * no construtor do adaptador, e não quando o servidor sobe — o runtime Lua vem bem depois, e não
 * precisa existir para o conteúdo estar registrado.
 *
 * <p>Cobre o essencial do manifesto: bloco com dureza, resistência, som, cor de mapa e ferramenta
 * exigida, e o item correspondente. Variantes visuais, estados declarados e formas de colisão
 * continuam no adaptador Fabric — a diferença está documentada, e o que falta aqui não finge
 * existir.
 */
public final class NeoForgeContentRegistrar {
    private final Logger logger;
    private final DeferredRegister.Blocks blocks;
    private final DeferredRegister.Items items;

    private final Map<ResourceLocation, Supplier<Block>> registered = new LinkedHashMap<>();
    private final List<String> names = new ArrayList<>();

    public NeoForgeContentRegistrar(Logger logger, IEventBus modBus, String modId) {
        this.logger = logger;
        this.blocks = DeferredRegister.createBlocks(modId);
        this.items = DeferredRegister.createItems(modId);

        blocks.register(modBus);
        items.register(modBus);
    }

    /** Blocos registrados até agora, por identificador. */
    public List<String> registeredBlocks() {
        return List.copyOf(names);
    }

    /**
     * Registra o conteúdo de um manifesto.
     *
     * <p>Um mod com conteúdo inválido não impede os outros: a falha é registrada e a carga segue,
     * como no resto do loader.
     */
    public void register(ModManifest manifest) {
        registerBlocks(manifest);
        registerItems(manifest);
    }

    private void registerBlocks(ModManifest manifest) {
        if (manifest.blocks == null) return;

        for (ModManifest.BlockDefinition definition : manifest.blocks) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);
            if (registered.containsKey(id)) {
                logger.error("Bloco ja registrado, ignorando: {}", id);
                continue;
            }

            try {
                var settings = settingsOf(definition);
                var block = blocks.registerSimpleBlock(nameIn(manifest, definition.id), settings);

                // O item do bloco existe para o jogador poder segurar e colocar: um bloco sem item
                // so aparece por comando, e nao e o que o manifesto quer dizer.
                items.registerSimpleBlockItem(nameIn(manifest, definition.id), block);

                registered.put(id, block::get);
                names.add(id.toString());
                logger.info("Lua Loader registrou bloco {} ({})", id, definition.name);
            } catch (RuntimeException error) {
                logger.error("Falha ao registrar o bloco {}: {}", id, error.getMessage());
            }
        }
    }

    private void registerItems(ModManifest manifest) {
        if (manifest.items == null) return;

        for (ModManifest.ItemEntryDefinition definition : manifest.items) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);

            try {
                var properties = new Item.Properties()
                        .stacksTo(Math.max(1, Math.min(64, definition.maxStackSize)))
                        .rarity(rarityOf(definition.rarity));

                if (definition.maxDamage > 0) properties = properties.durability(definition.maxDamage);
                if (definition.fireResistant) properties = properties.fireResistant();

                final var built = properties;
                items.registerSimpleItem(nameIn(manifest, definition.id), built);
                logger.info("Lua Loader registrou item {} ({})", id, definition.name);
            } catch (RuntimeException error) {
                logger.error("Falha ao registrar o item {}: {}", id, error.getMessage());
            }
        }
    }

    /**
     * O nome dentro do registro do mod.
     *
     * <p>O DeferredRegister ja carrega o namespace, entao aqui vai so o caminho. Passar o
     * identificador inteiro produziria {@code lua_loader:hello_lua:ruby_block}.
     */
    private static String nameIn(ModManifest manifest, String id) {
        return manifest.id + "__" + id;
    }

    private static BlockBehaviour.Properties settingsOf(ModManifest.BlockDefinition definition) {
        ModManifest.SettingsDefinition values = definition.settings == null
                ? new ModManifest.SettingsDefinition()
                : definition.settings;

        var properties = BlockBehaviour.Properties.of()
                .strength((float) values.hardness, (float) values.resistance)
                .mapColor(mapColorOf(definition.material == null ? null : definition.material.mapColor))
                .sound(soundOf(definition.material == null ? null : definition.material.sound));

        if (values.requiresTool) properties = properties.requiresCorrectToolForDrops();
        if (values.luminance > 0) {
            final int light = Math.min(15, values.luminance);
            properties = properties.lightLevel(state -> light);
        }
        return properties;
    }

    private static MapColor mapColorOf(String name) {
        if (name == null) return MapColor.STONE;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "red" -> MapColor.COLOR_RED;
            case "blue" -> MapColor.COLOR_BLUE;
            case "green" -> MapColor.COLOR_GREEN;
            case "yellow" -> MapColor.COLOR_YELLOW;
            case "black" -> MapColor.COLOR_BLACK;
            case "white" -> MapColor.SNOW;
            case "metal" -> MapColor.METAL;
            case "wood" -> MapColor.WOOD;
            default -> MapColor.STONE;
        };
    }

    private static SoundType soundOf(String name) {
        if (name == null) return SoundType.STONE;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "wood" -> SoundType.WOOD;
            case "metal" -> SoundType.METAL;
            case "glass" -> SoundType.GLASS;
            case "wool" -> SoundType.WOOL;
            case "sand" -> SoundType.SAND;
            case "gravel" -> SoundType.GRAVEL;
            default -> SoundType.STONE;
        };
    }

    private static Rarity rarityOf(String name) {
        if (name == null) return Rarity.COMMON;
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "uncommon" -> Rarity.UNCOMMON;
            case "rare" -> Rarity.RARE;
            case "epic" -> Rarity.EPIC;
            default -> Rarity.COMMON;
        };
    }

    /** Nome legível de um bloco ou item, para a tradução gerada. */
    public static Component displayName(String name) {
        return Component.literal(name == null ? "" : name);
    }
}
