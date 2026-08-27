package dev.lualoader.network;

import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.util.Identifier;

/**
 * Cargas trocadas entre servidor e cliente.
 *
 * <p>Os nomes de canal e o número de versão vêm do núcleo, para que o significado seja o mesmo em
 * qualquer adaptador; o que este arquivo define é apenas como isso vira bytes no Fabric.
 */
public final class ScreenPayloads {
    /** Namespace do loader, para o conjunto ser reconhecível e não colidir com outros mods. */
    public static final String NAMESPACE = "lua_loader";

    private ScreenPayloads() {
    }

    private static Identifier channel(String name) {
        return Identifier.of(NAMESPACE, name);
    }

    /** Abre uma tela desenhada no cliente. */
    public record OpenScreen(int version, String screenId, String description) implements CustomPayload {
        public static final CustomPayload.Id<OpenScreen> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_OPEN));

        public static final PacketCodec<RegistryByteBuf, OpenScreen> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, OpenScreen::version,
                PacketCodecs.STRING, OpenScreen::screenId,
                PacketCodecs.STRING, OpenScreen::description,
                OpenScreen::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Substitui o conteúdo da tela aberta. */
    public record UpdateScreen(String description) implements CustomPayload {
        public static final CustomPayload.Id<UpdateScreen> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_UPDATE));

        public static final PacketCodec<RegistryByteBuf, UpdateScreen> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, UpdateScreen::description,
                UpdateScreen::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Fecha a tela do loader. */
    public record CloseScreen(String reason) implements CustomPayload {
        public static final CustomPayload.Id<CloseScreen> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_CLOSE));

        public static final PacketCodec<RegistryByteBuf, CloseScreen> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, CloseScreen::reason,
                CloseScreen::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Define os elementos fixos na tela. */
    public record SetHud(String description) implements CustomPayload {
        public static final CustomPayload.Id<SetHud> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_HUD));

        public static final PacketCodec<RegistryByteBuf, SetHud> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, SetHud::description,
                SetHud::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Registra uma sobreposição que aparece sobre uma tela do jogo.
     *
     * <p>Vai junto o alvo, dentro da descrição: o cliente precisa saber sobre qual tela desenhar
     * antes de qualquer tela abrir, porque o registro sobrevive a abrir e fechar o inventário.
     */
    public record SetOverlay(int version, String overlayId, String description) implements CustomPayload {
        public static final CustomPayload.Id<SetOverlay> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_OVERLAY));

        public static final PacketCodec<RegistryByteBuf, SetOverlay> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, SetOverlay::version,
                PacketCodecs.STRING, SetOverlay::overlayId,
                PacketCodecs.STRING, SetOverlay::description,
                SetOverlay::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Remove uma sobreposição registrada. */
    public record ClearOverlay(String overlayId) implements CustomPayload {
        public static final CustomPayload.Id<ClearOverlay> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_OVERLAY_CLEAR));

        public static final PacketCodec<RegistryByteBuf, ClearOverlay> CODEC = PacketCodec.tuple(
                PacketCodecs.STRING, ClearOverlay::overlayId,
                ClearOverlay::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Tamanho da tela do cliente, em unidades de interface.
     *
     * <p>Enviado ao entrar e sempre que muda — a janela é redimensionada ou o jogador troca a escala
     * da interface. É o que permite ao mod montar uma tela que caiba naquele cliente.
     */
    public record ClientInfo(int version, int width, int height) implements CustomPayload {
        public static final CustomPayload.Id<ClientInfo> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_CLIENT_INFO));

        public static final PacketCodec<RegistryByteBuf, ClientInfo> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ClientInfo::version,
                PacketCodecs.VAR_INT, ClientInfo::width,
                PacketCodecs.VAR_INT, ClientInfo::height,
                ClientInfo::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /**
     * Fato do jogo relatado pelo cliente: o jogador abriu ou fechou uma tela do proprio jogo.
     *
     * <p>Separado de {@link ScreenEvent} porque fala de outra coisa. Aquele descreve o que o
     * jogador fez numa tela que o mod desenhou; este avisa que uma tela do jogo apareceu. Juntar os
     * dois faria o script conferir de qual dos dois mundos veio cada mensagem.
     */
    public record ClientEvent(int version, String event, String target) implements CustomPayload {
        public static final CustomPayload.Id<ClientEvent> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_CLIENT_EVENT));

        public static final PacketCodec<RegistryByteBuf, ClientEvent> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ClientEvent::version,
                PacketCodecs.STRING, ClientEvent::event,
                PacketCodecs.STRING, ClientEvent::target,
                ClientEvent::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Catálogo de hotkeys que o servidor publicou para este cliente. */
    public record Keybinds(int version, String definitions) implements CustomPayload {
        public static final CustomPayload.Id<Keybinds> ID =
                new CustomPayload.Id<>(channel(dev.lualoader.input.KeybindProtocol.CHANNEL_SET));

        public static final PacketCodec<RegistryByteBuf, Keybinds> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, Keybinds::version,
                PacketCodecs.STRING, Keybinds::definitions,
                Keybinds::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Evento cliente -> servidor: uma hotkey qualificada foi pressionada. */
    public record KeybindEvent(int version, String qualifiedId) implements CustomPayload {
        public static final CustomPayload.Id<KeybindEvent> ID =
                new CustomPayload.Id<>(channel(dev.lualoader.input.KeybindProtocol.CHANNEL_EVENT));

        public static final PacketCodec<RegistryByteBuf, KeybindEvent> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, KeybindEvent::version,
                PacketCodecs.STRING, KeybindEvent::qualifiedId,
                KeybindEvent::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Evento vindo do cliente: o que o jogador fez em qual elemento. */
    public record ScreenEvent(int version, String screenId, String elementId,
                              String action, String value) implements CustomPayload {
        public static final CustomPayload.Id<ScreenEvent> ID =
                new CustomPayload.Id<>(channel(ScreenProtocol.CHANNEL_EVENT));

        public static final PacketCodec<RegistryByteBuf, ScreenEvent> CODEC = PacketCodec.tuple(
                PacketCodecs.VAR_INT, ScreenEvent::version,
                PacketCodecs.STRING, ScreenEvent::screenId,
                PacketCodecs.STRING, ScreenEvent::elementId,
                PacketCodecs.STRING, ScreenEvent::action,
                PacketCodecs.STRING, ScreenEvent::value,
                ScreenEvent::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }
}
