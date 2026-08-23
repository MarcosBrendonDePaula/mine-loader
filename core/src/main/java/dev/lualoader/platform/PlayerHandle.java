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

    /** Fecha o menu aberto, se houver. */
    void closeMenu();
}
