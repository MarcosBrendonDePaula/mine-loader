package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import org.jetbrains.annotations.Nullable;

/**
 * Guarda dados e itens de um bloco declarativo naquela posição do mundo.
 *
 * <p>É o par da {@code DeclarativeBlockEntity} do adaptador Fabric, e resolve duas coisas de uma
 * vez. Os dados por posição já existiam neste adaptador, mas viviam num mapa em memória e sumiam ao
 * desligar o servidor — funcionava numa sessão e mentia entre duas. Agora vão para o disco com o
 * resto do mundo, como sempre deveriam.
 *
 * <p>O inventário é a outra metade. Onde o Fabric implementa {@code SidedInventory}, aqui a mesma
 * ideia é uma capability: o bloco guarda um container comum e publica um {@code IItemHandler} por
 * cima dele, que é o que funis e tubos procuram. O mod não vê nenhum dos dois nomes.
 */
public class NeoForgeDeclarativeBlockEntity extends BlockEntity
        implements MenuProvider, net.minecraft.world.Container {
    private static final String DATA_KEY = "lua_data";
    private static final String ITEMS_KEY = "lua_items";

    private String data = "{}";

    /** O que o manifesto declarou; {@code null} quando o bloco só guarda dados. */
    @Nullable
    private final ModManifest.InventoryDefinition inventory;

    /** Os slots, ou {@code null} quando o bloco não declara inventário. */
    @Nullable
    private final SimpleContainer contents;

    /**
     * A visão que a automação tem do inventário: com as permissões aplicadas.
     *
     * <p>É o que funis e tubos alcançam, e por isso é aqui que {@code allow_insert} e
     * {@code allow_extract} recusam.
     */
    @Nullable
    private final IItemHandler sided;

    /**
     * A visão sem restrição, para quem acessa o bloco sem lado.
     *
     * <p>A distinção não é sutil e custou um teste vermelho para aparecer. Uma fornalha declarada
     * aceita entrada e recusa saída automática — mas ela mesma precisa tirar o minério de dentro
     * para processá-lo, e o único caminho que o mod tem é a API do loader. Se as permissões
     * valessem também ali, {@code allow_extract: false} viraria uma armadilha: o autor trancaria o
     * próprio bloco e descobriria depois.
     *
     * <p>É também o que o adaptador Fabric faz, e não por acaso: lá o acesso sem lado ignora
     * {@code canExtract} porque a pergunta "deste lado, pode?" não tem resposta sem um lado.
     */
    @Nullable
    private final IItemHandler unsided;

    public NeoForgeDeclarativeBlockEntity(BlockPos pos, BlockState state) {
        super(NeoForgeBlockEntities.type(), pos, state);

        this.inventory = NeoForgeContentRegistrar.inventoryOf(state.getBlock());
        if (inventory == null) {
            this.contents = null;
            this.sided = null;
            this.unsided = null;
            return;
        }

        this.contents = new SimpleContainer(Math.max(1, inventory.size));
        // Sem isto o conteudo mudaria e o mundo nao seria marcado para gravar: os itens
        // sobreviveriam a sessao e sumiriam no proximo carregamento.
        this.contents.addListener(container -> setChanged());

        final ModManifest.InventoryDefinition declared = inventory;
        this.unsided = new InvWrapper(contents);
        this.sided = new InvWrapper(contents) {
            @Override
            public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
                if (!declared.machineCanInsert()) return stack;
                return super.insertItem(slot, stack, simulate);
            }

            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                if (!declared.machineCanExtract()) return ItemStack.EMPTY;
                return super.extractItem(slot, amount, simulate);
            }
        };
    }

    /** Conteúdo atual, como texto JSON. */
    public String data() {
        return data;
    }

    /** Substitui o conteúdo e marca o bloco para ser gravado com o mundo. */
    public void setData(String json) {
        this.data = json == null || json.isBlank() ? "{}" : json;
        setChanged();
    }

    /** Se este bloco guarda itens. */
    public boolean hasInventory() {
        return contents != null;
    }

    /** O que o manifesto declarou sobre o inventário, ou {@code null}. */
    @Nullable
    public ModManifest.InventoryDefinition inventoryDefinition() {
        return inventory;
    }

    /** Os slots, para quem precisa derramá-los quando o bloco quebra. */
    @Nullable
    public SimpleContainer contents() {
        return contents;
    }

    /**
     * O que a capability publica, conforme o lado por onde vem o acesso.
     *
     * @param side o lado do bloco, ou {@code null} quando o acesso nao tem lado
     */
    @Nullable
    public IItemHandler handler(@Nullable net.minecraft.core.Direction side) {
        return side == null ? unsided : sided;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString(DATA_KEY, data);

        if (contents != null) {
            NonNullList<ItemStack> list = NonNullList.withSize(contents.getContainerSize(),
                    ItemStack.EMPTY);
            for (int slot = 0; slot < contents.getContainerSize(); slot++) {
                list.set(slot, contents.getItem(slot));
            }

            CompoundTag stored = new CompoundTag();
            ContainerHelper.saveAllItems(stored, list, registries);
            tag.put(ITEMS_KEY, stored);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        data = tag.contains(DATA_KEY) ? tag.getString(DATA_KEY) : "{}";

        if (contents != null) {
            contents.clearContent();
            if (!tag.contains(ITEMS_KEY)) return;

            NonNullList<ItemStack> list = NonNullList.withSize(contents.getContainerSize(),
                    ItemStack.EMPTY);
            ContainerHelper.loadAllItems(tag.getCompound(ITEMS_KEY), list, registries);

            // O tamanho vem do manifesto, e nao do que foi gravado: um manifesto que encolheu o
            // inventario depois de o mundo existir nao pode ressuscitar os slots antigos.
            for (int slot = 0; slot < Math.min(list.size(), contents.getContainerSize()); slot++) {
                contents.setItem(slot, list.get(slot));
            }
        }
    }

    // ------------------------------------------------------------------ container

    /*
     * Delegacao ao container interno.
     *
     * <p>Nao e cerimonia: a capability sozinha nao basta. Comandos do jogo -- {@code /item replace
     * block}, {@code /loot}, {@code /clear} -- e boa parte dos mods procuram um {@code Container} na
     * entidade, e nao a capability. Sem isto o inventario existia para funil e nao existia para o
     * resto do jogo, o que so aparece quando alguem tenta. O adaptador Fabric ganha isso de graca,
     * porque la {@code SidedInventory} ja e a interface do jogo.
     */

    @Override
    public int getContainerSize() {
        return contents == null ? 0 : contents.getContainerSize();
    }

    @Override
    public boolean isEmpty() {
        return contents == null || contents.isEmpty();
    }

    @Override
    public ItemStack getItem(int slot) {
        return contents == null ? ItemStack.EMPTY : contents.getItem(slot);
    }

    @Override
    public ItemStack removeItem(int slot, int amount) {
        return contents == null ? ItemStack.EMPTY : contents.removeItem(slot, amount);
    }

    @Override
    public ItemStack removeItemNoUpdate(int slot) {
        return contents == null ? ItemStack.EMPTY : contents.removeItemNoUpdate(slot);
    }

    @Override
    public void setItem(int slot, ItemStack stack) {
        if (contents != null) contents.setItem(slot, stack);
    }

    @Override
    public boolean stillValid(Player player) {
        // A mesma regra do baú: longe demais, a janela fecha. Sem isto um jogador manteria a tela
        // aberta atravessando o mundo, mexendo num inventário que já nem está carregado.
        if (level == null || level.getBlockEntity(worldPosition) != this) return false;
        return player.distanceToSqr(worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clearContent() {
        if (contents != null) contents.clearContent();
    }

    // ------------------------------------------------------------------ janela

    @Override
    public Component getDisplayName() {
        if (inventory != null && inventory.title != null && !inventory.title.isBlank()) {
            return Component.literal(inventory.title);
        }
        return getBlockState().getBlock().getName();
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
        if (contents == null) return null;

        // A tela do baú, e não uma própria: o inventário declarado é uma grade de slots, que é
        // exatamente o que ela desenha. Uma tela do loader aqui exigiria protocolo para algo que o
        // jogo já faz, e não funcionaria em cliente vanilla.
        // Uma janela declarada e montada slot a slot, na posicao que o manifesto disser: as
        // formas do jogo sao fechadas, e uma maquina raramente tem uma delas.
        if (inventory != null && inventory.layout != null) {
            return new NeoForgeDeclaredMenu(containerId, playerInventory, getBlockPos(),
                    contents, inventory);
        }

        // A janela 3x3 e a do dispenser: o jogo ja a desenha com essa forma, e a forma e o que da
        // sentido a um padrao. Nove slots numa fileira unica nao dizem "a espada e duas barras em
        // cima de um graveto" -- o jogador olha e nao tem como desenhar.
        if (inventory != null && "3x3".equals(inventory.window)) {
            return inventory.ghost
                    ? new NeoForgeGhost3x3Menu(containerId, playerInventory, contents)
                    : new net.minecraft.world.inventory.DispenserMenu(
                            containerId, playerInventory, contents);
        }

        int rows = Math.max(1, Math.min(6, contents.getContainerSize() / 9));

        // Um inventario fantasma abre a mesma tela, com o clique reescrito: o desenho continua sendo
        // o do jogo e so o comportamento muda, que e a parte que o servidor decide sozinho.
        if (inventory != null && inventory.ghost) {
            return new NeoForgeGhostMenu(typeFor(rows), containerId, playerInventory, contents, rows);
        }
        return new ChestMenu(typeFor(rows), containerId, playerInventory, contents, rows);
    }

    private static MenuType<ChestMenu> typeFor(int rows) {
        return switch (rows) {
            case 1 -> MenuType.GENERIC_9x1;
            case 2 -> MenuType.GENERIC_9x2;
            case 3 -> MenuType.GENERIC_9x3;
            case 4 -> MenuType.GENERIC_9x4;
            case 5 -> MenuType.GENERIC_9x5;
            default -> MenuType.GENERIC_9x6;
        };
    }

    /** Tipo registrado uma vez, cobrindo todos os blocos declarativos com dados. */
    public static BlockEntityType<NeoForgeDeclarativeBlockEntity> createType(
            net.minecraft.world.level.block.Block[] blocks) {
        return new BlockEntityType<>(NeoForgeDeclarativeBlockEntity::new, blocks);
    }
}
