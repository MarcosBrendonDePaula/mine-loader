package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.EntityDefinition;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttribute;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.Item;
import net.minecraft.item.SpawnEggItem;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra as espécies declaradas pelos mods.
 *
 * <p>O irmão de {@link BlockRegistrar} e {@link ContentRegistrar}, e com o mesmo limite: só vale
 * enquanto o jogo carrega. O Minecraft congela os registros antes de o mundo existir, e uma espécie
 * oferecida depois disso não entra em lugar nenhum — por isso {@link #close()} passa a recusar em
 * vez de aceitar em silêncio.
 *
 * <p>O que a espécie declara como padrão não é aplicado aqui, e sim ao nascer. Vida e atributos são
 * a exceção: entram no contêiner de atributos do tipo, porque um atributo aplicado depois do
 * nascimento deixaria a criatura viver um instante com a vida da base — o suficiente para ela
 * aparecer com o número errado na tela de quem está olhando.
 */
public final class EntityRegistrar {
    private final Logger logger;
    private final Map<Identifier, EntityType<?>> types = new LinkedHashMap<>();
    private final Map<Identifier, EntityDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, List<Identifier>> spawnEggsByMod = new LinkedHashMap<>();

    private boolean open = true;

    public EntityRegistrar(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registra as espécies de todos os mods, em ordem de herança.
     *
     * <p>Todos de uma vez, e não mod a mod: uma espécie pode descender da espécie declarada por
     * outro mod, e a ordem de descoberta não é a ordem de herança. Registrar na ordem em que os
     * mods aparecem faria o adaptador procurar uma base que ainda não existe e recusar uma espécie
     * perfeitamente declarada — e o mod que a declarou nem seria o culpado.
     */
    public void registerAll(java.util.List<dev.lualoader.manifest.ModLoader.LoadedMod> mods) {
        Map<String, java.util.List<EntityDefinition>> byMod = new LinkedHashMap<>();
        for (var mod : mods) {
            if (mod.manifest().entities == null || mod.manifest().entities.isEmpty()) continue;
            byMod.put(mod.manifest().id, mod.manifest().entities);
        }
        if (byMod.isEmpty()) return;

        var resolution = dev.lualoader.platform.EntityDerivation.resolve(
                byMod, base -> EntityBases.get(base) != null);

        for (var rejected : resolution.rejected()) {
            // Recusa dita e nomeada: uma espécie que some sem motivo vira "o mod não funciona".
            logger.error("Especie {} recusada: {}", rejected.id(), rejected.reason());
        }
        for (var resolved : resolution.ordered()) {
            String namespace = resolved.id().substring(0, resolved.id().indexOf(':'));
            try {
                register(namespace, resolved.definition());
            } catch (RuntimeException error) {
                logger.error("Falha ao registrar {}: {}", resolved.id(), error.getMessage());
            }
        }
    }

    /**
     * Registra uma espécie, venha ela do manifesto ou de um script.
     *
     * @return o tipo criado
     * @throws IllegalStateException quando o registro já fechou, a base não é suportada ou o id já
     *                               existe — os três casos em que aceitar produziria um bicho que
     *                               não funciona sem dizer por quê
     */
    public EntityType<?> register(String namespace, EntityDefinition definition) {
        if (!open) {
            throw new IllegalStateException("o registro de entidades ja fechou: o jogo so aceita"
                    + " especie nova enquanto carrega");
        }
        if (definition == null || definition.id == null) {
            throw new IllegalStateException("especie sem id");
        }

        EntityBases.Base base = EntityBases.get(definition.base);
        if (base == null) {
            throw new IllegalStateException("base nao suportada para " + namespace + ":"
                    + definition.id + ": " + definition.base
                    + " (suportadas: " + EntityBases.supported() + ")");
        }

        Identifier id = Identifier.of(namespace, definition.id);
        if (types.containsKey(id) || Registries.ENTITY_TYPE.containsId(id)) {
            throw new IllegalStateException("Entidade ja registrada: " + id);
        }

        EntityType<?> type = build(id, definition, base);
        Registry.register(Registries.ENTITY_TYPE, id, type);
        types.put(id, type);
        definitions.put(id, definition);

        FabricDefaultAttributeRegistry.register(cast(type), attributesFor(definition, base));
        registerSpawnEgg(namespace, id, definition, type);

        logger.info("Lua Loader registrou entidade {} ({}), derivada de {}",
                id, definition.name, definition.base);
        return type;
    }

    /**
     * Monta o tipo a partir do que a espécie declarou.
     *
     * <p>Zero significa "herda", e por isso cada campo pergunta à base antes de decidir. Um padrão
     * numérico próprio faria uma espécie que não declarou tamanho nascer com a caixa de colisão
     * errada — atravessando parede ou flutuando — em vez de com a da base.
     */
    private EntityType<?> build(Identifier id, EntityDefinition definition, EntityBases.Base base) {
        EntityType<? extends LivingEntity> vanilla = base.vanilla();
        SpawnGroup group = EntityBases.categoryOf(definition.category, vanilla);

        EntityType.Builder<LivingEntity> builder =
                EntityType.Builder.create(castFactory(base.factory()), group);

        builder.dimensions(
                definition.width > 0 ? definition.width : vanilla.getWidth(),
                definition.height > 0 ? definition.height : vanilla.getHeight());
        builder.maxTrackingRange(definition.trackingRange > 0
                ? definition.trackingRange
                : vanilla.getMaxTrackDistance());
        builder.trackingTickInterval(definition.updateInterval > 0
                ? definition.updateInterval
                : vanilla.getTrackTickInterval());

        if (definition.fireImmune) builder.makeFireImmune();
        if (!definition.summonable) builder.disableSummon();
        if (!definition.saveable) builder.disableSaving();

        return builder.build(id.toString());
    }

    /**
     * Os atributos com que a espécie nasce.
     *
     * <p>Parte dos da base e sobrescreve o que foi declarado. Começar de um contêiner vazio faria
     * um lobo declarado nascer sem velocidade nem alcance de ataque, porque o manifesto raramente
     * lista tudo — e o resultado seria uma criatura parada.
     */
    private DefaultAttributeContainer.Builder attributesFor(EntityDefinition definition,
                                                            EntityBases.Base base) {
        DefaultAttributeContainer.Builder attributes = base.attributes().get();
        if (definition.defaults == null) return attributes;

        if (definition.defaults.health != null) {
            attributes.add(EntityAttributes.GENERIC_MAX_HEALTH, definition.defaults.health);
        }
        for (Map.Entry<String, Double> declared : definition.defaults.attributesOrEmpty().entrySet()) {
            RegistryEntry<EntityAttribute> attribute = attributeOf(declared.getKey());
            if (attribute == null) {
                // Ignorado e dito, e nao recusado: o jogo ganha atributos a cada versao, e um mod
                // escrito para uma mais nova nao deveria deixar de carregar por causa de um campo.
                logger.warn("Atributo desconhecido em {}: {}", definition.id, declared.getKey());
                continue;
            }
            attributes.add(attribute, declared.getValue());
        }
        return attributes;
    }

    private static RegistryEntry<EntityAttribute> attributeOf(String id) {
        Identifier parsed = Identifier.tryParse(id);
        if (parsed == null) return null;
        return Registries.ATTRIBUTE.getEntry(parsed).orElse(null);
    }

    /**
     * Registra o ovo de criação da espécie.
     *
     * <p>Sem ovo, uma espécie declarada só chega ao mundo por comando ou por script: quem joga no
     * criativo não tem como encontrá-la, e é assim que quase todo mod é experimentado primeiro.
     */
    private void registerSpawnEgg(String namespace, Identifier entityId,
                                  EntityDefinition definition, EntityType<?> type) {
        EntityDefinition.SpawnEggDefinition egg = definition.spawnEgg;
        if (egg == null || !egg.register) return;

        String eggPath = egg.id == null || egg.id.isBlank()
                ? definition.id + "_spawn_egg"
                : egg.id;
        Identifier eggId = Identifier.of(namespace, eggPath);
        if (Registries.ITEM.containsId(eggId)) {
            logger.warn("Ovo {} ja existe; a especie {} fica sem ovo", eggId, entityId);
            return;
        }

        Item item = new SpawnEggItem(castMob(type), egg.primaryColor, egg.secondaryColor,
                new Item.Settings());
        Registry.register(Registries.ITEM, eggId, item);
        spawnEggsByMod.computeIfAbsent(namespace, key -> new java.util.ArrayList<>()).add(eggId);
    }

    /**
     * Fecha o registro.
     *
     * <p>Chamado quando o jogo termina de carregar. Depois disso, registrar não é mais possível, e
     * o único desfecho honesto é a recusa: responder que deu certo faria o mod só descobrir a
     * mentira ao não encontrar o bicho.
     */
    public void close() {
        open = false;
    }

    public boolean isOpen() {
        return open;
    }

    /**
     * Aplica o que a espécie declarou à criatura que acabou de entrar no mundo.
     *
     * <p>É chamado a cada entrada, inclusive ao recarregar o mundo, então o que já foi aplicado
     * precisa ser reconhecido — senão um bicho equipado ganharia outra armadura a cada vez que o
     * pedaço de mundo fosse carregado. A marca é uma etiqueta de comando, que o jogo já salva com
     * a entidade; guardá-la em NBT próprio seria inventar formato para o que já existe.
     */
    public void applyDeclaredDefaults(Entity entity) {
        EntityDefinition definition = definitions.get(Registries.ENTITY_TYPE.getId(entity.getType()));
        if (definition == null) return;

        // A IA e aplicada a cada entrada no mundo, e nao uma vez so: os seletores de meta nao sao
        // salvos com a entidade, entao ao recarregar o pedaco de mundo a criatura voltaria com o
        // comportamento da base. Acrescentar duas vezes a mesma meta nao acontece porque, ao
        // recarregar, ela e uma instancia nova com os seletores da base.
        if (definition.ai != null && entity instanceof net.minecraft.entity.mob.MobEntity mob) {
            DeclaredGoals.apply(logger, mob, definition.ai);
        }

        if (definition.defaults == null) return;
        // Os padroes, ao contrario, sao salvos: nome, equipamento e efeitos sobrevivem ao
        // recarregar, e aplica-los de novo daria outra armadura a cada carga.
        if (!entity.addCommandTag(READY_TAG)) return;

        FabricGameBridge.applyDeclaredSpec(entity, definition.defaults);
    }

    /** Marca de que os padrões já foram aplicados a esta criatura. */
    private static final String READY_TAG = "lualoader.declared";

    /** Os ovos de um mod, para entrarem na aba criativa junto do resto. */
    public List<Identifier> spawnEggs(String modId) {
        return List.copyOf(spawnEggsByMod.getOrDefault(modId, List.of()));
    }

    /** Os ids das espécies registradas por este loader, e não as do jogo. */
    public List<String> declaredEntities() {
        return types.keySet().stream().map(Identifier::toString).toList();
    }

    /** O que foi declarado para uma espécie deste loader, ou {@code null} se ela não é daqui. */
    public EntityDefinition declaredEntity(Identifier id) {
        return definitions.get(id);
    }

    /** As bases que cada espécie registrada usa, para o cliente conferir a cobertura. */
    public Map<Identifier, String> basesInUse() {
        Map<Identifier, String> bases = new LinkedHashMap<>();
        definitions.forEach((id, definition) -> bases.put(id, definition.base));
        return bases;
    }

    public Map<Identifier, EntityType<?>> registeredEntities() {
        return Map.copyOf(types);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends LivingEntity> cast(EntityType<?> type) {
        return (EntityType<? extends LivingEntity>) type;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends MobEntity> castMob(EntityType<?> type) {
        return (EntityType<? extends MobEntity>) type;
    }

    @SuppressWarnings("unchecked")
    private static EntityType.EntityFactory<LivingEntity> castFactory(
            EntityType.EntityFactory<? extends LivingEntity> factory) {
        return (EntityType.EntityFactory<LivingEntity>) factory;
    }
}
