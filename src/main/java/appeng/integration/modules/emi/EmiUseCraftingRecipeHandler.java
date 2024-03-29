package appeng.integration.modules.emi;

import appeng.core.localization.ItemModText;
import appeng.integration.modules.itemlists.CraftingHelper;
import appeng.menu.me.items.CraftingTermMenu;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.stack.EmiStack;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.NonNullList;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Recipe transfer implementation with the intended purpose of actually crafting an item. Most of the work is done
 * server-side because permission-checks and inventory extraction cannot be done client-side.
 * <p>
 * Here is how it works, depending on the various cases. In any case, we highlight missing entries in red and craftable
 * entries in blue. How the {@code +} button is rendered and what it does depends on various cases:
 * <ul>
 * <li><b>All items are present:</b> normal gray, can click + to move.</li>
 * <li><b>All items are missing:</b> red, can't click.</li>
 * <li><b>Some items are missing, all craftable:</b> blue, can click to move what's available or ctrl + click to
 * additionally schedule autocrafting of what's craftable.</li>
 * <li><b>Some items are missing, some not craftable:</b> orange, same action as above.</li>
 * </ul>
 */
public class EmiUseCraftingRecipeHandler<T extends CraftingTermMenu> extends AbstractRecipeHandler<T> {

    public EmiUseCraftingRecipeHandler(Class<T> containerClass) {
        super(containerClass);
    }

    @Override
    protected Result transferRecipe(T menu, RecipeHolder<?> holder, EmiRecipe emiRecipe, boolean doTransfer) {

        var recipeId = holder != null ? holder.id() : null;
        var recipe = holder != null ? holder.value() : null;

        boolean craftingRecipe = isCraftingRecipe(recipe, emiRecipe);
        if (!craftingRecipe) {
            return transferItemsToInventory(menu, recipeId, recipe, doTransfer);
        }

        if (!fitsIn3x3Grid(recipe, emiRecipe)) {
            return Result.createFailed(ItemModText.RECIPE_TOO_LARGE.text());
        }

        if (recipe == null) {
            recipe = createFakeCraftingRecipe(emiRecipe);
        }

        // Find missing ingredient
        var slotToIngredientMap = getGuiSlotToIngredientMap(recipe);
        var missingSlots = menu.findMissingIngredients(slotToIngredientMap);

        if (missingSlots.missingSlots().size() == slotToIngredientMap.size()) {
            // All missing, can't do much...
            return Result.createFailed(ItemModText.NO_ITEMS.text(), missingSlots.missingSlots());
        }

        if (!doTransfer) {
            if (missingSlots.anyMissingOrCraftable()) {
                // Highlight the slots with missing ingredients
                return new Result.PartiallyCraftable(missingSlots);
            }
        } else {
            // Thank you RS for pioneering this amazing feature! :)
            boolean craftMissing = AbstractContainerScreen.hasControlDown();
            CraftingHelper.performTransfer(menu, recipeId, recipe, craftMissing);
        }

        // No error
        return Result.createSuccessful();
    }

    private Result transferItemsToInventory(T menu, ResourceLocation recipeId, @Nullable Recipe<?> recipe, boolean doTransfer) {
        if (recipe == null) {
            return Result.createNotApplicable();
        }

        // Find missing ingredient
        var slotToIngredientMap = getGuiSlotToIngredientMapTransfer(recipe);
        var missingSlots = menu.findMissingIngredients(slotToIngredientMap);

        if (doTransfer) {
            boolean craftMissing = Screen.hasControlDown();
            CraftingHelper.performTransfer(menu, recipeId, recipe, craftMissing);
            // craft items and move to player inventory
        } else {
            if (missingSlots.anyMissingOrCraftable()) {
                // Highlight the slots with missing ingredients
                return new Result.PartiallyCraftable(missingSlots);
            }
        }

        return Result.createSuccessful();
    }

    private Recipe<?> createFakeCraftingRecipe(EmiRecipe display) {
        var ingredients = NonNullList.withSize(CRAFTING_GRID_WIDTH * CRAFTING_GRID_HEIGHT,
                Ingredient.EMPTY);

        for (int i = 0; i < Math.min(display.getInputs().size(), ingredients.size()); i++) {
            var ingredient = Ingredient.of(display.getInputs().get(i).getEmiStacks().stream()
                    .map(EmiStack::getItemStack)
                    .filter(is -> !is.isEmpty()));
            ingredients.set(i, ingredient);
        }

        var pattern = new ShapedRecipePattern(CRAFTING_GRID_WIDTH, CRAFTING_GRID_HEIGHT, ingredients, Optional.empty());
        return new ShapedRecipe("", CraftingBookCategory.MISC, pattern, ItemStack.EMPTY);
    }

    public static Map<Integer, Ingredient> getGuiSlotToIngredientMap(Recipe<?> recipe) {
        var ingredients = recipe.getIngredients();

        // JEI will align non-shaped recipes smaller than 3x3 in the grid. It'll center them horizontally, and
        // some will be aligned to the bottom. (i.e. slab recipes).
        int width;
        if (recipe instanceof ShapedRecipe shapedRecipe) {
            width = shapedRecipe.getWidth();
        } else {
            width = CRAFTING_GRID_WIDTH;
        }

        var result = new HashMap<Integer, Ingredient>(ingredients.size());
        for (int i = 0; i < ingredients.size(); i++) {
            var guiSlot = (i / width) * CRAFTING_GRID_WIDTH + (i % width);
            var ingredient = ingredients.get(i);
            if (!ingredient.isEmpty()) {
                result.put(guiSlot, ingredient);
            }
        }
        return result;
    }

    public static Map<Integer, Ingredient> getGuiSlotToIngredientMapTransfer(Recipe<?> recipe) {
        var ingredients = recipe.getIngredients();

        Map<Integer, Ingredient> result = new HashMap<>();
        for (int i = 0; i < ingredients.size(); i++) {
            var ingredient = ingredients.get(i);
            if (!ingredient.isEmpty()) {
                result.put(i, ingredient);
            }
        }
        return result;
    }
}
