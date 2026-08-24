package dev.lualoader.neoforge;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * O mesmo contrato do adaptador Fabric, respondido com as APIs do NeoForge.
 *
 * <p>É aqui que a aposta do projeto se comprova ou não. O núcleo pede "os itens que este bloco
 * guarda" sem saber que no Fabric isso é {@code Storage<ItemVariant>} e aqui é
 * {@code IItemHandler}. Um mod Lua escrito para o loader roda nos dois sem mudar uma linha, porque
 * a diferença mora inteira neste arquivo.
 *
 * <p>Cobertura parcial, e de propósito: as operações centrais primeiro, para o caminho ser
 * verificável cedo. O que ainda não existe recusa com mensagem clara em vez de responder errado.
 */
public class NeoForgeGameBridge implements GameBridge {
    private MinecraftServer server;
    private ServerLevel currentLevel;

    public void setServer(MinecraftServer server) {
        this.server = server;
        // Um servidor novo traz datapacks novos, e com eles outras tabelas de loot.
        this.dropIndex = null;
    }

    /** Define em que dimensão as operações seguintes agem. */
    public void setCurrentLevel(ServerLevel level) {
        this.currentLevel = level;
    }

    private MinecraftServer requireServer() {
        if (server == null) throw new BridgeException("servidor ainda nao iniciou");
        return server;
    }

    private ServerLevel requireLevel() {
        if (currentLevel != null) return currentLevel;

        // Fora de um evento, o overworld: e o comportamento previsivel quando o script nao disse
        // onde atuar. A mesma escolha do adaptador Fabric, e pelo mesmo motivo.
        return requireServer().overworld();
    }

    private static ResourceLocation parse(String id) {
        ResourceLocation parsed = ResourceLocation.tryParse(id);
        if (parsed == null) throw new BridgeException("identificador invalido: " + id);
        return parsed;
    }

    // ------------------------------------------------------------------ servidor e mundo

    @Override
    public void broadcast(String message) {
        requireServer().getPlayerList().broadcastSystemMessage(Component.literal(message), false);
    }

    @Override
    public boolean isWorldAvailable() {
        return server != null;
    }

    @Override
    public List<String> onlinePlayers() {
        List<String> names = new ArrayList<>();
        for (var player : requireServer().getPlayerList().getPlayers()) {
            names.add(player.getName().getString());
        }
        return names;
    }

    @Override
    public long timeOfDay() {
        return requireLevel().getDayTime() % 24000L;
    }

    @Override
    public String worldName() {
        return requireLevel().dimension().location().toString();
    }

    @Override
    public String getBlock(int x, int y, int z) {
        BlockState state = requireLevel().getBlockState(new BlockPos(x, y, z));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    @Override
    public void setBlock(String blockId, int x, int y, int z) {
        Block block = requireBlock(blockId);
        requireLevel().setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState());
    }

    @Override
    public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
        Block block = requireBlock(blockId);
        ServerLevel level = requireLevel();
        int changed = 0;

