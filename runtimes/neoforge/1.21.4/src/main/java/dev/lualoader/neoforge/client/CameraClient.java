package dev.lualoader.neoforge.client;

import dev.lualoader.camera.CameraProtocol;
import dev.lualoader.neoforge.NeoForgeLuaLoader;

import java.util.LinkedHashMap;
import java.util.Map;

/** Catálogo client-side de câmeras lógicas publicado pelo servidor. */
public final class CameraClient {
    private static final Map<String, CameraProtocol.Camera> CAMERAS = new LinkedHashMap<>();

    private CameraClient() {
    }

    public static void set(int version, String definitions) {
        if (version != CameraProtocol.VERSION) {
            NeoForgeLuaLoader.LOGGER.warn("Catálogo de câmeras em versão {} ignorado; esta em uso a {}",
                    version, CameraProtocol.VERSION);
            return;
        }
        try {
            Map<String, CameraProtocol.Camera> next = new LinkedHashMap<>();
            for (CameraProtocol.Camera camera : CameraProtocol.decode(definitions)) {
                next.put(camera.qualifiedId(), camera);
            }
            synchronized (CAMERAS) {
                CAMERAS.clear();
                CAMERAS.putAll(next);
            }
        } catch (IllegalArgumentException error) {
            NeoForgeLuaLoader.LOGGER.warn("Catálogo de câmeras inválido: {}", error.getMessage());
        }
    }

    public static CameraProtocol.Camera get(String qualifiedId) {
        if (qualifiedId == null || qualifiedId.isBlank()) return null;
        synchronized (CAMERAS) {
            return CAMERAS.get(qualifiedId);
        }
    }

    public static void clear() {
        synchronized (CAMERAS) {
            CAMERAS.clear();
        }
    }
}
