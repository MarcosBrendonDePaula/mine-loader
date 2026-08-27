package dev.lualoader.ui;

import java.util.Set;

/**
 * Contrato neutro do elemento de mapa no HUD.
 *
 * <p>O servidor envia apenas cores, posições relativas e marcadores serializáveis. Os bridges são
 * responsáveis por recortar, desenhar e aplicar a máscara visual, sem expor tipos de Minecraft ao
 * Lua ou ao core.
 */
public final class MapHudProtocol {
    private MapHudProtocol() {
    }

    /** Versão do contrato de dados do mapa. */
    public static final int VERSION = 1;

    /** Largura máxima da grelha enviada em uma actualização. */
    public static final int MAX_COLUMNS = 64;

    /** Altura máxima da grelha enviada em uma actualização. */
    public static final int MAX_ROWS = 64;

    /** Máximo de células de uma actualização de mapa. */
    public static final int MAX_CELLS = 4_096;

    /** Máximo de marcadores sobrepostos ao mapa. */
    public static final int MAX_MARKERS = 64;

    /** Limite de texto de um marcador. */
    public static final int MAX_MARKER_LABEL = 48;

    /** Formas comuns de recorte, sem textura específica de uma plataforma. */
    public static final Set<String> SHAPES = Set.of("square", "round");

    /** Fontes de imagem suportadas pelo elemento. */
    public static final Set<String> RENDER_MODES = Set.of("server_cells", "client_topdown", "client_camera");

    /** Resolução máxima da textura aérea client-side. */
    public static final int MAX_CLIENT_RESOLUTION = 192;

    /** Raio máximo do mundo representado pela textura aérea. */
    public static final int MAX_CLIENT_RADIUS = 96;

    /** Intervalo máximo entre actualizações client-side, em ticks. */
    public static final int MAX_CLIENT_UPDATE_TICKS = 40;

    /** Classes de marcador que os renderers entendem. */
    public static final Set<String> MARKER_TYPES = Set.of("waypoint", "entity", "player");
}