        for (int x = Math.min(x1, x2); x <= Math.max(x1, x2); x++) {
            for (int y = Math.min(y1, y2); y <= Math.max(y1, y2); y++) {
                for (int z = Math.min(z1, z2); z <= Math.max(z1, z2); z++) {
                    level.setBlockAndUpdate(new BlockPos(x, y, z), block.defaultBlockState());
                    changed++;
                }
            }
        }
        return changed;
    }

    private static Block requireBlock(String blockId) {
        ResourceLocation id = parse(blockId);
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new BridgeException("bloco desconhecido: " + blockId);
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    // ------------------------------------------------------------------ registro do jogo

    @Override
    public List<String> registeredItems(String namespace, String contains, int limit) {
        List<String> found = new ArrayList<>();

        for (ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
            if (namespace != null && !id.getNamespace().equals(namespace)) continue;
            if (contains != null && !contains.isBlank() && !id.getPath().contains(contains)) continue;
            found.add(id.toString());
        }

        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    // ------------------------------------------------------------------ capacidades

    /**
     * O inventario de um bloco, pelo contrato do NeoForge.
     *
     * <p>Onde o Fabric publica {@code Storage<ItemVariant>}, aqui a mesma ideia se chama
     * {@code IItemHandler} e chega por capability. O nucleo nao vê nenhum dos dois nomes.
     */
    private IItemHandler itemHandlerAt(int x, int y, int z) {
        return requireLevel().getCapability(Capabilities.ItemHandler.BLOCK, new BlockPos(x, y, z), null);
    }

    @Override
    public Set<String> capabilitiesAt(int x, int y, int z) {
        Set<String> found = new LinkedHashSet<>();
        if (itemHandlerAt(x, y, z) != null) found.add("items");
        return found;
    }

    @Override
    public List<String> containerAt(int x, int y, int z) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        List<String> contents = new ArrayList<>();
        for (int slot = 0; slot < handler.getSlots(); slot++) {
            ItemStack stack = handler.getStackInSlot(slot);
            if (stack.isEmpty()) continue;

            contents.add(slot + ";"
                    + BuiltInRegistries.ITEM.getKey(stack.getItem()) + ";" + stack.getCount());
        }
        return contents;
    }

    @Override
    public int insertInto(int x, int y, int z, String itemId, int count) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        ItemStack remaining = new ItemStack(requireItem(itemId), count);
        for (int slot = 0; slot < handler.getSlots() && !remaining.isEmpty(); slot++) {
            remaining = handler.insertItem(slot, remaining, false);
        }
        return remaining.getCount();
    }

    @Override
    public int extractFrom(int x, int y, int z, String itemId, int count) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);

        Item wanted = requireItem(itemId);
        int taken = 0;

        for (int slot = 0; slot < handler.getSlots() && taken < count; slot++) {
            if (!handler.getStackInSlot(slot).is(wanted)) continue;

            ItemStack removed = handler.extractItem(slot, count - taken, false);
            taken += removed.getCount();
        }
        return taken;
    }

    private static Item requireItem(String itemId) {
        ResourceLocation id = parse(itemId);
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new BridgeException("item desconhecido: " + itemId);
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    /**
     * Operações que este adaptador ainda não cobre.
     *
     * <p>Recusam com o nome da operação em vez de devolver vazio. Um mod que dependa delas descobre
     * na primeira chamada, e não por um comportamento estranho meia hora depois.
     */
    private static BridgeException pending(String operation) {
        return new BridgeException(operation + " ainda nao existe no adaptador NeoForge");
    }

    /** Só um bloco declarado tem as propriedades que estas operações escrevem. */
    private static NeoForgeDeclarativeBlock requireDeclarativeBlock(String blockId) {
        Block block = requireBlock(blockId);
        if (!(block instanceof NeoForgeDeclarativeBlock declarative)) {
            throw new BridgeException("bloco nao foi declarado por um mod Lua: " + blockId);
        }
        return declarative;
    }

    /**
     * Escreve uma propriedade no bloco daquela posição, preservando as demais.
     *
     * <p>Parte do estado que já está no mundo, e não do estado padrão: um bloco aceso que trocasse
     * de variante voltaria a apagar, porque o padrão não sabe o que o script fez antes.
     */
    private void setBlockState(String blockId, int x, int y, int z,
                               IntegerProperty property, int value) {
        NeoForgeDeclarativeBlock block = requireDeclarativeBlock(blockId);
        ServerLevel level = requireLevel();
        BlockPos pos = new BlockPos(x, y, z);

        BlockState current = level.getBlockState(pos);
        BlockState next = current.is(block)
                ? current.setValue(property, value)
                : block.defaultBlockState().setValue(property, value);

        level.setBlockAndUpdate(pos, next);
    }

    @Override
    public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
        if (variant < 0 || variant >= NeoForgeDeclarativeBlock.VARIANT_COUNT) {
            throw new BridgeException("variante fora da faixa aceita: " + variant);
        }
        setBlockState(blockId, x, y, z, NeoForgeDeclarativeBlock.VARIANT, variant);
    }

    @Override
    public void setBlockProperty(String blockId, String property, float value) {
        try {
            requireDeclarativeBlock(blockId).setDynamicProperty(property, value);
        } catch (IllegalArgumentException error) {
            throw new BridgeException(error.getMessage(), error);
        }
    }

    @Override
    public void setBlockLuminance(String blockId, int x, int y, int z, int luminance) {
        if (luminance < 0 || luminance > 15) {
            throw new BridgeException("luminancia fora da faixa aceita: " + luminance);
        }
        setBlockState(blockId, x, y, z, NeoForgeDeclarativeBlock.LUMINANCE, luminance);
    }

    // ------------------------------------------------------------------ feedback

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
        ResourceLocation id = parse(soundId);
        var sound = BuiltInRegistries.SOUND_EVENT.get(id);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        requireLevel().playSound(null, new BlockPos(x, y, z), sound,
                net.minecraft.sounds.SoundSource.BLOCKS, volume, pitch);
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread) {
        ResourceLocation id = parse(particleId);
        var type = BuiltInRegistries.PARTICLE_TYPE.get(id);
        if (type == null) throw new BridgeException("particula desconhecida: " + particleId);

        // Uma particula com parametro -- dust, block -- nao e um ParticleOptions sozinha, e
        // recusar e melhor que emitir a errada em silencio.
        if (!(type instanceof net.minecraft.core.particles.ParticleOptions options)) {
            throw new BridgeException("particula exige parametros e nao e suportada: " + particleId);
        }

        requireLevel().sendParticles(options, x, y, z, count, spread, spread, spread, 0.0);
    }

    // ------------------------------------------------------------------ dados por bloco

    /**
     * Os dados vivem na entidade do bloco, e vão para o disco com o mundo.
     *
     * <p>Antes ficavam num mapa em memória, o que funcionava dentro de uma sessão e mentia entre
     * duas: o script gravava, o servidor reiniciava e o altar não lembrava de oferenda nenhuma.
     * Um bloco que declara {@code block_data} agora persiste como no adaptador Fabric.
     */
    private NeoForgeDeclarativeBlockEntity dataEntityAt(int x, int y, int z) {
        BlockPos pos = new BlockPos(x, y, z);
        return requireLevel().getBlockEntity(pos) instanceof NeoForgeDeclarativeBlockEntity entity
                ? entity
                : null;
    }

    @Override
    public String getBlockData(int x, int y, int z) {
        NeoForgeDeclarativeBlockEntity entity = dataEntityAt(x, y, z);
        return entity == null ? "{}" : entity.data();
    }

    @Override
    public void setBlockData(int x, int y, int z, String json) {
        NeoForgeDeclarativeBlockEntity entity = dataEntityAt(x, y, z);
        if (entity == null) {
            // Recusar e o certo: gravar num bloco que nao declarou block_data escreveria num lugar
            // que nao existe, e o script so descobriria ao ler de volta e achar vazio.
            throw new BridgeException(
                    "bloco em " + x + "," + y + "," + z + " nao declarou block_data");
        }
        entity.setData(json);
    }

    // ------------------------------------------------------------------ entidades

    @Override
    public String spawnEntity(String entityId, double x, double y, double z) {
        ResourceLocation id = parse(entityId);
        var type = BuiltInRegistries.ENTITY_TYPE.getOptional(id)
                .orElseThrow(() -> new BridgeException("entidade desconhecida: " + entityId));

        ServerLevel level = requireLevel();
        var entity = type.create(level);
        if (entity == null) throw new BridgeException("entidade nao pode ser criada: " + entityId);

        entity.moveTo(x, y, z, entity.getYRot(), entity.getXRot());
        level.addFreshEntity(entity);
        return entity.getUUID().toString();
    }

    @Override
    public String spawnEntity(String entityId, double x, double y, double z,
                              dev.lualoader.platform.EntitySpec spec) {
        String uuid = spawnEntity(entityId, x, y, z);
        if (spec == null || spec.isEmpty()) return uuid;

        var entity = requireLevel().getEntity(java.util.UUID.fromString(uuid));
        if (entity != null) applySpec(entity, spec);
        return uuid;
    }

    /**
     * Aplica o que o mod declarou sobre a entidade.
     *
     * <p>É o par do mesmo método no adaptador Fabric, e a comparação entre os dois é a razão de o
     * vocabulário existir: nenhum nome de método aqui coincide com o de lá, e o script não muda.
     *
     * <p>O que a entidade não suporta é ignorado, e não recusado. Declarar {@code tame} para um
     * conjunto de bichos não deveria falhar no que não é domesticável — o campo simplesmente não se
     * aplica ali.
     */
    private static void applySpec(net.minecraft.world.entity.Entity entity,
                                  dev.lualoader.platform.EntitySpec spec) {
        if (spec.name() != null) entity.setCustomName(Component.literal(spec.name()));
        if (spec.nameVisible() != null) entity.setCustomNameVisible(spec.nameVisible());
        if (spec.invulnerable() != null) entity.setInvulnerable(spec.invulnerable());
        if (spec.silent() != null) entity.setSilent(spec.silent());
        if (spec.noGravity() != null) entity.setNoGravity(spec.noGravity());

        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            if (spec.noAi() != null) mob.setNoAi(spec.noAi());
            // Persistente e o que impede o jogo de remover o bicho quando ninguem esta por perto;
            // sem isso, um guardiao invocado por um mod sumiria sozinho.
            if (Boolean.TRUE.equals(spec.persistent())) mob.setPersistenceRequired();
        }
        if (entity instanceof net.minecraft.world.entity.AgeableMob ageable && spec.baby() != null) {
            ageable.setBaby(spec.baby());
        }
        if (entity instanceof net.minecraft.world.entity.TamableAnimal tamable
                && spec.tame() != null) {
            tamable.setTame(spec.tame(), true);
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse
                && spec.tame() != null) {
            // Cavalos nao sao TamableAnimal: tem a propria nocao de domado, e sem este ramo
            // declarar tame num cavalo nao faria nada -- que e o caso mais obvio de todos.
            horse.setTamed(spec.tame());
        }

        if (spec.health() != null
                && entity instanceof net.minecraft.world.entity.LivingEntity living) {
            float health = (float) Math.max(1.0, spec.health());
            var attribute = living.getAttribute(net.minecraft.world.entity.ai.attributes
                    .Attributes.MAX_HEALTH);
            // O maximo primeiro: definir a vida acima do maximo sem mexer no atributo faria o jogo
            // recortar o valor de volta, e o mod veria a declaracao sumir.
            if (attribute != null) attribute.setBaseValue(health);
            living.setHealth(health);
        }
    }

    @Override
    public List<String> entitiesNear(double x, double y, double z, double radius) {
        var caixa = new net.minecraft.world.phys.AABB(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

        List<String> found = new ArrayList<>();
        for (var entity : requireLevel().getEntities((net.minecraft.world.entity.Entity) null, caixa,
                e -> true)) {
            found.add(entity.getUUID() + ";"
                    + BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + ";"
                    + entity.getBlockX() + ";" + entity.getBlockY() + ";" + entity.getBlockZ());
        }
        return found;
    }

    private net.minecraft.world.entity.Entity findEntity(String entityUuid) {
        java.util.UUID uuid;
        try {
            uuid = java.util.UUID.fromString(entityUuid);
        } catch (IllegalArgumentException error) {
            throw new BridgeException("identificador de entidade invalido: " + entityUuid);
        }

        for (ServerLevel level : requireServer().getAllLevels()) {
            var entity = level.getEntity(uuid);
            if (entity != null) return entity;
        }
        return null;
    }

    @Override
    public boolean removeEntity(String entityUuid) {
        var entity = findEntity(entityUuid);
        if (entity == null) return false;

        entity.discard();
        return true;
    }

    @Override
    public boolean damageEntity(String entityUuid, float amount) {
        var entity = findEntity(entityUuid);
        if (entity == null) return false;

        return entity.hurt(requireLevel().damageSources().magic(), amount);
    }

    // ------------------------------------------------------------------ ainda nao implementado

    // ------------------------------------------------------------------ receitas

    /** Teto de itens por posicao de ingrediente, para uma tag grande nao inchar a resposta. */
    private static final int MAX_ALTERNATIVES = 32;

    @Override
    public List<String> recipesFor(String itemId, int limit) {
        ResourceLocation wanted = parse(itemId);
        return collectRecipes(limit, recipe -> {
            ItemStack result = recipe.getResultItem(requireServer().registryAccess());
            return result != null && BuiltInRegistries.ITEM.getKey(result.getItem()).equals(wanted);
        });
    }

    @Override
    public List<String> recipesUsing(String itemId, int limit) {
        ResourceLocation wanted = parse(itemId);
        return collectRecipes(limit, recipe -> {
            for (var ingredient : recipe.getIngredients()) {
                for (ItemStack stack : ingredient.getItems()) {
                    if (BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(wanted)) return true;
                }
            }
            return false;
        });
    }

    private List<String> collectRecipes(int limit,
                                        java.util.function.Predicate<
                                                net.minecraft.world.item.crafting.Recipe<?>> filter) {
        List<String> found = new ArrayList<>();

        for (var entry : requireServer().getRecipeManager().getRecipes()) {
            if (found.size() >= limit) break;

            var recipe = entry.value();
            try {
                if (!filter.test(recipe)) continue;
                found.add(describeRecipe(entry.id(), recipe));
            } catch (RuntimeException ignored) {
                // Uma receita de outro mod pode recusar responder fora do contexto de craft.
                // Pular uma e melhor que derrubar a consulta inteira.
            }
        }
        return found;
    }

    private String describeRecipe(ResourceLocation id,
                                  net.minecraft.world.item.crafting.Recipe<?> recipe) {
        var json = new com.google.gson.JsonObject();
        json.addProperty("id", id.toString());
        json.addProperty("type",
                BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType()).toString());

        ItemStack result = recipe.getResultItem(requireServer().registryAccess());
        var output = new com.google.gson.JsonObject();
        output.addProperty("item", BuiltInRegistries.ITEM.getKey(result.getItem()).toString());
        output.addProperty("count", result.getCount());
        json.add("output", output);

        // A forma so existe em receita com padrao; zero diz ao mod que nao ha grade.
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe shaped) {
            json.addProperty("width", shaped.getWidth());
            json.addProperty("height", shaped.getHeight());
        } else {
            json.addProperty("width", 0);
            json.addProperty("height", 0);
        }

        var ingredients = new com.google.gson.JsonArray();
        for (var ingredient : recipe.getIngredients()) {
            var alternatives = new com.google.gson.JsonArray();
            int total = 0;
            for (ItemStack stack : ingredient.getItems()) {
                if (total++ >= MAX_ALTERNATIVES) break;
                alternatives.add(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            }
            ingredients.add(alternatives);
        }
        json.add("ingredients", ingredients);

        return json.toString();
    }

    // ------------------------------------------------------------------ drops

    /** Fonte para itens, montado uma vez a partir das tabelas de loot carregadas. */
    private java.util.Map<String, java.util.Set<String>> dropIndex;

    @Override
    public List<String> dropsOf(String sourceId, int limit) {
        ResourceLocation id = parse(sourceId);
        if (!BuiltInRegistries.BLOCK.containsKey(id)
                && !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) {
            throw new BridgeException("bloco ou entidade desconhecido: " + sourceId);
        }

        List<String> found = new ArrayList<>(
                dropIndex().getOrDefault(id.toString(), java.util.Set.of()));
        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    @Override
    public List<String> droppedBy(String itemId, int limit) {
        String wanted = parse(itemId).toString();

        List<String> found = new ArrayList<>();
        for (var entry : dropIndex().entrySet()) {
            if (entry.getValue().contains(wanted)) found.add(entry.getKey());
        }

        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    /**
     * Indice de quem derruba o que, pelo mesmo raciocinio do adaptador Fabric.
     *
     * <p>Perguntar a tabela de cada bloco e de cada tipo de entidade erra: a ovelha tem uma tabela
     * por cor, escolhida dentro da instancia, e o tipo so conhece a generica -- que da carne, e nao
     * la. Varrer as tabelas carregadas e deduzir o dono pelo nome resolve os dois casos.
     */
    private java.util.Map<String, java.util.Set<String>> dropIndex() {
        if (dropIndex != null) return dropIndex;

        java.util.Map<String, java.util.Set<String>> index = new java.util.HashMap<>();
        var registries = requireServer().reloadableRegistries();

        for (var id : registries.getKeys(net.minecraft.core.registries.Registries.LOOT_TABLE)) {
            String owner = ownerOfLootTable(id);
            if (owner == null) continue;

            var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.LOOT_TABLE, id);
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
     * <p>Tabelas sem dono -- bau de masmorra, pesca, presente de aldeao -- ficam de fora: elas nao
     * respondem "o que este bloco derruba".
     */
    private static String ownerOfLootTable(ResourceLocation tableId) {
        String path = tableId.getPath();
        String prefix = path.startsWith("blocks/") ? "blocks/"
                : path.startsWith("entities/") ? "entities/"
                : null;
        if (prefix == null) return null;

        String rest = path.substring(prefix.length());
        int slash = rest.indexOf('/');
        String name = slash < 0 ? rest : rest.substring(0, slash);

        ResourceLocation owner = ResourceLocation.fromNamespaceAndPath(tableId.getNamespace(), name);
        boolean known = prefix.equals("blocks/")
                ? BuiltInRegistries.BLOCK.containsKey(owner)
                : BuiltInRegistries.ENTITY_TYPE.containsKey(owner);

        return known ? owner.toString() : null;
    }

    private java.util.Set<String> itemsOfLootTable(
            net.minecraft.resources.ResourceKey<
                    net.minecraft.world.level.storage.loot.LootTable> key) {
        java.util.Set<String> items = new java.util.LinkedHashSet<>();

        var table = requireServer().reloadableRegistries().getLootTable(key);
        if (!(table instanceof dev.lualoader.neoforge.mixin.LootTableAccessor accessor)) return items;

        for (var pool : accessor.lua_loader$pools()) {
            if (!(pool instanceof dev.lualoader.neoforge.mixin.LootPoolAccessor poolAccessor)) {
                continue;
            }
            for (var entry : poolAccessor.lua_loader$entries()) {
                collectItems(entry, items, 0);
            }
        }
        return items;
    }

    /** Recolhe os itens de uma entrada, descendo nas compostas. */
    private static void collectItems(
            net.minecraft.world.level.storage.loot.entries.LootPoolEntryContainer entry,
            java.util.Set<String> items, int depth) {
        if (depth > 8) return;

        if (entry instanceof dev.lualoader.neoforge.mixin.LootItemAccessor itemAccessor) {
            items.add(BuiltInRegistries.ITEM.getKey(itemAccessor.lua_loader$item().value())
                    .toString());
            return;
        }
        if (entry instanceof dev.lualoader.neoforge.mixin.CompositeEntryAccessor composite) {
            for (var child : composite.lua_loader$children()) {
                collectItems(child, items, depth + 1);
            }
        }
    }
}
