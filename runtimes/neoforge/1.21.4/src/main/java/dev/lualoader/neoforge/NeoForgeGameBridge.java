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
import java.util.Locale;
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
    public int redstoneSignal(int x, int y, int z) {
        return requireLevel().getBestNeighborSignal(new BlockPos(x, y, z));
    }

    @Override
    public String getBlock(int x, int y, int z) {
        BlockState state = requireLevel().getBlockState(new BlockPos(x, y, z));
        return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
    }

    @Override
    public dev.lualoader.platform.BlockStateSnapshot blockState(int x, int y, int z) {
        BlockState state = requireLevel().getBlockState(new BlockPos(x, y, z));
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var properties = new java.util.LinkedHashMap<String, String>();
        for (var property : state.getProperties()) {
            properties.put(property.getName(), ((net.minecraft.world.level.block.state.properties.Property) property)
                    .getName((Comparable) state.getValue(
                            (net.minecraft.world.level.block.state.properties.Property) property)));
        }
        return new dev.lualoader.platform.BlockStateSnapshot(
                id == null ? "minecraft:air" : id.toString(), properties);
    }

    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public boolean setBlockState(int x, int y, int z,
                                 java.util.Map<String, String> properties) {
        ServerLevel level = requireLevel();
        BlockPos position = new BlockPos(x, y, z);
        BlockState state = level.getBlockState(position);
        BlockState next = state;
        for (var entry : properties.entrySet()) {
            var property = state.getBlock().getStateDefinition().getProperty(entry.getKey());
            if (property == null) {
                throw new BridgeException("propriedade desconhecida: " + entry.getKey());
            }
            var parsed = property.getValue(entry.getValue()).orElseThrow(() ->
                    new BridgeException("valor invalido para " + entry.getKey() + ": " + entry.getValue()));
            next = next.setValue(
                    (net.minecraft.world.level.block.state.properties.Property) property,
                    (Comparable) parsed);
        }
        return level.setBlock(position, next, 3);
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
        return BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
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
    public List<String> containerSlotLayout(int x, int y, int z) {
        ServerLevel level = requireLevel();
        BlockPos pos = new BlockPos(x, y, z);

        // O menu vem do bloco, e nao de uma tabela nossa: e o mesmo objeto que o jogo usa para
        // desenhar a tela dele, entao nao ha o que divergir.
        net.minecraft.world.MenuProvider provider =
                level.getBlockState(pos).getMenuProvider(level, pos);
        if (provider == null) return List.of();

        // Montar um menu exige um jogador. Qualquer um serve: a posicao dos slots da maquina nao
        // depende de quem abriu -- so os slots do inventario do jogador dependem, e esses sao
        // descartados abaixo.
        net.minecraft.server.level.ServerPlayer player = level.getServer().getPlayerList().getPlayers().stream()
                .findFirst().orElse(null);
        if (player == null) {
            throw new BridgeException("container_slot_layout precisa de um jogador no servidor");
        }

        List<String> layout = new ArrayList<>();
        try {
            // Um id que nao colide com menu aberto nenhum: este menu e lido e jogado fora, nunca
            // mostrado nem registrado.
            net.minecraft.world.inventory.AbstractContainerMenu menu = provider.createMenu(-1, player.getInventory(), player);
            if (menu == null) return List.of();

            net.minecraft.world.Container inventarioDoJogador = player.getInventory();
            int indice = 0;
            for (net.minecraft.world.inventory.Slot slot : menu.slots) {
                // Os slots do jogador aparecem em toda tela e nao sao da maquina: reconhecidos por
                // pertencerem a outro inventario, e nao por posicao -- posicao varia por tela.
                if (slot.container == inventarioDoJogador) continue;
                layout.add(indice++ + ";" + slot.x + ";" + slot.y);
            }
        } catch (RuntimeException error) {
            // `createMenu` e codigo de outro mod rodando fora do contexto que ele espera. Alguns
            // registram ouvintes ali e nao gostam de ser chamados assim. Falhar aqui devolve a
            // lista vazia, e quem chamou desenha em fileira -- pior desenho, nenhum estrago.
            NeoForgeLuaLoader.LOGGER.warn("Nao consegui ler o desenho da tela de {},{},{}: {}",
                    x, y, z, error.toString());
            return List.of();
        }
        return layout;
    }

    @Override
    public int containerSize(int x, int y, int z) {
        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);
        return handler.getSlots();
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
    public void setSlot(int x, int y, int z, int slot, String itemId, int count) {
        // Escreve no Container do bloco, e nao pela capability: a capability e o portao de maquina,
        // e um inventario fantasma o fecha de proposito. Quem desenha e o mod dono do bloco.
        if (!(requireLevel().getBlockEntity(new BlockPos(x, y, z))
                instanceof NeoForgeDeclarativeBlockEntity entity)) {
            throw new BridgeException("set_slot exige um bloco do loader em "
                    + x + "," + y + "," + z);
        }
        if (slot < 0 || slot >= entity.getContainerSize()) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem "
                    + entity.getContainerSize());
        }

        if (itemId == null || itemId.isBlank() || count <= 0) {
            entity.setItem(slot, ItemStack.EMPTY);
        } else {
            entity.setItem(slot, new ItemStack(requireItem(itemId), count));
        }
        entity.setChanged();
    }

    @Override
    public int insertIntoSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return insertInto(x, y, z, itemId, count);

        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);
        if (slot >= handler.getSlots()) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem "
                    + handler.getSlots());
        }

        ItemStack remaining = new ItemStack(requireItem(itemId), count);
        return handler.insertItem(slot, remaining, false).getCount();
    }

    @Override
    public int extractFromSlot(int x, int y, int z, int slot, String itemId, int count) {
        if (slot < 0) return extractFrom(x, y, z, itemId, count);

        IItemHandler handler = itemHandlerAt(x, y, z);
        if (handler == null) throw new BridgeException("nao ha inventario em " + x + "," + y + "," + z);
        if (slot >= handler.getSlots()) {
            throw new BridgeException("slot " + slot + " nao existe; o inventario tem "
                    + handler.getSlots());
        }

        // O item pedido e conferido contra o que esta no slot: sem isso, errar o indice esvaziaria
        // o slot errado em silencio.
        ItemStack present = handler.getStackInSlot(slot);
        if (present.isEmpty() || present.getItem() != requireItem(itemId)) return 0;

        return handler.extractItem(slot, count, false).getCount();
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
        return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
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
        BlockState base = current.is(block) ? current : block.defaultBlockState();

        // Um bloco de textura unica nao tem a propriedade de variante: ela custa dezesseis valores
        // e so e registrada para quem declara mais de uma. Recusar com o motivo e o que evita a
        // mensagem do Minecraft sobre propriedade ausente, que nao diz nada a quem escreveu o mod.
        if (!base.hasProperty(property)) {
            throw new BridgeException("o bloco " + blockId + " nao tem a propriedade "
                    + property.getName() + "; ela so existe quando o manifesto a pede");
        }

        level.setBlockAndUpdate(pos, base.setValue(property, value));
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
        playSoundIn(soundId, x, y, z, volume, pitch, net.minecraft.sounds.SoundSource.BLOCKS);
    }

    @Override
    public void playSound(String soundId, int x, int y, int z, float volume, float pitch,
                          String category) {
        playSoundIn(soundId, x, y, z, volume, pitch, categoryOf(category));
    }

    private void playSoundIn(String soundId, int x, int y, int z, float volume, float pitch,
                             net.minecraft.sounds.SoundSource category) {
        ResourceLocation id = parse(soundId);
        var sound = BuiltInRegistries.SOUND_EVENT.getOptional(id).orElse(null);
        if (sound == null) throw new BridgeException("som desconhecido: " + soundId);

        requireLevel().playSound(null, new BlockPos(x, y, z), sound, category, volume, pitch);
    }

    /** A categoria pedida, ou a de blocos quando o script nao disse. */
    private static net.minecraft.sounds.SoundSource categoryOf(String name) {
        if (name == null || name.isBlank()) return net.minecraft.sounds.SoundSource.BLOCKS;
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        if (!GameBridge.SOUND_CATEGORIES.contains(normalized)) {
            throw new BridgeException("categoria de som desconhecida: " + name
                    + "; conhecidas: " + GameBridge.SOUND_CATEGORIES);
        }
        return net.minecraft.sounds.SoundSource.valueOf(normalized.toUpperCase(Locale.ROOT));
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread) {
        ResourceLocation id = parse(particleId);
        throw pending("spawn_particles");
    }

    @Override
    public void spawnParticles(String particleId, double x, double y, double z,
                               int count, double spread, double speed) {
        ResourceLocation id = parse(particleId);
        throw pending("spawn_particles");
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
    public void scheduleBlockTick(int x, int y, int z, int ticks) {
        ServerLevel level = requireLevel();
        BlockPos pos = new BlockPos(x, y, z);
        var state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof NeoForgeDeclarativeBlock)) {
            // Agendar num bloco do jogo agendaria de verdade -- e o tique iria para o metodo dele,
            // nao para o script. O pedido pareceria aceito e nada chegaria.
            throw new BridgeException("o bloco em " + x + "," + y + "," + z
                    + " nao foi declarado por um mod; so bloco do loader recebe tique agendado");
        }
        level.scheduleTick(pos, state.getBlock(), ticks);
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
        var entity = type.create(level, net.minecraft.world.entity.EntitySpawnReason.COMMAND);
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
     * vocabulário existir: quase nenhum nome de método aqui coincide com o de lá, e o script não
     * muda.
     *
     * <p>O que a entidade não suporta é ignorado, e não recusado. Declarar {@code tame} para um
     * conjunto de bichos não deveria falhar no que não é domesticável — o campo simplesmente não se
     * aplica ali.
     */
    /**
     * A mesma aplicação, para quem faz nascer uma espécie declarada.
     *
     * <p>Existe para o registrador não repetir a tradução de vinte campos: o que uma espécie
     * declara como padrão é o mesmo que um script declara ao criar uma entidade, e duas cópias
     * divergiriam no primeiro campo novo.
     */
    public static void applyDeclaredSpec(net.minecraft.world.entity.Entity entity,
                                         dev.lualoader.platform.EntitySpec spec) {
        if (spec != null && !spec.isEmpty()) applySpec(entity, spec);
    }

    private static void applySpec(net.minecraft.world.entity.Entity entity,
                                  dev.lualoader.platform.EntitySpec spec) {
        if (spec.name != null) entity.setCustomName(Component.literal(spec.name));
        if (spec.nameVisible != null) entity.setCustomNameVisible(spec.nameVisible);
        if (spec.invulnerable != null) entity.setInvulnerable(spec.invulnerable);
        if (spec.silent != null) entity.setSilent(spec.silent);
        if (spec.noGravity != null) entity.setNoGravity(spec.noGravity);
        if (spec.glowing != null) entity.setGlowingTag(spec.glowing);
        if (spec.fireTicks != null) entity.setRemainingFireTicks(spec.fireTicks);
        if (spec.frozenTicks != null) entity.setTicksFrozen(spec.frozenTicks);

        if (spec.yaw != null || spec.pitch != null) {
            float yaw = spec.yaw == null ? entity.getYRot() : spec.yaw;
            float pitch = spec.pitch == null ? entity.getXRot() : spec.pitch;
            entity.setYRot(yaw);
            entity.setXRot(pitch);
            // A cabeca acompanha o corpo: sem isto o bicho olharia para um lado e encararia outro.
            entity.setYHeadRot(yaw);
        }

        if (entity instanceof net.minecraft.world.entity.Mob mob) {
            if (spec.noAi != null) mob.setNoAi(spec.noAi);
            // Persistente e o que impede o jogo de remover o bicho quando ninguem esta por perto;
            // sem isso, um guardiao invocado por um mod sumiria sozinho.
            if (Boolean.TRUE.equals(spec.persistent)) mob.setPersistenceRequired();
            applyEquipment(mob, spec);
        }
        if (entity instanceof net.minecraft.world.entity.AgeableMob ageable && spec.baby != null) {
            ageable.setBaby(spec.baby);
        }
        if (entity instanceof net.minecraft.world.entity.TamableAnimal tamable
                && spec.tame != null) {
            tamable.setTame(spec.tame, true);
        }
        if (entity instanceof net.minecraft.world.entity.animal.horse.AbstractHorse horse
                && spec.tame != null) {
            // Cavalos nao sao TamableAnimal: tem a propria nocao de domado, e sem este ramo
            // declarar tame num cavalo nao faria nada -- que e o caso mais obvio de todos.
            horse.setTamed(spec.tame);
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
    private static void applyVariant(net.minecraft.world.entity.Entity entity,
                                     dev.lualoader.platform.EntitySpec spec) {
        if (spec.variant == null || spec.variant.isBlank()) return;

        if (entity instanceof net.minecraft.world.entity.animal.horse.Horse horse) {
            var color = horseColor(spec.variant);
            if (color != null) horse.setVariant(color);
        }
    }

    private static net.minecraft.world.entity.animal.horse.Variant horseColor(String name) {
        return switch (name.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "white" -> net.minecraft.world.entity.animal.horse.Variant.WHITE;
            case "creamy" -> net.minecraft.world.entity.animal.horse.Variant.CREAMY;
            case "chestnut" -> net.minecraft.world.entity.animal.horse.Variant.CHESTNUT;
            case "brown" -> net.minecraft.world.entity.animal.horse.Variant.BROWN;
            case "black" -> net.minecraft.world.entity.animal.horse.Variant.BLACK;
            case "gray" -> net.minecraft.world.entity.animal.horse.Variant.GRAY;
            case "dark_brown" -> net.minecraft.world.entity.animal.horse.Variant.DARK_BROWN;
            default -> null;
        };
    }

    /** Vida, atributos e o cuidado de aplicar o máximo antes do valor atual. */
    private static void applyBody(net.minecraft.world.entity.Entity entity,
                                  dev.lualoader.platform.EntitySpec spec) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return;

        for (var declared : spec.attributesOrEmpty().entrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(declared.getKey());
            if (id == null) continue;

            var attribute = BuiltInRegistries.ATTRIBUTE.get(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.ATTRIBUTE, id)).orElse(null);
            if (attribute == null) continue;

            var instance = living.getAttribute(attribute);
            if (instance != null) instance.setBaseValue(declared.getValue());
        }

        if (spec.health == null) return;
        float health = (float) Math.max(1.0, spec.health);

        var maximum = living.getAttribute(
                net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH);
        // O maximo primeiro: definir a vida acima do maximo sem mexer no atributo faria o jogo
        // recortar o valor de volta, e o mod veria a declaracao sumir.
        if (maximum != null && maximum.getBaseValue() < health) maximum.setBaseValue(health);
        living.setHealth(health);
    }

    /** Efeitos de poção declarados. */
    private static void applyEffects(net.minecraft.world.entity.Entity entity,
                                     dev.lualoader.platform.EntitySpec spec) {
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return;

        for (var effect : spec.effectsOrEmpty()) {
            ResourceLocation id = ResourceLocation.tryParse(effect.id);
            if (id == null) continue;

            var type = BuiltInRegistries.MOB_EFFECT.get(
                    net.minecraft.resources.ResourceKey.create(
                            net.minecraft.core.registries.Registries.MOB_EFFECT, id)).orElse(null);
            if (type == null) continue;

            // Trinta segundos quando nao declarado: um efeito sem duracao seria descartado no mesmo
            // tique, e o script veria a declaracao nao fazer nada.
            int duration = effect.duration == null ? 600 : effect.duration;
            int amplifier = effect.amplifier == null ? 0 : effect.amplifier;

            living.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    type, duration, amplifier,
                    Boolean.TRUE.equals(effect.ambient),
                    !Boolean.FALSE.equals(effect.showParticles)));
        }
    }

    /**
     * Equipamento por espaço do corpo.
     *
     * <p>Só um {@code Mob} veste: um item solto ou uma bola de fogo não têm onde pôr, e tentar
     * aplicar ali seria escrever num lugar que não existe.
     */
    private static void applyEquipment(net.minecraft.world.entity.Mob mob,
                                       dev.lualoader.platform.EntitySpec spec) {
        for (var entry : spec.equipmentOrEmpty().entrySet()) {
            var slot = equipmentSlot(entry.getKey());
            if (slot == null) continue;

            var piece = entry.getValue();
            ResourceLocation id = ResourceLocation.tryParse(piece.item);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) continue;

            ItemStack stack = new ItemStack(requireItem(id.toString()));
            if (piece.data != null && !piece.data.isEmpty()) {
                NeoForgePlayerHandle.applySpec(stack, piece.data, mob.level());
            }
            mob.setItemSlot(slot, stack);

            // Sem isto, o jogo escolhe a chance de queda sozinho -- costuma ser quase zero, e o
            // mod que vestiu um chefe esperaria ver o equipamento cair.
            if (piece.dropChance != null) mob.setDropChance(slot, piece.dropChance);
        }
    }

    /** Traduz o nome do espaço declarado para o do jogo. */
    private static net.minecraft.world.entity.EquipmentSlot equipmentSlot(String name) {
        if (name == null) return null;
        return switch (name.trim().toLowerCase(Locale.ROOT)) {
            case "main_hand", "mainhand", "hand" ->
                    net.minecraft.world.entity.EquipmentSlot.MAINHAND;
            case "off_hand", "offhand" -> net.minecraft.world.entity.EquipmentSlot.OFFHAND;
            case "head", "helmet" -> net.minecraft.world.entity.EquipmentSlot.HEAD;
            case "chest", "chestplate" -> net.minecraft.world.entity.EquipmentSlot.CHEST;
            case "legs", "leggings" -> net.minecraft.world.entity.EquipmentSlot.LEGS;
            case "feet", "boots" -> net.minecraft.world.entity.EquipmentSlot.FEET;
            default -> null;
        };
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

        return entity.hurtServer(requireLevel(), requireLevel().damageSources().magic(), amount);
    }

    // ------------------------------------------------------------------ ainda nao implementado

    // ------------------------------------------------------------------ receitas

    /** Teto de itens por posicao de ingrediente, para uma tag grande nao inchar a resposta. */
    private static final int MAX_ALTERNATIVES = 32;

    private ItemStack recipeResult(net.minecraft.world.item.crafting.Recipe<?> recipe) {
        var context = net.minecraft.world.item.crafting.display.SlotDisplayContext
                .fromLevel(requireLevel());
        return recipe.display().stream()
                .findFirst()
                .map(display -> display.result().resolveForFirstStack(context))
                .orElse(ItemStack.EMPTY);
    }

    @Override
    public List<String> recipesFor(String itemId, int limit) {
        ResourceLocation wanted = parse(itemId);
        return collectRecipes(limit, recipe -> {
            ItemStack result = recipeResult(recipe);
            return result != null && BuiltInRegistries.ITEM.getKey(result.getItem()).equals(wanted);
        });
    }

    @Override
    public List<String> recipesUsing(String itemId, int limit) {
        ResourceLocation wanted = parse(itemId);
        return collectRecipes(limit, recipe -> {
            for (var ingredient : recipe.placementInfo().ingredients()) {
                if (ingredient.items().anyMatch(entry ->
                        BuiltInRegistries.ITEM.getKey(entry.value()).equals(wanted))) return true;
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
     * <p>Nada e consumido: {@code assemble} monta o resultado a partir da entrada e nao mexe nela.
     */
    @Override
    public String craftingResult(List<String> items) {
        net.minecraft.world.level.Level level = requireLevel();

        java.util.List<ItemStack> pilhas = new ArrayList<>(9);
        boolean vazio = true;
        for (int slot = 0; slot < 9; slot++) {
            String id = slot < items.size() && items.get(slot) != null ? items.get(slot).trim() : "";
            if (id.isEmpty()) {
                pilhas.add(ItemStack.EMPTY);
                continue;
            }

            ResourceLocation parsed = parse(id);
            if (!BuiltInRegistries.ITEM.containsKey(parsed)) {
                throw new BridgeException("item desconhecido no arranjo: " + id);
            }
            pilhas.add(new ItemStack(requireItem(parsed.toString())));
            vazio = false;
        }
        // Uma bancada vazia nao produz nada, e perguntar ao livro de receitas custaria a varredura
        // inteira para chegar a mesma resposta.
        if (vazio) return null;

        var entrada = net.minecraft.world.item.crafting.CraftingInput.of(3, 3, pilhas);
        var achada = requireServer().getRecipeManager().getRecipeFor(
                net.minecraft.world.item.crafting.RecipeType.CRAFTING, entrada, level);
        if (achada.isEmpty()) return null;

        ItemStack saida = achada.get().value().assemble(entrada, requireServer().registryAccess());
        if (saida.isEmpty()) return null;

        return BuiltInRegistries.ITEM.getKey(saida.getItem()) + ";" + saida.getCount();
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
                found.add(describeRecipe(entry.id().location(), recipe));
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

        ItemStack result = recipeResult(recipe);
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
        for (var ingredient : recipe.placementInfo().ingredients()) {
            var alternatives = new com.google.gson.JsonArray();
            int total = 0;
            ingredient.items().limit(MAX_ALTERNATIVES).forEach(entry ->
                    alternatives.add(BuiltInRegistries.ITEM.getKey(entry.value()).toString()));
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

    // ------------------------------------------------------------------ tempo e clima

    @Override
    public void setTimeOfDay(long time) {
        // A hora e a do nivel corrente; o jogo guarda o total de tiques, entao mudar so a hora do
        // dia exige preservar os dias ja passados -- senao um mod que anoitece rebobinaria o mundo.
        ServerLevel level = requireLevel();
        long days = level.getDayTime() / 24000L;
        level.setDayTime(days * 24000L + Math.floorMod(time, 24000L));
    }

    @Override
    public String weather() {
        ServerLevel level = requireLevel();
        // A leitura sai dos dados do mundo, e nao de level.isThundering(): aquele metodo compara a
        // intensidade *visual* da tempestade, que sobe e desce ao longo de varios tiques. Logo
        // depois de set_weather("clear") a flag ja esta limpa e a intensidade ainda nao desceu,
        // entao o par escrever-e-ler se contradizia -- o script limpava o clima e lia "thunder".
        var data = level.getLevelData();
        if (data.isThundering()) return "thunder";
        return data.isRaining() ? "rain" : "clear";
    }

    @Override
    public void setWeather(String weather, int duration) {
        ServerLevel level = requireLevel();
        // Vinte minutos quando nao declarado, que e a ordem de grandeza que o jogo usa.
        int ticks = duration > 0 ? duration : 24000;

        switch (weather) {
            case "thunder" -> level.setWeatherParameters(0, ticks, true, true);
            case "rain" -> level.setWeatherParameters(0, ticks, true, false);
            default -> level.setWeatherParameters(ticks, 0, false, false);
        }
    }

    // ------------------------------------------------------------------ regras e dificuldade

    @Override
    public String gameRule(String name) {
        var rule = gameRuleValue(name);
        if (rule instanceof net.minecraft.world.level.GameRules.BooleanValue booleanValue) {
            return Boolean.toString(booleanValue.get());
        }
        if (rule instanceof net.minecraft.world.level.GameRules.IntegerValue integerValue) {
            return Integer.toString(integerValue.get());
        }
        throw new BridgeException("tipo de regra nao suportado: " + name);
    }

    @Override
    public void setGameRule(String name, String value) {
        if (value == null) throw new BridgeException("valor de regra ausente: " + name);
        var rule = gameRuleValue(name);
        if (rule instanceof net.minecraft.world.level.GameRules.BooleanValue booleanValue) {
            if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                throw new BridgeException("regra booleana exige true ou false: " + name);
            }
            booleanValue.set(Boolean.parseBoolean(value), requireServer());
            return;
        }
        if (rule instanceof net.minecraft.world.level.GameRules.IntegerValue integerValue) {
            final int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException error) {
                throw new BridgeException("regra inteira exige um numero: " + name, error);
            }
            if (parsed < 0 || parsed > 1_000_000) {
                throw new BridgeException("valor de regra fora do limite seguro: " + name);
            }
            integerValue.set(parsed, requireServer());
            return;
        }
        throw new BridgeException("tipo de regra nao suportado: " + name);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private net.minecraft.world.level.GameRules.Value<?> gameRuleValue(String name) {
        if (name == null || !GameBridge.GAME_RULES.contains(name)) {
            throw new BridgeException("Game Rule nao permitida: " + name);
        }
        net.minecraft.world.level.GameRules.Key<?> key = switch (name) {
            case "do_fire_tick" -> net.minecraft.world.level.GameRules.RULE_DOFIRETICK;
            case "mob_griefing" -> net.minecraft.world.level.GameRules.RULE_MOBGRIEFING;
            case "keep_inventory" -> net.minecraft.world.level.GameRules.RULE_KEEPINVENTORY;
            case "do_mob_spawning" -> net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING;
            case "do_mob_loot" -> net.minecraft.world.level.GameRules.RULE_DOMOBLOOT;
            case "do_tile_drops" -> net.minecraft.world.level.GameRules.RULE_DOBLOCKDROPS;
            case "do_entity_drops" -> net.minecraft.world.level.GameRules.RULE_DOENTITYDROPS;
            case "natural_regeneration" -> net.minecraft.world.level.GameRules.RULE_NATURAL_REGENERATION;
            case "do_daylight_cycle" -> net.minecraft.world.level.GameRules.RULE_DAYLIGHT;
            case "do_weather_cycle" -> net.minecraft.world.level.GameRules.RULE_WEATHER_CYCLE;
            case "send_command_feedback" -> net.minecraft.world.level.GameRules.RULE_SENDCOMMANDFEEDBACK;
            case "announce_advancements" -> net.minecraft.world.level.GameRules.RULE_ANNOUNCE_ADVANCEMENTS;
            case "show_death_messages" -> net.minecraft.world.level.GameRules.RULE_SHOWDEATHMESSAGES;
            case "disable_raids" -> net.minecraft.world.level.GameRules.RULE_DISABLE_RAIDS;
            case "do_insomnia" -> net.minecraft.world.level.GameRules.RULE_DOINSOMNIA;
            case "do_immediate_respawn" -> net.minecraft.world.level.GameRules.RULE_DO_IMMEDIATE_RESPAWN;
            case "drowning_damage" -> net.minecraft.world.level.GameRules.RULE_DROWNING_DAMAGE;
            case "fall_damage" -> net.minecraft.world.level.GameRules.RULE_FALL_DAMAGE;
            case "fire_damage" -> net.minecraft.world.level.GameRules.RULE_FIRE_DAMAGE;
            case "freeze_damage" -> net.minecraft.world.level.GameRules.RULE_FREEZE_DAMAGE;
            case "do_patrol_spawning" -> net.minecraft.world.level.GameRules.RULE_DO_PATROL_SPAWNING;
            case "do_trader_spawning" -> net.minecraft.world.level.GameRules.RULE_DO_TRADER_SPAWNING;
            case "do_warden_spawning" -> net.minecraft.world.level.GameRules.RULE_DO_WARDEN_SPAWNING;
            case "forgive_dead_players" -> net.minecraft.world.level.GameRules.RULE_FORGIVE_DEAD_PLAYERS;
            case "universal_anger" -> net.minecraft.world.level.GameRules.RULE_UNIVERSAL_ANGER;
            case "do_vines_spread" -> net.minecraft.world.level.GameRules.RULE_DO_VINES_SPREAD;
            case "random_tick_speed" -> net.minecraft.world.level.GameRules.RULE_RANDOMTICKING;
            case "spawn_radius" -> net.minecraft.world.level.GameRules.RULE_SPAWN_RADIUS;
            case "max_entity_cramming" -> net.minecraft.world.level.GameRules.RULE_MAX_ENTITY_CRAMMING;
            case "players_sleeping_percentage" -> net.minecraft.world.level.GameRules.RULE_PLAYERS_SLEEPING_PERCENTAGE;
            case "snow_accumulation_height" -> net.minecraft.world.level.GameRules.RULE_SNOW_ACCUMULATION_HEIGHT;
            case "spawn_chunk_radius" -> net.minecraft.world.level.GameRules.RULE_SPAWN_CHUNK_RADIUS;
            default -> throw new BridgeException("Game Rule nao permitida: " + name);
        };
        return requireLevel().getGameRules().getRule((net.minecraft.world.level.GameRules.Key) key);
    }

    @Override
    public String difficulty() {
        return requireServer().getWorldData().getDifficulty().getKey();
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
        if (requireServer().getWorldData().isDifficultyLocked()) {
            throw new BridgeException("a dificuldade do mundo esta bloqueada");
        }
        requireServer().setDifficulty(parsed, true);
    }

    // ------------------------------------------------------------------ mundo

    @Override
    public int topY(int x, int z) {
        return requireLevel().getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
    }

    @Override
    public boolean breakBlock(int x, int y, int z, boolean drop) {
        ServerLevel level = requireLevel();
        BlockPos pos = new BlockPos(x, y, z);
        if (level.getBlockState(pos).isAir()) return false;

        // destroyBlock, e nao escrever ar: respeita a tabela de loot e derrama o inventario do
        // bloco, que e o que "quebrar" significa para quem joga.
        return level.destroyBlock(pos, drop);
    }

    // ------------------------------------------------------------------ entidades

    @Override
    public boolean healEntity(String uuid, float amount) {
        var entity = findEntity(uuid);
        if (!(entity instanceof net.minecraft.world.entity.LivingEntity living)) return false;

        living.heal(amount);
        return true;
    }

    @Override
    public boolean applyToEntity(String uuid, dev.lualoader.platform.EntitySpec spec) {
        var entity = findEntity(uuid);
        if (entity == null) return false;

        if (spec != null && !spec.isEmpty()) applySpec(entity, spec);
        return true;
    }

    @Override
    public String entityInfo(String uuid) {
        var entity = findEntity(uuid);
        if (entity == null) return null;

        float health = 0f;
        float maximum = 0f;
        if (entity instanceof net.minecraft.world.entity.LivingEntity living) {
            health = living.getHealth();
            maximum = living.getMaxHealth();
        }

        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        String name = entity.getCustomName() == null ? "" : entity.getCustomName().getString();

        return String.join(";", uuid, type == null ? "" : type.toString(),
                String.valueOf(entity.getX()), String.valueOf(entity.getY()),
                String.valueOf(entity.getZ()),
                String.valueOf(health), String.valueOf(maximum), name);
    }

    // ------------------------------------------------------------------ registro do jogo

    @Override
    public List<String> registeredBlocks(String namespace, String contains, int limit) {
        return filterRegistry(BuiltInRegistries.BLOCK.keySet(), namespace, contains, limit);
    }

    @Override
    public List<String> registeredEntities(String namespace, String contains, int limit) {
        return filterRegistry(BuiltInRegistries.ENTITY_TYPE.keySet(), namespace, contains, limit);
    }

    /** O mesmo filtro dos itens, para as três consultas responderem igual. */
    private static List<String> filterRegistry(java.util.Collection<ResourceLocation> ids,
                                               String namespace, String contains, int limit) {
        List<String> found = new ArrayList<>();
        for (ResourceLocation id : ids) {
            if (namespace != null && !id.getNamespace().equals(namespace)) continue;
            if (contains != null && !contains.isBlank() && !id.getPath().contains(contains)) continue;
            found.add(id.toString());
        }
        java.util.Collections.sort(found);
        return found.size() > limit ? found.subList(0, limit) : found;
    }

    @Override
    public String biomeAt(int x, int y, int z) {
        var level = requireLevel();
        var biome = level.getBiome(new net.minecraft.core.BlockPos(x, y, z));
        return biome.unwrapKey()
                .map(key -> key.location().toString())
                .orElse("minecraft:plains");
    }

    @Override
    public int lightAt(int x, int y, int z, boolean sky) {
        var level = requireLevel();
        var position = new net.minecraft.core.BlockPos(x, y, z);
        return sky
                ? level.getBrightness(net.minecraft.world.level.LightLayer.SKY, position)
                : level.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, position);
    }

    @Override
    public boolean teleportEntity(String uuid, double x, double y, double z) {
        var level = requireLevel();
        net.minecraft.world.entity.Entity entity = level.getEntity(java.util.UUID.fromString(uuid));
        if (entity == null) return false;

        // Angulos preservados: teleportar nao deveria virar o bicho para o norte, o que faria um
        // mod que so puxa a criatura um bloco parecer estar girando ela.
        entity.teleportTo(level, x, y, z,
                java.util.Set.<net.minecraft.world.entity.Relative>of(),
                entity.getYRot(), entity.getXRot(), true);
        return true;
    }

    @Override
    public boolean pushEntity(String uuid, double x, double y, double z) {
        var level = requireLevel();
        net.minecraft.world.entity.Entity entity = level.getEntity(java.util.UUID.fromString(uuid));
        if (entity == null) return false;

        entity.push(x, y, z);
        // Sem isto o empurrao acontece no servidor e o cliente nao ve: a velocidade so viaja
        // quando marcada, e a criatura desliza de volta como se nada tivesse acontecido.
        entity.hurtMarked = true;
        return true;
    }

    // ------------------------------------------------------------------ especies declaradas

    /**
     * Os ids das espécies que este loader registrou, e não as do jogo.
     *
     * <p>Separado de {@code registeredEntities}, que enxerga o registro inteiro: um mod que estende
     * o bestiário de outro precisa saber o que veio de um mod, e essa distinção se perde numa lista
     * de milhares de tipos.
     */
    @Override
    public java.util.List<String> declaredEntities() {
        var registrar = NeoForgeLuaLoader.entityRegistrar();
        return registrar == null ? java.util.List.of() : registrar.declaredEntities();
    }

    @Override
    public dev.lualoader.platform.EntityDefinition declaredEntity(String id) {
        var registrar = NeoForgeLuaLoader.entityRegistrar();
        if (registrar == null) return null;

        net.minecraft.resources.ResourceLocation parsed =
                net.minecraft.resources.ResourceLocation.tryParse(id);
        return parsed == null ? null : registrar.declaredEntity(parsed);
    }
}
