package dev.lualoader.mixin;

import net.minecraft.loot.entry.CombinedEntry;
import net.minecraft.loot.entry.LootPoolEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe os filhos de uma entrada composta de loot.
 *
 * <p>Uma tabela raramente é uma lista plana de itens. Minério, folha e mudas usam alternativa —
 * "com Toque Suave dá isto, sem dá aquilo" — e a alternativa é uma entrada que contém outras.
 * Lendo só o nível de cima, um minério parece não derrubar nada.
 */
@Mixin(CombinedEntry.class)
public interface CombinedEntryAccessor {
    @Accessor("children")
    java.util.List<LootPoolEntry> lua_loader$children();
}
