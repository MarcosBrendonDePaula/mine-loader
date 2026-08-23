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
    };
}
