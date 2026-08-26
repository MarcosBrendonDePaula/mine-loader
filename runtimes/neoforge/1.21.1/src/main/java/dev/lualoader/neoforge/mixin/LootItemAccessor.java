package dev.lualoader.neoforge.mixin;

import net.minecraft.core.Holder;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expõe o item de uma entrada de loot. Ver {@link LootTableAccessor}. */
@Mixin(LootItem.class)
public interface LootItemAccessor {
    @Accessor("item")
    Holder<Item> lua_loader$item();
}
