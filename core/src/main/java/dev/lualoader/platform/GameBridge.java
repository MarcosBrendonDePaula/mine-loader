package dev.lualoader.platform;

/**
 * Fronteira entre o núcleo do loader e a plataforma que hospeda o jogo.
 *
 * <p>O núcleo nunca conhece Fabric, NeoForge ou classes do Minecraft. Toda operação
 * que precise tocar o jogo passa por esta interface, implementada pelo adaptador de
 * plataforma. Cada implementação é responsável por agendar a chamada na thread correta
 * e por traduzir falhas para {@link BridgeException}.
 */
public interface GameBridge {
    /**
     * Capacidades que um bloco pode oferecer, nomeadas pelo loader e não por nenhuma plataforma.
     *
     * <p>Cada adaptador traduz para o mecanismo da casa: no Fabric é a Transfer API, no NeoForge são
     * as capabilities, no Paper é o inventário do Bukkit. Nenhuma das três aparece aqui, e é isso
     * que faz um mod escrito para este loader rodar nas outras sem mudar uma linha — algo que
     * escrever direto para Fabric ou NeoForge não permite.
     */
    java.util.Set<String> CAPABILITIES = java.util.Set.of("items", "fluid", "energy");

    /** Envia uma mensagem pública a todos os jogadores conectados. */
    void broadcast(String message);

    /** Altera a variante visual do bloco declarativo na posição indicada. */
    void setBlockVariant(String blockId, int x, int y, int z, int variant);

    /** Altera uma propriedade física dinâmica de um bloco declarativo. */
    void setBlockProperty(String blockId, String property, float value);

    /** Altera a luminosidade do bloco declarativo na posição indicada. */
    void setBlockLuminance(String blockId, int x, int y, int z, int luminance);

    /** Indica se há um mundo ativo capaz de receber operações de escrita. */
    boolean isWorldAvailable();

    /** Nomes dos jogadores conectados. */
    java.util.List<String> onlinePlayers();

    /**
     * Hora do dia no mundo corrente, em ticks de 0 a 23999.
     *
     * <p>Zero é o amanhecer e 13000 é o anoitecer, a mesma escala usada pelo comando {@code /time}.
     */
    long timeOfDay();

    /** Identificador da dimensão em que as operações estão agindo. */
    String worldName();

    /**
     * Lê o identificador do bloco na posição indicada.
     *
     * @return identificador no formato {@code mod:bloco}, por exemplo {@code minecraft:stone}
     */
    String getBlock(int x, int y, int z);

    /**
     * Substitui o bloco na posição indicada por qualquer bloco registrado, do jogo ou de um mod.
     *
     * <p>Esta é a primitiva que permite a um mod construir: sem ela o script só consegue alterar
     * blocos declarativos que já existem no mundo.
     */
    void setBlock(String blockId, int x, int y, int z);

