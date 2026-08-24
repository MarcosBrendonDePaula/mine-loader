package dev.lualoader.manifest;

import java.util.Set;

/**
 * Nomes de evento que o loader reconhece.
 *
 * <p>Existe como fonte única porque a lista era mantida em dois lugares — o validador do manifesto
 * e o runtime — e elas divergiram: um evento acrescentado ao runtime continuava sendo recusado no
 * manifesto, e a mensagem de erro apontava para o script em vez da causa. Qualquer evento novo entra
 * aqui e vale nos dois lados de imediato.
 */
public final class LoaderEvents {
    private LoaderEvents() {
    }

    /** Eventos sem dono, entregues ao mod inteiro. */
    public static final Set<String> GLOBAL = Set.of(
            "loader_ready",
            "server_started",
            "server_stopped",
            "player_joined",
            "player_left",
            "tick",
            "mod_reloaded"
    );

    /**
     * Eventos originados no cliente de quem joga.
     *
     * <p>É o lado que faltava. Até aqui todo evento nascia no servidor — ciclo de vida, tique,
     * bloco, item — e o cliente era só um renderizador: recebia descrição de tela e devolvia
     * clique. Um mod não tinha como saber que o jogador abriu o inventário, e o loader já
     * desenhava sobreposições justamente sobre essas telas.
     *
     * <p><b>O código continua sem atravessar a rede.</b> O cliente não passa a rodar script: ele
     * relata um fato de um vocabulário fechado, e o script que reage àquilo continua no servidor.
     * A regra de {@code UI_SPEC.md} -- o cliente interpreta dados, nunca código -- fica de pé.
     *
     * <p>O nome da tela vem de {@code ScreenProtocol.TARGETS}, o mesmo conjunto que a sobreposição
     * usa. Reusar é o que impede um mod de ter que aprender dois vocabulários para falar da mesma
     * tela.
     */
    public static final Set<String> CLIENT = Set.of(
            "client_screen_opened",
            "client_screen_closed"
    );

    /** Eventos originados por um bloco declarativo. */
    public static final Set<String> BLOCK = Set.of(
            "block_used",
            "block_attacked",
            "block_placed",
            "block_broken",
            "block_random_tick",
            "block_neighbor_update"
    );

    /** Eventos originados por uma janela aberta pelo mod. */
    public static final Set<String> MENU = Set.of(
            "menu_click",
            "menu_closed"
    );

    /** Eventos originados por um item declarado. */
    public static final Set<String> ITEM = Set.of(
            "item_used",
            "item_used_on_block"
    );

    /** Todos os eventos aceitos, usados tanto na validação quanto no disparo. */
    public static final Set<String> ALL = union(GLOBAL, CLIENT, BLOCK, ITEM, MENU);

    private static Set<String> union(Set<String>... groups) {
        var all = new java.util.LinkedHashSet<String>();
        for (Set<String> group : groups) all.addAll(group);
        return Set.copyOf(all);
    }
}
