package dev.lualoader.client;

import dev.lualoader.minecraft.DeclaredMenus;
import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;

/**
 * O clique num botão de janela declarada, a caminho do script.
 *
 * <p>Usa o <b>mesmo canal</b> dos eventos de tela do loader, e não um novo: um botão numa janela de
 * container e um botão numa tela desenhada são a mesma coisa para quem escreve o mod — um id que foi
 * clicado. Um canal separado daria duas formas de dizer a mesma coisa, e o script teria dois
 * lugares para tratar.
 *
 * <p>O nome da tela é o <b>id do bloco</b>, então o mod registra o handler com o nome do bloco. E o
 * valor é a posição: sem ela o script saberia que alguém clicou em "importar" e não em qual máquina.
 */
public final class DeclaredScreenEvents {
    private DeclaredScreenEvents() {
    }

    public static void send(BlockPos pos, String buttonId) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || pos == null || buttonId == null) return;

        String screenId = DeclaredMenus.screenIdOf(client.world, pos);
        if (screenId == null) return;

        ClientPlayNetworking.send(new ScreenPayloads.ScreenEvent(
                ScreenProtocol.VERSION,
                screenId,
                buttonId,
                "click",
                pos.getX() + "," + pos.getY() + "," + pos.getZ()));
    }
}
