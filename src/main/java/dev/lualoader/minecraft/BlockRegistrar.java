package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registra blocos declarados no manifesto durante a inicialização do Minecraft. */
public final class BlockRegistrar {
    private final Logger logger;
    private final Map<Identifier, Block> blocks = new LinkedHashMap<>();
    private final Map<Identifier, Integer> variantCounts = new LinkedHashMap<>();

    /**
     * O inventario declarado de cada bloco que tem um.
     *
     * <p>Estatico porque a entidade so recebe posicao e estado ao nascer, e precisa descobrir
     * quantos slots tem antes de ler o NBT. E o bloco quem sabe, e este mapa e o caminho ate ele.
     */
    private static final Map<Block, ModManifest.InventoryDefinition> inventories =
            new LinkedHashMap<>();

    /** O inventario declarado daquele bloco, ou {@code null} se ele nao guarda itens. */
    public static ModManifest.InventoryDefinition inventoryOf(Block block) {
        return inventories.get(block);
    }
    private final Map<String, List<Identifier>> blockItemsByMod = new LinkedHashMap<>();
    private final List<Block> dataBlocks = new ArrayList<>();

    public BlockRegistrar(Logger logger) {
        this.logger = logger;
    }

    public void register(ModManifest manifest) {
        if (manifest.blocks == null) return;

        for (ModManifest.BlockDefinition definition : manifest.blocks) {
            Identifier id = Identifier.of(manifest.id, definition.id);
            if (blocks.containsKey(id) || Registries.BLOCK.containsId(id)) {
                throw new IllegalStateException("Bloco já registrado: " + id);
            }

            ModManifest.SettingsDefinition values = definition.settings == null
                    ? new ModManifest.SettingsDefinition()
                    : definition.settings;
            DeclarativeStateProperties declaredState = DeclarativeStateProperties.from(definition);
            Block block;
            // As propriedades declaradas precisam estar visiveis durante o construtor do bloco.
            DeclarativeBlock.beginConstruction(declaredState);
            try {
                // Um bloco so paga o custo de guardar dados quando o manifesto pede. Um
                // inventario tambem mora na entidade, entao pedi-lo implica te-la.
                boolean withData = definition.blockData || definition.inventory != null;
                // declared() em vez de byName(): caixas proprias no manifesto ganham do nome.
                var outline = DeclarativeShapes.declared(
                        definition.shape,
                        definition.shape == null ? null : definition.shape.outline);
                var collision = DeclarativeShapes.declared(
                        definition.shape,
                        definition.shape == null ? null : definition.shape.collision);

                var settings = BlockSettingsFactory.create(definition);
                if (withData) {
                    block = new DeclarativeDataBlock(settings,
                            values.hardness, values.resistance, values.slipperiness,
                            values.velocityMultiplier, values.jumpVelocityMultiplier);
                    dataBlocks.add(block);
                } else if (outline != null || collision != null) {
                    block = new DeclarativeShapes.ShapedBlock(settings,
                            values.hardness, values.resistance, values.slipperiness,
                            values.velocityMultiplier, values.jumpVelocityMultiplier,
                            outline, collision);
                } else {
                    block = new DeclarativeBlock(settings,
                            values.hardness, values.resistance, values.slipperiness,
                            values.velocityMultiplier, values.jumpVelocityMultiplier);
                }
            } finally {
                DeclarativeBlock.endConstruction();
            }
            if (!declaredState.isEmpty()) {
                logger.info("Bloco {} recebeu {} propriedade(s) de estado declaradas",
                        id, declaredState.properties().size());
            }
            Registry.register(Registries.BLOCK, id, block);
            blocks.put(id, block);
            if (definition.inventory != null) inventories.put(block, definition.inventory);
            int variants = definition.render == null || definition.render.variantTextures == null
                    ? 1
                    : Math.max(1, definition.render.variantTextures.size());
            variantCounts.put(id, variants);

            if (definition.item == null || definition.item.register) {
                Item.Settings itemSettings = new Item.Settings();
                if (definition.item != null && definition.item.maxStackSize > 0) {
                    itemSettings = itemSettings.maxCount(Math.min(definition.item.maxStackSize, 64));
                }
                if (definition.item != null && definition.item.maxDamage > 0) {
                    itemSettings = itemSettings.maxDamage(definition.item.maxDamage);
                }
                if (definition.item != null) {
                    itemSettings = itemSettings.rarity(rarity(definition.item.rarity));
                    if (definition.item.fireResistant) itemSettings = itemSettings.fireproof();
                }
                Registry.register(Registries.ITEM, id, new BlockItem(block, itemSettings));
                blockItemsByMod.computeIfAbsent(manifest.id, key -> new ArrayList<>()).add(id);
            }

            logger.info("Lua Loader registrou bloco {} ({})", id, definition.name);
        }
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

    /** Blocos que declararam guardar dados, para o registro do tipo correspondente. */
    public List<Block> dataBlocks() {
        return List.copyOf(dataBlocks);
    }

    /** Itens de bloco registrados para o mod, na ordem de declaracao. */
    public List<Identifier> blockItems(String modId) {
        return List.copyOf(blockItemsByMod.getOrDefault(modId, List.of()));
    }

    public Block get(Identifier id) {
        return blocks.get(id);
    }

    /** Quantidade de variantes visuais declaradas para o bloco, no mínimo 1. */
    public int variantCount(Identifier id) {
        return variantCounts.getOrDefault(id, 1);
    }

    public Map<Identifier, Block> registeredBlocks() {
        return Map.copyOf(blocks);
    }
}
