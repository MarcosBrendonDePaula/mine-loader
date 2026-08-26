package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventories;
import net.minecraft.inventory.SidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.NamedScreenHandlerFactory;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.text.Text;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/**
 * Guarda dados e itens de um bloco declarativo naquela posição do mundo.
 *
 * <p>Sem isto, um mod só tinha estado por mod: uma máquina, um baú customizado ou um bloco que
 * lembra quem o colocou não tinham onde guardar nada. O conteúdo é mantido como JSON em texto,
 * porque é o mesmo formato já usado no estado do mod e sobrevive a mudanças no manifesto sem exigir
 * um esquema NBT por bloco.
 *
 * <p>O inventário é a outra metade, e é o que separa um bloco decorativo de uma máquina: itens
 * presos àquela posição, que sobrevivem a sair e voltar do mundo. Implementa {@link SidedInventory}
 * — e não apenas {@code Inventory} — porque é o que faz funis e tubos enxergarem o bloco. Um baú de
 * mod que não recebe de funil não é um baú.
 */
public class DeclarativeBlockEntity extends BlockEntity implements SidedInventory,
        NamedScreenHandlerFactory,
        net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory<BlockPos> {
    private static final String DATA_KEY = "lua_data";
    private static final String ITEMS_KEY = "lua_items";

    private String data = "{}";

    /**
     * Os slots, ou vazio quando o bloco não declara inventário.
     *
     * <p>O tamanho vem do bloco, e não do NBT: um manifesto que diminui o inventário depois de o
     * mundo existir precisa de um limite conhecido antes de ler o que foi gravado, senão itens
     * gravados além do novo tamanho ressuscitariam a lista antiga.
     */
    private final DefaultedList<ItemStack> items;

    /** O que o manifesto declarou; {@code null} quando o bloco só guarda dados. */
    @Nullable
    private final ModManifest.InventoryDefinition inventory;

    public DeclarativeBlockEntity(BlockPos pos, BlockState state) {
        super(BlockEntityRegistrar.type(), pos, state);

        this.inventory = BlockRegistrar.inventoryOf(state.getBlock());
        this.items = inventory == null
                ? DefaultedList.ofSize(0, ItemStack.EMPTY)
                : DefaultedList.ofSize(Math.max(1, inventory.size), ItemStack.EMPTY);
    }

    /** Conteúdo atual, como texto JSON. */
    public String data() {
        return data;
    }

    /** Substitui o conteúdo e marca o bloco para ser gravado com o mundo. */
    public void setData(String json) {
        this.data = json == null || json.isBlank() ? "{}" : json;
        markDirty();
    }

    /** Se este bloco guarda itens. */
    public boolean hasInventory() {
        return inventory != null;
    }

    /** O que o manifesto declarou sobre o inventário, ou {@code null}. */
    @Nullable
    public ModManifest.InventoryDefinition inventoryDefinition() {
        return inventory;
    }

    @Override
    protected void writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.writeNbt(nbt, registries);
        nbt.putString(DATA_KEY, data);

        if (inventory != null) {
            NbtCompound stored = new NbtCompound();
            Inventories.writeNbt(stored, items, registries);
            nbt.put(ITEMS_KEY, stored);
        }
    }

    @Override
    protected void readNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup registries) {
        super.readNbt(nbt, registries);
        data = nbt.contains(DATA_KEY) ? nbt.getString(DATA_KEY) : "{}";

        if (inventory != null) {
            items.clear();
            if (nbt.contains(ITEMS_KEY)) {
                Inventories.readNbt(nbt.getCompound(ITEMS_KEY), items, registries);
            }
        }
    }

    // ------------------------------------------------------------------ inventario

    @Override
    public int size() {
        return items.size();
    }

    @Override
    public boolean isEmpty() {
        for (ItemStack stack : items) {
            if (!stack.isEmpty()) return false;
        }
        return true;
    }

    @Override
    public ItemStack getStack(int slot) {
        return slot >= 0 && slot < items.size() ? items.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public ItemStack removeStack(int slot, int amount) {
        ItemStack removed = Inventories.splitStack(items, slot, amount);
        if (!removed.isEmpty()) markDirty();
        return removed;
    }

    @Override
    public ItemStack removeStack(int slot) {
        ItemStack removed = Inventories.removeStack(items, slot);
        if (!removed.isEmpty()) markDirty();
        return removed;
    }

    @Override
    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= items.size()) return;

        items.set(slot, stack);
        if (stack.getCount() > stack.getMaxCount()) stack.setCount(stack.getMaxCount());
        markDirty();
    }

    @Override
    public boolean canPlayerUse(PlayerEntity player) {
        // A mesma regra do baú: longe demais, a janela fecha. Sem isto um jogador manteria a tela
        // aberta atravessando o mundo, mexendo num inventário que já nem está carregado.
        if (world == null || world.getBlockEntity(pos) != this) return false;
        return player.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    @Override
    public void clear() {
        items.clear();
        markDirty();
    }

    /** Os itens guardados, para quem precisa derramá-los quando o bloco quebra. */
    public DefaultedList<ItemStack> contents() {
        return items;
    }

    // ------------------------------------------------------------------ automacao

    /**
     * Todos os slots, de qualquer lado.
     *
     * <p>O loader não deixa o mod escolher slots por face: a diferença útil — aceitar entrada e
     * recusar saída — é expressa por {@code allow_insert} e {@code allow_extract}, que valem para
     * todos os lados. Slots por face exigiriam o mod conhecer orientação de bloco, que ele não
     * declara.
     */
    @Override
    public int[] getAvailableSlots(Direction side) {
        int[] slots = new int[items.size()];
        for (int index = 0; index < slots.length; index++) slots[index] = index;
        return slots;
    }

    @Override
    public boolean canInsert(int slot, ItemStack stack, @Nullable Direction side) {
        return inventory != null && inventory.machineCanInsert();
    }

    @Override
    public boolean canExtract(int slot, ItemStack stack, Direction side) {
        return inventory != null && inventory.machineCanExtract();
    }

    // ------------------------------------------------------------------ janela

    @Override
    public Text getDisplayName() {
        if (inventory != null && inventory.title != null && !inventory.title.isBlank()) {
            return Text.literal(inventory.title);
        }
        return getCachedState().getBlock().getName();
    }

    /**
     * O unico dado que o cliente precisa: qual bloco. O desenho ele acha no manifesto que ja tem.
     */
    @Override
    public BlockPos getScreenOpeningData(net.minecraft.server.network.ServerPlayerEntity player) {
        return getPos();
    }

    @Nullable
    @Override
    public ScreenHandler createMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        if (inventory == null) return null;

        // A tela do baú, e não uma própria: o inventário declarado é uma grade de slots, que é
        // exatamente o que ela desenha. Uma tela do loader aqui exigiria protocolo para algo que o
        // jogo já faz, e não funcionaria em cliente vanilla.
        // Uma janela declarada e montada slot a slot, na posicao que o manifesto disser: as
        // formas do jogo sao fechadas, e uma maquina raramente tem uma delas.
        if (inventory.layout != null) {
            return new DeclaredScreenHandler(syncId, playerInventory, getPos(), this, inventory);
        }

        // A janela 3x3 e a do dispenser: o jogo ja a desenha com essa forma, e a forma e o que da
        // sentido a um padrao. Nove slots numa fileira unica nao dizem "a espada e duas barras em
        // cima de um graveto" -- o jogador olha e nao tem como desenhar.
        if ("3x3".equals(inventory.window)) {
            return inventory.ghost
                    ? new Ghost3x3ScreenHandler(syncId, playerInventory, this)
                    : new net.minecraft.screen.Generic3x3ContainerScreenHandler(
                            syncId, playerInventory, this);
        }

        int rows = Math.max(1, Math.min(6, items.size() / 9));

        // Um inventario fantasma abre a mesma tela, com o clique reescrito: o desenho continua sendo
        // o do jogo e so o comportamento muda, que e a parte que o servidor decide sozinho.
        if (inventory.ghost) {
            return new GhostContainerScreenHandler(
                    typeFor(rows), syncId, playerInventory, this, rows);
        }
        return new GenericContainerScreenHandler(
                typeFor(rows), syncId, playerInventory, this, rows);
    }

    private static net.minecraft.screen.ScreenHandlerType<GenericContainerScreenHandler> typeFor(
            int rows) {
        return switch (rows) {
            case 1 -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X1;
            case 2 -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X2;
            case 3 -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X3;
            case 4 -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X4;
            case 5 -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X5;
            default -> net.minecraft.screen.ScreenHandlerType.GENERIC_9X6;
        };
    }

    /** Tipo registrado uma vez, cobrindo todos os blocos declarativos com dados. */
    public static BlockEntityType<DeclarativeBlockEntity> createType(net.minecraft.block.Block[] blocks) {
        return BlockEntityType.Builder.create(DeclarativeBlockEntity::new, blocks).build();
    }
}
