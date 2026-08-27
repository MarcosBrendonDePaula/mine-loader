package dev.lualoader.neoforge.client;

import dev.lualoader.neoforge.NeoForgeLuaLoader;
import dev.lualoader.neoforge.network.NeoForgeScreenNetwork;
import dev.lualoader.neoforge.network.NeoForgeScreenPayloads;
import dev.lualoader.ui.ScreenModel;
import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.client.Minecraft;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * O lado cliente do adaptador: recebe as descrições e desenha.
 *
 * <p>O servidor manda dados; quem decide o que aparece é este lado. Nunca chega código do servidor —
 * é a decisão central da camada de interface, e vale igual nas duas plataformas.
 */
public final class NeoForgeLuaLoaderClient implements NeoForgeScreenNetwork.ClientSink {
    private NeoForgeLuaLoaderClient() {
    }

    /** Chamado só no cliente, pela inicialização do mod. */
    public static void install(IEventBus modBus) {
        NeoForgeScreenNetwork.setClientSink(new NeoForgeLuaLoaderClient());
        KeybindClient.install();

        NeoForge.EVENT_BUS.addListener((RenderGuiEvent.Post event) ->
                NeoForgeHud.render(event.getGuiGraphics()));

        // O tamanho vai ao entrar e a cada tela que abre, que e quando o jogo recalcula depois de
        // um redimensionamento ou de uma troca de escala da interface.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingIn event) ->
                sendScreenSize());
        NeoForge.EVENT_BUS.addListener((ScreenEvent.Init.Post event) -> sendScreenSize());

        // A tela da janela declarada. Uma so para todos os blocos: o que muda e o manifesto que ela
        // le, a mesma ideia da tela generica do loader.
        modBus.addListener((net.neoforged.neoforge.client.event.RegisterMenuScreensEvent event) -> {
            if (dev.lualoader.neoforge.NeoForgeDeclaredMenus.type() != null) {
                event.register(dev.lualoader.neoforge.NeoForgeDeclaredMenus.type(),
                        NeoForgeDeclaredScreen::new);
            }
        });

        // As entidades desenhadas apontam para o mundo em que foram criadas; sair dele as invalida.
        NeoForge.EVENT_BUS.addListener((ClientPlayerNetworkEvent.LoggingOut event) -> {
            KeybindClient.clear();
            CameraClient.clear();
            NeoForgeScreenRenderer.forgetEntities();
        });
    }

    private static int lastWidth;
    private static int lastHeight;

    /**
     * Informa ao servidor o tamanho da tela, em unidades de interface.
     *
     * <p>Sem esse aviso, uma janela calculada para caber em escala 2 sai da tela em escala 3, porque
     * a escala divide a resolução.
     */
    private static void sendScreenSize() {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return;

        int width = client.getWindow().getGuiScaledWidth();
        int height = client.getWindow().getGuiScaledHeight();
        // So quando muda: a tela reinicia com frequencia, e reenviar o mesmo valor seria ruido.
        if (width == lastWidth && height == lastHeight) return;

        lastWidth = width;
        lastHeight = height;
        PacketDistributor.sendToServer(
                new NeoForgeScreenPayloads.ClientInfo(ScreenProtocol.VERSION, width, height));
    }

    // ------------------------------------------------------------------ o que chega do servidor

    @Override
    public void openScreen(int version, String screenId, String description) {
        if (version != ScreenProtocol.VERSION) {
            NeoForgeLuaLoader.LOGGER.warn("Tela em versao {} recusada; cliente usa a {}",
                    version, ScreenProtocol.VERSION);
            return;
        }

        ScreenModel model = ScreenModel.parse(description);
        if (model == null) {
            NeoForgeLuaLoader.LOGGER.error("Descricao de tela invalida recebida para {}", screenId);
            return;
        }

        NeoForgeLuaLoader.LOGGER.info("Abrindo tela {} com {} elemento(s)",
                screenId, model.elements().size());
        Minecraft.getInstance().setScreen(new NeoForgeLuaScreen(screenId, model));
    }

    @Override
    public void updateScreen(String description) {
        if (!(Minecraft.getInstance().screen instanceof NeoForgeLuaScreen screen)) return;

        ScreenModel model = ScreenModel.parse(description);
        if (model == null) return;

        screen.updateModel(model);
    }

    @Override
    public void closeScreen() {
        if (Minecraft.getInstance().screen instanceof NeoForgeLuaScreen) {
            Minecraft.getInstance().setScreen(null);
        }
    }

    @Override
    public void setHud(String description) {
        NeoForgeHud.set(description);
    }

    @Override
    public void setOverlay(int version, String overlayId, String description) {
        if (version != ScreenProtocol.VERSION) {
            NeoForgeLuaLoader.LOGGER.warn("Sobreposicao em versao {} recusada; cliente usa a {}",
                    version, ScreenProtocol.VERSION);
            return;
        }
        NeoForgeGameScreenOverlay.set(overlayId, description);
    }

    @Override
    public void clearOverlay(String overlayId) {
        NeoForgeGameScreenOverlay.clear(overlayId);
    }

    @Override
    public void setKeybinds(int version, String definitions) {
        KeybindClient.set(version, definitions);
    }

    @Override
    public void setCameras(int version, String definitions) {
        CameraClient.set(version, definitions);
    }
}
