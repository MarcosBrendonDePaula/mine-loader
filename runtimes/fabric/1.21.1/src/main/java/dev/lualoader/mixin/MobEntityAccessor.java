package dev.lualoader.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe os seletores de meta de uma criatura.
 *
 * <p>Eles são protegidos porque o jogo espera que cada espécie monte a própria IA no construtor —
 * o que vale quando a espécie é uma classe Java. Uma espécie declarada empresta o corpo da base e
 * precisa ajustar o comportamento depois, de fora, e este acessor é o caminho.
 *
 * <p>Um acessor, e não uma subclasse por base: dez subclasses só para alcançar dois campos
 * multiplicariam por dez o trabalho de acrescentar uma base nova.
 */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {
    @Accessor("goalSelector")
    GoalSelector lua_loader$goalSelector();

    @Accessor("targetSelector")
    GoalSelector lua_loader$targetSelector();
}
