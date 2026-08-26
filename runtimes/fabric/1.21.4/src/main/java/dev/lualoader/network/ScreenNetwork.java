package dev.lualoader.network;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.minecraft.FabricPlayerHandle;
import dev.lualoader.ui.ScreenProtocol;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * Liga o protocolo de interface à rede do Fabric.
 *
 * <p>É o único lugar do adaptador que conhece pacotes. O núcleo entrega uma descrição já validada e
 * recebe de volta eventos já traduzidos, então trocar de plataforma significa reescrever este
 * arquivo e o cliente, e nada mais.
 */
public final class ScreenNetwork {
    private ScreenNetwork() {
    }

    /** Registra os tipos de carga. Precisa acontecer nos dois lados, antes de qualquer envio. */
    public static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.OpenScreen.ID, ScreenPayloads.OpenScreen.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.UpdateScreen.ID, ScreenPayloads.UpdateScreen.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.CloseScreen.ID, ScreenPayloads.CloseScreen.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.SetHud.ID, ScreenPayloads.SetHud.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.SetOverlay.ID, ScreenPayloads.SetOverlay.CODEC);
        PayloadTypeRegistry.playS2C().register(
                ScreenPayloads.ClearOverlay.ID, ScreenPayloads.ClearOverlay.CODEC);
        PayloadTypeRegistry.playC2S().register(
                ScreenPayloads.ScreenEvent.ID, ScreenPayloads.ScreenEvent.CODEC);
        PayloadTypeRegistry.playC2S().register(
                ScreenPayloads.ClientInfo.ID, ScreenPayloads.ClientInfo.CODEC);
        PayloadTypeRegistry.playC2S().register(
                ScreenPayloads.ClientEvent.ID, ScreenPayloads.ClientEvent.CODEC);
    }

    /** Passa a receber os eventos que o cliente envia. */
    public static void registerServerReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenPayloads.ScreenEvent.ID,
                (payload, context) -> {
                    // A carga chega na thread de rede; tocar o jogo exige a thread do servidor.
                    context.server().execute(() -> handleEvent(payload, context.player()));
                });
    }

    /** Guarda o tamanho de tela informado, para o mod poder montar uma tela que caiba. */
    private static void handleClientInfo(ScreenPayloads.ClientInfo payload,
                                         ServerPlayerEntity player) {
        if (payload.version() != ScreenProtocol.VERSION) return;
        FabricPlayerHandle.rememberScreenSize(player.getUuid(), payload.width(), payload.height());
    }

    private static void handleEvent(ScreenPayloads.ScreenEvent payload, ServerPlayerEntity player) {
        LuaLoaderMod.LOGGER.info("Evento de tela recebido: {} elemento={} acao={}",
                payload.screenId(), payload.elementId(), payload.action());

        if (payload.version() != ScreenProtocol.VERSION) {
            LuaLoaderMod.LOGGER.warn("Evento de tela em versao {} ignorado; esta em uso a {}",
                    payload.version(), ScreenProtocol.VERSION);
            return;
        }

        // O jogador fechou a tela: o servidor precisa parar de considera-la aberta.
        if ("close".equals(payload.action())) {
            FabricPlayerHandle.forgetScreen(player.getUuid());
        }

        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return;

        // O runtime confere a acao contra o vocabulario fechado antes de chamar o script.
        runtime.triggerScreenEvent(
                payload.screenId(),
                payload.elementId(),
                payload.action(),
                payload.value(),
                new FabricPlayerHandle(player));
    }

    /** Passa a receber o tamanho de tela que o cliente informa. */
    public static void registerClientInfoReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenPayloads.ClientInfo.ID,
                (payload, context) ->
                        context.server().execute(() -> handleClientInfo(payload, context.player())));
    }

    /** Passa a receber os fatos do jogo que o cliente relata. */
    public static void registerClientEventReceiver() {
        ServerPlayNetworking.registerGlobalReceiver(ScreenPayloads.ClientEvent.ID,
                (payload, context) ->
                        context.server().execute(() -> handleClientEvent(payload, context.player())));
    }

    private static void handleClientEvent(ScreenPayloads.ClientEvent payload,
                                          ServerPlayerEntity player) {
        if (payload.version() != ScreenProtocol.VERSION) return;

        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return;

        // O runtime confere o nome do evento e o da tela contra os conjuntos fechados: o que chega
        // aqui vem da maquina de quem joga, e nao vale mais que um pedido.
        runtime.triggerClientEvent(payload.event(), payload.target(),
                new FabricPlayerHandle(player));
    }

    /** Indica se o cliente daquele jogador registrou o canal de telas. */
    public static boolean supports(ServerPlayerEntity player) {
        return ServerPlayNetworking.canSend(player, ScreenPayloads.OpenScreen.ID);
    }
}
