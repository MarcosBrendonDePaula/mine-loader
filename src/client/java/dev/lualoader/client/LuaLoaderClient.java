package dev.lualoader.client;

import dev.lualoader.network.ScreenNetwork;
import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Ponto de entrada do lado cliente.
 *
 * <p>Existe para uma coisa: desenhar. Registrar os canais aqui é o que faz o servidor saber que este
 * jogador entende o protocolo — um cliente sem o loader simplesmente não tem o canal, e o servidor
 * usa isso para não enviar telas que ninguém pode mostrar.
 */
public class LuaLoaderClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        // As cargas precisam ser conhecidas dos dois lados, com os mesmos codecs.
        ScreenNetwork.registerPayloads();

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.OpenScreen.ID,
                (payload, context) -> context.client().execute(() -> abrir(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.UpdateScreen.ID,
                (payload, context) -> context.client().execute(() -> atualizar(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.CloseScreen.ID,
                (payload, context) -> context.client().execute(LuaLoaderClient::fechar));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.SetHud.ID,
                (payload, context) -> context.client().execute(() -> HudOverlay.set(payload.description())));

        HudOverlay.register();
    }

    private static void abrir(ScreenPayloads.OpenScreen payload) {
        if (payload.version() != ScreenProtocol.VERSION) {
            // Versão diferente: melhor não abrir do que abrir errado.
            return;
        }
        ScreenModel model = ScreenModel.parse(payload.description());
        if (model == null) return;

        MinecraftClient.getInstance().setScreen(new LuaScreen(payload.screenId(), model));
    }

    private static void atualizar(ScreenPayloads.UpdateScreen payload) {
        if (!(MinecraftClient.getInstance().currentScreen instanceof LuaScreen tela)) return;

        ScreenModel model = ScreenModel.parse(payload.description());
        if (model == null) return;

        tela.updateModel(model);
    }

    private static void fechar() {
        if (MinecraftClient.getInstance().currentScreen instanceof LuaScreen) {
            MinecraftClient.getInstance().setScreen(null);
        }
    }
}
