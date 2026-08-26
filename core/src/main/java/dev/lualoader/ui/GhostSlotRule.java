package dev.lualoader.ui;

/**
 * A regra de um slot <b>fantasma</b>, escrita uma vez para os dois adaptadores e as duas janelas.
 *
 * <p>Um slot fantasma mostra um item sem guardar item nenhum: clicar com um item no cursor copia a
 * <i>identidade</i> dele para o slot e devolve o cursor intacto; mão vazia limpa. <b>Nada é
 * consumido, nunca.</b>
 *
 * <p>A regra é a do {@code DummySlot} do Logistic Pipes, que é onde esta lacuna apareceu: ele monta
 * o padrão de fabricação com slots que recusam {@code canTakeStack} e têm limite de pilha zero, e
 * reescreve o clique para copiar em vez de mover.
 *
 * <p><b>Mora no núcleo porque são quatro implementações.</b> Cada plataforma tem a sua, e cada uma
 * precisa da janela de fileiras <i>e</i> da janela 3x3 — o tipo de janela do jogo é uma classe
 * diferente para cada formato, e não dá para herdar as duas. Quatro cópias da mesma decisão
 * divergiriam no primeiro ajuste, e o sintoma seria o clique fazer uma coisa numa tela e outra na
 * outra.
 */
public final class GhostSlotRule {
    private GhostSlotRule() {
    }

    /**
     * Quanto o slot deve mostrar depois de um clique, dado o que está no cursor.
     *
     * @param cursorCount quantos itens estão no cursor; zero quando a mão está vazia
     * @param rightButton se o clique foi com o botão direito
     * @return quantos desenhar, ou zero para limpar o slot
     */
    public static int countAfterClick(int cursorCount, boolean rightButton) {
        // Mão vazia limpa: é o gesto de tirar da bancada.
        if (cursorCount <= 0) return 0;

        // Direito desenha um, esquerdo desenha o que está na mão. É o que distingue "quero um aqui"
        // de "quero esta quantidade aqui" -- e uma receita que pede três de algo precisa dos dois.
        return rightButton ? 1 : Math.max(1, cursorCount);
    }
}
