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
import net.minecraft.world.food.FoodProperties;
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

    /** Quantas variantes cada bloco declara; o evento leva o número ao script. */
    private final Map<ResourceLocation, Integer> variantCounts = new LinkedHashMap<>();

    /**
     * O inventario declarado de cada bloco que tem um.
     *
     * <p>Estatico porque a entidade so recebe posicao e estado ao nascer, e precisa descobrir
     * quantos slots tem antes de ler o NBT. E o bloco quem sabe, e este mapa e o caminho ate ele.
     */
    private static final Map<Block, ModManifest.InventoryDefinition> inventories =
            new LinkedHashMap<>();

    /** Blocos que guardam dados ou itens, e por isso precisam de entidade. */
    private final List<Block> dataBlocks = new ArrayList<>();

    /** O inventario declarado daquele bloco, ou {@code null} se ele nao guarda itens. */
    public static ModManifest.InventoryDefinition inventoryOf(Block block) {
        return inventories.get(block);
    }

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

    /**
     * Quantas variantes visuais o bloco declara, no mínimo uma.
     *
     * <p>É o total declarado no manifesto, e não a faixa fixa da propriedade de estado: um script
     * que cicla variantes precisa saber onde a volta fecha, e a faixa do bloco tem sempre dezesseis
     * valores mesmo quando o mod declarou dois.
     */
    public int variantCount(ResourceLocation id) {
        return variantCounts.getOrDefault(id, 1);
    }

    /**
     * Se o item foi declarado por um mod Lua.
     *
     * <p>O adaptador Fabric responde isso pela classe do item; aqui os itens declarados são
     * {@code Item} comuns, então quem sabe é o registro. O filtro existe para os eventos de item
     * não dispararem para todo item do jogo.
     */
    public boolean isDeclaredItem(ResourceLocation id) {
        return items.containsKey(id);
    }

    private void onRegister(RegisterEvent event) {
        event.register(Registries.BLOCK, registry -> {
            for (ModManifest manifest : manifests) registerBlocks(manifest, registry::register);
        });

        event.register(Registries.ITEM, registry -> {
            for (ModManifest manifest : manifests) registerItems(manifest, registry::register);
        });

        // Depois dos blocos, e nao junto: o tipo precisa conhecer no registro todos os blocos que
        // aceita, e ate aqui eles ja existem.
        event.register(Registries.BLOCK_ENTITY_TYPE, registry -> {
            var type = NeoForgeBlockEntities.create(logger, dataBlocks);
            if (type != null) {
                registry.register(
                        ResourceLocation.fromNamespaceAndPath("lua_loader", "declarative"), type);
            }
        });

        // A janela declarada: um tipo so para todos os blocos, e o mapa de quem tem desenho
        // proprio. O mapa e preenchido dos dois lados, e e o que permite o layout nao trafegar.
        event.register(Registries.MENU, registry -> {
            for (ModManifest manifest : manifests) {
                if (manifest.blocks == null) continue;
                for (ModManifest.BlockDefinition block : manifest.blocks) {
                    if (block == null || block.inventory == null) continue;
                    NeoForgeDeclaredMenus.declare(manifest.id + ":" + block.id, block.inventory);
                }
            }
            if (NeoForgeDeclaredMenus.anyDeclared()) {
                registry.register(NeoForgeDeclaredMenus.ID, NeoForgeDeclaredMenus.create());
            }
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
                ModManifest.SettingsDefinition values = definition.settings == null
                        ? new ModManifest.SettingsDefinition()
                        : definition.settings;

                // Um bloco so paga o custo de guardar dados quando o manifesto pede. Um
                // inventario tambem mora na entidade, entao pedi-lo implica te-la.
                boolean withData = definition.blockData || definition.inventory != null;

                // A forma vem do nucleo, a mesma que gera o modelo desenhado: e o que impede um
                // bloco de ter colisao de laje e aparencia de cubo.
                var outline = NeoForgeShapes.declared(definition.shape,
                        definition.shape == null ? null : definition.shape.outline);
                var collision = NeoForgeShapes.declared(definition.shape,
                        definition.shape == null ? null : definition.shape.collision);

                // As propriedades declaradas sao publicadas antes do construtor porque o jogo monta
                // a definicao de estado de dentro dele, quando nenhum campo de instancia existe
                // ainda. O par begin/end fica em try/finally: uma excecao no meio deixaria a
                // publicacao valendo, e o proximo bloco registrado herdaria as propriedades deste.
                NeoForgeStateProperties declaredState = NeoForgeStateProperties.from(definition);
                Block block;
                NeoForgeDeclarativeBlock.beginConstruction(declaredState);
                try {
                    // Conectar vem antes de guardar dados, e o bloco conectado sabe fazer os
                    // dois. A condicao anterior era `connects && !withData`: um cano que
                    // declarasse block_data virava bloco de dados e nunca crescia braco, sem erro
                    // nenhum. Vale para as duas plataformas -- uma so faria o mesmo manifesto
                    // conectar de um lado e nao do outro.
                    if (NeoForgeStateProperties.connects(definition)) {
                        // Um bloco que conecta calcula a propria forma a partir do estado, entao
                        // ignora outline e collision declarados -- nucleo e braco os substituem.
                        block = new NeoForgeConnectedBlock(settingsOf(id, definition), values.luminance,
                                dev.lualoader.content.BlockShapes.coreBoxes(definition.shape),
                                dev.lualoader.content.BlockShapes.armBoxes(definition.shape),
                                definition.shape.connectsTo,
                                withData);
                    } else {
                        block = withData
                                ? new NeoForgeDeclarativeDataBlock(
                                        settingsOf(id, definition), values.luminance, outline, collision)
                                : new NeoForgeDeclarativeBlock(
                                        settingsOf(id, definition), values.luminance, outline, collision);
                    }
                } finally {
                    NeoForgeDeclarativeBlock.endConstruction();
                }
                sink.accept(id, block);

                blocks.put(id, block);
                if (withData) dataBlocks.add(block);
                if (definition.inventory != null) inventories.put(block, definition.inventory);
                variantCounts.put(id, definition.render == null
                        || definition.render.variantTextures == null
                        ? 1
                        : Math.max(1, definition.render.variantTextures.size()));
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

                // O bloco pode declarar como o proprio item se comporta -- empilhamento,
                // durabilidade, raridade, resistencia ao fogo -- e ate pedir para nao ter item
                // nenhum. Enquanto isto era um Item.Properties vazio, os cinco campos eram
                // aceitos pelo manifesto e descartados aqui, "register": false inclusive.
                ModManifest.ItemDefinition itemDefinition = definition.item == null
                        ? new ModManifest.ItemDefinition()
                        : definition.item;
                if (!itemDefinition.register) continue;

                var itemProperties = new Item.Properties()
                        .stacksTo(Math.max(1, Math.min(64, itemDefinition.maxStackSize)))
                        .rarity(rarityOf(itemDefinition.rarity));
                if (itemDefinition.maxDamage > 0) {
                    itemProperties = itemProperties.durability(itemDefinition.maxDamage);
                }
                if (itemDefinition.fireResistant) itemProperties = itemProperties.fireResistant();
                itemProperties = itemProperties.setId(
                        net.minecraft.resources.ResourceKey.create(Registries.ITEM, id));

                sink.accept(id, new BlockItem(block, itemProperties));
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
                if (definition.food != null) {
                    FoodProperties.Builder food = new FoodProperties.Builder()
                            .nutrition(definition.food.nutrition)
                            .saturationModifier((float) definition.food.saturation);
                    if (definition.food.alwaysEdible) food.alwaysEdible();
                    properties = properties.food(food.build());
                    if (Math.abs(definition.food.consumeSeconds - 1.6) > 0.000001
                            || !definition.food.effects.isEmpty()) {
                        var consumable = net.minecraft.world.item.component.Consumable.builder()
                                .consumeSeconds((float) definition.food.consumeSeconds);
                        for (ModManifest.FoodEffectDefinition effect : definition.food.effects) {
                            ResourceLocation effectId = ResourceLocation.tryParse(effect.id);
                            if (effectId == null) {
                                throw new IllegalArgumentException("Efeito inválido: " + effect.id);
                            }
                            var effectHolder = net.minecraft.core.registries.BuiltInRegistries.MOB_EFFECT.get(
                                    net.minecraft.resources.ResourceKey.create(Registries.MOB_EFFECT, effectId))
                                    .orElse(null);
                            if (effectHolder == null) {
                                throw new IllegalArgumentException("Efeito desconhecido: " + effect.id);
                            }
                            consumable.onConsume(new net.minecraft.world.item.consume_effects.ApplyStatusEffectsConsumeEffect(
                                    new net.minecraft.world.effect.MobEffectInstance(effectHolder,
                                            effect.duration, effect.amplifier, effect.ambient,
                                            effect.showParticles, effect.showParticles),
                                    (float) effect.chance));
                        }
                        properties = properties.component(
                                net.minecraft.core.component.DataComponents.CONSUMABLE, consumable.build());
                    }
                }
                properties = properties.setId(
                        net.minecraft.resources.ResourceKey.create(Registries.ITEM, id));

                // Ferramenta e armadura vem antes do item comum: as duas trazem a propria
                // durabilidade e os proprios atributos, e cair no Item comum era o que fazia uma
                // picareta declarada virar enfeite empilhavel nesta plataforma.
                Item item;
                if (definition.tool != null) {
                    item = NeoForgeToolMaterial.create(definition.tool, properties);
                } else if (definition.armor != null) {
                    item = NeoForgeArmorMaterial.create(definition.armor, properties);
                } else if (definition.fuelBurnTime > 0) {
                    item = new NeoForgeFuelItem(properties, definition.fuelBurnTime);
                } else {
                    item = new Item(properties);
                }
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
        // Um mod pode declarar a aba e pedir que ela nao seja registrada -- e como ele coloca o
        // conteudo numa aba do jogo em vez de criar a propria. Sem esta guarda, "register": false
        // era lido e ignorado, e a aba aparecia assim mesmo.
        if (!manifest.creativeTab.register) return;

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

        if (manifest.entities != null) {
            for (dev.lualoader.platform.EntityDefinition entity : manifest.entities) {
                if (entity == null || entity.id == null) continue;
                if (entity.spawnEgg == null || !entity.spawnEgg.register) continue;

                // A mesma condicao do registrador: uma especie com base desconhecida nao ganha ovo,
                // e po-lo na aba deixaria um buraco onde o jogo desenharia ar.
                if (NeoForgeEntityBases.get(entity.base) == null) continue;

                String eggPath = entity.spawnEgg.id == null || entity.spawnEgg.id.isBlank()
                        ? entity.id + "_spawn_egg"
                        : entity.spawnEgg.id;
                contents.add(ResourceLocation.fromNamespaceAndPath(manifest.id, eggPath));
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
        var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        return item == null ? ItemStack.EMPTY : new ItemStack(item);
    }

    /**
     * Converte o manifesto para as propriedades de bloco do NeoForge.
     *
     * <p>A ordem dos campos aqui acompanha {@code BlockSettingsFactory} do adaptador Fabric de
     * proposito: os dois leem o mesmo manifesto e precisam chegar ao mesmo bloco, e ler os dois
     * lado a lado e a unica forma barata de conferir isso. Enquanto este metodo aplicava seis dos
     * cerca de trinta campos, um gelo escorregadio declarado uma vez escorregava numa plataforma e
     * nao na outra -- e {@code randomTicks} ficar de fora fazia {@code block_random_tick} nunca
     * ocorrer, o que parecia falta do evento e era falta da propriedade.
     */
    private static BlockBehaviour.Properties settingsOf(ResourceLocation id,
                                                         ModManifest.BlockDefinition definition) {
        ModManifest.MaterialDefinition material = definition.material == null
                ? new ModManifest.MaterialDefinition()
                : definition.material;
        ModManifest.SettingsDefinition values = definition.settings == null
                ? new ModManifest.SettingsDefinition()
                : definition.settings;

        var properties = BlockBehaviour.Properties.of()
                .setId(net.minecraft.resources.ResourceKey.create(Registries.BLOCK, id))
                .mapColor(mapColorOf(material.mapColor))
                .sound(soundOf(material.sound))
                .instrument(instrumentOf(material.instrument))
                .pushReaction(pushReactionOf(material.pistonBehavior))
                .strength((float) values.hardness, (float) values.resistance)
                .friction(values.slipperiness)
                .speedFactor(values.velocityMultiplier)
                .jumpFactor(values.jumpVelocityMultiplier);

        if (values.requiresTool) properties = properties.requiresCorrectToolForDrops();
        if (values.randomTicks) properties = properties.randomTicks();
        if (values.noCollision || !values.collidable) properties = properties.noCollission();
        if (values.nonOpaque || !material.opaque) properties = properties.noOcclusion();
        if (values.breakInstantly) properties = properties.instabreak();
        if (material.burnable) properties = properties.ignitedByLava();
        if (material.replaceable) properties = properties.replaceable();
        if (material.liquid) properties = properties.liquid();
        if (material.air) properties = properties.air();
        if (values.solid && material.solid) properties = properties.forceSolidOn();
        if (!values.blockBreakParticles) properties = properties.noTerrainParticles();
        if (values.dynamicBounds || (definition.shape != null && definition.shape.dynamic)) {
            properties = properties.dynamicShape();
        }
        if (values.offset != null) properties = properties.offsetType(offsetOf(values.offset));
        if (values.dropsNothing) properties = properties.noLootTable();
        // Largar o mesmo que outro bloco e como se declara uma variante decorativa sem repetir a
        // tabela de loot -- o minerio que larga o mesmo do minerio do jogo, por exemplo. O alvo e
        // resolvido por fornecedor porque este metodo roda durante o registro, quando o bloco
        // apontado pode ainda nao existir.
        // drops_like é materializado pelo resource pack gerado no core. A API de Properties da
        // 1.21.4 não aceita mais um fornecedor de loot durante o registro do bloco.

        // A luminancia nao entra aqui: o bloco a le do estado, para um script poder acender um
        // exemplar sem acender todos os outros do mesmo tipo no mundo.
        return properties;
    }

    private static net.minecraft.world.level.block.state.properties.NoteBlockInstrument instrumentOf(
            String name) {
        var fallback = net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HARP;
        if (name == null || name.isBlank()) return fallback;
        return switch (name.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "basedrum", "base_drum" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASEDRUM;
            case "snare" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.SNARE;
            case "hat" -> net.minecraft.world.level.block.state.properties.NoteBlockInstrument.HAT;
            case "bass" -> net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BASS;
            case "flute" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.FLUTE;
            case "bell" -> net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BELL;
            case "guitar" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.GUITAR;
            case "chime" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.CHIME;
            case "xylophone" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.XYLOPHONE;
            case "iron_xylophone" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.IRON_XYLOPHONE;
            case "cow_bell" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.COW_BELL;
            case "didgeridoo" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.DIDGERIDOO;
            case "bit" -> net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BIT;
            case "banjo" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.BANJO;
            case "pling" ->
                    net.minecraft.world.level.block.state.properties.NoteBlockInstrument.PLING;
            default -> fallback;
        };
    }

    private static net.minecraft.world.level.material.PushReaction pushReactionOf(String name) {
        var fallback = net.minecraft.world.level.material.PushReaction.NORMAL;
        if (name == null || name.isBlank()) return fallback;
        return switch (name.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "block" -> net.minecraft.world.level.material.PushReaction.BLOCK;
            case "destroy" -> net.minecraft.world.level.material.PushReaction.DESTROY;
            case "push_only" -> net.minecraft.world.level.material.PushReaction.PUSH_ONLY;
            default -> fallback;
        };
    }

    private static BlockBehaviour.OffsetType offsetOf(String name) {
        if (name == null || name.isBlank()) return BlockBehaviour.OffsetType.NONE;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "xz" -> BlockBehaviour.OffsetType.XZ;
            case "xyz" -> BlockBehaviour.OffsetType.XYZ;
            default -> BlockBehaviour.OffsetType.NONE;
        };
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
