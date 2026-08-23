package dev.lualoader.minecraft;

import dev.lualoader.LuaLoaderMod;
import dev.lualoader.platform.ItemEventData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypedActionResult;
import net.minecraft.world.World;

/**
 * Item do loader, capaz de entregar ao runtime os eventos declarados no manifesto.
 *
 * <p>Antes desta classe, itens eram registrados como {@link Item} comum: apareciam no inventário,
 * empilhavam e nada mais. Agora o manifesto pode associar lógica a cada item, do mesmo modo que já
 * fazia com blocos.
 */
public class DeclarativeItem extends Item {
    public DeclarativeItem(Settings settings) {
        super(settings);
    }

    @Override
    public TypedActionResult<ItemStack> use(World world, PlayerEntity player, Hand hand) {
        ItemStack stack = player.getStackInHand(hand);
        if (hand != Hand.MAIN_HAND) return TypedActionResult.pass(stack);

        // Cancelar aqui impede o consumo e qualquer efeito padrão do item.
        if (notifyLoader("item_used", world, player, null)) {
            return TypedActionResult.fail(stack);
        }
        return super.use(world, player, hand);
    }

    @Override
    public ActionResult useOnBlock(ItemUsageContext context) {
        if (context.getHand() != Hand.MAIN_HAND) return ActionResult.PASS;

        String targetBlock = null;
        var world = context.getWorld();
        var pos = context.getBlockPos();
        if (!world.isClient()) {
            Identifier blockId = Registries.BLOCK.getId(world.getBlockState(pos).getBlock());
            targetBlock = blockId == null ? null : blockId.toString();
        }

        if (notifyLoader("item_used_on_block", world, context.getPlayer(),
                new int[]{pos.getX(), pos.getY(), pos.getZ()}, targetBlock)) {
            return ActionResult.FAIL;
        }
        return super.useOnBlock(context);
    }

    private boolean notifyLoader(String event, World world, PlayerEntity player, int[] position) {
        return notifyLoader(event, world, player, position, null);
    }

    /**
     * Entrega o evento ao runtime.
     *
     * @return {@code true} se um script pediu para cancelar a ação padrão
     */
    private boolean notifyLoader(String event, World world, PlayerEntity player,
                                 int[] position, String targetBlock) {
        if (world == null || world.isClient()) return false;
        if (!(player instanceof ServerPlayerEntity serverPlayer)) return false;

        var runtime = LuaLoaderMod.luaRuntime();
        if (runtime == null) return false;

        Identifier id = Registries.ITEM.getId(this);
        if (id == null) return false;

        ItemEventData data = new ItemEventData(
                id.toString(),
                targetBlock,
                position == null ? 0 : position[0],
                position == null ? 0 : position[1],
                position == null ? 0 : position[2],
                position != null
        );
        return runtime.triggerItem(event, new FabricPlayerHandle(serverPlayer), data);
    }
}
