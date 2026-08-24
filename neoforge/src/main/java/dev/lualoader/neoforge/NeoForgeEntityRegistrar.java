package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import dev.lualoader.platform.EntityDefinition;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.RegisterEvent;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Registra as espécies declaradas pelos mods, no NeoForge.
 *
 * <p>O espelho de {@code EntityRegistrar} do Fabric, e é de propósito que sejam dois: o momento e o
 * caminho do registro são diferentes em cada plataforma, e uma abstração comum esconderia
 * justamente a parte que diverge. O que <b>não</b> pode divergir é o resultado — mesmo manifesto,
 * mesma criatura —, e é o que os GameTests dos dois lados conferem.
 *
 * <p>Segue o mesmo motivo de {@link NeoForgeContentRegistrar} para usar {@code RegisterEvent} em vez
 * de {@code DeferredRegister}: o segundo fixa um namespace só, e um mod chamado {@code
 * crystal_world} precisa registrar {@code crystal_world:crystal_guardian}, que é o identificador
 * que o Fabric usa e que os scripts escrevem.
 */
public final class NeoForgeEntityRegistrar {
    private final Logger logger;
    private final List<ModManifest> manifests = new ArrayList<>();

    private final Map<ResourceLocation, EntityType<?>> types = new LinkedHashMap<>();
    private final Map<ResourceLocation, EntityDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, List<ResourceLocation>> spawnEggsByMod = new LinkedHashMap<>();

    private boolean open = true;

    public NeoForgeEntityRegistrar(Logger logger, IEventBus modBus) {
        this.logger = logger;
        modBus.addListener(this::onRegister);
        modBus.addListener(this::onCreateAttributes);
    }

    /** Guarda um manifesto para registrar quando o jogo pedir. */
    public void declare(ModManifest manifest) {
        manifests.add(manifest);
    }

    /**
     * Registra os tipos e os ovos.
     *
     * <p>Os ovos entram junto dos itens, e não num evento próprio: um item registrado depois que o
     * registro de itens passou não entra em lugar nenhum, e o ovo sumiria sem erro.
     */
    private void onRegister(RegisterEvent event) {
        event.register(Registries.ENTITY_TYPE, registry -> {
            registerAll(registry::register);
            // Daqui em diante o jogo congela: aceitar espécie depois produziria um tipo perdido.
            open = false;
        });

        event.register(Registries.ITEM, registry -> registerSpawnEggs(registry::register));
    }

    /** O que o registro do jogo aceita: um identificador e o valor. */
    private interface Sink<T> {
        void accept(ResourceLocation id, T value);
    }

    /**
     * Registra as espécies de todos os mods, em ordem de herança.
     *
     * <p>Todos de uma vez, e não mod a mod: uma espécie pode descender da declarada por outro mod,
     * e a ordem de descoberta não é a ordem de herança. A resolução vem do núcleo, e é a mesma que
     * o Fabric usa — duas implementações concordariam até o primeiro caso torto, e o caso torto
     * aqui é um mod que carrega numa plataforma e não na outra.
     */
    private void registerAll(Sink<EntityType<?>> sink) {
        Map<String, List<EntityDefinition>> byMod = new LinkedHashMap<>();
        for (ModManifest manifest : manifests) {
            if (manifest.entities == null || manifest.entities.isEmpty()) continue;
            byMod.put(manifest.id, new ArrayList<>(manifest.entities));
        }
        if (byMod.isEmpty()) return;

        var resolution = dev.lualoader.platform.EntityDerivation.resolve(
                byMod, base -> NeoForgeEntityBases.get(base) != null);

        for (var rejected : resolution.rejected()) {
            // Recusa dita e nomeada: uma especie que some sem motivo vira "o mod nao funciona".
            logger.error("Especie {} recusada: {}", rejected.id(), rejected.reason());
        }

        for (var resolved : resolution.ordered()) {
            String full = resolved.id();
            EntityDefinition definition = resolved.definition();
            String namespace = full.substring(0, full.indexOf(':'));

            NeoForgeEntityBases.Base base = NeoForgeEntityBases.get(definition.base);
            if (base == null) continue;

            ResourceLocation id =
                    ResourceLocation.fromNamespaceAndPath(namespace, definition.id);
            if (types.containsKey(id)) {
                logger.error("Entidade ja registrada, ignorando: {}", id);
                continue;
            }

            EntityType<?> type = build(id, definition, base);
            sink.accept(id, type);
            types.put(id, type);
            definitions.put(id, definition);

            logger.info("Lua Loader registrou entidade {} ({}), derivada de {}",
                    id, definition.name, definition.base);
        }
    }

