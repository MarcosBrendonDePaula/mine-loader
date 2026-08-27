package dev.lualoader.neoforge;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

/** Item de comida do bridge 1.21.1 com duração de uso declarada no contrato do MineLoader. */
public final class NeoForgeFoodItem extends Item {
    private final int useDuration;
    private final int burnTime;

    public NeoForgeFoodItem(Properties properties, double consumeSeconds, int burnTime) {
        super(properties);
        this.useDuration = (int) Math.ceil(consumeSeconds * 20.0);
        this.burnTime = burnTime;
    }

    @Override
    public int getUseDuration(ItemStack stack, LivingEntity user) {
        return useDuration;
    }

    @Override
    public int getBurnTime(ItemStack stack, RecipeType<?> recipeType) {
        return burnTime;
    }
}
