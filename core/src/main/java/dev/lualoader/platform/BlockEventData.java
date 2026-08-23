package dev.lualoader.platform;

/**
 * Dados de um bloco declarativo envolvido em uma interação, neutros em relação à plataforma.
 *
 * <p>O adaptador resolve a posição e o estado atual antes de entregar o evento ao núcleo,
 * de modo que o script receba a variante corrente sem consultar o jogo.
 *
 * @param blockId      identificador completo, no formato {@code mod:bloco}
 * @param variant      variante visual atualmente aplicada no mundo
 * @param variantCount quantidade de variantes declaradas no manifesto, no mínimo 1
 */
public record BlockEventData(String blockId, int x, int y, int z, int variant, int variantCount) {
}
