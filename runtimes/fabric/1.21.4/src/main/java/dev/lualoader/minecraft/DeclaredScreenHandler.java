package dev.lualoader.minecraft;

import dev.lualoader.manifest.ModManifest;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.math.BlockPos;

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
public class DeclaredScreenHandler extends ScreenHandler {
    private final Inventory inventory;
    private final BlockPos pos;

    /** Quantos slots são do bloco. Os seguintes são o inventário do jogador. */
    private final int blockSlots;

    /** Se os slots do bloco desenham sem guardar. */
    private final boolean ghost;

    /**
     * Construtor do lado do cliente: recebe a posição e procura o resto no manifesto.
     *
     * <p>O inventário aqui é um vazio do tamanho certo. O conteúdo chega pelo caminho normal do
     * jogo, que sincroniza slot a slot pela própria janela — mandar os itens junto seria mandar duas
     * vezes.
     */
    public DeclaredScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos) {
        this(syncId, playerInventory, pos, null,
                DeclaredMenus.inventoryOf(playerInventory.player.getWorld(), pos));
    }

    public DeclaredScreenHandler(int syncId, PlayerInventory playerInventory, BlockPos pos,
                                 Inventory inventory,
                                 ModManifest.InventoryDefinition declared) {
        super(DeclaredMenus.type(), syncId);
        this.pos = pos;

        ModManifest.LayoutDefinition layout = declared == null ? null : declared.layout;
        int size = declared == null ? 0 : declared.size;

        this.inventory = inventory != null ? inventory : new SimpleInventory(Math.max(1, size));
        this.blockSlots = size;
        this.ghost = declared != null && declared.ghost;

        // Os slots do bloco, cada um onde o manifesto mandou.
        for (int slot = 0; slot < size; slot++) {
            ModManifest.SlotDefinition posicao = layout != null && slot < layout.slots.size()
                    ? layout.slots.get(slot)
                    : null;
            int x = posicao == null ? 8 + (slot % 9) * 18 : posicao.x;
            int y = posicao == null ? 18 + (slot / 9) * 18 : posicao.y;
            addSlot(new Slot(this.inventory, slot, x, y));
        }

        // E o inventário do jogador, na posição declarada. Sem ele não há de onde arrastar, e a
        // janela vira decoração -- que foi o erro que esta sessão cometeu ao desenhar uma tela sem
        // slot nenhum.
        if (layout != null && layout.player != null) {
            adicionarInventarioDoJogador(playerInventory, layout.player.x, layout.player.y);
        }
    }

    private void adicionarInventarioDoJogador(PlayerInventory playerInventory, int x, int y) {
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
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType,
                            PlayerEntity player) {
        if (!isGhost(slotIndex)) {
            super.onSlotClick(slotIndex, button, actionType, player);
            return;
        }

        Slot slot = slots.get(slotIndex);
        int quantos = dev.lualoader.ui.GhostSlotRule.countAfterClick(getCursorStack().getCount(),
                button == 1);
        if (quantos <= 0) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            ItemStack desenho = getCursorStack().copy();
            desenho.setCount(quantos);
            slot.setStack(desenho);
        }

        slot.markDirty();
        sendContentUpdates();
    }

    /**
     * Shift-clique move entre o bloco e o jogador, e nunca mexe em slot fantasma.
     *
     * <p>Num inventário fantasma o gesto criaria itens: o slot não tirou de lugar nenhum o que
     * mostra.
     */
    @Override
    public ItemStack quickMove(PlayerEntity player, int slotIndex) {
        if (ghost) return ItemStack.EMPTY;

        Slot slot = slots.get(slotIndex);
        if (!slot.hasStack()) return ItemStack.EMPTY;

        ItemStack original = slot.getStack();
        ItemStack copia = original.copy();

        boolean doBloco = slotIndex < blockSlots;
        boolean moveu = doBloco
                ? insertItem(original, blockSlots, slots.size(), true)
                : insertItem(original, 0, blockSlots, false);
        if (!moveu) return ItemStack.EMPTY;

        if (original.isEmpty()) {
            slot.setStack(ItemStack.EMPTY);
        } else {
            slot.markDirty();
        }
        return copia;
    }

    @Override
    public boolean canInsertIntoSlot(ItemStack stack, Slot slot) {
        return !isGhost(slots.indexOf(slot)) && super.canInsertIntoSlot(stack, slot);
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return inventory.canPlayerUse(player);
    }

    /** A posição do bloco, para o cliente achar o manifesto e para o botão saber a quem responder. */
    public BlockPos pos() {
        return pos;
    }
}