    /**
     * Preenche a região delimitada pelos dois cantos, inclusive.
     *
     * <p>Existe como operação própria porque preencher bloco a bloco a partir do Lua seria
     * ordens de grandeza mais lento.
     *
     * @return quantidade de blocos efetivamente alterados
     */
    int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2);

    /**
     * Toca um som na posição indicada.
     *
     * @param soundId identificador do som, por exemplo {@code minecraft:block.anvil.use}
     * @param volume  1.0 é o volume normal
     * @param pitch   1.0 é o tom normal
     */
    void playSound(String soundId, int x, int y, int z, float volume, float pitch);

    /**
     * Emite partículas na posição indicada.
     *
     * @param particleId identificador, por exemplo {@code minecraft:happy_villager}
     * @param spread     dispersão em blocos ao redor do ponto
     */
    void spawnParticles(String particleId, double x, double y, double z, int count, double spread);

    /**
     * Lê os dados guardados na posição, como texto JSON.
     *
     * @return {@code "{}"} quando o bloco não guarda dados ou nada foi gravado ainda
     */
    String getBlockData(int x, int y, int z);

    /** Grava dados na posição. O bloco precisa ter sido declarado com {@code block_data}. */
    void setBlockData(int x, int y, int z, String json);

    /**
     * Invoca uma entidade do jogo na posição indicada.
     *
     * @param entityId identificador, por exemplo {@code minecraft:zombie}
     * @return identificador único da entidade criada
     */
    String spawnEntity(String entityId, double x, double y, double z);

    /**
     * Lista as entidades dentro de um raio.
     *
     * @return para cada entidade, uma linha {@code uuid;tipo;x;y;z}
     */
    java.util.List<String> entitiesNear(double x, double y, double z, double radius);

    /**
     * Remove uma entidade pelo identificador único.
     *
     * @return {@code false} quando a entidade não foi encontrada
     */
    boolean removeEntity(String entityUuid);

    /**
     * Aplica dano a uma entidade.
     *
     * @return {@code false} quando a entidade não foi encontrada
     */
    boolean damageEntity(String entityUuid, float amount);

    /**
     * Identificadores dos itens registrados no jogo, em ordem alfabética.
     *
     * <p>Inclui o que outros mods registraram, porque o registro é único: é o que permite a um mod
     * montar um catálogo do jogo inteiro em vez de apenas do próprio conteúdo.
     *
     * @param namespace prefixo exigido, ou {@code null} para qualquer um
     * @param contains  trecho que o caminho precisa conter, ou {@code null}
     * @param limit     teto de resultados, para um catálogo inteiro não virar uma tabela gigante
     */
    java.util.List<String> registeredItems(String namespace, String contains, int limit);

    /**
     * Receitas que produzem um item.
     *
     * <p>Junto com {@link #recipesUsing} responde as duas perguntas que um catálogo existe para
     * responder: como se obtém isto, e para que isto serve. Sem elas o mod lista itens sem saber
     * ligá-los entre si.
     *
     * @return uma linha JSON por receita, com {@code id}, {@code type}, {@code output},
     *         {@code width}, {@code height} e {@code ingredients} — este último uma lista de
     *         posições, cada uma com os itens que servem ali
     */
    java.util.List<String> recipesFor(String itemId, int limit);

    /** Receitas que consomem um item em alguma posição. */
    java.util.List<String> recipesUsing(String itemId, int limit);

    /**
     * Itens que um bloco ou uma entidade pode derrubar.
     *
     * <p>É a terceira pergunta de um catálogo, e para boa parte do jogo é a verdadeira: minério,
     * pedra e madeira chegam ao jogador por mineração, e couro e lã por matar um mob.
     *
     * <p>Aceita os dois porque um catálogo pergunta "o que isto derruba" sem saber de antemão qual
     * dos dois é, e obrigá-lo a escolher a chamada certa só passaria o problema adiante.
     *
     * <p>Cobre apenas o que o jogo guarda como tabela de loot. Uma interação que vive em código —
     * tosquiar uma ovelha, encher um balde numa vaca — não é consultável em lugar nenhum, e para
     * aparecer num catálogo precisa ser declarada como processo.
     *
     * @param sourceId identificador de bloco ou de entidade
     * @return uma linha por item
     */
    java.util.List<String> dropsOf(String sourceId, int limit);

    /**
     * Blocos e entidades que podem derrubar um item.
     *
     * @return uma linha por bloco ou entidade
     */
    java.util.List<String> droppedBy(String itemId, int limit);

    /**
     * Capacidades que o bloco naquela posição oferece.
     *
     * <p>É a fronteira com o resto do ecossistema. Um baú, um forno e a máquina de outro mod
     * expõem, cada plataforma à sua maneira, a mesma ideia: aqui dentro há itens que podem ser
     * lidos, tirados e postos. O núcleo nomeia essa ideia; o adaptador sabe como perguntá-la.
     *
     * <p>Os nomes são um vocabulário fechado — {@code items}, {@code fluid}, {@code energy} — e não
     * os nomes de nenhuma API: {@code Storage} é do Fabric, {@code IItemHandler} é do NeoForge e
     * {@code Inventory} é do Bukkit. Se o contrato citasse um deles, um mod escrito para este
     * loader deixaria de rodar nos outros, que é exatamente o que esta camada existe para impedir.
     *
     * @return nomes das capacidades presentes, possivelmente vazio
     */
    java.util.Set<String> capabilitiesAt(int x, int y, int z);

    /**
     * Conteúdo do inventário naquela posição.
     *
     * @return uma linha por slot ocupado, no formato {@code slot;item;quantidade}
     */
    java.util.List<String> containerAt(int x, int y, int z);

    /**
     * Coloca itens no inventário daquela posição.
     *
     * <p>Devolve o que sobrou, e não o que entrou, pela mesma razão que {@code giveItem}: um script
     * que ignora o retorno pelo menos não some com item, e um que o lê descobre o inventário cheio
     * sem precisar contar antes.
     *
     * @return quantidade que não coube
     */
    int insertInto(int x, int y, int z, String itemId, int count);

    /**
     * Retira itens do inventário daquela posição.
     *
     * @return quantidade efetivamente retirada, que pode ser menor que a pedida
     */
    int extractFrom(int x, int y, int z, String itemId, int count);

    /** Bridge inerte, usada quando nenhuma plataforma está conectada (testes e validação offline). */
    GameBridge DETACHED = new GameBridge() {
        @Override
        public void broadcast(String message) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void setBlockProperty(String blockId, String property, float value) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public boolean isWorldAvailable() {
            return false;
        }

        @Override
        public java.util.List<String> onlinePlayers() {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public long timeOfDay() {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public String worldName() {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public String getBlock(int x, int y, int z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void setBlock(String blockId, int x, int y, int z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void spawnParticles(String particleId, double x, double y, double z,
                                   int count, double spread) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public String getBlockData(int x, int y, int z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public void setBlockData(int x, int y, int z, String json) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public String spawnEntity(String entityId, double x, double y, double z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> entitiesNear(double x, double y, double z, double radius) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public boolean removeEntity(String entityUuid) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public boolean damageEntity(String entityUuid, float amount) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> registeredItems(String namespace, String contains, int limit) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> recipesFor(String itemId, int limit) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> recipesUsing(String itemId, int limit) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.Set<String> capabilitiesAt(int x, int y, int z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> containerAt(int x, int y, int z) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public int insertInto(int x, int y, int z, String itemId, int count) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public int extractFrom(int x, int y, int z, String itemId, int count) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> dropsOf(String blockId, int limit) {
            throw new BridgeException("nenhuma plataforma conectada");
        }

        @Override
        public java.util.List<String> droppedBy(String itemId, int limit) {
            throw new BridgeException("nenhuma plataforma conectada");
        }
    };
}
