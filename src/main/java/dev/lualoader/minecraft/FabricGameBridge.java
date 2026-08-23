package dev.lualoader.minecraft;

import dev.lualoader.platform.BridgeException;
import dev.lualoader.platform.GameBridge;
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
