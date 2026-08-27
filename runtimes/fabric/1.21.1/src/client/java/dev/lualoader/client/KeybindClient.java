package dev.lualoader.client;

import dev.lualoader.input.KeybindProtocol;
import dev.lualoader.network.ScreenPayloads;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementação Fabric da entrada declarativa.
 *
 * <p>O cliente só faz polling da tecla e envia o id do binding. A função Lua nunca é carregada no
 * cliente e o servidor continua a ser a autoridade sobre o que a tecla faz.
 */
public final class KeybindClient {
    private static final Map<String, KeybindProtocol.Binding> BINDINGS = new java.util.LinkedHashMap<>();
    private static final Map<String, Boolean> PRESSED = new HashMap<>();

    private KeybindClient() {
    }

    public static void install() {
        ClientTickEvents.END_CLIENT_TICK.register(KeybindClient::tick);
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT
                .register((handler, client) -> clear());
    }

    public static void set(int version, String json) {
        if (version != KeybindProtocol.VERSION) {
            LuaLoaderClient.LOGGER.warn("Hotkeys em versao {} recusadas; cliente usa a {}",
                    version, KeybindProtocol.VERSION);
            return;
        }
        try {
            BINDINGS.clear();
            for (KeybindProtocol.Binding binding : KeybindProtocol.decode(json)) {
                InputUtil.Key key = InputUtil.fromTranslationKey(binding.key());
                if (key.equals(InputUtil.UNKNOWN_KEY)) {
                    LuaLoaderClient.LOGGER.warn("Hotkey {} usa tecla desconhecida: {}",
                            binding.qualifiedId(), binding.key());
                    continue;
                }
                BINDINGS.put(binding.qualifiedId(), binding);
            }
            PRESSED.clear();
            LuaLoaderClient.LOGGER.info("{} hotkey(s) declarada(s) recebida(s)", BINDINGS.size());
        } catch (IllegalArgumentException error) {
            BINDINGS.clear();
            PRESSED.clear();
            LuaLoaderClient.LOGGER.warn("Catalogo de hotkeys recusado: {}", error.getMessage());
        }
    }

    public static void clear() {
        BINDINGS.clear();
        PRESSED.clear();
    }

    private static void tick(MinecraftClient client) {
        if (BINDINGS.isEmpty()) return;
        if (client.player == null || client.getWindow() == null || client.currentScreen != null) {
            // Não transportar teclas de chat, inventário ou outra tela, e não deixar uma tecla
            // segurada antes de fechar uma tela virar um disparo falso depois dela.
            PRESSED.clear();
            return;
        }

        long window = client.getWindow().getHandle();
        for (KeybindProtocol.Binding binding : List.copyOf(BINDINGS.values())) {
            InputUtil.Key key = InputUtil.fromTranslationKey(binding.key());
            boolean down = InputUtil.isKeyPressed(window, key.getCode()) && modifiersDown(window, binding);
            boolean wasDown = PRESSED.getOrDefault(binding.qualifiedId(), false);
            PRESSED.put(binding.qualifiedId(), down);
            if (down && !wasDown) {
                ClientPlayNetworking.send(new ScreenPayloads.KeybindEvent(
                        KeybindProtocol.VERSION, binding.qualifiedId()));
            }
        }
    }

    private static boolean modifiersDown(long window, KeybindProtocol.Binding binding) {
        for (String modifier : binding.modifiers()) {
            boolean down = switch (modifier) {
                case "ctrl" -> pressed(window, GLFW.GLFW_KEY_LEFT_CONTROL)
                        || pressed(window, GLFW.GLFW_KEY_RIGHT_CONTROL);
                case "shift" -> pressed(window, GLFW.GLFW_KEY_LEFT_SHIFT)
                        || pressed(window, GLFW.GLFW_KEY_RIGHT_SHIFT);
                case "alt" -> pressed(window, GLFW.GLFW_KEY_LEFT_ALT)
                        || pressed(window, GLFW.GLFW_KEY_RIGHT_ALT);
                default -> false;
            };
            if (!down) return false;
        }
        return true;
    }

    private static boolean pressed(long window, int code) {
        return InputUtil.isKeyPressed(window, code);
    }
}
