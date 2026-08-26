package dev.lualoader.mixin;

import net.minecraft.item.Item;
import net.minecraft.loot.entry.ItemEntry;
import net.minecraft.registry.entry.RegistryEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expõe o item de uma entrada de loot. Ver {@link LootTableAccessor}. */
@Mixin(ItemEntry.class)
public interface ItemEntryAccessor {
    @Accessor("item")
    RegistryEntry<Item> lua_loader$item();
}
