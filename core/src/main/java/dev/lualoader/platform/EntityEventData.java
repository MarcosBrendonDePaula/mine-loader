package dev.lualoader.platform;

/**
 * Dados de uma entidade envolvida em um evento, neutros em relação à plataforma.
 *
 * <p>O adaptador resolve tudo antes de entregar ao núcleo, e é de propósito: no instante em que uma
 * criatura morre, perguntar a vida dela já responderia zero, e perguntar a posição de um projétil
 * que sumiu não responderia nada. Um script que precisasse consultar o mundo para saber o que
 * acabou de acontecer chegaria sempre tarde demais.
 *
 * <p>{@code null} nos campos de origem significa "não houve": um bicho que morreu de queda não tem
 * quem o matou, e isso é diferente de ter e o adaptador não saber dizer.
 *
 * <p><b>A entidade não é necessariamente do loader.</b> Estes eventos falam de qualquer criatura do
 * mundo, e é o que os torna úteis: um mod de combate reage ao zumbi do jogo, e não só ao bicho que
 * ele mesmo declarou. Filtrar por {@code entity_id} é decisão de quem escreve o mod.
 *
 * @param uuid         identificador da entidade no mundo
 * @param entityId     tipo, no formato {@code mod:especie}
 * @param health       vida no momento do evento, antes do que ele causa
 * @param maxHealth    vida máxima
 * @param name         nome personalizado, ou {@code null}
 * @param amount       dano ou cura envolvida, quando o evento tem um número
 * @param sourceId     como o dano chegou, no vocabulário do jogo, ou {@code null}
 * @param sourceUuid   quem causou, ou {@code null} quando não houve quem
 * @param sourceName   nome de quem causou, para a mensagem não exigir uma segunda consulta
 */
public record EntityEventData(String uuid,
                              String entityId,
                              double x,
                              double y,
                              double z,
                              float health,
                              float maxHealth,
                              String name,
                              float amount,
                              String sourceId,
                              String sourceUuid,
                              String sourceName) {

    /** Um evento sem dano nem origem: nascimento, domesticação. */
    public static EntityEventData simple(String uuid, String entityId,
                                         double x, double y, double z,
                                         float health, float maxHealth, String name) {
        return new EntityEventData(uuid, entityId, x, y, z, health, maxHealth, name,
                0.0f, null, null, null);
    }
}
