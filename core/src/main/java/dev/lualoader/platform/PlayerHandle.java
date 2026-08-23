package dev.lualoader.platform;

/**
 * Referência neutra a um jogador, válida apenas durante o callback que a recebeu.
 *
 * <p>O adaptador embrulha a entidade real da plataforma; o núcleo enxerga somente
 * estas operações.
 */
public interface PlayerHandle {
    String name();

    String uuid();

    void sendMessage(String message);

    /** Mensagem curta acima da barra de itens, que desaparece sozinha. */
    void sendActionBar(String message);

    /** Identificador do item na mão principal, ou {@code minecraft:air}. */
    String heldItem();

    /** Quantidade do item no inventário inteiro. */
    int countItem(String itemId);

    /**
     * Coloca itens no inventário.
     *
     * @return quantidade que não coube no inventário e foi derrubada no mundo
     */
    int giveItem(String itemId, int count);

    /**
     * Remove itens do inventário.
     *
     * @return quantidade efetivamente removida, que pode ser menor que a pedida
     */
    int takeItem(String itemId, int count);

    /** Posição atual, em blocos. */
    int[] position();

    /** Vida atual e máxima, nesta ordem. */
    float[] health();

    /** Move o jogador para a posição indicada. */
    void teleport(double x, double y, double z);

    /**
     * Abre um menu de itens para o jogador.
     *
     * <p>Usa a tela de container do próprio jogo, o que dispensa um renderizador novo no cliente e
     * faz o recurso funcionar em qualquer cliente vanilla. Cada linha é {@code item;quantidade;rotulo},
     * e o jogador não retira o que está exposto: os slots são botões, não armazenamento.
     *
     * @param menuId identificador do menu, devolvido ao script quando um slot é clicado
     * @param rows   número de linhas, de 1 a 6
     */
    void openMenu(String menuId, String title, int rows, java.util.List<String> items);

    /**
     * Substitui o conteúdo do menu aberto sem fechá-lo.
     *
     * <p>Sem isto, reagir a um clique exigiria fechar e reabrir a tela, o que pisca e devolve o
     * cursor ao centro.
     *
     * @return {@code false} quando não há menu do loader aberto
     */
    boolean updateMenu(java.util.List<String> items);

    /** Identificador do menu aberto, ou {@code null} quando não há nenhum. */
    String openMenuId();

    /**
     * Indica se o cliente deste jogador entende o protocolo de interface.
     *
     * <p>Um cliente vanilla não tem o canal, e nesse caso a tela desenhada não pode ser aberta. O
     * mod decide o que fazer: a janela de itens continua disponível como alternativa que funciona
     * em qualquer cliente.
     */
    boolean supportsScreens();

    /**
     * Abre uma tela desenhada pelo mod.
     *
     * @param screenId    identificador da tela, devolvido junto com cada evento
     * @param descriptionJson descrição já validada pelo núcleo
     * @return {@code false} quando o cliente não entende o protocolo
     */
    boolean openScreen(String screenId, String descriptionJson);

    /**
     * Substitui o conteúdo da tela aberta sem reabri-la.
     *
     * @return {@code false} quando não há tela do loader aberta
     */
    boolean updateScreen(String descriptionJson);

    /** Fecha a tela do loader, se houver. */
    void closeScreen();

    /** Identificador da tela aberta, ou {@code null}. */
    String openScreenId();

    /**
     * Define os elementos fixos na tela do jogador.
     *
     * <p>Diferente de uma tela, o HUD não captura mouse nem pausa o jogo. Uma descrição sem
     * elementos limpa o que estava sendo desenhado.
     */
    void setHud(String descriptionJson);

    /** Fecha o menu aberto, se houver. */
    void closeMenu();
}
