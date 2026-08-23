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

    private static Identifier channel(String nome) {
        return Identifier.of(NAMESPACE, nome);
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
