package dev.lualoader.neoforge.mixin;

import net.minecraft.world.level.storage.loot.entries.CompositeEntryBase;
import net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe os filhos de uma entrada composta.
 *
 * <p>Minério, folha e mudas usam alternativa — "com Toque Suave dá isto, sem dá aquilo" — e a
 * alternativa contém outras entradas. Lendo só o nível de cima, um minério parece não derrubar
 * nada: foi exatamente o bug encontrado no adaptador Fabric.
 */
@Mixin(CompositeEntryBase.class)
public interface CompositeEntryAccessor {
    @Accessor("children")
    java.util.List<LootPoolEntryContainer> lua_loader$children();
}
