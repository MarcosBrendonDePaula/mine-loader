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
    public static final String CHANNEL_OVERLAY = "overlay_set";
    public static final String CHANNEL_OVERLAY_CLEAR = "overlay_clear";
    public static final String CHANNEL_EVENT = "screen_event";

    /**
     * Cliente para servidor: o tamanho da tela, em unidades de interface.
     *
     * <p>Sem isto o mod monta a tela às cegas. As âncoras sabem colar um elemento na borda, mas não
     * sabem se ele cabe: um painel de 156 px ao lado do inventário cabe em escala 2 e sai da tela em
     * escala 3, porque a escala divide a resolução. Quem pode decidir quantas colunas usar, ou de
     * que lado desenhar, é o mod — e para isso ele precisa saber com quanto conta.
     */
    public static final String CHANNEL_CLIENT_INFO = "client_info";

    /**
     * Ações que o cliente pode reportar.
     *
     * <p>O vocabulário é fechado para que o script não precise interpretar texto livre vindo do
     * cliente: uma ação fora desta lista é descartada pelo servidor.
     */
    public static final Set<String> ACTIONS = Set.of("click", "change", "submit", "close");

    /** Tipos de elemento que o renderizador entende. */
    public static final Set<String> ELEMENTS = Set.of(
            "panel", "label", "image", "item", "progress", "button", "input",
            "grid", "viewport", "entity");

    /**
     * Elementos que recebem interação e por isso exigem um {@code id}.
     *
     * <p>{@code viewport} entra pelo mesmo motivo, ainda que não gere evento: os elementos que
     * rolam dentro dele referem-se ao seu {@code id}, e um recorte sem nome não teria como ser
     * apontado.
     */
    public static final Set<String> INTERACTIVE = Set.of("button", "input", "grid", "viewport");

    /**
     * Teto de células de uma grade.
     *
     * <p>Uma grade é um elemento só, mas desenha muitos: sem teto próprio, o limite de elementos
     * por tela deixaria de significar o que promete.
     */
    public static final int MAX_CELLS = 512;

    /** Maior número de colunas de uma grade. */
    public static final int MAX_COLUMNS = 32;

    /**
     * Âncoras aceitas para posicionar um elemento.
     *
     * <p>As nove primeiras referem-se à superfície inteira: a janela do mod em uma tela própria, a
     * tela do jogo em um HUD ou sobreposição. As cinco com prefixo {@code gui_} referem-se à janela
     * da tela do jogo que está por baixo — o retângulo do inventário, do baú, do forno. É o que
     * permite a uma sobreposição ficar colada ao inventário em qualquer resolução, em vez de
     * calcular a posição dele à mão. Fora de uma tela de container, elas equivalem às âncoras
     * comuns, porque não existe janela a que se prender.
     */
    public static final Set<String> ANCHORS = Set.of(
            "top_left", "top", "top_right",
            "left", "center", "right",
            "bottom_left", "bottom", "bottom_right",
            "gui_top_left", "gui_top_right", "gui_center",
            "gui_left", "gui_right");

    /**
     * Telas do jogo sobre as quais um mod pode desenhar.
     *
     * <p>O vocabulário é fechado, como o das ações: um mod não nomeia classes do cliente, porque
     * elas mudam entre versões do jogo e entre plataformas. O adaptador traduz cada nome para a
     * tela correspondente, e um nome que aquele cliente não conhece simplesmente nunca casa.
     */
    public static final Set<String> TARGETS = Set.of(
            "any", "container", "inventory", "creative", "crafting",
            "furnace", "chest", "anvil", "pause", "death", "title");

    /**
     * Estilos de painel que o cliente sabe desenhar sem textura nenhuma.
     *
     * <p>O visual de janela do Minecraft não vem de imagem: vem de bisel — uma borda clara em cima
     * e à esquerda, escura embaixo e à direita. Descrevê-lo como regra, e não como arquivo, deixa
     * o painel acompanhar qualquer tamanho e dispensa o mod distribuir textura.
     */
    public static final Set<String> PANEL_STYLES =
            Set.of("flat", "vanilla", "slot", "inset", "divider");

    /** Teto de sobreposições simultâneas por jogador. */
    public static final int MAX_OVERLAYS = 16;

    /** Teto de elementos por tela, para uma descrição enorme não travar o cliente. */
    public static final int MAX_ELEMENTS = 256;

    /** Teto de caracteres de qualquer texto exibido. */
    public static final int MAX_TEXT_LENGTH = 512;

    /** Teto do JSON enviado, em caracteres. */
    public static final int MAX_PAYLOAD_CHARS = 64 * 1024;

    /** Maior dimensão aceita para uma tela, em unidades de interface. */
    public static final int MAX_SCREEN_SIZE = 1024;

    /**
     * Maior altura de conteúdo rolável.
     *
     * <p>Tem teto próprio, e muito maior que o de uma tela, porque mede justamente o que não cabe
     * nela: uma lista com o registro inteiro do jogo passa de três mil pixels. Limitá-la ao tamanho
     * de uma janela anularia a razão de o campo existir.
     */
    public static final int MAX_CONTENT_SIZE = 64 * 1024;
}
