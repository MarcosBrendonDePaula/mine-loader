package dev.lualoader.mixin;

import net.minecraft.loot.LootPool;
import net.minecraft.loot.LootTable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe as reservas de uma tabela de loot.
 *
 * <p>O jogo sabe sortear um resultado, mas não oferece nenhuma forma de perguntar "o que esta
 * tabela pode dar". Sortear várias vezes e juntar o que saiu responderia por amostragem, e uma
 * amostra nunca prova que um item raro não existe — por isso a leitura é direta.
 */
@Mixin(LootTable.class)
public interface LootTableAccessor {
    @Accessor("pools")
    java.util.List<LootPool> lua_loader$pools();
}
