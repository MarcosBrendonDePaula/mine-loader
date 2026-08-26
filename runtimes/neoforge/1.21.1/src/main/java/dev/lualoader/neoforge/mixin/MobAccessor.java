package dev.lualoader.neoforge.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe os seletores de meta de uma criatura, no NeoForge.
 *
 * <p>O par de {@code MobEntityAccessor} do Fabric. Eles são protegidos porque o jogo espera que
 * cada espécie monte a própria IA no construtor — o que vale quando a espécie é uma classe Java.
 * Uma espécie declarada empresta o corpo da base e precisa ajustar o comportamento de fora.
 */
@Mixin(Mob.class)
public interface MobAccessor {
    @Accessor("goalSelector")
    GoalSelector lua_loader$goalSelector();

    @Accessor("targetSelector")
    GoalSelector lua_loader$targetSelector();
}
