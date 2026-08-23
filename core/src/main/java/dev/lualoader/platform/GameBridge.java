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
    };
}
