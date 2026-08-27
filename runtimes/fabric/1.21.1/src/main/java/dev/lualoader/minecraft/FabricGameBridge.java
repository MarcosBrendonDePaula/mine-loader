package dev.lualoader.minecraft;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.recipe.Ingredient;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.recipe.ShapedRecipe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleType;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

/**
 * Adaptador Fabric da {@link GameBridge}.
 *
 * <p>Esta classe é o único ponto em que operações do núcleo viram chamadas ao Minecraft.
 * Um adaptador para outra plataforma implementa a mesma interface sem que o núcleo mude.
 */
public final class FabricGameBridge implements GameBridge {
    private final BlockRegistrar registrar;
    private MinecraftServer server;

    /**
     * Mundo em que o evento corrente aconteceu.
     *
     * <p>Sem isto todas as operacoes agiriam no overworld: um bloco usado no Nether leria e
     * escreveria dados na dimensao errada, sem erro visivel. O adaptador publica o mundo antes de
     * entregar o evento ao runtime e o limpa depois, de modo que o script atue onde a acao ocorreu.
     *
     * <p>E um campo por thread porque os eventos chegam na thread do servidor, mas o valor nao pode
     * vazar entre eventos.
     */
    private final ThreadLocal<ServerWorld> currentWorld = new ThreadLocal<>();

    public FabricGameBridge(BlockRegistrar registrar) {
        this.registrar = registrar;
    }

    /** Atualiza o servidor ativo. Recebe {@code null} quando o servidor para. */
    public void setServer(MinecraftServer server) {
        // Um servidor novo traz datapacks novos, e com eles outras tabelas de loot.
        this.dropIndex = null;
        this.server = server;
    }

    /** Publica o mundo do evento corrente. Deve ser limpo ao fim do evento. */
    public void setCurrentWorld(ServerWorld world) {
        if (world == null) currentWorld.remove();
        else currentWorld.set(world);
    }

    /**
     * Mundo onde as operacoes devem agir.
     *
     * <p>Fora de um evento, como em uma tarefa agendada, nao ha dimensao de origem e o overworld e
     * usado, que e o comportamento previsivel para um script que nao informou onde atuar.
     */
    private ServerWorld requireWorld() {
        ServerWorld world = currentWorld.get();
        if (world != null) return world;
        return requireServer().getOverworld();
    }

    @Override
    public boolean isWorldAvailable() {
        return server != null && server.getOverworld() != null;
    }

