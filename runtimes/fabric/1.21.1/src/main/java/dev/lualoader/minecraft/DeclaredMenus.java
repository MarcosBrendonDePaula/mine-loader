package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * O tipo de janela declarada, registrado uma vez, e o mapa que liga um bloco ao desenho dele.
 *
 * <p><b>Um tipo só para todos os blocos.</b> O tipo é o que o cliente usa para escolher a tela que
 * desenha; um por bloco encheria o registro do jogo e daria uma tela por bloco no cliente. Aqui a
 * tela é uma, e o que muda é o manifesto que ela lê — a mesma ideia da tela genérica do loader, que
 * interpreta dados em vez de existir uma por mod.
 */
public final class DeclaredMenus {
    private DeclaredMenus() {
    }

    /** O identificador do tipo, igual nos dois lados e nas duas plataformas. */
    public static final Identifier ID = Identifier.of("lua_loader", "declared_menu");

    private static ScreenHandlerType<DeclaredScreenHandler> type;

    /**
     * O inventário declarado de cada bloco, por id.
     *
     * <p>Preenchido no registro, dos dois lados: o servidor precisa para montar a janela, e o
     * cliente para saber onde desenhar cada slot. É o que permite o layout <b>não trafegar</b> —
     * mandar posições pela rede seria mandar dado que já está na outra ponta.
     */
    private static final Map<String, ModManifest.InventoryDefinition> DECLARED =
            new LinkedHashMap<>();

    /** A folha de fundo de cada bloco, já no caminho que a tela usa para desenhar. */
    private static final Map<String, String> TEXTURES = new LinkedHashMap<>();

    public static void declare(String blockId, ModManifest.InventoryDefinition inventory) {
        if (blockId == null || inventory == null || inventory.layout == null) return;
        DECLARED.put(blockId, inventory);

        // O caminho da folha é montado aqui e não na tela: aqui se sabe de que mod o bloco é, e a
        // tela só tem a posição. É o mesmo caminho fixo que o montador do pacote escreve.
        ModManifest.TextureDefinition textura = inventory.layout.texture;
        if (textura != null && textura.ref != null && !textura.ref.isBlank()) {
            String modId = blockId.substring(0, blockId.indexOf(':'));
            TEXTURES.put(blockId, modId + ":textures/gui/" + textura.ref + ".png");
        }
    }

    private static String blockIdAt(BlockView world, BlockPos pos) {
        if (world == null || pos == null) return null;

        Block block = world.getBlockState(pos).getBlock();
        Identifier id = Registries.BLOCK.getId(block);
        return id == null ? null : id.toString();
    }

    public static ModManifest.InventoryDefinition inventoryOf(BlockView world, BlockPos pos) {
        String id = blockIdAt(world, pos);
        return id == null ? null : DECLARED.get(id);
    }

    /** O caminho da folha daquele bloco, ou {@code null} quando ele não declarou arte. */
    public static String textureOf(BlockView world, BlockPos pos) {
        String id = blockIdAt(world, pos);
        return id == null ? null : TEXTURES.get(id);
    }

    /** O id do bloco daquela posição, que é o nome da tela para onde o botão responde. */
    public static String screenIdOf(BlockView world, BlockPos pos) {
        return blockIdAt(world, pos);
    }

    /** Se algum bloco declarou janela própria. Sem isso o tipo não precisa nem existir. */
    public static boolean anyDeclared() {
        return !DECLARED.isEmpty();
    }

    public static ScreenHandlerType<DeclaredScreenHandler> register() {
        if (type != null) return type;

        type = Registry.register(Registries.SCREEN_HANDLER, ID,
                new net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType<>(
                        DeclaredScreenHandler::new, BlockPos.PACKET_CODEC));
        return type;
    }

    public static ScreenHandlerType<DeclaredScreenHandler> type() {
        return type;
    }
}
