package dev.lualoader.neoforge;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A janela de um bloco cujo desenho vem do manifesto: cada slot na posição que o mod disse.
 *
 * <p>As janelas do jogo são formas fechadas — fileiras de nove, ou 3x3 — e uma máquina raramente tem
 * essa forma. Um cano de fabricação precisa de 3x3 <b>mais</b> um slot de saída, e nenhuma janela do
 * jogo tem isso com um container qualquer: a da bancada monta os próprios inventários e calcula pelo
 * livro de receitas.
 *
 * <p><b>O layout não trafega.</b> O cliente lê o mesmo manifesto que o servidor — o loader carrega
 * os mods dos dois lados —, então basta dizer <i>qual bloco</i> e ele descobre o resto. Mandar as
 * posições pela rede seria mandar dado que já está na outra ponta, e abriria a porta para as duas
 * pontas discordarem.
 */
public class NeoForgeDeclaredMenu extends AbstractContainerMenu {
    private final Container container;
    private final BlockPos pos;

    /** Quantos slots são do bloco. Os seguintes são o inventário do jogador. */
    private final int blockSlots;

    /** Se os slots do bloco desenham sem guardar. */
    private final boolean ghost;

    /**
     * Construtor do lado do cliente: recebe a posição e procura o resto no manifesto.
     *
     * <p>O container aqui é um vazio do tamanho certo. O conteúdo chega pelo caminho normal do jogo,
     * que sincroniza slot a slot pela própria janela — mandar os itens junto seria mandar duas
     * vezes.
     */
    public NeoForgeDeclaredMenu(int containerId, Inventory playerInventory,
                                RegistryFriendlyByteBuf buffer) {
        this(containerId, playerInventory, buffer.readBlockPos(), null, null);
    }

    public NeoForgeDeclaredMenu(int containerId, Inventory playerInventory, BlockPos pos,
                                Container container, ModManifest.InventoryDefinition declared) {
        super(NeoForgeDeclaredMenus.type(), containerId);
        this.pos = pos;

        ModManifest.InventoryDefinition inventory = declared != null
                ? declared
                : NeoForgeDeclaredMenus.inventoryOf(playerInventory.player.level(), pos);

        ModManifest.LayoutDefinition layout = inventory == null ? null : inventory.layout;
        int size = inventory == null ? 0 : inventory.size;

        this.container = container != null ? container : new SimpleContainer(Math.max(1, size));
        this.blockSlots = size;
        this.ghost = inventory != null && inventory.ghost;

        // Os slots do bloco, cada um onde o manifesto mandou.
        for (int slot = 0; slot < size; slot++) {
            ModManifest.SlotDefinition posicao = layout != null && slot < layout.slots.size()
                    ? layout.slots.get(slot)
                    : null;
            int x = posicao == null ? 8 + (slot % 9) * 18 : posicao.x;
            int y = posicao == null ? 18 + (slot / 9) * 18 : posicao.y;
            addSlot(new Slot(this.container, slot, x, y));
        }

        // E o inventário do jogador, na posição declarada. Sem ele não há de onde arrastar, e a
        // janela vira decoração -- que foi o erro que esta sessão cometeu ao desenhar uma tela sem
        // slot nenhum.
        if (layout != null && layout.player != null) {
            adicionarInventarioDoJogador(playerInventory, layout.player.x, layout.player.y);
        }
    }

    private void adicionarInventarioDoJogador(Inventory playerInventory, int x, int y) {
        // Três fileiras e a barra, com o vão de quatro pixels entre elas -- as mesmas medidas de
        // qualquer tela do jogo, para o desenho não parecer estrangeiro.
        for (int linha = 0; linha < 3; linha++) {
            for (int coluna = 0; coluna < 9; coluna++) {
                addSlot(new Slot(playerInventory, coluna + linha * 9 + 9,
                        x + coluna * 18, y + linha * 18));
            }
        }
        for (int coluna = 0; coluna < 9; coluna++) {
            addSlot(new Slot(playerInventory, coluna, x + coluna * 18, y + 58));
        }
    }

    private boolean isGhost(int slotIndex) {
        return ghost && slotIndex >= 0 && slotIndex < blockSlots;
    }

    /** O slot fantasma copia em vez de mover; a regra mora no núcleo. */
    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!isGhost(slotId)) {
            super.clicked(slotId, button, clickType, player);
            return;
        }

        Slot slot = slots.get(slotId);
        int quantos = dev.lualoader.ui.GhostSlotRule.countAfterClick(getCarried().getCount(),
                button == 1);
        if (quantos <= 0) {
            slot.set(ItemStack.EMPTY);
        } else {
            ItemStack desenho = getCarried().copy();
            desenho.setCount(quantos);
            slot.set(desenho);
        }

        slot.setChanged();
        broadcastChanges();
    }

    /**
     * Shift-clique move entre o bloco e o jogador, e nunca mexe em slot fantasma.
     *
     * <p>Num inventário fantasma o gesto criaria itens: o slot não tirou de lugar nenhum o que
     * mostra.
     */
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        if (ghost) return ItemStack.EMPTY;

        Slot slot = slots.get(slotIndex);
        if (!slot.hasItem()) return ItemStack.EMPTY;

        ItemStack original = slot.getItem();
        ItemStack copia = original.copy();

        boolean doBloco = slotIndex < blockSlots;
        boolean moveu = doBloco
                ? moveItemStackTo(original, blockSlots, slots.size(), true)
                : moveItemStackTo(original, 0, blockSlots, false);
        if (!moveu) return ItemStack.EMPTY;

        if (original.isEmpty()) {
            slot.set(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return copia;
    }

    @Override
    public boolean canDragTo(Slot slot) {
        return !isGhost(slots.indexOf(slot)) && super.canDragTo(slot);
    }

    @Override
    public boolean stillValid(Player player) {
        return container.stillValid(player);
    }

    /** A posição do bloco, para o cliente achar o manifesto e para o botão saber a quem responder. */
    public BlockPos pos() {
        return pos;
    }
}
