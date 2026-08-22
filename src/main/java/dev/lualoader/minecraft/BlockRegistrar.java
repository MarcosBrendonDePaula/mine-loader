package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.Map;

/** Registra blocos declarados no manifesto durante a inicialização do Minecraft. */
public final class BlockRegistrar {
    private final Logger logger;
    private final Map<Identifier, Block> blocks = new LinkedHashMap<>();

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
            Block block = new DeclarativeBlock(
                    BlockSettingsFactory.create(definition),
                    values.hardness,
                    values.resistance,
                    values.slipperiness,
                    values.velocityMultiplier,
                    values.jumpVelocityMultiplier
            );
            Registry.register(Registries.BLOCK, id, block);
            blocks.put(id, block);

            if (definition.item == null || definition.item.register) {
                Item.Settings itemSettings = new Item.Settings();
                if (definition.item != null && definition.item.maxStackSize > 0) {
                    itemSettings = itemSettings.maxCount(Math.min(definition.item.maxStackSize, 64));
                }
                if (definition.item != null && definition.item.maxDamage > 0) {
                    itemSettings = itemSettings.maxDamage(definition.item.maxDamage);
                }
                Registry.register(Registries.ITEM, id, new BlockItem(block, itemSettings));
            }

            logger.info("Lua Loader registrou bloco {} ({})", id, definition.name);
        }
    }

    public Block get(Identifier id) {
        return blocks.get(id);
    }

    public Map<Identifier, Block> registeredBlocks() {
        return Map.copyOf(blocks);
    }
}
