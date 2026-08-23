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
     * @return quantidade que não coube e foi devolvida ao mundo ou perdida
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
}
