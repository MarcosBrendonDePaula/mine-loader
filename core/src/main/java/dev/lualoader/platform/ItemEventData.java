package dev.lualoader.platform;

/**
 * Dados de um item envolvido em um evento, neutros em relação à plataforma.
 *
 * @param itemId      identificador do item, no formato {@code mod:item}
 * @param targetBlock bloco alvo quando o item foi usado sobre um bloco; {@code null} caso contrário
 * @param hasPosition indica se as coordenadas descrevem uma posição real, e não o uso no ar
 */
public record ItemEventData(String itemId, String targetBlock, int x, int y, int z, boolean hasPosition) {
}
