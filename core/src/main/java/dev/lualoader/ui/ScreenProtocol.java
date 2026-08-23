package dev.lualoader.ui;

import java.util.Set;

/**
 * Vocabulário comum da camada de interface.
 *
 * <p>Vive no núcleo, e não em cada adaptador, porque o protocolo precisa significar a mesma coisa em
 * qualquer plataforma. Um adaptador escolhe como transportar os bytes; o que os bytes querem dizer é
 * definido aqui. Sem isso, dois adaptadores acabariam com telas sutilmente diferentes e um mod
 * deixaria de ser portável.
 */
public final class ScreenProtocol {
    private ScreenProtocol() {
    }

    /**
     * Versão do protocolo.
     *
     * <p>Muda quando o significado de um campo existente muda. Acrescentar um tipo de elemento ou um
     * campo opcional não muda a versão, porque um cliente antigo ignora o que não conhece e um
     * servidor recusa um cliente mais velho que a versão mínima.
     */
    public static final int VERSION = 1;

    /** Nomes dos canais, iguais em qualquer adaptador. */
    public static final String CHANNEL_OPEN = "screen_open";
    public static final String CHANNEL_UPDATE = "screen_update";
    public static final String CHANNEL_CLOSE = "screen_close";
    public static final String CHANNEL_HUD = "hud_set";
    public static final String CHANNEL_EVENT = "screen_event";

    /**
     * Ações que o cliente pode reportar.
     *
     * <p>O vocabulário é fechado para que o script não precise interpretar texto livre vindo do
     * cliente: uma ação fora desta lista é descartada pelo servidor.
     */
    public static final Set<String> ACTIONS = Set.of("click", "change", "submit", "close");

    /** Tipos de elemento que o renderizador entende. */
    public static final Set<String> ELEMENTS = Set.of(
            "panel", "label", "image", "item", "progress", "button", "input");

    /** Elementos que recebem interação e por isso exigem um {@code id}. */
    public static final Set<String> INTERACTIVE = Set.of("button", "input");

    /** Âncoras aceitas para posicionar um elemento em relação à tela. */
    public static final Set<String> ANCHORS = Set.of(
            "top_left", "top", "top_right",
            "left", "center", "right",
            "bottom_left", "bottom", "bottom_right");

    /** Teto de elementos por tela, para uma descrição enorme não travar o cliente. */
    public static final int MAX_ELEMENTS = 256;

    /** Teto de caracteres de qualquer texto exibido. */
    public static final int MAX_TEXT_LENGTH = 512;

    /** Teto do JSON enviado, em caracteres. */
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** Maior dimensão aceita para uma tela, em unidades de interface. */
    public static final int MAX_SCREEN_SIZE = 1024;
}
