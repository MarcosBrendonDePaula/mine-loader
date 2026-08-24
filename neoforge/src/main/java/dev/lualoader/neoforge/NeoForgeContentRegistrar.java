package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Registra no jogo os blocos e itens declarados nos manifestos.
 *
 * <p>Precisa acontecer muito antes do resto: o registro do Minecraft fecha durante a inicialização,
 * e um bloco declarado depois disso simplesmente não existe.
 *
 * <p>Usa {@code RegisterEvent} em vez de {@code DeferredRegister} por um motivo que não é de estilo:
 * o segundo fixa um namespace só, e todo conteúdo sairia como {@code lua_loader:algo}. Um mod
 * chamado {@code crystal_world} precisa registrar {@code crystal_world:altar} — é o identificador
 * que o adaptador Fabric usa, que o resource pack gera e que os scripts Lua escrevem. Nomes
 * diferentes por plataforma quebrariam a promessa de o mesmo mod rodar nas duas.
 */
public final class NeoForgeContentRegistrar {
    private final Logger logger;
    private final List<ModManifest> manifests = new ArrayList<>();

    private final Map<ResourceLocation, Block> blocks = new LinkedHashMap<>();
    private final Map<ResourceLocation, Item> items = new LinkedHashMap<>();

    public NeoForgeContentRegistrar(Logger logger, IEventBus modBus) {
        this.logger = logger;
        modBus.addListener(this::onRegister);
    }

    /** Guarda um manifesto para registrar quando o jogo pedir. */
    public void declare(ModManifest manifest) {
        manifests.add(manifest);
    }

    /** Blocos registrados, por identificador. */
    public List<String> registeredBlocks() {
        return blocks.keySet().stream().map(ResourceLocation::toString).toList();
    }

    private void onRegister(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            for (ModManifest manifest : manifests) registerBlocks(manifest, registry::register);
        });

        event.register(Registries.ITEM, registry -> {
            for (ModManifest manifest : manifests) registerItems(manifest, registry::register);
        });

        event.register(Registries.CREATIVE_MODE_TAB, registry -> {
            for (ModManifest manifest : manifests) registerCreativeTab(manifest, registry::register);
        });
    }

    /** O que o registro do jogo aceita: um identificador e o valor. */
    private interface Sink<T> {
        void accept(ResourceLocation id, T value);
    }

    private void registerBlocks(ModManifest manifest, Sink<Block> sink) {
        if (manifest.blocks == null) return;

        for (ModManifest.BlockDefinition definition : manifest.blocks) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);
            if (blocks.containsKey(id)) {
                logger.error("Bloco ja registrado, ignorando: {}", id);
                continue;
            }

            try {
                // O pack descreve o bloco por variante, e um bloco sem a propriedade nao casa com
                // nenhuma: o jogo procura a variante sem propriedades, nao acha, e desenha o cubo
                // de textura ausente -- os quadrados roxos e pretos.
                Block block = new NeoForgeDeclarativeBlock(settingsOf(definition));
                sink.accept(id, block);

                blocks.put(id, block);
                logger.info("Lua Loader registrou bloco {} ({})", id, definition.name);
            } catch (RuntimeException error) {
                logger.error("Falha ao registrar o bloco {}: {}", id, error.getMessage());
            }
        }
    }

    private void registerItems(ModManifest manifest, Sink<Item> sink) {
        // O item de bloco vem primeiro, na mesma ordem dos blocos: e o que o jogador segura para
        // colocar o bloco, e um bloco sem ele so aparece por comando.
        if (manifest.blocks != null) {
            for (ModManifest.BlockDefinition definition : manifest.blocks) {
                ResourceLocation id =
                        ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);
                Block block = blocks.get(id);
                if (block == null) continue;

                sink.accept(id, new BlockItem(block, new Item.Properties()));

            }
        }

        if (manifest.items == null) return;

        for (ModManifest.ItemEntryDefinition definition : manifest.items) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(manifest.id, definition.id);

            try {
                var properties = new Item.Properties()
                        .stacksTo(Math.max(1, Math.min(64, definition.maxStackSize)))
                        .rarity(rarityOf(definition.rarity));

                if (definition.maxDamage > 0) properties = properties.durability(definition.maxDamage);
                if (definition.fireResistant) properties = properties.fireResistant();

                Item item = new Item(properties);
                sink.accept(id, item);

                items.put(id, item);
                logger.info("Lua Loader registrou item {} ({})", id, definition.name);
            } catch (RuntimeException error) {
                logger.error("Falha ao registrar o item {}: {}", id, error.getMessage());
            }
        }
    }

    /**
     * Cria a aba do mod no inventario criativo.
     *
     * <p>Sem ela, o conteudo declarado so aparece pela busca ou por comando: existe no jogo e nao
     * se acha. Um mod que declara a aba espera ver o proprio conteudo agrupado.
     */
    private void registerCreativeTab(ModManifest manifest, Sink<CreativeModeTab> sink) {
        if (manifest.creativeTab == null) return;

        // A lista sai do manifesto, e nao do que ja foi registrado: a ordem em que o jogo processa
        // os registros nao e a que este arquivo declara, e a aba criativa e montada antes dos
        // itens existirem. Depender da ordem produzia uma aba com um item so.
        List<ResourceLocation> contents = new ArrayList<>();
        if (manifest.blocks != null) {
            for (ModManifest.BlockDefinition block : manifest.blocks) {
                contents.add(ResourceLocation.fromNamespaceAndPath(manifest.id, block.id));
            }
        }
        if (manifest.items != null) {
            for (ModManifest.ItemEntryDefinition item : manifest.items) {
                contents.add(ResourceLocation.fromNamespaceAndPath(manifest.id, item.id));
            }
        }

        if (contents.isEmpty()) {
            logger.warn("Mod {} declara creative_tab sem blocos nem itens; aba nao registrada",
                    manifest.id);
            return;
        }

        String tabName = manifest.creativeTab.id == null ? "main" : manifest.creativeTab.id;
        ResourceLocation tabId = ResourceLocation.fromNamespaceAndPath(manifest.id, tabName);

        // O icone declarado, ou o primeiro conteudo do mod: uma aba sem icone nao e desenhavel.
        ResourceLocation iconId = manifest.creativeTab.icon == null
                ? contents.get(0)
                : ResourceLocation.tryParse(manifest.creativeTab.icon);
        if (iconId == null) iconId = contents.get(0);

        final ResourceLocation icon = iconId;
        final List<ResourceLocation> entries = List.copyOf(contents);

        try {
            CreativeModeTab tab = CreativeModeTab.builder()
                    .title(Component.literal(manifest.creativeTab.name == null
                            ? manifest.name
                            : manifest.creativeTab.name))
                    .icon(() -> stackOf(icon))
                    .displayItems((parameters, output) -> {
                        for (ResourceLocation id : entries) output.accept(stackOf(id));
                    })
                    .build();

            sink.accept(tabId, tab);
            logger.info("Lua Loader registrou aba criativa {} com {} item(ns)", tabId, entries.size());
        } catch (RuntimeException error) {
            logger.error("Falha ao registrar a aba de {}: {}", manifest.id, error.getMessage());
        }
    }

    private static ItemStack stackOf(ResourceLocation id) {
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.get(id);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
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
}