    @Override
    public java.util.List<String> onlinePlayers() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (var player : requireServer().getPlayerManager().getPlayerList()) {
            names.add(player.getName().getString());
        }
        return names;
    }

    @Override
    public long timeOfDay() {
        return requireWorld().getTimeOfDay() % 24000L;
    }

    @Override
    public String worldName() {
        return requireWorld().getRegistryKey().getValue().toString();
    }

    @Override
    public int redstoneSignal(int x, int y, int z) {
        return requireWorld().getReceivedRedstonePower(new BlockPos(x, y, z));
    }

    @Override
    public void broadcast(String message) {
        requireServer().getPlayerManager().broadcast(Text.literal(message), false);
    }

    @Override
    public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
        DeclarativeBlock block = requireDeclarativeBlock(blockId);
        BlockPos pos = new BlockPos(x, y, z);
        var world = requireWorld();
        var current = world.getBlockState(pos);
        // Preserva os demais estados do bloco; usar o estado padrao descartaria
        // luminancia e qualquer propriedade declarada no manifesto.
        // Um bloco de textura unica nao tem a propriedade: ela custa dezesseis valores e so e
        // registrada para quem declara mais de uma. Recusar com o motivo e o que evita a mensagem
        // de erro do Minecraft sobre propriedade ausente, que nao diz nada a quem escreveu o mod.
        var base = current.isOf(block) ? current : block.getDefaultState();
        if (!base.contains(DeclarativeBlock.LUA_VARIANT)) {
            throw new BridgeException("o bloco " + blockId
                    + " nao declara variantes de textura; set_block_variant nao se aplica");
        }
        world.setBlockState(pos, base.with(DeclarativeBlock.LUA_VARIANT, variant), 3);
    }

    @Override
    public void setBlockProperty(String blockId, String property, float value) {
        DeclarativeBlock block = requireDeclarativeBlock(blockId);
        try {
            block.setDynamicProperty(property, value);
        } catch (IllegalArgumentException error) {
            throw new BridgeException(error.getMessage(), error);
        }
    }

    @Override
    public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
        DeclarativeBlock block = requireDeclarativeBlock(blockId);
        BlockPos pos = new BlockPos(x, y, z);
        var world = requireWorld();
        var current = world.getBlockState(pos);
        // Um bloco que nao declara luminosidade dinamica nao tem a propriedade: ela custa dezesseis
        // valores e quase nenhum bloco a usa. Recusar com o motivo e o que evita a mensagem do
        // Minecraft sobre propriedade ausente, que nao diz nada a quem escreveu o mod.
        var base = current.isOf(block) ? current : block.getDefaultState();
        if (!base.contains(DeclarativeBlock.LUA_LUMINANCE)) {
            throw new BridgeException("o bloco " + blockId
                    + " nao declara luminosidade dinamica; ponha state.dynamic_luminance no manifesto");
        }
        world.setBlockState(pos, base.with(DeclarativeBlock.LUA_LUMINANCE, luminance), 3);
    }

    @Override
    public String getBlock(int x, int y, int z) {
        var world = requireWorld();
        var state = world.getBlockState(new BlockPos(x, y, z));
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    @Override
    public dev.lualoader.platform.BlockStateSnapshot blockState(int x, int y, int z) {
        var state = requireWorld().getBlockState(new BlockPos(x, y, z));
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        var properties = new java.util.LinkedHashMap<String, String>();
        for (var property : state.getProperties()) {
            properties.put(property.getName(), ((net.minecraft.state.property.Property) property)
                    .name((Comparable) state.get((net.minecraft.state.property.Property) property)));
        }
        return new dev.lualoader.platform.BlockStateSnapshot(
                id == null ? "minecraft:air" : id.toString(), properties);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean setBlockState(int x, int y, int z,
                                 java.util.Map<String, String> properties) {
        var world = requireWorld();
        var position = new BlockPos(x, y, z);
        var state = world.getBlockState(position);
        var next = state;
        for (var entry : properties.entrySet()) {
            var property = state.getBlock().getStateManager().getProperty(entry.getKey());
            if (property == null) {
                throw new BridgeException("propriedade desconhecida: " + entry.getKey());
            }
            var parsed = property.parse(entry.getValue()).orElseThrow(() ->
                    new BridgeException("valor invalido para " + entry.getKey() + ": " + entry.getValue()));
            next = next.with((net.minecraft.state.property.Property) property, (Comparable) parsed);
        }
        return world.setBlockState(position, next, 3);
    }

    @Override
    public void setBlock(String blockId, int x, int y, int z) {
        Block block = requireAnyBlock(blockId);
        requireWorld().setBlockState(new BlockPos(x, y, z), block.getDefaultState(), 3);
    }

    @Override
    public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
        Block block = requireAnyBlock(blockId);
        var world = requireWorld();
        var state = block.getDefaultState();

        int minX = Math.min(x1, x2);
        int minY = Math.min(y1, y2);
        int minZ = Math.min(z1, z2);
        int maxX = Math.max(x1, x2);
        int maxY = Math.max(y1, y2);
        int maxZ = Math.max(z1, z2);

        // Reutiliza a mesma posicao mutavel para nao alocar um BlockPos por bloco.
        BlockPos.Mutable pos = new BlockPos.Mutable();
        int changed = 0;
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    pos.set(x, y, z);
                    if (world.getBlockState(pos).isOf(block)) continue;
                    if (world.setBlockState(pos, state, 3)) changed++;
                }
            }
        }
        return changed;
    }

    /** Resolve qualquer bloco registrado, do jogo ou de um mod. */
    private Block requireAnyBlock(String blockId) {
        Identifier id = parseIdentifier(blockId);
        if (!Registries.BLOCK.containsId(id)) {
            throw new BridgeException("bloco desconhecido: " + id);
        }
        return Registries.BLOCK.get(id);
    }

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch,
                          String category) {
        playSoundIn(soundId, x, y, z, volume, pitch, categoryOf(category));
    }

    /** A categoria pedida, ou a de blocos quando o script nao disse. */
    private static SoundCategory categoryOf(String name) {
        if (name == null || name.isBlank()) return SoundCategory.BLOCKS;
        String normalized = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (!GameBridge.SOUND_CATEGORIES.contains(normalized)) {
            throw new BridgeException("categoria de som desconhecida: " + name
                    + "; conhecidas: " + GameBridge.SOUND_CATEGORIES);
        }
        return SoundCategory.valueOf(normalized.toUpperCase(java.util.Locale.ROOT));
    }

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
        playSoundIn(soundId, x, y, z, volume, pitch, SoundCategory.BLOCKS);
    }

    private void playSoundIn(String soundId, int x, int y, int z, float volume, float pitch,
                             SoundCategory category) {
        Identifier id = parseIdentifier(soundId);
        SoundEvent sound = Registries.SOUND_EVENT.get(id);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        requireWorld().playSound(null, new BlockPos(x, y, z), sound, category, volume, pitch);
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread, double speed) {
        Identifier id = parseIdentifier(particleId);
        ParticleType<?> type = Registries.PARTICLE_TYPE.get(id);
        if (!(type instanceof ParticleEffect effect)) {
            throw new BridgeException("particula desconhecida ou com parametros: " + particleId);
        }
        requireWorld().spawnParticles(effect, x, y, z, count, spread, spread, spread, speed);
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread) {
        Identifier id = parseIdentifier(particleId);
        ParticleType<?> type = Registries.PARTICLE_TYPE.get(id);
        if (!(type instanceof ParticleEffect effect)) {
            throw new BridgeException("particula desconhecida ou com parametros: " + particleId);
        }
        // A dispersao vale para os tres eixos; a velocidade fica em zero para a particula
        // apenas aparecer, sem ser lancada em uma direcao.
        requireWorld().spawnParticles(effect, x, y, z, count, spread, spread, spread, 0.0);
    }

    @Override
    public void scheduleBlockTick(int x, int y, int z, int ticks) {
        var world = requireWorld();
        var pos = new BlockPos(x, y, z);
        var state = world.getBlockState(pos);
        if (!(state.getBlock() instanceof DeclarativeBlock)) {
            // Agendar num bloco do jogo agendaria de verdade -- e o tique iria para o metodo dele,
            // nao para o script. O pedido pareceria aceito e nada chegaria.
            throw new BridgeException("o bloco em " + x + "," + y + "," + z
                    + " nao foi declarado por um mod; so bloco do loader recebe tique agendado");
        }
        world.scheduleBlockTick(pos, state.getBlock(), ticks);
    }

    @Override
    public String getBlockData(int x, int y, int z) {
        var entity = requireWorld().getBlockEntity(new BlockPos(x, y, z));
        if (entity instanceof DeclarativeBlockEntity data) return data.data();
        return "{}";
    }

    @Override
    public void setBlockData(int x, int y, int z, String json) {
        var entity = requireWorld().getBlockEntity(new BlockPos(x, y, z));
        if (!(entity instanceof DeclarativeBlockEntity data)) {
            throw new BridgeException("o bloco em " + x + "," + y + "," + z
                    + " nao foi declarado com block_data");
        }
        data.setData(json);
    }

    @Override
    public int dropItem(String itemId, double x, double y, double z, int count) {
        Item item = resolveItemForTransfer(itemId);
        ServerWorld world = requireWorld();
        int remaining = count;
        int dropped = 0;
        while (remaining > 0) {
            int batch = Math.min(remaining, item.getMaxCount());
            net.minecraft.entity.ItemEntity entity = new net.minecraft.entity.ItemEntity(
                    world, x, y, z, new ItemStack(item, batch));
            if (!world.spawnEntity(entity)) {
                throw new BridgeException("nao foi possivel largar " + itemId);
            }
            dropped += batch;
            remaining -= batch;
        }
        return dropped;
    }

    @Override
    public String spawnEntity(String entityId, double x, double y, double z) {
        Identifier id = parseIdentifier(entityId);
        if (!Registries.ENTITY_TYPE.containsId(id)) {
            throw new BridgeException("entidade desconhecida: " + entityId);
        }
        EntityType<?> type = Registries.ENTITY_TYPE.get(id);
        var world = requireWorld();

        Entity entity = type.spawn(world, new BlockPos((int) x, (int) y, (int) z), SpawnReason.COMMAND);
        if (entity == null) throw new BridgeException("nao foi possivel invocar " + entityId);

        entity.refreshPositionAndAngles(x, y, z, entity.getYaw(), entity.getPitch());
        return entity.getUuidAsString();
    }

    @Override
    public String spawnEntity(String entityId, double x, double y, double z,
                              dev.lualoader.platform.EntitySpec spec) {
        String uuid = spawnEntity(entityId, x, y, z);
        if (spec == null || spec.isEmpty()) return uuid;

        var world = requireWorld();
        Entity entity = world.getEntity(java.util.UUID.fromString(uuid));
        if (entity == null) return uuid;

        applySpec(entity, spec);
        return uuid;
    }

    /**
     * Aplica o que o mod declarou sobre a entidade.
     *
     * <p>Cada campo é traduzido para o que esta versão do jogo chama daquilo. É o trabalho que
     * justifica o vocabulário fechado: o mod diz "domado", e onde isso mora muda entre versões.
     *
     * <p>O que a entidade não suporta é ignorado em silêncio, e não recusado. Um mod que declara
     * {@code tame} para um conjunto de bichos não deveria falhar no que não é domesticável — o
     * campo simplesmente não se aplica ali, como não se aplica a um bloco.
     */
    /**
     * A mesma aplicação, para quem faz nascer uma espécie declarada.
     *
     * <p>Existe para o registrador não repetir a tradução de vinte campos: o que uma espécie
     * declara como padrão é o mesmo que um script declara ao criar uma entidade, e duas cópias
     * divergiriam no primeiro campo novo.
     */
    public static void applyDeclaredSpec(Entity entity, dev.lualoader.platform.EntitySpec spec) {
        if (spec != null && !spec.isEmpty()) applySpec(entity, spec);
    }

    private static void applySpec(Entity entity, dev.lualoader.platform.EntitySpec spec) {
        if (spec.name != null) entity.setCustomName(Text.literal(spec.name));
        if (spec.nameVisible != null) entity.setCustomNameVisible(spec.nameVisible);
        if (spec.invulnerable != null) entity.setInvulnerable(spec.invulnerable);
        if (spec.silent != null) entity.setSilent(spec.silent);
        if (spec.noGravity != null) entity.setNoGravity(spec.noGravity);
        if (spec.glowing != null) entity.setGlowing(spec.glowing);
        if (spec.fireTicks != null) entity.setFireTicks(spec.fireTicks);
        if (spec.frozenTicks != null) entity.setFrozenTicks(spec.frozenTicks);

        if (spec.yaw != null || spec.pitch != null) {
            float yaw = spec.yaw == null ? entity.getYaw() : spec.yaw;
            float pitch = spec.pitch == null ? entity.getPitch() : spec.pitch;
            entity.setYaw(yaw);
            entity.setPitch(pitch);
            // A cabeca acompanha o corpo: sem isto o bicho olharia para um lado e encararia outro.
            entity.setHeadYaw(yaw);
        }

        if (entity instanceof net.minecraft.entity.mob.MobEntity mob) {
            if (spec.noAi != null) mob.setAiDisabled(spec.noAi);
            // Persistente e o que impede o jogo de remover o bicho quando ninguem esta por perto;
            // sem isso, um guardiao invocado por um mod sumiria sozinho.
            if (Boolean.TRUE.equals(spec.persistent)) mob.setPersistent();
            applyEquipment(mob, spec);
        }
        if (entity instanceof net.minecraft.entity.passive.PassiveEntity passive
                && spec.baby != null) {
            passive.setBaby(spec.baby);
        }
        if (entity instanceof net.minecraft.entity.passive.TameableEntity tameable
                && spec.tame != null) {
            tameable.setTamed(spec.tame, true);
        }
        if (entity instanceof net.minecraft.entity.passive.AbstractHorseEntity horse
                && spec.tame != null) {
            // Cavalos nao sao TameableEntity: tem a propria nocao de domado, e sem este ramo
            // declarar tame num cavalo nao faria nada -- que e o caso mais obvio de todos.
            horse.setTame(spec.tame);
        }
        applyVariant(entity, spec);

        applyBody(entity, spec);
        applyEffects(entity, spec);
    }

    /**
     * A variante visual, quando a espécie tem uma.
     *
     * <p>Cobertura estreita de propósito, e dita em voz alta: cada espécie nomeia a própria
     * variante de um jeito, e não há um contrato do jogo que sirva a todas. Cobrir o cavalo, onde a
     * cor é o caso que alguém quer declarar, é melhor que um mapeamento inventado que acertaria uma
     * espécie e mentiria nas outras.
     *
     * <p>Um nome que a espécie não conhece é ignorado, como qualquer campo que não se aplica.
     */
    private static void applyVariant(Entity entity, dev.lualoader.platform.EntitySpec spec) {
        if (spec.variant == null || spec.variant.isBlank()) return;

        if (entity instanceof net.minecraft.entity.passive.HorseEntity horse) {
            var color = horseColor(spec.variant);
            if (color != null) horse.setVariant(color);
        }
    }

    private static net.minecraft.entity.passive.HorseColor horseColor(String name) {
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "white" -> net.minecraft.entity.passive.HorseColor.WHITE;
            case "creamy" -> net.minecraft.entity.passive.HorseColor.CREAMY;
            case "chestnut" -> net.minecraft.entity.passive.HorseColor.CHESTNUT;
            case "brown" -> net.minecraft.entity.passive.HorseColor.BROWN;
            case "black" -> net.minecraft.entity.passive.HorseColor.BLACK;
            case "gray" -> net.minecraft.entity.passive.HorseColor.GRAY;
            case "dark_brown" -> net.minecraft.entity.passive.HorseColor.DARK_BROWN;
            default -> null;
        };
    }

    /** Vida, atributos e o cuidado de aplicar o máximo antes do valor atual. */
    private static void applyBody(Entity entity, dev.lualoader.platform.EntitySpec spec) {
        if (!(entity instanceof net.minecraft.entity.LivingEntity living)) return;

        for (var declared : spec.attributesOrEmpty().entrySet()) {
            Identifier id = Identifier.tryParse(declared.getKey());
            if (id == null) continue;

            var attribute = Registries.ATTRIBUTE.getEntry(id).orElse(null);
            if (attribute == null) continue;

            var instance = living.getAttributeInstance(attribute);
            if (instance != null) instance.setBaseValue(declared.getValue());
        }

        if (spec.health == null) return;
        float health = (float) Math.max(1.0, spec.health);

        var maximum = living.getAttributeInstance(
                net.minecraft.entity.attribute.EntityAttributes.GENERIC_MAX_HEALTH);
        // O maximo primeiro: definir a vida acima do maximo sem mexer no atributo faria o jogo
        // recortar o valor de volta, e o mod veria a declaracao sumir.
        if (maximum != null && maximum.getBaseValue() < health) maximum.setBaseValue(health);
        living.setHealth(health);
    }

    /** Efeitos de poção declarados. */
    private static void applyEffects(Entity entity, dev.lualoader.platform.EntitySpec spec) {
        if (!(entity instanceof net.minecraft.entity.LivingEntity living)) return;

        for (var effect : spec.effectsOrEmpty()) {
            Identifier id = Identifier.tryParse(effect.id);
            if (id == null) continue;

            var type = Registries.STATUS_EFFECT.getEntry(id).orElse(null);
            if (type == null) continue;

            // Trinta segundos quando nao declarado: um efeito sem duracao seria descartado no mesmo
            // tique, e o script veria a declaracao nao fazer nada.
            int duration = effect.duration == null ? 600 : effect.duration;
            int amplifier = effect.amplifier == null ? 0 : effect.amplifier;

            living.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(
                    type, duration, amplifier,
                    Boolean.TRUE.equals(effect.ambient),
                    !Boolean.FALSE.equals(effect.showParticles)));
        }
    }

    /**
     * Equipamento por espaço do corpo.
     *
     * <p>Só um {@code MobEntity} veste: um item solto ou uma bola de fogo não têm onde pôr, e
     * tentar aplicar ali seria escrever num lugar que não existe.
     */
    private static void applyEquipment(net.minecraft.entity.mob.MobEntity mob,
                                       dev.lualoader.platform.EntitySpec spec) {
        for (var entry : spec.equipmentOrEmpty().entrySet()) {
            var slot = equipmentSlot(entry.getKey());
            if (slot == null) continue;

            var piece = entry.getValue();
            Identifier id = Identifier.tryParse(piece.item);
            if (id == null || !Registries.ITEM.containsId(id)) continue;

            ItemStack stack = new ItemStack(Registries.ITEM.get(id));
            if (piece.data != null && !piece.data.isEmpty()) {
                FabricPlayerHandle.applySpec(stack, piece.data, mob.getWorld());
            }
            mob.equipStack(slot, stack);

            // Sem isto, o jogo escolhe a chance de queda sozinho -- costuma ser quase zero, e o
            // mod que vestiu um chefe esperaria ver o equipamento cair.
            if (piece.dropChance != null) mob.setEquipmentDropChance(slot, piece.dropChance);
        }
    }

    /** Traduz o nome do espaço declarado para o do jogo. */
    private static net.minecraft.entity.EquipmentSlot equipmentSlot(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "main_hand", "mainhand", "hand" -> net.minecraft.entity.EquipmentSlot.MAINHAND;
            case "off_hand", "offhand" -> net.minecraft.entity.EquipmentSlot.OFFHAND;
            case "head", "helmet" -> net.minecraft.entity.EquipmentSlot.HEAD;
            case "chest", "chestplate" -> net.minecraft.entity.EquipmentSlot.CHEST;
            case "legs", "leggings" -> net.minecraft.entity.EquipmentSlot.LEGS;
            case "feet", "boots" -> net.minecraft.entity.EquipmentSlot.FEET;
            default -> null;
        };
    }

    @Override
    public java.util.List<String> entitiesNear(double x, double y, double z, double radius) {
        var world = requireWorld();
        var box = new net.minecraft.util.math.Box(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

        java.util.List<String> found = new java.util.ArrayList<>();
        for (Entity entity : world.getOtherEntities(null, box)) {
            Identifier type = Registries.ENTITY_TYPE.getId(entity.getType());
            found.add(entity.getUuidAsString() + ";" + (type == null ? "?" : type)
                    + ";" + entity.getBlockX() + ";" + entity.getBlockY() + ";" + entity.getBlockZ());
        }
        return found;
    }

    @Override
    public boolean removeEntity(String entityUuid) {
        Entity entity = findEntity(entityUuid);
        if (entity == null) return false;
        entity.discard();
        return true;
    }

    @Override
    public boolean damageEntity(String entityUuid, float amount) {
        Entity entity = findEntity(entityUuid);
        if (entity == null) return false;

        var world = requireWorld();
        return entity.damage(requireWorld().getDamageSources().magic(), amount);
    }

    @Override
    public java.util.List<String> registeredItems(String namespace, String contains, int limit) {
        // O registro e consultado direto: nao depende de mundo carregado, e o mesmo conteudo vale
        // para qualquer dimensao. Ordenar aqui e o que torna a paginacao estavel entre chamadas.
        java.util.List<String> found = new java.util.ArrayList<>();

        for (Identifier id : Registries.ITEM.getIds()) {
            if (namespace != null && !id.getNamespace().equals(namespace)) continue;
            if (contains != null && !contains.isBlank() && !id.getPath().contains(contains)) continue;
            found.add(id.toString());
        }

        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /** Teto de itens listados por posicao de ingrediente, para uma tag grande nao inchar a carga. */
    private static final int MAX_ALTERNATIVES = 32;

    @Override
    public java.util.List<String> recipesFor(String itemId, int limit) {
        Identifier wanted = parseIdentifier(itemId);
        return collectRecipes(limit, recipe -> {
            ItemStack result = recipe.getResult(requireServer().getRegistryManager());
            return result != null && Registries.ITEM.getId(result.getItem()).equals(wanted);
        });
    }

    @Override
    public java.util.List<String> recipesUsing(String itemId, int limit) {
        Identifier wanted = parseIdentifier(itemId);
        return collectRecipes(limit, recipe -> {
            for (Ingredient ingredient : recipe.getIngredients()) {
                for (ItemStack stack : ingredient.getMatchingStacks()) {
                    if (Registries.ITEM.getId(stack.getItem()).equals(wanted)) return true;
                }
            }
            return false;
        });
    }

    /**
     * O que sai de um arranjo de nove slots, perguntando ao proprio jogo.
     *
     * <p>A mesma busca que a bancada usa, no mesmo mundo: assim vale a receita de qualquer mod
     * instalado, e nao so as que o loader conheceria. Um casamento escrito aqui saberia apenas as
     * receitas com formato -- e ficaria devendo as sem formato, as de tag e as que outro mod define
     * em codigo.
     *
     * <p>Nada e consumido: {@code craft} monta o resultado a partir da entrada e nao mexe nela.
     */
    @Override
    public String craftingResult(java.util.List<String> items) {
        var world = requireWorld();

        java.util.List<ItemStack> pilhas = new java.util.ArrayList<>(9);
        boolean vazio = true;
        for (int slot = 0; slot < 9; slot++) {
            String id = slot < items.size() && items.get(slot) != null ? items.get(slot).trim() : "";
            if (id.isEmpty()) {
                pilhas.add(ItemStack.EMPTY);
                continue;
            }

            Identifier parsed = parseIdentifier(id);
            if (!Registries.ITEM.containsId(parsed)) {
                throw new BridgeException("item desconhecido no arranjo: " + id);
            }
            pilhas.add(new ItemStack(Registries.ITEM.get(parsed)));
            vazio = false;
        }
        // Uma bancada vazia nao produz nada, e perguntar ao livro de receitas custaria a varredura
        // inteira para chegar a mesma resposta.
        if (vazio) return null;

        var entrada = net.minecraft.recipe.input.CraftingRecipeInput.create(3, 3, pilhas);
        var achada = world.getServer().getRecipeManager().getFirstMatch(
                net.minecraft.recipe.RecipeType.CRAFTING, entrada, world);
        if (achada.isEmpty()) return null;

        ItemStack saida = achada.get().value().craft(entrada, world.getRegistryManager());
        if (saida.isEmpty()) return null;

        return Registries.ITEM.getId(saida.getItem()) + ";" + saida.getCount();
    }

    /**
     * Percorre o livro de receitas do servidor uma vez, aplicando o filtro.
     *
     * <p>Nao ha indice por item na API do jogo: as duas perguntas custam uma varredura. Por isso o
     * teto e obrigatorio, e por isso um mod deve guardar o que ja perguntou em vez de repetir a
     * consulta a cada quadro.
     */
    private java.util.List<String> collectRecipes(int limit,
                                                  java.util.function.Predicate<Recipe<?>> filter) {
        java.util.List<String> found = new java.util.ArrayList<>();

        for (RecipeEntry<?> entry : requireServer().getRecipeManager().values()) {
            if (found.size() >= limit) break;

            Recipe<?> recipe = entry.value();
            try {
                if (!filter.test(recipe)) continue;
                found.add(describeRecipe(entry.id(), recipe));
            } catch (RuntimeException ignored) {
                // Uma receita de outro mod pode recusar getResult ou getIngredients fora do contexto
                // de craft. Pular uma e melhor que derrubar a consulta inteira.
            }
        }
        return found;
    }

    private String describeRecipe(Identifier id, Recipe<?> recipe) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("type", Registries.RECIPE_TYPE.getId(recipe.getType()).toString());

        ItemStack result = recipe.getResult(requireServer().getRegistryManager());
        JsonObject output = new JsonObject();
        output.addProperty("item", Registries.ITEM.getId(result.getItem()).toString());
        output.addProperty("count", result.getCount());
        json.add("output", output);

        // A forma so existe em receita com padrao; nas demais, zero diz ao mod que nao ha grade.
        if (recipe instanceof ShapedRecipe shaped) {
            json.addProperty("width", shaped.getWidth());
            json.addProperty("height", shaped.getHeight());
        } else {
            json.addProperty("width", 0);
            json.addProperty("height", 0);
        }

        JsonArray ingredients = new JsonArray();
        for (Ingredient ingredient : recipe.getIngredients()) {
            JsonArray alternatives = new JsonArray();
            int total = 0;
            for (ItemStack stack : ingredient.getMatchingStacks()) {
                if (total++ >= MAX_ALTERNATIVES) break;
                alternatives.add(Registries.ITEM.getId(stack.getItem()).toString());
            }
            ingredients.add(alternatives);
        }
        json.add("ingredients", ingredients);

        return json.toString();
    }

    @Override
    public java.util.List<String> dropsOf(String sourceId, int limit) {
        Identifier id = parseIdentifier(sourceId);
        if (!Registries.BLOCK.containsId(id) && !Registries.ENTITY_TYPE.containsId(id)) {
            throw new BridgeException("bloco ou entidade desconhecido: " + sourceId);
        }

        java.util.List<String> found =
                new java.util.ArrayList<>(dropIndex().getOrDefault(id.toString(),
                        java.util.Set.of()));
        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    @Override
    public java.util.List<String> droppedBy(String itemId, int limit) {
        String wanted = parseIdentifier(itemId).toString();

        java.util.List<String> found = new java.util.ArrayList<>();
        for (var entry : dropIndex().entrySet()) {
            if (entry.getValue().contains(wanted)) found.add(entry.getKey());
        }

        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /** Fonte para itens, montado uma vez a partir de todas as tabelas de loot carregadas. */
    private java.util.Map<String, java.util.Set<String>> dropIndex;

    /**
     * Indice de quem derruba o que.
     *
     * <p>Perguntar a tabela de loot de cada bloco e de cada tipo de entidade parece o caminho
     * obvio, e erra: a ovelha tem uma tabela por cor, escolhida dentro da instancia conforme a
     * cor e se ela ja foi tosquiada, e o tipo so conhece a generica -- que da carne, e nao la. O
     * mesmo vale para qualquer entidade cuja tabela dependa do estado.
     *
     * <p>Varrer as tabelas carregadas e olhar o nome de cada uma resolve os dois casos de uma vez,
     * e ainda descobre variantes que ninguem precisou prever.
     */
    private java.util.Map<String, java.util.Set<String>> dropIndex() {
        if (dropIndex != null) return dropIndex;

        java.util.Map<String, java.util.Set<String>> index = new java.util.HashMap<>();
        var lookup = requireServer().getReloadableRegistries();

        for (Identifier tableId : lookup.getIds(net.minecraft.registry.RegistryKeys.LOOT_TABLE)) {
            String owner = ownerOfLootTable(tableId);
            if (owner == null) continue;

            var key = net.minecraft.registry.RegistryKey.of(
                    net.minecraft.registry.RegistryKeys.LOOT_TABLE, tableId);
            java.util.Set<String> items = itemsOfLootTable(key);
            if (items.isEmpty()) continue;

            index.computeIfAbsent(owner, ignored -> new java.util.LinkedHashSet<>()).addAll(items);
        }

        dropIndex = index;
        return index;
    }

    /**
     * De quem e uma tabela de loot, a julgar pelo nome dela.
     *
     * <p>{@code blocks/stone} e do bloco de mesmo nome; {@code entities/sheep/white} e da ovelha,
     * porque o segundo trecho e a variante. Tabelas sem dono -- bau de masmorra, pesca, presente de
     * aldeao -- ficam de fora: elas nao respondem "o que este bloco derruba".
     */
    private static String ownerOfLootTable(Identifier tableId) {
        String path = tableId.getPath();
        String prefix = path.startsWith("blocks/") ? "blocks/"
                : path.startsWith("entities/") ? "entities/"
                : null;
        if (prefix == null) return null;

        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);

        Identifier owner = Identifier.of(tableId.getNamespace(), name);
        boolean known = prefix.equals("blocks/")
                ? Registries.BLOCK.containsId(owner)
                : Registries.ENTITY_TYPE.containsId(owner);

        return known ? owner.toString() : null;
    }

    /**
     * Itens que uma tabela de loot pode dar.
     *
     * <p>Le a tabela em vez de sortear: sortear responderia por amostragem, e uma amostra nunca
     * prova que um item raro nao existe. So entradas de item sao consideradas -- uma entrada que
     * aponta para outra tabela e ignorada, e isso esta documentado como limite.
     */
    private java.util.Set<String> itemsOfLootTable(
            net.minecraft.registry.RegistryKey<net.minecraft.loot.LootTable> key) {
        java.util.Set<String> items = new java.util.LinkedHashSet<>();
        if (key == null) return items;

        var table = requireServer().getReloadableRegistries().getLootTable(key);
        if (!(table instanceof dev.lualoader.mixin.LootTableAccessor accessor)) return items;

        for (var pool : accessor.lua_loader$pools()) {
            if (!(pool instanceof dev.lualoader.mixin.LootPoolAccessor poolAccessor)) continue;

            for (var entry : poolAccessor.lua_loader$entries()) {
                collectItems(entry, items, 0);
            }
        }
        return items;
    }

    /**
     * Recolhe os itens de uma entrada de loot, descendo nas compostas.
     *
     * <p>Uma tabela raramente e uma lista plana. Minerio e folha usam alternativa -- "com Toque
     * Suave da isto, sem da aquilo" -- e a alternativa e uma entrada que contem outras. Lendo so o
     * nivel de cima, um minerio parecia nao derrubar nada.
     */
    private static void collectItems(net.minecraft.loot.entry.LootPoolEntry entry,
                                     java.util.Set<String> items, int depth) {
        // Uma tabela nao aninha muito, e o teto evita percorrer para sempre se alguma o fizer.
        if (depth > 8) return;

        if (entry instanceof dev.lualoader.mixin.ItemEntryAccessor itemAccessor) {
            items.add(Registries.ITEM.getId(itemAccessor.lua_loader$item().value()).toString());
            return;
        }
        if (entry instanceof dev.lualoader.mixin.CombinedEntryAccessor combined) {
            for (var child : combined.lua_loader$children()) {
                collectItems(child, items, depth + 1);
            }
        }
    }

    @Override
    public java.util.Set<String> capabilitiesAt(int x, int y, int z) {
        java.util.Set<String> found = new java.util.LinkedHashSet<>();
        if (itemStorageAt(x, y, z) != null) found.add("items");

        // Fluido e energia entram quando houver operacao para eles. Anunciar uma capacidade que o
        // loader nao sabe usar faria o mod perguntar e nao ter o que fazer com a resposta.
        return found;
    }

    @Override
    public java.util.List<String> containerSlotLayout(int x, int y, int z) {
        var world = requireWorld();
        BlockPos pos = new BlockPos(x, y, z);

        // O menu vem do bloco, e nao de uma tabela nossa: e o mesmo objeto que o jogo usa para
        // desenhar a tela dele, entao nao ha o que divergir.
        var factory = world.getBlockState(pos).createScreenHandlerFactory(world, pos);
        if (factory == null) return java.util.List.of();

        // Montar um menu exige um jogador. Qualquer um serve: a posicao dos slots da maquina nao
        // depende de quem abriu -- so os slots do inventario do jogador dependem, e esses sao
        // descartados abaixo.
        var player = world.getServer() == null ? null
                : world.getServer().getPlayerManager().getPlayerList().stream()
                        .findFirst().orElse(null);
        if (player == null) {
            throw new BridgeException("container_slot_layout precisa de um jogador no servidor");
        }

        java.util.List<String> layout = new java.util.ArrayList<>();
        try {
            // Um id que nao colide com menu aberto nenhum: este menu e lido e jogado fora, nunca
            // mostrado nem registrado.
            var menu = factory.createMenu(-1, player.getInventory(), player);
            if (menu == null) return java.util.List.of();

            var inventarioDoJogador = player.getInventory();
            int indice = 0;
            for (var slot : menu.slots) {
                // Os slots do jogador aparecem em toda tela e nao sao da maquina: reconhecidos por
                // pertencerem a outro inventario, e nao por posicao -- posicao varia por tela.
                if (slot.inventory == inventarioDoJogador) continue;
                layout.add(indice++ + ";" + slot.x + ";" + slot.y);
            }
        } catch (RuntimeException error) {
            // `createMenu` e codigo de outro mod rodando fora do contexto que ele espera. Alguns
            // registram ouvintes ali e nao gostam de ser chamados assim. Falhar aqui devolve a
            // lista vazia, e quem chamou desenha em fileira -- pior desenho, nenhum estrago.
            dev.lualoader.LuaLoaderMod.LOGGER.warn("Nao consegui ler o desenho da tela de {},{},{}: {}",
                    x, y, z, error.toString());
            return java.util.List.of();
        }
        return layout;
    }

    @Override
    public int containerSize(int x, int y, int z) {
        var storage = itemStorageAt(x, y, z);
        if (storage == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        // A Transfer API expoe visoes, e nao um numero de slots: contar as visoes e o equivalente.
        int slots = 0;
        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            for (var ignored : storage) slots++;
        }
        return slots;
    }

    @Override
    public java.util.List<String> containerAt(int x, int y, int z) {
        var storage = itemStorageAt(x, y, z);
        if (storage == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        java.util.List<String> contents = new java.util.ArrayList<>();
        int slot = 0;

        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            for (var view : storage) {
                var resource = view.getResource();
                long amount = view.getAmount();

                // Um slot vazio ainda e um slot: contar a posicao dele mantem os indices estaveis
                // entre leituras, que e o que permite a um script falar "o terceiro slot".
                if (!resource.isBlank() && amount > 0) {
                    contents.add(slot + ";"
                            + Registries.ITEM.getId(resource.getItem()) + ";" + amount);
                }
                slot++;
            }
            transaction.abort();
        }
        return contents;
    }

    @Override
    public void setSlot(int x, int y, int z, int slot, String itemId, int count) {
        // Escreve no Inventory do bloco, e nao pela Transfer API: aquela e o portao de maquina, e um
        // inventario fantasma o fecha de proposito. Quem desenha e o mod dono do bloco.
        if (!(requireWorld().getBlockEntity(new BlockPos(x, y, z))
                instanceof DeclarativeBlockEntity entity)) {
            throw new BridgeException("set_slot exige um bloco do loader em "
                    + x + "," + y + "," + z);
        }
        if (slot < 0 || slot >= entity.size()) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem "
                    + entity.size());
        }

        if (itemId == null || itemId.isBlank() || count <= 0) {
            entity.setStack(slot, ItemStack.EMPTY);
        } else {
            entity.setStack(slot, new ItemStack(resolveItemForTransfer(itemId), count));
        }
        entity.markDirty();
    }

    @Override
    public int insertIntoSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return insertInto(x, y, z, itemId, count);

        var single = slotAt(x, y, z, slot);
        var variant = net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(
                resolveItemForTransfer(itemId));

        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            long inserted = single.insert(variant, count, transaction);
            transaction.commit();
            return (int) (count - inserted);
        }
    }

    @Override
    public int extractFromSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return extractFrom(x, y, z, itemId, count);

        var single = slotAt(x, y, z, slot);
        var variant = net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(
                resolveItemForTransfer(itemId));

        // O item pedido e conferido contra o que esta no slot: sem isso, errar o indice esvaziaria
        // o slot errado em silencio.
        if (!variant.equals(single.getResource()) || single.getAmount() <= 0) return 0;

        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            long extracted = single.extract(variant, count, transaction);
            transaction.commit();
            return (int) extracted;
        }
    }

    /**
     * Um slot especifico daquele inventario.
     *
     * <p>Nem todo inventario da Transfer API expoe slots: um tanque ou um fornecedor calculado
     * responde por conteudo e nao por posicao. Recusar com o motivo e melhor que devolver zero, que
     * quem escreve o mod leria como "o slot estava vazio".
     */
    private net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage<
            net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> slotAt(
            int x, int y, int z, int slot) {
        var storage = itemStorageAt(x, y, z);
        if (storage == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        if (!(storage instanceof net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage<
                net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> slotted)) {
            throw new BridgeException("o inventario em " + x + "," + y + "," + z
                    + " nao responde por slot");
        }
        if (slot >= slotted.getSlotCount()) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem "
                    + slotted.getSlotCount());
        }
        return slotted.getSlot(slot);
    }

    @Override
    public int insertInto(int x, int y, int z, String itemId, int count) {
        var storage = itemStorageAt(x, y, z);
        if (storage == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        var variant = net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(
                resolveItemForTransfer(itemId));

        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            long inserted = storage.insert(variant, count, transaction);
            transaction.commit();
            return (int) (count - inserted);
        }
    }

    @Override
    public int extractFrom(int x, int y, int z, String itemId, int count) {
        var storage = itemStorageAt(x, y, z);
        if (storage == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        var variant = net.fabricmc.fabric.api.transfer.v1.item.ItemVariant.of(
                resolveItemForTransfer(itemId));

        try (var transaction = net.fabricmc.fabric.api.transfer.v1.transaction.Transaction
                .openOuter()) {
            long extracted = storage.extract(variant, count, transaction);
            transaction.commit();
            return (int) extracted;
        }
    }

    /**
     * O inventario de um bloco, pelo contrato comum do Fabric.
     *
     * <p>E o que faz o loader alcancar a maquina de outro mod sem saber que ela existe: qualquer
     * bloco que exponha o armazenamento padrao responde, seja um bau do jogo ou um forno de mod.
     */
    private net.fabricmc.fabric.api.transfer.v1.storage.Storage<
            net.fabricmc.fabric.api.transfer.v1.item.ItemVariant> itemStorageAt(int x, int y, int z) {
        return net.fabricmc.fabric.api.transfer.v1.item.ItemStorage.SIDED.find(
                requireWorld(), new BlockPos(x, y, z), null);
    }

    private static Item resolveItemForTransfer(String itemId) {
        Identifier id = parseIdentifier(itemId);
        if (!Registries.ITEM.containsId(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return Registries.ITEM.get(id);
    }

    private Entity findEntity(String entityUuid) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(entityUuid);
        } catch (IllegalArgumentException error) {
            throw new BridgeException("identificador de entidade invalido: " + entityUuid);
        }
        // A busca cobre todos os mundos: uma entidade pode ter mudado de dimensao.
        for (var world : requireServer().getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    private MinecraftServer requireServer() {
        if (server == null) throw new BridgeException("nenhum servidor ativo");
        return server;
    }

    private DeclarativeBlock requireDeclarativeBlock(String blockId) {
        if (registrar == null) throw new BridgeException("registro de blocos indisponível");
        Identifier id = parseIdentifier(blockId);
        var block = registrar.get(id);
        if (!(block instanceof DeclarativeBlock declarativeBlock)) {
            throw new BridgeException("bloco não é declarativo ou não foi encontrado: " + id);
        }
        return declarativeBlock;
    }

    private static Identifier parseIdentifier(String value) {
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1) {
            throw new BridgeException("identificador inválido: " + value);
        }
        return Identifier.of(value.substring(0, separator), value.substring(separator + 1));
    }

    // ------------------------------------------------------------------ tempo e clima

    @Override
    public void setTimeOfDay(long time) {
        // A hora e a do mundo corrente; o jogo guarda o total de tiques, entao mudar so a hora do
        // dia exige preservar os dias ja passados -- senao um mod que anoitece rebobinaria o mundo.
        var world = requireWorld();
        long days = world.getTimeOfDay() / 24000L;
        world.setTimeOfDay(days * 24000L + Math.floorMod(time, 24000L));
    }

    @Override
    public String weather() {
        var world = requireWorld();
        // A leitura sai das propriedades do mundo, e nao de world.isThundering(): aquele metodo
        // compara a intensidade *visual* da tempestade, que sobe e desce ao longo de varios
        // tiques. Logo depois de set_weather("clear") a flag ja esta limpa e a intensidade ainda
        // nao desceu, entao o par escrever-e-ler se contradizia -- o script limpava o clima e lia
        // "thunder". O defeito valia nas duas plataformas, porque o metodo e o mesmo do jogo.
        var properties = world.getLevelProperties();
        if (properties.isThundering()) return "thunder";
        return properties.isRaining() ? "rain" : "clear";
    }

    @Override
    public void setWeather(String weather, int duration) {
        var world = requireWorld();
        // Vinte minutos quando nao declarado, que e a ordem de grandeza que o jogo usa.
        int ticks = duration > 0 ? duration : 24000;

        switch (weather) {
            case "thunder" -> world.setWeather(0, ticks, true, true);
            case "rain" -> world.setWeather(0, ticks, true, false);
            default -> world.setWeather(ticks, 0, false, false);
        }
    }

    // ------------------------------------------------------------------ regras e dificuldade

    @Override
    public String gameRule(String name) {
        var rule = gameRuleValue(name);
        if (rule instanceof net.minecraft.world.GameRules.BooleanRule booleanRule) {
            return Boolean.toString(booleanRule.get());
        }
        if (rule instanceof net.minecraft.world.GameRules.IntRule intRule) {
            return Integer.toString(intRule.get());
        }
        throw new BridgeException("tipo de regra nao suportado: " + name);
    }

    @Override
    public void setGameRule(String name, String value) {
        if (value == null) throw new BridgeException("valor de regra ausente: " + name);
        var rule = gameRuleValue(name);
        if (rule instanceof net.minecraft.world.GameRules.BooleanRule booleanRule) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new BridgeException("regra booleana exige true ou false: " + name);
            }
            booleanRule.set(Boolean.parseBoolean(value), requireServer());
            return;
        }
        if (rule instanceof net.minecraft.world.GameRules.IntRule intRule) {
            final int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new BridgeException("regra inteira exige um numero: " + name, error);
            }
            if (parsed < 0 || parsed > 1_000_000) {
                throw new BridgeException("valor de regra fora do limite seguro: " + name);
            }
            intRule.set(parsed, requireServer());
            return;
        }
        throw new BridgeException("tipo de regra nao suportado: " + name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private net.minecraft.world.GameRules.Rule<?> gameRuleValue(String name) {
        if (name == null || !GameBridge.GAME_RULES.contains(name)) {
            throw new BridgeException("Game Rule nao permitida: " + name);
        }
        net.minecraft.world.GameRules.Key<?> key = switch (name) {
            case "do_fire_tick" -> net.minecraft.world.GameRules.DO_FIRE_TICK;
            case "mob_griefing" -> net.minecraft.world.GameRules.DO_MOB_GRIEFING;
            case "keep_inventory" -> net.minecraft.world.GameRules.KEEP_INVENTORY;
            case "do_mob_spawning" -> net.minecraft.world.GameRules.DO_MOB_SPAWNING;
            case "do_mob_loot" -> net.minecraft.world.GameRules.DO_MOB_LOOT;
            case "do_tile_drops" -> net.minecraft.world.GameRules.DO_TILE_DROPS;
            case "do_entity_drops" -> net.minecraft.world.GameRules.DO_ENTITY_DROPS;
            case "natural_regeneration" -> net.minecraft.world.GameRules.NATURAL_REGENERATION;
            case "do_daylight_cycle" -> net.minecraft.world.GameRules.DO_DAYLIGHT_CYCLE;
            case "do_weather_cycle" -> net.minecraft.world.GameRules.DO_WEATHER_CYCLE;
            case "send_command_feedback" -> net.minecraft.world.GameRules.SEND_COMMAND_FEEDBACK;
            case "announce_advancements" -> net.minecraft.world.GameRules.ANNOUNCE_ADVANCEMENTS;
            case "show_death_messages" -> net.minecraft.world.GameRules.SHOW_DEATH_MESSAGES;
            case "disable_raids" -> net.minecraft.world.GameRules.DISABLE_RAIDS;
            case "do_insomnia" -> net.minecraft.world.GameRules.DO_INSOMNIA;
            case "do_immediate_respawn" -> net.minecraft.world.GameRules.DO_IMMEDIATE_RESPAWN;
            case "drowning_damage" -> net.minecraft.world.GameRules.DROWNING_DAMAGE;
            case "fall_damage" -> net.minecraft.world.GameRules.FALL_DAMAGE;
            case "fire_damage" -> net.minecraft.world.GameRules.FIRE_DAMAGE;
            case "freeze_damage" -> net.minecraft.world.GameRules.FREEZE_DAMAGE;
            case "do_patrol_spawning" -> net.minecraft.world.GameRules.DO_PATROL_SPAWNING;
            case "do_trader_spawning" -> net.minecraft.world.GameRules.DO_TRADER_SPAWNING;
            case "do_warden_spawning" -> net.minecraft.world.GameRules.DO_WARDEN_SPAWNING;
            case "forgive_dead_players" -> net.minecraft.world.GameRules.FORGIVE_DEAD_PLAYERS;
            case "universal_anger" -> net.minecraft.world.GameRules.UNIVERSAL_ANGER;
            case "do_vines_spread" -> net.minecraft.world.GameRules.DO_VINES_SPREAD;
            case "random_tick_speed" -> net.minecraft.world.GameRules.RANDOM_TICK_SPEED;
            case "spawn_radius" -> net.minecraft.world.GameRules.SPAWN_RADIUS;
            case "max_entity_cramming" -> net.minecraft.world.GameRules.MAX_ENTITY_CRAMMING;
            case "players_sleeping_percentage" -> net.minecraft.world.GameRules.PLAYERS_SLEEPING_PERCENTAGE;
            case "snow_accumulation_height" -> net.minecraft.world.GameRules.SNOW_ACCUMULATION_HEIGHT;
            case "spawn_chunk_radius" -> net.minecraft.world.GameRules.SPAWN_CHUNK_RADIUS;
            default -> throw new BridgeException("Game Rule nao permitida: " + name);
        };
        return requireWorld().getGameRules().get((net.minecraft.world.GameRules.Key) key);
    }

    @Override
    public String difficulty() {
        return requireServer().getSaveProperties().getDifficulty().getName();
    }

    @Override
    public void setDifficulty(String difficulty) {
        if (difficulty == null) throw new BridgeException("dificuldade ausente");
        net.minecraft.world.Difficulty parsed = switch (difficulty) {
            case "peaceful" -> net.minecraft.world.Difficulty.PEACEFUL;
            case "easy" -> net.minecraft.world.Difficulty.EASY;
            case "normal" -> net.minecraft.world.Difficulty.NORMAL;
            case "hard" -> net.minecraft.world.Difficulty.HARD;
            default -> throw new BridgeException("dificuldade invalida: " + difficulty);
        };
        if (requireServer().getSaveProperties().isDifficultyLocked()) {
            throw new BridgeException("a dificuldade do mundo esta bloqueada");
        }
        requireServer().setDifficulty(parsed, true);
    }

    // ------------------------------------------------------------------ mundo

    @Override
    public int topY(int x, int z) {
        return requireWorld().getTopY(
                net.minecraft.world.Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    @Override
    public boolean breakBlock(int x, int y, int z, boolean drop) {
        var world = requireWorld();
        BlockPos pos = new BlockPos(x, y, z);
        if (world.getBlockState(pos).isAir()) return false;

        // breakBlock, e nao escrever ar: respeita a tabela de loot e derrama o inventario do bloco,
        // que e o que "quebrar" significa para quem joga.
        return world.breakBlock(pos, drop);
    }

    // ------------------------------------------------------------------ entidades

    @Override
    public boolean healEntity(String uuid, float amount) {
        Entity entity = findEntity(uuid);
        if (!(entity instanceof net.minecraft.entity.LivingEntity living)) return false;

        living.heal(amount);
        return true;
    }

    @Override
    public boolean applyToEntity(String uuid, dev.lualoader.platform.EntitySpec spec) {
        Entity entity = findEntity(uuid);
        if (entity == null) return false;

        if (spec != null && !spec.isEmpty()) applySpec(entity, spec);
        return true;
    }

    @Override
    public String entityInfo(String uuid) {
        Entity entity = findEntity(uuid);
        if (entity == null) return null;

        float health = 0f;
        float maximum = 0f;
        if (entity instanceof net.minecraft.entity.LivingEntity living) {
            health = living.getHealth();
            maximum = living.getMaxHealth();
        }

        Identifier type = Registries.ENTITY_TYPE.getId(entity.getType());
        String name = entity.getCustomName() == null ? "" : entity.getCustomName().getString();

        return String.join(";", uuid, type == null ? "" : type.toString(),
                String.valueOf(entity.getX()), String.valueOf(entity.getY()),
                String.valueOf(entity.getZ()),
                String.valueOf(health), String.valueOf(maximum), name);
    }

    // ------------------------------------------------------------------ registro do jogo

    @Override
    public java.util.List<String> registeredBlocks(String namespace, String contains, int limit) {
        return filterRegistry(Registries.BLOCK.getIds(), namespace, contains, limit);
    }

    @Override
    public java.util.List<String> registeredEntities(String namespace, String contains, int limit) {
        return filterRegistry(Registries.ENTITY_TYPE.getIds(), namespace, contains, limit);
    }

    /** O mesmo filtro dos itens, para as três consultas responderem igual. */
    private static java.util.List<String> filterRegistry(java.util.Collection<Identifier> ids,
                                                         String namespace, String contains,
                                                         int limit) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (Identifier id : ids) {
            if (namespace != null && !id.getNamespace().equals(namespace)) continue;
            if (contains != null && !contains.isBlank() && !id.getPath().contains(contains)) continue;
            found.add(id.toString());
        }
        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        var world = requireWorld();
        var biome = world.getBiome(new BlockPos(x, y, z));
        return biome.getKey()
                .map(key -> key.getValue().toString())
                // Um bioma sem chave e um bioma vindo de um datapack que nao registrou o nome.
                // Responder vazio seria pior que dizer que nao da para saber.
                .orElse("minecraft:plains");
    }

    @Override
    public int lightAt(int x, int y, int z, boolean sky) {
        var world = requireWorld();
        BlockPos position = new BlockPos(x, y, z);
        return sky
                ? world.getLightLevel(net.minecraft.world.LightType.SKY, position)
                : world.getLightLevel(net.minecraft.world.LightType.BLOCK, position);
    }

    @Override
    public boolean teleportEntity(String uuid, double x, double y, double z) {
        var world = requireWorld();
        Entity entity = world.getEntity(java.util.UUID.fromString(uuid));
        if (entity == null) return false;

        // Angulos preservados: teleportar nao deveria virar o bicho para o norte, o que faria um
        // mod que so puxa a criatura um bloco parecer estar girando ela.
        entity.teleport(world, x, y, z, java.util.Set.of(), entity.getYaw(), entity.getPitch());
        return true;
    }

    @Override
    public boolean pushEntity(String uuid, double x, double y, double z) {
        var world = requireWorld();
        Entity entity = world.getEntity(java.util.UUID.fromString(uuid));
        if (entity == null) return false;

        entity.addVelocity(x, y, z);
        // Sem isto o empurrao acontece no servidor e o cliente nao ve: a velocidade so viaja
        // quando marcada, e a criatura desliza de volta como se nada tivesse acontecido.
        entity.velocityModified = true;
        return true;
    }

    // ------------------------------------------------------------------ especies declaradas

    /**
     * Os ids das espécies que este loader registrou, e não as do jogo.
     *
     * <p>Separado de {@link #registeredEntities}, que enxerga o registro inteiro: um mod que
     * estende o bestiário de outro precisa saber o que veio de um mod, e essa distinção se perde
     * numa lista de milhares de tipos.
     */
    @Override
    public java.util.List<String> declaredEntities() {
        var registrar = dev.lualoader.LuaLoaderMod.entityRegistrar();
        return registrar == null ? java.util.List.of() : registrar.declaredEntities();
    }

    @Override
    public dev.lualoader.platform.EntityDefinition declaredEntity(String id) {
        var registrar = dev.lualoader.LuaLoaderMod.entityRegistrar();
        if (registrar == null) return null;

        Identifier parsed = Identifier.tryParse(id);
        return parsed == null ? null : registrar.declaredEntity(parsed);
    }

    /**
     * Por quantos tiques o item queima, perguntado ao proprio jogo.
     *
     * <p>O mapa de combustiveis da fornalha e o mesmo que qualquer maquina do jogo consulta, e
     * inclui o que outros mods registraram. Uma tabela escrita no loader saberia so o que o autor
     * dele conhecia.
     */
    @Override
    public int fuelBurnTime(String item) {
        String limpo = item == null ? "" : item.trim();
        if (limpo.isEmpty()) return 0;

        Identifier parsed = parseIdentifier(limpo);
        if (!Registries.ITEM.containsId(parsed)) {
            throw new BridgeException("item desconhecido: " + item);
        }

        // Exige mundo pela mesma razao das outras: e a fase em que o registro esta pronto.
        requireWorld();
        Integer tiques = net.minecraft.block.entity.AbstractFurnaceBlockEntity
                .createFuelTimeMap().get(Registries.ITEM.get(parsed));
        return tiques == null ? 0 : tiques;
    }
}
