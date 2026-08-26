package dev.lualoader.neoforge;

import dev.lualoader.lua.LuaRuntime;
import dev.lualoader.platform.BlockEventData;
import dev.lualoader.platform.ItemEventData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;

import java.util.function.Supplier;

/**
 * Traduz interações do jogador nos eventos que os scripts Lua declaram.
 *
 * <p>É o par do {@code BlockInteractionEvents} do adaptador Fabric, e entrega ao núcleo os mesmos
 * {@link BlockEventData} e {@link ItemEventData} neutros — um mod que reage a {@code block_used}
 * roda igual nas duas plataformas sem saber em qual está.
 *
 * <p>Escuta os eventos do jogo em vez de sobrescrever métodos no bloco, como o adaptador Fabric faz.
 * Os dois chegam ao mesmo lugar, mas aqui um ouvinte só cobre todo o conteúdo declarado, presente e
 * futuro: blocos que ainda não existem — os com inventário próprio, por exemplo — passam a disparar
 * eventos sem precisar herdar de nada.
 *
 * <p>O runtime chega por um fornecedor porque ainda não existe quando esta classe é construída: o
 * conteúdo é registrado durante a inicialização do jogo, e o runtime Lua só nasce quando o servidor
 * sobe. Guardar a referência agora guardaria {@code null} para sempre.
 */
public final class NeoForgeInteractionEvents {
    private final Supplier<LuaRuntime> runtime;
    private final Supplier<NeoForgeGameBridge> bridge;
    private final NeoForgeContentRegistrar content;

    public NeoForgeInteractionEvents(Supplier<LuaRuntime> runtime,
                                     Supplier<NeoForgeGameBridge> bridge,
                                     NeoForgeContentRegistrar content) {
        this.runtime = runtime;
        this.bridge = bridge;
        this.content = content;
    }

    public void register(IEventBus gameBus) {
        gameBus.addListener(this::onRightClickBlock);
        gameBus.addListener(this::onLeftClickBlock);
        gameBus.addListener(this::onRightClickItem);
        gameBus.addListener(this::onPlace);
        gameBus.addListener(this::onBreak);
    }

    // ------------------------------------------------------------------ blocos

    private void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // O evento chega uma vez por mao; sem o filtro cada clique contaria duas vezes, e um ciclo
        // de duas variantes voltaria ao inicio dentro do mesmo clique.
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack held = event.getItemStack();

        // Clicar segurando um item declarado e usar o item sobre o bloco, e nao usar o bloco.
        if (isDeclaredItem(held)) {
            BlockPos pos = event.getPos();
            BlockState target = event.getLevel().getBlockState(pos);
            ItemEventData data = new ItemEventData(
                    idOf(held), idOf(target), pos.getX(), pos.getY(), pos.getZ(), true);
            if (dispatchItem("item_used_on_block", event.getLevel(), event.getEntity(), data)) {
                event.setCanceled(true);
            }
            return;
        }

        // Clicar segurando um bloco significa posicionar, nao usar o bloco alvo.
        if (held.getItem() instanceof BlockItem) return;

        if (dispatchBlock("block_used", event.getLevel(), event.getPos(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (dispatchBlock("block_attacked", event.getLevel(), event.getPos(), event.getEntity())) {
            event.setCanceled(true);
        }
    }

    private void onPlace(BlockEvent.EntityPlaceEvent event) {
        // Qualquer entidade posiciona blocos, mas o contrato do nucleo exige um jogador: um bloco
        // posto por pistao ou dispensador nao tem quem o tenha posto.
        if (!(event.getEntity() instanceof Player player)) return;
        if (dispatchBlock("block_placed", event.getLevel(), event.getPos(), player)) {
            event.setCanceled(true);
        }
    }

    private void onBreak(BlockEvent.BreakEvent event) {
        if (dispatchBlock("block_broken", event.getLevel(), event.getPos(), event.getPlayer())) {
            event.setCanceled(true);
        }
    }

    /** @return {@code true} se um script pediu para cancelar a acao padrao */
    private boolean dispatchBlock(String event, LevelAccessor level, BlockPos pos, Player player) {
        ServerLevel serverLevel = serverLevelOf(level);
        if (serverLevel == null || !(player instanceof ServerPlayer serverPlayer)) return false;

        BlockState state = serverLevel.getBlockState(pos);
        if (!(state.getBlock() instanceof NeoForgeDeclarativeBlock)) return false;

        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (id == null) return false;

        BlockEventData data = new BlockEventData(
                id.toString(),
                pos.getX(), pos.getY(), pos.getZ(),
                state.hasProperty(NeoForgeDeclarativeBlock.VARIANT)
                        ? state.getValue(NeoForgeDeclarativeBlock.VARIANT) : 0,
                content.variantCount(id));

        return run(serverLevel,
                lua -> lua.triggerBlock(event, new NeoForgePlayerHandle(serverPlayer), data));
    }

    // ------------------------------------------------------------------ itens

    private void onRightClickItem(PlayerInteractEvent.RightClickItem event) {
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        ItemStack held = event.getItemStack();
        if (!isDeclaredItem(held)) return;

        // Uso no ar: as coordenadas nao descrevem posicao nenhuma, e o contrato diz isso pelo
        // ultimo campo em vez de fingir que o item foi usado na origem do mundo.
        ItemEventData data = new ItemEventData(idOf(held), null, 0, 0, 0, false);
        if (dispatchItem("item_used", event.getLevel(), event.getEntity(), data)) {
            event.setCanceled(true);
        }
    }

    private boolean dispatchItem(String event, LevelAccessor level, Player player, ItemEventData data) {
        ServerLevel serverLevel = serverLevelOf(level);
        if (serverLevel == null || !(player instanceof ServerPlayer serverPlayer)) return false;

        return run(serverLevel,
                lua -> lua.triggerItem(event, new NeoForgePlayerHandle(serverPlayer), data));
    }

    private boolean isDeclaredItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && content.isDeclaredItem(id);
    }

    private static String idOf(ItemStack stack) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id == null ? "minecraft:air" : id.toString();
    }

    private static String idOf(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return id == null ? null : id.toString();
    }

    // ------------------------------------------------------------------ execucao

    /** O que o nucleo faz com o runtime; existe para os dois disparos partilharem o preparo. */
    private interface Dispatch {
        boolean run(LuaRuntime runtime);
    }

    /**
     * Prepara a ponte, dispara e devolve o mundo corrente ao estado anterior.
     *
     * <p>A ponte precisa saber em que dimensão o evento aconteceu para o script atuar no lugar
     * certo, e precisa esquecer depois: um mundo que sobra faria a próxima chamada sem contexto
     * agir na dimensão errada.
     */
    private boolean run(ServerLevel level, Dispatch dispatch) {
        LuaRuntime lua = runtime.get();
        if (lua == null) return false;

        NeoForgeGameBridge current = bridge.get();
        if (current != null) current.setCurrentLevel(level);
        try {
            return dispatch.run(lua);
        } finally {
            if (current != null) current.setCurrentLevel(null);
        }
    }

    /** Interações também chegam no cliente; o loader só reage no lado servidor. */
    private static ServerLevel serverLevelOf(LevelAccessor level) {
        return level instanceof ServerLevel serverLevel ? serverLevel : null;
    }
}
