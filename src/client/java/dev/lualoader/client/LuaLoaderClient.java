package dev.lualoader.client;

import dev.lualoader.network.ScreenPayloads;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ponto de entrada do lado cliente.
 *
 * <p>Existe para uma coisa: desenhar. Registrar os canais aqui é o que faz o servidor saber que este
 * jogador entende o protocolo — um cliente sem o loader simplesmente não tem o canal, e o servidor
 * usa isso para não enviar telas que ninguém pode mostrar.
 */
public class LuaLoaderClient implements ClientModInitializer {
    static final Logger LOGGER = LoggerFactory.getLogger("lua_loader/client");

    @Override
    public void onInitializeClient() {
        // As cargas ja foram registradas pelo entrypoint principal, que tambem roda no cliente.
        // Registrar de novo aqui derruba o jogo com "packet type already registered", porque em
        // um mundo local os dois entrypoints vivem no mesmo processo.
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
            LOGGER.warn("Tela em versao {} recusada; cliente usa a {}",
                    payload.version(), ScreenProtocol.VERSION);
            return;
        }
        ScreenModel model = ScreenModel.parse(payload.description());
        if (model == null) {
            LOGGER.error("Descricao de tela invalida recebida para {}", payload.screenId());
            return;
        }
        LOGGER.info("Abrindo tela {} com {} elemento(s)", payload.screenId(), model.elements().size());

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