    /**
     * Monta o tipo a partir do que a espécie declarou.
     *
     * <p>Zero significa "herda", e por isso cada campo pergunta à base antes de decidir. Um padrão
     * numérico próprio faria uma espécie que não declarou tamanho nascer com a caixa de colisão
     * errada — atravessando parede ou flutuando — em vez de com a da base.
     */
    private EntityType<?> build(ResourceLocation id, EntityDefinition definition,
                                NeoForgeEntityBases.Base base) {
        EntityType<? extends LivingEntity> vanilla = base.vanilla();
        MobCategory category = NeoForgeEntityBases.categoryOf(definition.category, vanilla);

        EntityType.Builder<LivingEntity> builder =
                EntityType.Builder.of(castFactory(base.factory()), category);

        builder.sized(
                definition.width > 0 ? definition.width : vanilla.getWidth(),
                definition.height > 0 ? definition.height : vanilla.getHeight());
        builder.clientTrackingRange(definition.trackingRange > 0
                ? definition.trackingRange
                : vanilla.clientTrackingRange());
        builder.updateInterval(definition.updateInterval > 0
                ? definition.updateInterval
                : vanilla.updateInterval());

        if (definition.fireImmune) builder.fireImmune();
        if (!definition.summonable) builder.noSummon();
        if (!definition.saveable) builder.noSave();

        return builder.build(id.toString());
    }

    /**
     * Publica os atributos de cada espécie.
     *
     * <p>Evento próprio, e depois do registro: o jogo pergunta os atributos de tudo que existe, e
     * um tipo sem resposta derruba a carga com "Entity ... has no attributes". Vida e velocidade
     * entram aqui, e não depois do nascimento, porque aplicadas depois a criatura viveria um
     * instante com a vida da base — o bastante para aparecer com o número errado na tela.
     */
    private void onCreateAttributes(EntityAttributeCreationEvent event) {
        definitions.forEach((id, definition) -> {
            NeoForgeEntityBases.Base base = NeoForgeEntityBases.get(definition.base);
            if (base == null) return;
            event.put(cast(types.get(id)), attributesFor(definition, base).build());
        });
    }

    /**
     * Os atributos com que a espécie nasce.
     *
     * <p>Parte dos da base e sobrescreve o que foi declarado. Começar de um contêiner vazio faria
     * um lobo declarado nascer sem velocidade nem alcance de ataque, porque o manifesto raramente
     * lista tudo — e o resultado seria uma criatura parada.
     */
    private AttributeSupplier.Builder attributesFor(EntityDefinition definition,
                                                    NeoForgeEntityBases.Base base) {
        AttributeSupplier.Builder attributes = base.attributes().get();
        if (definition.defaults == null) return attributes;

        if (definition.defaults.health != null) {
            attributes.add(Attributes.MAX_HEALTH, definition.defaults.health);
        }
        for (Map.Entry<String, Double> declared
                : definition.defaults.attributesOrEmpty().entrySet()) {
            ResourceLocation parsed = ResourceLocation.tryParse(declared.getKey());
            var attribute = parsed == null ? null : BuiltInRegistries.ATTRIBUTE.getHolder(parsed);
            if (attribute == null || attribute.isEmpty()) {
                // Ignorado e dito, e nao recusado: o jogo ganha atributos a cada versao, e um mod
                // escrito para uma mais nova nao deveria deixar de carregar por causa de um campo.
                logger.warn("Atributo desconhecido em {}: {}", definition.id, declared.getKey());
                continue;
            }
            attributes.add(attribute.get(), declared.getValue());
        }
        return attributes;
    }

