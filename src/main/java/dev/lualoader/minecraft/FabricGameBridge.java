package dev.lualoader.minecraft;

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
        java.util.List<String> nomes = new java.util.ArrayList<>();
        for (var player : requireServer().getPlayerManager().getPlayerList()) {
            nomes.add(player.getName().getString());
        }
        return nomes;
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
        var caixa = new net.minecraft.util.math.Box(
                x - radius, y - radius, z - radius, x + radius, y + radius, z + radius);

        java.util.List<String> encontradas = new java.util.ArrayList<>();
        for (Entity entity : world.getOtherEntities(null, caixa)) {
            Identifier tipo = Registries.ENTITY_TYPE.getId(entity.getType());
            encontradas.add(entity.getUuidAsString() + ";" + (tipo == null ? "?" : tipo)
                    + ";" + entity.getBlockX() + ";" + entity.getBlockY() + ";" + entity.getBlockZ());
        }
        return encontradas;
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
