package dev.lualoader.platform;

/**
 * Falha ao executar uma operação de plataforma solicitada por um mod.
 *
 * <p>O adaptador lança esta exceção em vez de propagar tipos específicos do jogo, para
 * que o núcleo possa reportar o erro ao mod sem conhecer a plataforma.
 */
public class BridgeException extends RuntimeException {
    public BridgeException(String message) {
        super(message);
    }

    public BridgeException(String message, Throwable cause) {
        super(message, cause);
    }
}
