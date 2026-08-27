package dev.lualoader.camera;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Contrato comum de câmeras lógicas publicadas pelo servidor.
 *
 * <p>O catálogo transporta apenas intenção: projecção, origem, âncora e qualidade. O bridge decide
 * como isso vira textura, framebuffer ou rasterização, sem expor APIs do Minecraft ao core/Lua.
 */
public final class CameraProtocol {
    private static final Gson GSON = new Gson();
    private static final Type CAMERA_LIST = new TypeToken<List<Camera>>() { }.getType();

    /** Versão do significado dos campos e do canal de catálogo. */
    public static final int VERSION = 1;

    /** Canal servidor -> cliente que publica câmeras disponíveis. */
    public static final String CHANNEL_SET = "cameras_set";

    public static final int MAX_CAMERAS = 64;
    /** O limite acompanha o identificador de mod aceito pelo manifesto. */
    public static final int MAX_MOD_ID_LENGTH = 64;
    /** IDs escritos pelo modder continuam curtos; o qualificado é modId:id. */
    public static final int MAX_CAMERA_ID_LENGTH = 32;
    /** Compatibilidade para consumidores do contrato que usavam o nome antigo. */
    @Deprecated
    public static final int MAX_ID_LENGTH = MAX_CAMERA_ID_LENGTH;
    public static final int MAX_RESOLUTION = 192;
    public static final int MAX_RADIUS = 96;
    public static final int MAX_UPDATE_TICKS = 40;

    public static final Set<String> PROJECTIONS = Set.of("orthographic");
    public static final Set<String> SOURCES = Set.of("world");
    public static final Set<String> ANCHORS = Set.of("player");
    public static final Set<String> ORIENTATIONS = Set.of("north", "player");
    public static final Set<String> OUTPUTS = Set.of("texture");

    private CameraProtocol() {
    }

    /** Definição resolvida de uma câmera lógica pronta para publicar ao cliente. */
    public record Camera(String modId, String id, String projection, String source,
                         String anchor, String orientation, int resolution, int radius,
                         int updateTicks, String output) {
        public Camera {
            modId = requireModId(modId);
            id = requireCameraId(id);
            projection = requireChoice(projection, "projection", PROJECTIONS);
            source = requireChoice(source, "source", SOURCES);
            anchor = requireChoice(anchor, "anchor", ANCHORS);
            orientation = requireChoice(orientation, "orientation", ORIENTATIONS);
            output = requireChoice(output, "output", OUTPUTS);
            if (resolution < 16 || resolution > MAX_RESOLUTION) {
                throw new IllegalArgumentException("resolution de câmera fora do limite");
            }
            if (radius < 8 || radius > MAX_RADIUS) {
                throw new IllegalArgumentException("radius de câmera fora do limite");
            }
            if (updateTicks < 1 || updateTicks > MAX_UPDATE_TICKS) {
                throw new IllegalArgumentException("update_ticks de câmera fora do limite");
            }
        }

        public String qualifiedId() {
            return modId + ":" + id;
        }
    }

    public static String encode(List<Camera> cameras) {
        List<Camera> safe = cameras == null ? List.of() : cameras;
        validateCatalog(safe);
        return GSON.toJson(safe, CAMERA_LIST);
    }

    public static List<Camera> decode(String json) {
        if (json == null || json.length() > 64 * 1024) {
            throw new IllegalArgumentException("catálogo de câmeras inválido");
        }
        try {
            List<Camera> cameras = GSON.fromJson(json, CAMERA_LIST);
            if (cameras == null) {
                throw new IllegalArgumentException("catálogo de câmeras inválido");
            }
            validateCatalog(cameras);
            return List.copyOf(cameras);
        } catch (JsonParseException | IllegalStateException | NullPointerException error) {
            throw new IllegalArgumentException("catálogo de câmeras inválido", error);
        }
    }

    private static void validateCatalog(List<Camera> cameras) {
        if (cameras == null || cameras.size() > MAX_CAMERAS) {
            throw new IllegalArgumentException("catálogo de câmeras excede o limite");
        }
        Set<String> ids = new LinkedHashSet<>();
        for (Camera camera : cameras) {
            if (camera == null) {
                throw new IllegalArgumentException("câmera nula no catálogo");
            }
            if (!ids.add(camera.qualifiedId())) {
                throw new IllegalArgumentException("câmera duplicada: " + camera.qualifiedId());
            }
        }
    }

    private static String requireModId(String value) {
        if (value == null || !value.matches("^[a-z0-9][a-z0-9_-]{1,63}$")) {
            throw new IllegalArgumentException("modId de câmera inválido: " + value);
        }
        return value;
    }

    private static String requireCameraId(String value) {
        if (value == null || !value.matches("^[a-z][a-z0-9_-]{0,31}$")) {
            throw new IllegalArgumentException("id de câmera inválido: " + value);
        }
        return value;
    }

    private static String requireChoice(String value, String field, Set<String> choices) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (!choices.contains(normalized)) {
            throw new IllegalArgumentException(field + " de câmera inválido: " + value);
        }
        return normalized;
    }
}
