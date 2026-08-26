package dev.lualoader.mixin;

import net.minecraft.loot.LootPool;
import net.minecraft.loot.entry.LootPoolEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expõe as entradas de uma reserva de loot. Ver {@link LootTableAccessor}. */
@Mixin(LootPool.class)
public interface LootPoolAccessor {
    @Accessor("entries")
    java.util.List<LootPoolEntry> lua_loader$entries();
}
