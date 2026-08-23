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
        var state = current.isOf(block)
                ? current.with(DeclarativeBlock.LUA_VARIANT, variant)
                : block.getDefaultState().with(DeclarativeBlock.LUA_VARIANT, variant);
        world.setBlockState(pos, state, 3);
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
        var state = current.isOf(block)
                ? current.with(DeclarativeBlock.LUA_LUMINANCE, luminance)
                : block.getDefaultState().with(DeclarativeBlock.LUA_LUMINANCE, luminance);
        world.setBlockState(pos, state, 3);
    }

    @Override
    public String getBlock(int x, int y, int z) {
        var world = requireWorld();
        var state = world.getBlockState(new BlockPos(x, y, z));
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
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
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch) {
        Identifier id = parseIdentifier(soundId);
        SoundEvent sound = Registries.SOUND_EVENT.get(id);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        requireWorld().playSound(
                null, new BlockPos(x, y, z), sound, SoundCategory.BLOCKS, volume, pitch);
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
                if (!(entry instanceof dev.lualoader.mixin.ItemEntryAccessor itemAccessor)) continue;
                items.add(Registries.ITEM.getId(itemAccessor.lua_loader$item().value()).toString());
            }
        }
        return items;
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
}
