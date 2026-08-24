package dev.lualoader.minecraft;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.EntityEventData;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

/**
 * Liga o que acontece com as criaturas do mundo ao runtime Lua.
 *
 * <p>Eram dezessete eventos e nenhum de entidade. Um mod de combate não tinha onde se prender: não
 * havia como saber que algo morreu, apanhou ou nasceu, e a única saída era varrer o mundo a cada
 * tique perguntando a vida de todo mundo — caro, e ainda assim cego para o que acontece entre dois
 * tiques.
 *
 * <p><b>Vale para qualquer criatura, e não só para as declaradas pelo loader.</b> É o que torna os
 * eventos úteis: um mod reage ao zumbi do jogo. Filtrar por tipo é decisão de quem escreve o mod.
 *
 * <p>O par deste arquivo no NeoForge é {@code NeoForgeEntityEvents}. Os dois existem porque cada
 * plataforma nomeia e entrega os eventos do seu jeito; o que não pode divergir é quando eles
 * disparam e com que dados — e é o que o {@code autoteste} confere dos dois lados.
 */
public final class EntityEvents {
    private final LuaRuntime runtime;

    public EntityEvents(LuaRuntime runtime) {
        this.runtime = runtime;
    }

    public void register() {
        // Nascer: o mesmo evento que aplica os padrões da espécie declarada. Aqui ele vale para
        // qualquer criatura, inclusive as do jogo.
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(entity instanceof LivingEntity living)) return;
            // O jogador tambem entra no mundo por este caminho, e anunciar a chegada dele como
            // "uma criatura nasceu" faria todo mod de combate contar jogador como bicho.
            if (entity instanceof net.minecraft.entity.player.PlayerEntity) return;
            runtime.triggerEntity("entity_spawned", null, snapshot(living, 0.0f, null));
        });

        // Apanhar: cancelavel. E o unico dos quatro em que o script decide se o jogo segue, e por
        // isso usa ALLOW em vez de AFTER -- depois do dano aplicado nao ha o que impedir.
        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (entity instanceof net.minecraft.entity.player.PlayerEntity) return true;
            return !runtime.triggerEntity("entity_damaged", null,
                    snapshot(entity, amount, source));
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (entity instanceof net.minecraft.entity.player.PlayerEntity) return;
            runtime.triggerEntity("entity_died", null, snapshot(entity, 0.0f, source));
        });
    }

    /**
     * Avisa que uma criatura foi domesticada.
     *
     * <p>Nao ha evento de plataforma para isto no Fabric, entao quem chama e a propria acao de
     * domesticar, pela ponte. Sem isso, o unico jeito de um mod saber seria comparar o dono de cada
     * bicho a cada tique.
     */
    public void tamed(TameableEntity entity) {
        runtime.triggerEntity("entity_tamed", null, snapshot(entity, 0.0f, null));
    }

    /**
     * Fotografa a criatura no instante do evento.
     *
     * <p>Resolvido aqui, e nao consultado depois pelo script: no instante da morte a vida ja e
     * zero, e um script que perguntasse ao mundo chegaria sempre tarde demais.
     */
    private static EntityEventData snapshot(LivingEntity entity, float amount, DamageSource source) {
        String type = Registries.ENTITY_TYPE.getId(entity.getType()).toString();
        Text custom = entity.getCustomName();

        String sourceId = null;
        String sourceUuid = null;
        String sourceName = null;
        if (source != null) {
            sourceId = source.getName();
            Entity attacker = source.getAttacker();
            if (attacker != null) {
                sourceUuid = attacker.getUuidAsString();
                sourceName = attacker.getName().getString();
            }
        }

        return new EntityEventData(
                entity.getUuidAsString(), type,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getHealth(), entity.getMaxHealth(),
                custom == null ? null : custom.getString(),
                amount, sourceId, sourceUuid, sourceName);
    }
}
