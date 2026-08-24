package dev.lualoader.neoforge.network;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.neoforge.NeoForgeLuaLoader;
import dev.lualoader.neoforge.NeoForgePlayerHandle;
import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.slf4j.Logger;

/**
 * Liga o protocolo de interface à rede do NeoForge.
 *
 * <p>É o único lugar do adaptador que conhece pacotes. O núcleo entrega uma descrição já validada e
 * recebe de volta eventos já traduzidos — a mesma divisão do adaptador Fabric, e a razão de portar
 * a camada de interface ter sido reescrever este arquivo e o cliente, e nada mais.
 */
public final class NeoForgeScreenNetwork {
    private static Logger logger;

    private NeoForgeScreenNetwork() {
    }

    /**
     * Registra os tipos de carga.
     *
     * <p>Todos opcionais, e isso não é detalhe: uma carga obrigatória faz o servidor recusar
     * qualquer cliente que não a conheça. O loader precisa aceitar cliente vanilla — é o caso que
     * {@code supports_screens} existe para o mod detectar e contornar.
     */
    public static void register(RegisterPayloadHandlersEvent event, Logger log) {
        logger = log;
        // A versao de rede sai da do protocolo, e nao de uma constante propria: duas versoes que
        // pudessem divergir seriam duas fontes de verdade para a mesma coisa.
        PayloadRegistrar registrar =
                event.registrar(String.valueOf(ScreenProtocol.VERSION)).optional();

        registrar.playToClient(NeoForgeScreenPayloads.OpenScreen.TYPE,
                NeoForgeScreenPayloads.OpenScreen.CODEC, (payload, context) -> {
                });
        registrar.playToClient(NeoForgeScreenPayloads.UpdateScreen.TYPE,
                NeoForgeScreenPayloads.UpdateScreen.CODEC, (payload, context) -> {
                });
        registrar.playToClient(NeoForgeScreenPayloads.CloseScreen.TYPE,
                NeoForgeScreenPayloads.CloseScreen.CODEC, (payload, context) -> {
                });
        registrar.playToClient(NeoForgeScreenPayloads.SetHud.TYPE,
                NeoForgeScreenPayloads.SetHud.CODEC, (payload, context) -> {
                });
        registrar.playToClient(NeoForgeScreenPayloads.SetOverlay.TYPE,
                NeoForgeScreenPayloads.SetOverlay.CODEC, (payload, context) -> {
                });
        registrar.playToClient(NeoForgeScreenPayloads.ClearOverlay.TYPE,
                NeoForgeScreenPayloads.ClearOverlay.CODEC, (payload, context) -> {
                });

        registrar.playToServer(NeoForgeScreenPayloads.ScreenEvent.TYPE,
                NeoForgeScreenPayloads.ScreenEvent.CODEC,
                // enqueueWork leva da thread de rede para a do servidor: tocar o jogo fora dela
                // corrompe estado de formas que so aparecem sob carga.
                (payload, context) -> context.enqueueWork(
                        () -> handleEvent(payload, (ServerPlayer) context.player())));

        registrar.playToServer(NeoForgeScreenPayloads.ClientInfo.TYPE,
                NeoForgeScreenPayloads.ClientInfo.CODEC,
                (payload, context) -> context.enqueueWork(
                        () -> handleClientInfo(payload, (ServerPlayer) context.player())));
    }

    /** Guarda o tamanho de tela informado, para o mod poder montar uma tela que caiba. */
    private static void handleClientInfo(NeoForgeScreenPayloads.ClientInfo payload,
                                         ServerPlayer player) {
        if (payload.version() != ScreenProtocol.VERSION) return;
        NeoForgePlayerHandle.rememberScreenSize(player.getUUID(), payload.width(), payload.height());
    }

    private static void handleEvent(NeoForgeScreenPayloads.ScreenEvent payload, ServerPlayer player) {
        if (logger != null) {
            logger.info("Evento de tela recebido: {} elemento={} acao={}",
                    payload.screenId(), payload.elementId(), payload.action());
        }

        if (payload.version() != ScreenProtocol.VERSION) {
            if (logger != null) {
                logger.warn("Evento de tela em versao {} ignorado; esta em uso a {}",
                        payload.version(), ScreenProtocol.VERSION);
            }
            return;
        }

        // O jogador fechou a tela: o servidor precisa parar de considera-la aberta.
        if ("close".equals(payload.action())) {
            NeoForgePlayerHandle.forgetScreen(player.getUUID());
        }

        LuaRuntime runtime = NeoForgeLuaLoader.luaRuntime();
        if (runtime == null) return;

        // O runtime confere a acao contra o vocabulario fechado antes de chamar o script.
        runtime.triggerScreenEvent(
                payload.screenId(),
                payload.elementId(),
                payload.action(),
                payload.value(),
                new NeoForgePlayerHandle(player));
    }

    /** Envia uma carga para um jogador. */
    public static void send(ServerPlayer player, CustomPacketPayload payload) {
        PacketDistributor.sendToPlayer(player, payload);
    }

    /** Indica se o cliente daquele jogador registrou o canal de telas. */
    public static boolean supports(ServerPlayer player) {
        return player.connection.hasChannel(NeoForgeScreenPayloads.OpenScreen.TYPE);
    }
}
