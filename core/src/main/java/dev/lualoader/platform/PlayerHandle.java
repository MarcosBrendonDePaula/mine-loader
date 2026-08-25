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
     * Entrega um item com o que o mod declarou sobre ele.
     *
     * <p>Padrao que ignora os dados, pela mesma razao do spawn: um adaptador que ainda nao os
     * aplique entrega o item comum, em vez de recusar.
     */
    default int giveItem(String itemId, int count, ItemSpec spec) {
        return giveItem(itemId, count);
    }

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
     * O bloco para onde quem joga esta olhando.
     *
     * <p>Devolve {@code [x, y, z, lado]}, onde o lado e a face atingida em numero -- a ordem do
     * jogo: 0 baixo, 1 cima, 2 norte, 3 sul, 4 oeste, 5 leste. Devolve {@code null} quando a linha
     * de visao nao encontra bloco nenhum dentro do alcance.
     *
     * <p><b>Por que isto faltava fazer diferenca.</b> Sem mira, todo comando de mod pede
     * coordenada digitada, e quem joga tem que abrir o F3 para descobrir onde esta o bloco que esta
     * vendo na frente. E a diferenca entre "clique e pronto" e "anote tres numeros".
     *
     * <p>Atravessa liquido, e nao para na agua: quem mira num bloco dentro d'agua quer o bloco.
     *
     * @param maxDistance alcance em blocos; o jogo usa cinco para o alcance de construcao
     */
    int[] lookingAt(double maxDistance);

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
    /**
     * @return {@code true} se o HUD chegou ao cliente
     */
    boolean setHud(String descriptionJson);

    /**
     * Desenha sobre uma tela que o próprio jogo abre.
     *
     * <p>É a diferença entre abrir uma tela e participar de uma existente: a sobreposição fica
     * registrada no cliente e passa a aparecer sempre que a tela alvo abrir, até ser removida. Um
     * botão ao lado do inventário, um painel colado ao forno ou um aviso na tela de morte dependem
     * disso, porque nenhum deles pode substituir a tela em que aparece.
     *
     * <p>Enviar de novo com o mesmo identificador substitui a anterior.
     *
     * @param overlayId       identificador, devolvido junto com cada evento como se fosse uma tela
     * @param descriptionJson descrição já validada pelo núcleo, com o alvo dentro
     * @return {@code false} quando o cliente não entende o protocolo
     */
    boolean setOverlay(String overlayId, String descriptionJson);

    /**
     * Remove uma sobreposição registrada.
     *
     * @return {@code false} quando o cliente não entende o protocolo
     */
    boolean clearOverlay(String overlayId);

    /**
     * Tamanho da tela do jogador, em unidades de interface, ou {@code null}.
     *
     * <p>É {@code null} quando o cliente não tem o loader ou ainda não informou. Um mod que desenha
     * precisa disto para caber: a escala da interface divide a resolução, então a mesma janela que
     * sobra espaço em escala 2 transborda em escala 3.
     *
     * @return largura e altura, nesta ordem
     */
    int[] screenSize();

    /** Fecha o menu aberto, se houver. */
    void closeMenu();

    // ------------------------------------------------------------------ corpo, escrita

    /**
     * Define a vida do jogador.
     *
     * <p>{@link #health()} lia sem que houvesse como escrever, o que deixava de fora qualquer mod
     * de cura, dano por armadilha ou penalidade.
     */
    default void setHealth(float health) {
        throw new BridgeException("set_health nao existe neste adaptador");
    }

    /** Fome e saturação, nessa ordem. */
    default float[] food() {
        throw new BridgeException("food nao existe neste adaptador");
    }

    default void setFood(int level, float saturation) {
        throw new BridgeException("set_food nao existe neste adaptador");
    }

    /** Nível e progresso dentro do nível, de 0 a 1. */
    default float[] experience() {
        throw new BridgeException("experience nao existe neste adaptador");
    }

    /** Acrescenta níveis; um valor negativo tira. */
    default void giveExperienceLevels(int levels) {
        throw new BridgeException("give_experience nao existe neste adaptador");
    }

    /** {@code survival}, {@code creative}, {@code adventure} ou {@code spectator}. */
    default String gameMode() {
        throw new BridgeException("game_mode nao existe neste adaptador");
    }

    default void setGameMode(String mode) {
        throw new BridgeException("set_game_mode nao existe neste adaptador");
    }

    /**
     * Nível de permissão do jogador no servidor, de zero a quatro.
     *
     * <p>Existe para uma operação só, e é a mais poderosa do loader: instalar um mod. Sem isto,
     * qualquer pessoa numa partida compartilhada poderia acrescentar código ao servidor, e a única
     * defesa seria o sandbox — que limita o que um script faz, não quem pôde colocá-lo lá.
     *
     * <p>Zero é o padrão seguro: uma plataforma que não responda por nível trata todo mundo como
     * jogador comum, e a instalação recusa em vez de liberar.
     */
    default int permissionLevel() {
        return 0;
    }

    /** Se o jogador comanda o servidor. Nível dois é o que o jogo chama de operador. */
    default boolean isOperator() {
        return permissionLevel() >= 2;
    }

    /** A dimensão em que o jogador está. */
    default String dimension() {
        throw new BridgeException("dimension nao existe neste adaptador");
    }

    /**
     * Aplica um efeito de poção ao jogador.
     *
     * <p>Efeito já valia para entidade criada por mod, e não para o jogador — a assimetria mais
     * estranha da API, porque o alvo mais provável de um efeito é justamente quem está jogando.
     */
    default void applyEffect(String effectId, int duration, int amplifier) {
        throw new BridgeException("apply_effect nao existe neste adaptador");
    }

    default void clearEffects() {
        throw new BridgeException("clear_effects nao existe neste adaptador");
    }

    // ------------------------------------------------------------------ feedback

    /**
     * Mostra um título no meio da tela.
     *
     * <p>Havia chat e barra de ação; faltava o aviso grande, que é o que se usa para começo de
     * evento, aviso de perigo e mudança de fase.
     *
     * @param fadeIn  tiques de entrada; valores negativos usam o padrão do jogo
     */
    default void showTitle(String title, String subtitle, int fadeIn, int stay, int fadeOut) {
        throw new BridgeException("show_title nao existe neste adaptador");
    }

    /**
     * Toca um som só para este jogador.
     *
     * <p>O som do mundo é ouvido por todos em volta. Um retorno de interface — o clique de um menu,
     * o aviso de que a compra deu certo — pertence a quem agiu, e não à vizinhança.
     */
    default void playSoundTo(String soundId, float volume, float pitch) {
        throw new BridgeException("play_sound_to nao existe neste adaptador");
    }

    // ------------------------------------------------------------------ inventário

    /**
     * O que o jogador carrega, cada linha como {@code slot;item;quantidade}.
     *
     * <p>{@link #countItem} responde por um item de cada vez, o que obriga a saber de antemão o que
     * procurar. Um mod que inspeciona, cobra por peso ou copia o inventário precisa vê-lo inteiro.
     */
    default java.util.List<String> inventory() {
        throw new BridgeException("inventory nao existe neste adaptador");
    }

    /** Esvazia o inventário. */
    default void clearInventory() {
        throw new BridgeException("clear_inventory nao existe neste adaptador");
    }
}
