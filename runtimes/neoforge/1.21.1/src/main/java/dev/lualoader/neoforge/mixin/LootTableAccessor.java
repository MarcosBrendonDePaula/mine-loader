package dev.lualoader.neoforge.mixin;

import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe as reservas de uma tabela de loot.
 *
 * <p>O equivalente ao acessor do adaptador Fabric, com os nomes desta plataforma. O jogo sabe
 * sortear um resultado, mas não oferece como perguntar o que a tabela pode dar — e uma amostra
 * nunca prova que um item raro não existe.
 */
@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor("pools")
    java.util.List<LootPool> lua_loader$pools();
}
