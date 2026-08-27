package dev.lualoader.neoforge.network;

import dev.lualoader.ui.ScreenProtocol;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Cargas trocadas entre servidor e cliente.
 *
 * <p>Os nomes de canal e o número de versão vêm do núcleo, para que o significado seja o mesmo em
 * qualquer adaptador; o que este arquivo define é apenas como isso vira bytes no NeoForge.
 *
 * <p>Como os canais são os do núcleo, um servidor Fabric e um cliente NeoForge falam a mesma língua
 * byte a byte. Não é o objetivo — cada plataforma roda o próprio par —, mas é o sinal de que o
 * protocolo mora mesmo no núcleo e não em nenhum dos dois adaptadores.
 */
public final class NeoForgeScreenPayloads {
    /** Namespace do loader, para o conjunto ser reconhecível e não colidir com outros mods. */
    public static final String NAMESPACE = "lua_loader";

    private NeoForgeScreenPayloads() {
    }

    private static ResourceLocation channel(String name) {
        return ResourceLocation.fromNamespaceAndPath(NAMESPACE, name);
    }

    /** Abre uma tela desenhada no cliente. */
    public record OpenScreen(int version, String screenId, String description)
            implements CustomPacketPayload {
        public static final Type<OpenScreen> TYPE = new Type<>(channel(ScreenProtocol.CHANNEL_OPEN));

        public static final StreamCodec<RegistryFriendlyByteBuf, OpenScreen> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, OpenScreen::version,
                        ByteBufCodecs.STRING_UTF8, OpenScreen::screenId,
                        ByteBufCodecs.STRING_UTF8, OpenScreen::description,
                        OpenScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Substitui o conteúdo da tela aberta. */
    public record UpdateScreen(String description) implements CustomPacketPayload {
        public static final Type<UpdateScreen> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_UPDATE));

        public static final StreamCodec<RegistryFriendlyByteBuf, UpdateScreen> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, UpdateScreen::description,
                        UpdateScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Fecha a tela do loader. */
    public record CloseScreen(String reason) implements CustomPacketPayload {
        public static final Type<CloseScreen> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_CLOSE));

        public static final StreamCodec<RegistryFriendlyByteBuf, CloseScreen> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, CloseScreen::reason,
                        CloseScreen::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Define os elementos fixos na tela. */
    public record SetHud(String description) implements CustomPacketPayload {
        public static final Type<SetHud> TYPE = new Type<>(channel(ScreenProtocol.CHANNEL_HUD));

        public static final StreamCodec<RegistryFriendlyByteBuf, SetHud> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, SetHud::description,
                        SetHud::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Registra uma sobreposição que aparece sobre uma tela do jogo.
     *
     * <p>Vai junto o alvo, dentro da descrição: o cliente precisa saber sobre qual tela desenhar
     * antes de qualquer tela abrir, porque o registro sobrevive a abrir e fechar o inventário.
     */
    public record SetOverlay(int version, String overlayId, String description)
            implements CustomPacketPayload {
        public static final Type<SetOverlay> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_OVERLAY));

        public static final StreamCodec<RegistryFriendlyByteBuf, SetOverlay> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, SetOverlay::version,
                        ByteBufCodecs.STRING_UTF8, SetOverlay::overlayId,
                        ByteBufCodecs.STRING_UTF8, SetOverlay::description,
                        SetOverlay::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Remove uma sobreposição registrada. */
    public record ClearOverlay(String overlayId) implements CustomPacketPayload {
        public static final Type<ClearOverlay> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_OVERLAY_CLEAR));

        public static final StreamCodec<RegistryFriendlyByteBuf, ClearOverlay> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.STRING_UTF8, ClearOverlay::overlayId,
                        ClearOverlay::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Tamanho da tela do cliente, em unidades de interface.
     *
     * <p>Enviado ao entrar e sempre que muda — a janela é redimensionada ou o jogador troca a escala
     * da interface. É o que permite ao mod montar uma tela que caiba naquele cliente.
     */
    public record ClientInfo(int version, int width, int height) implements CustomPacketPayload {
        public static final Type<ClientInfo> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_CLIENT_INFO));

        public static final StreamCodec<RegistryFriendlyByteBuf, ClientInfo> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientInfo::version,
                        ByteBufCodecs.VAR_INT, ClientInfo::width,
                        ByteBufCodecs.VAR_INT, ClientInfo::height,
                        ClientInfo::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Fato do jogo relatado pelo cliente: o jogador abriu ou fechou uma tela do proprio jogo.
     *
     * <p>Separado de {@link ScreenEvent} porque fala de outra coisa. Aquele descreve o que o
     * jogador fez numa tela que o mod desenhou; este avisa que uma tela do jogo apareceu.
     */
    public record ClientEvent(int version, String event, String target)
            implements CustomPacketPayload {
        public static final Type<ClientEvent> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_CLIENT_EVENT));

        public static final StreamCodec<RegistryFriendlyByteBuf, ClientEvent> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ClientEvent::version,
                        ByteBufCodecs.STRING_UTF8, ClientEvent::event,
                        ByteBufCodecs.STRING_UTF8, ClientEvent::target,
                        ClientEvent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Catálogo de hotkeys que o servidor publicou para este cliente. */
    public record Keybinds(int version, String definitions)
            implements CustomPacketPayload {
        public static final Type<Keybinds> TYPE =
                new Type<>(channel(dev.lualoader.input.KeybindProtocol.CHANNEL_SET));

        public static final StreamCodec<RegistryFriendlyByteBuf, Keybinds> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, Keybinds::version,
                        ByteBufCodecs.STRING_UTF8, Keybinds::definitions,
                        Keybinds::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Evento cliente -> servidor: uma hotkey qualificada foi pressionada. */
    public record KeybindEvent(int version, String qualifiedId)
            implements CustomPacketPayload {
        public static final Type<KeybindEvent> TYPE =
                new Type<>(channel(dev.lualoader.input.KeybindProtocol.CHANNEL_EVENT));

        public static final StreamCodec<RegistryFriendlyByteBuf, KeybindEvent> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, KeybindEvent::version,
                        ByteBufCodecs.STRING_UTF8, KeybindEvent::qualifiedId,
                        KeybindEvent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** Evento vindo do cliente: o que o jogador fez em qual elemento. */
    public record ScreenEvent(int version, String screenId, String elementId,
                              String action, String value) implements CustomPacketPayload {
        public static final Type<ScreenEvent> TYPE =
                new Type<>(channel(ScreenProtocol.CHANNEL_EVENT));

        public static final StreamCodec<RegistryFriendlyByteBuf, ScreenEvent> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ScreenEvent::version,
                        ByteBufCodecs.STRING_UTF8, ScreenEvent::screenId,
                        ByteBufCodecs.STRING_UTF8, ScreenEvent::elementId,
                        ByteBufCodecs.STRING_UTF8, ScreenEvent::action,
                        ByteBufCodecs.STRING_UTF8, ScreenEvent::value,
                        ScreenEvent::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
