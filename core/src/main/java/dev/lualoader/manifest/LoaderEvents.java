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
    public static final Set<String> ALL = union(GLOBAL, BLOCK, ITEM, MENU);

    private static Set<String> union(Set<String>... groups) {
        var all = new java.util.LinkedHashSet<String>();
        for (Set<String> group : groups) all.addAll(group);
        return Set.copyOf(all);
    }
}
