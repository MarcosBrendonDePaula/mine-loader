package dev.lualoader.minecraft;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
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

    public FabricGameBridge(BlockRegistrar registrar) {
        this.registrar = registrar;
    }

    /** Atualiza o servidor ativo. Recebe {@code null} quando o servidor para. */
    public void setServer(MinecraftServer server) {
        this.server = server;
    }

    @Override
    public boolean isWorldAvailable() {
        return server != null && server.getOverworld() != null;
    }

    @Override
    public void broadcast(String message) {
        requireServer().getPlayerManager().broadcast(Text.literal(message), false);
    }

    @Override
    public void setBlockVariant(String blockId, int x, int y, int z, int variant) {
        DeclarativeBlock block = requireDeclarativeBlock(blockId);
        BlockPos pos = new BlockPos(x, y, z);
        var world = requireServer().getOverworld();
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
        var world = requireServer().getOverworld();
        var current = world.getBlockState(pos);
        var state = current.isOf(block)
                ? current.with(DeclarativeBlock.LUA_LUMINANCE, luminance)
                : block.getDefaultState().with(DeclarativeBlock.LUA_LUMINANCE, luminance);
        world.setBlockState(pos, state, 3);
    }

    @Override
    public String getBlock(int x, int y, int z) {
        var world = requireServer().getOverworld();
        var state = world.getBlockState(new BlockPos(x, y, z));
        Identifier id = Registries.BLOCK.getId(state.getBlock());
        return id == null ? "minecraft:air" : id.toString();
    }

    @Override
    public void setBlock(String blockId, int x, int y, int z) {
        Block block = requireAnyBlock(blockId);
        requireServer().getOverworld().setBlockState(new BlockPos(x, y, z), block.getDefaultState(), 3);
    }

    @Override
    public int fillBlocks(String blockId, int x1, int y1, int z1, int x2, int y2, int z2) {
        Block block = requireAnyBlock(blockId);
        var world = requireServer().getOverworld();
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
