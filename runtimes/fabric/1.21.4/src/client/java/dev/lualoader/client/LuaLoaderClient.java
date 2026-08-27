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

        // A tela da janela declarada. Uma so para todos os blocos: o que muda e o manifesto que ela
        // le, a mesma ideia da tela generica do loader.
        //
        // Depois do registro do conteudo, que roda no entrypoint principal: o tipo so existe se
        // algum bloco declarou janela propria.
        if (dev.lualoader.minecraft.DeclaredMenus.type() != null) {
            net.minecraft.client.gui.screen.ingame.HandledScreens.register(
                    dev.lualoader.minecraft.DeclaredMenus.type(), DeclaredContainerScreen::new);
        }

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.CloseScreen.ID,
                (payload, context) -> context.client().execute(LuaLoaderClient::closeScreen));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.SetHud.ID,
                (payload, context) -> context.client().execute(() -> HudOverlay.set(payload.description())));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.SetOverlay.ID,
                (payload, context) -> context.client().execute(() -> setOverlay(payload)));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.ClearOverlay.ID,
                (payload, context) -> context.client().execute(
                        () -> GameScreenOverlay.clear(payload.overlayId())));

        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.Keybinds.ID,
                (payload, context) -> context.client().execute(
                        () -> KeybindClient.set(payload.version(), payload.definitions())));
        ClientPlayNetworking.registerGlobalReceiver(ScreenPayloads.Cameras.ID,
                (payload, context) -> context.client().execute(
                        () -> CameraClient.set(payload.version(), payload.definitions())));

        // Antes do HUD: uma especie sem desenhista e invisivel, e o aviso precisa sair cedo o
        // bastante para nao se perder no meio do log de carga.
        EntityRenderers.register();

        // Antes de qualquer modelo ser pedido: o resolvedor precisa estar de pe quando o jogo monta
        // os modelos, e registrar depois nao teria efeito nenhum nesta carga.
        ObjModels.register();

        addModsButton();
        KeybindClient.install();

        HudOverlay.register();
        GameScreenOverlay.register();
        reportScreenSize();
    }

    /**
     * Acrescenta o botão "Mods Lua" ao menu principal.
     *
     * <p>A lista de mods do Fabric e do NeoForge enxerga um mod só, o próprio loader: para quem
     * joga, os mods em Lua são invisíveis fora do jogo. Sem este botão, a única forma de ver o que
     * está instalado é entrar num mundo primeiro — e entrar num mundo para conferir um mod é o
     * caminho mais torto possível.
     *
     * <p><b>Fica no canto, e não na coluna do meio.</b> A primeira versão o colocou abaixo dos
     * botões do jogo e ele caiu em cima de Options e Quit Game: a coluna central já está ocupada, e
     * as posições variam com a altura da janela. O canto é o único lugar que não briga — nem com o
     * menu, nem com outro mod que acrescente o próprio botão à coluna.
     */
    private static void addModsButton() {
        net.fabricmc.fabric.api.client.screen.v1.ScreenEvents.AFTER_INIT.register(
                (client, screen, width, height) -> {
                    if (!(screen instanceof net.minecraft.client.gui.screen.TitleScreen)) return;

                    net.fabricmc.fabric.api.client.screen.v1.Screens.getButtons(screen).add(
                            net.minecraft.client.gui.widget.ButtonWidget.builder(
                                            net.minecraft.text.Text.literal("Mods Lua"),
                                            button -> ModsMenuScreen.open(screen))
                                    .dimensions(6, height - 26, 80, 20)
                                    .build());
                });
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
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register(
                (handler, client) -> {
                    CameraClient.clear();
                    TopDownMapRenderer.clear();
                });

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
