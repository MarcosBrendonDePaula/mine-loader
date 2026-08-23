package dev.lualoader.neoforge.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Expõe as entradas de uma reserva de loot. Ver {@link LootTableAccessor}. */
@Mixin(LootPool.class)
public interface LootPoolAccessor {
    @Accessor("entries")
    java.util.List<LootPoolEntryContainer> lua_loader$entries();
}
