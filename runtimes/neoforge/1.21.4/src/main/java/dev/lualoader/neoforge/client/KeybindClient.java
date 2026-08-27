package dev.lualoader.neoforge.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lualoader.input.KeybindProtocol;
import dev.lualoader.neoforge.NeoForgeLuaLoader;
import dev.lualoader.neoforge.network.NeoForgeScreenPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Implementação NeoForge da entrada declarativa.
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
        NeoForge.EVENT_BUS.addListener((ClientTickEvent.Post event) -> tick());
    }

    public static void set(int version, String json) {
        if (version != KeybindProtocol.VERSION) {
            NeoForgeLuaLoader.LOGGER.warn("Hotkeys em versao {} recusadas; cliente usa a {}",
                    version, KeybindProtocol.VERSION);
            return;
        }
        try {
            BINDINGS.clear();
            for (KeybindProtocol.Binding binding : KeybindProtocol.decode(json)) {
                InputConstants.Key key = InputConstants.getKey(binding.key());
                if (key == InputConstants.UNKNOWN) {
                    NeoForgeLuaLoader.LOGGER.warn("Hotkey {} usa tecla desconhecida: {}",
                            binding.qualifiedId(), binding.key());
                    continue;
                }
                BINDINGS.put(binding.qualifiedId(), binding);
            }
            PRESSED.clear();
            NeoForgeLuaLoader.LOGGER.info("{} hotkey(s) declarada(s) recebida(s)", BINDINGS.size());
        } catch (IllegalArgumentException error) {
            BINDINGS.clear();
            PRESSED.clear();
            NeoForgeLuaLoader.LOGGER.warn("Catalogo de hotkeys recusado: {}", error.getMessage());
        }
    }

    public static void clear() {
        BINDINGS.clear();
        PRESSED.clear();
    }

    private static void tick() {
        Minecraft client = Minecraft.getInstance();
        if (BINDINGS.isEmpty()) return;
        if (client.player == null || client.getWindow() == null || client.screen != null) {
            PRESSED.clear();
            return;
        }

        long window = client.getWindow().getWindow();
        for (KeybindProtocol.Binding binding : List.copyOf(BINDINGS.values())) {
            InputConstants.Key key = InputConstants.getKey(binding.key());
            boolean down = InputConstants.isKeyDown(window, key.getValue())
                    && modifiersDown(window, binding);
            boolean wasDown = PRESSED.getOrDefault(binding.qualifiedId(), false);
            PRESSED.put(binding.qualifiedId(), down);
            if (down && !wasDown) {
                PacketDistributor.sendToServer(new NeoForgeScreenPayloads.KeybindEvent(
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
        return InputConstants.isKeyDown(window, code);
    }
}
