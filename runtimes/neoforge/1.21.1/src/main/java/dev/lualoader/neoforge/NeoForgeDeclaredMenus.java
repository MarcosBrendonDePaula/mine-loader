package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;

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
public final class NeoForgeDeclaredMenus {
    private NeoForgeDeclaredMenus() {
    }

    /** O identificador do tipo, igual nos dois lados. */
    public static final ResourceLocation ID =
            ResourceLocation.fromNamespaceAndPath("lua_loader", "declared_menu");

    private static MenuType<NeoForgeDeclaredMenu> type;

    /**
     * O inventário declarado de cada bloco, por id.
     *
     * <p>Preenchido no registro, dos dois lados: o servidor precisa para montar a janela, e o
     * cliente para saber onde desenhar cada slot. É o que permite o layout <b>não trafegar</b> —
     * mandar posições pela rede seria mandar dado que já está na outra ponta.
     */
    private static final Map<String, ModManifest.InventoryDefinition> DECLARED =
            new LinkedHashMap<>();

    /** A folha de fundo de cada bloco, ja no caminho que o cliente usa para desenhar. */
    private static final Map<String, String> TEXTURAS = new LinkedHashMap<>();

    public static void declare(String blockId, ModManifest.InventoryDefinition inventory) {
        if (blockId == null || inventory == null || inventory.layout == null) return;
        DECLARED.put(blockId, inventory);

        // O caminho da folha e montado aqui e nao na tela: aqui se sabe de que mod o bloco e, e a
        // tela so tem a posicao. E o mesmo caminho fixo que o montador do pacote escreve.
        ModManifest.TextureDefinition textura = inventory.layout.texture;
        if (textura != null && textura.ref != null && !textura.ref.isBlank()) {
            String modId = blockId.substring(0, blockId.indexOf(':'));
            TEXTURAS.put(blockId, modId + ":textures/gui/" + textura.ref + ".png");
        }
    }

    /** O caminho da folha daquele bloco, ou {@code null} quando ele nao declarou arte. */
    public static String textureOf(BlockPos pos, BlockGetter level) {
        if (level == null || pos == null) return null;

        Block block = level.getBlockState(pos).getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : TEXTURAS.get(id.toString());
    }

    /** O id do bloco daquela posicao, que e o nome da tela para onde o botao responde. */
    public static String screenIdOf(BlockPos pos, BlockGetter level) {
        if (level == null || pos == null) return null;

        Block block = level.getBlockState(pos).getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : id.toString();
    }

    /** O inventário declarado daquele bloco, ou {@code null} quando ele não tem janela própria. */
    public static ModManifest.InventoryDefinition inventoryOf(BlockGetter level, BlockPos pos) {
        if (level == null || pos == null) return null;

        Block block = level.getBlockState(pos).getBlock();
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : DECLARED.get(id.toString());
    }

    public static ModManifest.InventoryDefinition inventoryOf(String blockId) {
        return DECLARED.get(blockId);
    }

    /** Se algum bloco declarou janela própria. Sem isso o tipo não precisa nem existir. */
    public static boolean anyDeclared() {
        return !DECLARED.isEmpty();
    }

    public static MenuType<NeoForgeDeclaredMenu> create() {
        type = net.neoforged.neoforge.common.extensions.IMenuTypeExtension
                .create(NeoForgeDeclaredMenu::new);
        return type;
    }

    public static MenuType<NeoForgeDeclaredMenu> type() {
        return type;
    }
}