    /**
     * Registra o ovo de criação de cada espécie.
     *
     * <p>Sem ovo, uma espécie declarada só chega ao mundo por comando ou por script: quem joga no
     * criativo não tem como encontrá-la, e é assim que quase todo mod é experimentado primeiro.
     */
    private void registerSpawnEggs(Sink<Item> sink) {
        definitions.forEach((entityId, definition) -> {
            EntityDefinition.SpawnEggDefinition egg = definition.spawnEgg;
            if (egg == null || !egg.register) return;

            // Sai do que foi de fato registrado, e nao do manifesto: uma especie recusada nao tem
            // tipo, e um ovo dela seria um item que nao faz nada ao ser usado.
            EntityType<?> type = types.get(entityId);
            if (type == null) return;

            String eggPath = egg.id == null || egg.id.isBlank()
                    ? definition.id + "_spawn_egg"
                    : egg.id;
            ResourceLocation eggId = ResourceLocation.fromNamespaceAndPath(
                    entityId.getNamespace(), eggPath);

            sink.accept(eggId, new SpawnEggItem(castMob(type), egg.primaryColor,
                    egg.secondaryColor, new Item.Properties()));
            spawnEggsByMod.computeIfAbsent(entityId.getNamespace(), key -> new ArrayList<>())
                    .add(eggId);
        });
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
        EntityDefinition definition =
                definitions.get(BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()));
        if (definition == null || definition.defaults == null) return;
        if (!entity.addTag(READY_TAG)) return;

        NeoForgeGameBridge.applyDeclaredSpec(entity, definition.defaults);
    }

    /** Marca de que os padrões já foram aplicados a esta criatura. */
    private static final String READY_TAG = "lualoader.declared";

    /** Se o registro ainda aceita espécie nova. */
    public boolean isOpen() {
        return open;
    }

    /** Os ovos de um mod, para entrarem na aba criativa junto do resto. */
    public List<ResourceLocation> spawnEggs(String modId) {
        return List.copyOf(spawnEggsByMod.getOrDefault(modId, List.of()));
    }

    /** Os ids das espécies registradas por este loader, e não as do jogo. */
    public List<String> declaredEntities() {
        return types.keySet().stream().map(ResourceLocation::toString).toList();
    }

    /** O que foi declarado para uma espécie deste loader, ou {@code null} se ela não é daqui. */
    public EntityDefinition declaredEntity(ResourceLocation id) {
        return definitions.get(id);
    }

    /** As bases que cada espécie registrada usa, para o cliente conferir a cobertura. */
    public Map<ResourceLocation, String> basesInUse() {
        Map<ResourceLocation, String> bases = new LinkedHashMap<>();
        definitions.forEach((id, definition) -> bases.put(id, definition.base));
        return bases;
    }

    public Map<ResourceLocation, EntityType<?>> registeredEntities() {
        return Map.copyOf(types);
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends LivingEntity> cast(EntityType<?> type) {
        return (EntityType<? extends LivingEntity>) type;
    }

    @SuppressWarnings("unchecked")
    private static EntityType<? extends Mob> castMob(EntityType<?> type) {
        return (EntityType<? extends Mob>) type;
    }

    @SuppressWarnings("unchecked")
    private static EntityType.EntityFactory<LivingEntity> castFactory(
            EntityType.EntityFactory<? extends LivingEntity> factory) {
        return (EntityType.EntityFactory<LivingEntity>) factory;
    }
}
