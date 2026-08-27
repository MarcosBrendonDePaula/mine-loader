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
            "mod_reloaded",
            "action_attempt"
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
            "block_neighbor_update",
            "block_scheduled"
    );

    /** Eventos originados por uma janela aberta pelo mod. */
    public static final Set<String> MENU = Set.of(
            "menu_click",
            "menu_closed"
    );

    /**
     * Eventos originados por uma criatura do mundo.
     *
     * <p>Eram dezessete eventos e nenhum de entidade, e {@code API_GAPS.md} chamava isso de a
     * lacuna que mais bloqueia mod de combate: um mod não tinha onde se prender para saber que algo
     * morreu, apanhou ou nasceu.
     *
     * <p><b>Valem para qualquer criatura, e não só para as declaradas pelo loader.</b> É o que os
     * torna úteis — um mod de combate reage ao zumbi do jogo. Filtrar pelo tipo é decisão de quem
     * escreve o mod, e não uma regra imposta aqui.
     *
     * <p>{@code entity_damage} é cancelável, como os de bloco: devolver {@code false} impede o
     * dano. Os outros são avisos do que já aconteceu, e o retorno deles não muda nada.
     */
    public static final Set<String> ENTITY = Set.of(
            "entity_spawned",
            "entity_damaged",
            "entity_died",
            "entity_tamed"
    );

    /** Eventos originados por um item declarado. */
    public static final Set<String> ITEM = Set.of(
            "item_used",
            "item_used_on_block"
    );

    /**
     * Eventos da fase de registro, antes de o jogo congelar os registros.
     *
     * <p>Separados de {@link #GLOBAL} porque acontecem num mundo que ainda não existe: aqui não há
     * servidor, jogador nem bloco para tocar, e só se pode acrescentar conteúdo. Misturá-los com os
     * outros faria um mod declarar {@code player_joined} para esta fase e nunca ser chamado.
     *
     * <p>Mapeiam para um <b>arquivo</b>, como o {@code behavior} de um bloco, e não para uma função
     * do {@code main.lua}. É o que impede o topo do entrypoint de rodar duas vezes: nesta fase ele
     * teria que ser carregado sozinho, e toda linha fora de função executaria de novo mais tarde —
     * um defeito que não dá erro, só estado errado.
     */
    public static final Set<String> REGISTRATION = Set.of(
            "on_register"
    );

    /** Todos os eventos aceitos, usados tanto na validação quanto no disparo. */
    public static final Set<String> ALL = union(GLOBAL, CLIENT, BLOCK, ENTITY, ITEM, MENU);

    private static Set<String> union(Set<String>... groups) {
        var all = new java.util.LinkedHashSet<String>();
        for (Set<String> group : groups) all.addAll(group);
        return Set.copyOf(all);
    }
}
