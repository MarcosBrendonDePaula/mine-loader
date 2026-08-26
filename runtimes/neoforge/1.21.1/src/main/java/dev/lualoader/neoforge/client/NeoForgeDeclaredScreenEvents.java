package dev.lualoader.neoforge.client;

import dev.lualoader.neoforge.NeoForgeDeclaredMenus;
import dev.lualoader.neoforge.network.NeoForgeScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;

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
public final class NeoForgeDeclaredScreenEvents {
    private NeoForgeDeclaredScreenEvents() {
    }

    public static void send(BlockPos pos, String buttonId) {
        Minecraft client = Minecraft.getInstance();
        if (client.level == null || pos == null || buttonId == null) return;

        String screenId = NeoForgeDeclaredMenus.screenIdOf(pos, client.level);
        if (screenId == null) return;

        PacketDistributor.sendToServer(new NeoForgeScreenPayloads.ScreenEvent(
                ScreenProtocol.VERSION,
                screenId,
                buttonId,
                "click",
                pos.getX() + "," + pos.getY() + "," + pos.getZ()));
    }
}
