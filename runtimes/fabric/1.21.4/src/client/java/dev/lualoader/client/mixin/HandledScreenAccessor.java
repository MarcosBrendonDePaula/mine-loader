package dev.lualoader.client.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Expõe o retângulo da janela de uma tela de container.
 *
 * <p>O jogo guarda essa posição em campos protegidos, calculados a cada abertura a partir da
 * resolução. Uma sobreposição que quisesse ficar ao lado do inventário sem isso teria de repetir a
 * conta do jogo e erraria toda vez que o jogador mudasse a escala da interface.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("x")
    int lua_loader$x();

    @Accessor("y")
    int lua_loader$y();

    @Accessor("backgroundWidth")
    int lua_loader$backgroundWidth();

    @Accessor("backgroundHeight")
    int lua_loader$backgroundHeight();
}
