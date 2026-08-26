package dev.lualoader.neoforge;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.EntityEventData;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.living.AnimalTameEvent;

import java.util.function.Supplier;

/**
 * Liga o que acontece com as criaturas do mundo ao runtime Lua, no NeoForge.
 *
 * <p>O par de {@code EntityEvents} do Fabric. Os dois existem porque cada plataforma nomeia e
 * entrega os eventos do seu jeito — aqui há um evento de domesticação pronto, e lá não —, mas
 * <b>quando</b> eles disparam e <b>com que dados</b> não pode divergir. É a espécie de diferença que
 * o repositório já viu se acumular em silêncio: sete eventos que não disparavam de um lado, e a
 * matriz de compatibilidade afirmando o contrário.
 *
 * <p>O runtime vem por fornecedor, e não por referência: ele só nasce quando o servidor sobe, e os
 * ouvintes são ligados muito antes disso.
 */
public final class NeoForgeEntityEvents {
    private final Supplier<LuaRuntime> runtime;

    public NeoForgeEntityEvents(Supplier<LuaRuntime> runtime) {
        this.runtime = runtime;
    }

    public void register(IEventBus bus) {
        bus.addListener(this::onJoin);
        bus.addListener(this::onDamage);
        bus.addListener(this::onDeath);
        bus.addListener(this::onTame);
    }

    private void onJoin(EntityJoinLevelEvent event) {
        LuaRuntime lua = runtime.get();
        if (lua == null || event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof LivingEntity living) || living instanceof Player) return;

        lua.triggerEntity("entity_spawned", null, snapshot(living, 0.0f, null));
    }

    /**
     * Apanhar, e o único cancelável dos quatro.
     *
     * <p>{@code LivingIncomingDamageEvent} é o ponto em que o dano ainda pode ser impedido; depois
     * de aplicado não há o que cancelar. É o equivalente do {@code ALLOW_DAMAGE} do Fabric.
     */
    private void onDamage(LivingIncomingDamageEvent event) {
        LuaRuntime lua = runtime.get();
        if (lua == null || event.getEntity() instanceof Player) return;

        if (lua.triggerEntity("entity_damaged", null,
                snapshot(event.getEntity(), event.getAmount(), event.getSource()))) {
            event.setCanceled(true);
        }
    }

    private void onDeath(LivingDeathEvent event) {
        LuaRuntime lua = runtime.get();
        if (lua == null || event.getEntity() instanceof Player) return;

        lua.triggerEntity("entity_died", null,
                snapshot(event.getEntity(), 0.0f, event.getSource()));
    }

    private void onTame(AnimalTameEvent event) {
        LuaRuntime lua = runtime.get();
        if (lua == null) return;

        lua.triggerEntity("entity_tamed", null, snapshot(event.getAnimal(), 0.0f, null));
    }

    /**
     * Fotografa a criatura no instante do evento.
     *
     * <p>Resolvido aqui, e nao consultado depois pelo script: no instante da morte a vida ja e
     * zero, e um script que perguntasse ao mundo chegaria sempre tarde demais.
     */
    private static EntityEventData snapshot(LivingEntity entity, float amount,
                                            DamageSource source) {
        String type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString();
        Component custom = entity.getCustomName();

        String sourceId = null;
        String sourceUuid = null;
        String sourceName = null;
        if (source != null) {
            sourceId = source.getMsgId();
            Entity attacker = source.getEntity();
            if (attacker != null) {
                sourceUuid = attacker.getStringUUID();
                sourceName = attacker.getName().getString();
            }
        }

        return new EntityEventData(
                entity.getStringUUID(), type,
                entity.getX(), entity.getY(), entity.getZ(),
                entity.getHealth(), entity.getMaxHealth(),
                custom == null ? null : custom.getString(),
                amount, sourceId, sourceUuid, sourceName);
    }
}
