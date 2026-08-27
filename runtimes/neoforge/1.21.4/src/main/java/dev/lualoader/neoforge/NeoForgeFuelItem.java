package dev.lualoader.neoforge;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.FuelValues;

/** Item NeoForge que expõe apenas o tempo de queima declarado pelo manifesto. */
final class NeoForgeFuelItem extends Item {
    private final int burnTime;

    NeoForgeFuelItem(Properties properties, int burnTime) {
        super(properties);
        this.burnTime = burnTime;
    }

    @Override
    public int getBurnTime(ItemStack stack, RecipeType<?> recipeType, FuelValues fuelValues) {
        return burnTime;
    }
}
