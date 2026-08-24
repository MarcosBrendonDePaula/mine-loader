package dev.lualoader.client;

import dev.lualoader.ui.ScreenModel;
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
                (payload, context) -> context.client().execute(() -> openScreen(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.UpdateScreen.ID,
                (payload, context) -> context.client().execute(() -> updateScreen(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.CloseScreen.ID,
                (payload, context) -> context.client().execute(LuaLoaderClient::closeScreen));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.SetHud.ID,
                (payload, context) -> context.client().execute(() -> HudOverlay.set(payload.description())));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.SetOverlay.ID,
                (payload, context) -> context.client().execute(() -> setOverlay(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.ClearOverlay.ID,
                (payload, context) -> context.client().execute(
                        () -> GameScreenOverlay.clear(payload.overlayId())));

        // Antes do HUD: uma especie sem desenhista e invisivel, e o aviso precisa sair cedo o
        // bastante para nao se perder no meio do log de carga.
        EntityRenderers.register();

        HudOverlay.register();
        GameScreenOverlay.register();
        reportScreenSize();
    }

    /**
     * Informa ao servidor o tamanho da tela, e reinforma quando ele muda.
     *
     * <p>O mod monta a interface no servidor e por isso nao enxerga a tela de quem joga. Sem este
     * aviso, uma janela calculada para caber em escala 2 sai da tela em escala 3, porque a escala
     * divide a resolucao. E enviado ao entrar e a cada tela que abre, que e quando o jogo recalcula
     * o tamanho depois de um redimensionamento ou de uma troca de escala.
     */
    private static void reportScreenSize() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.JOIN.register(
                (handler, sender, client) -> client.execute(LuaLoaderClient::sendScreenSize));

        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> sendScreenSize());
    }

    private static int lastWidth;
    private static int lastHeight;

    private static void sendScreenSize() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) return;

        int width = client.getWindow().getScaledWidth();
        int height = client.getWindow().getScaledHeight();
        // So quando muda: a tela reinicia com frequencia, e reenviar o mesmo valor seria ruido.
        if (width == lastWidth && height == lastHeight) return;

        lastWidth = width;
        lastHeight = height;
        ClientPlayNetworking.send(new ScreenPayloads.ClientInfo(
                ScreenProtocol.VERSION, width, height));
    }

    private static void openScreen(ScreenPayloads.OpenScreen payload) {
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

    private static void setOverlay(ScreenPayloads.SetOverlay payload) {
        if (payload.version() != ScreenProtocol.VERSION) {
            LOGGER.warn("Sobreposicao em versao {} recusada; cliente usa a {}",
                    payload.version(), ScreenProtocol.VERSION);
            return;
        }
        GameScreenOverlay.set(payload.overlayId(), payload.description());
    }

    private static void updateScreen(ScreenPayloads.UpdateScreen payload) {
        if (!(MinecraftClient.getInstance().currentScreen instanceof LuaScreen screen)) return;

        ScreenModel model = ScreenModel.parse(payload.description());
        if (model == null) return;

        screen.updateModel(model);
    }

    private static void closeScreen() {
        if (MinecraftClient.getInstance().currentScreen instanceof LuaScreen) {
            MinecraftClient.getInstance().setScreen(null);
        }
    }
}
