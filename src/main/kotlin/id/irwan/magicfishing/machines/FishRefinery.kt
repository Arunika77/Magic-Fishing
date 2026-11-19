package id.irwan.magicfishing.machines

import id.irwan.magicfishing.Fishes
import id.irwan.magicfishing.Items
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup
import id.irwan.magicfishing.icon
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType
import org.bukkit.Material
import io.github.thebusybiscuit.slimefun4.core.attributes.RecipeDisplayItem
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu
import org.bukkit.inventory.ItemStack

class FishRefinery(
    group: ItemGroup,
    item: SlimefunItemStack,
    recipeType: RecipeType,
    recipe: Array<ItemStack?>,
    energy: Int,
    seconds: Double,
    multiplier: Int = 1
) : MagicMachine(group, item, recipeType, recipe, energy, seconds, LAYOUT, multiplier), RecipeDisplayItem {

    /**
     * The fish is never consumed. For compressed refineries the number of fish in the slot is the
     * number of operations per cycle (up to the multiplier): 20 fish in a x64 refinery = 20 outputs.
     */
    override fun findOutput(menu: BlockMenu): WorkResult {
        val stack = menu.getItemInSlot(layout.inputs[0])
        val fish = Items.fishOf(stack) ?: return WorkResult(null, "Insert a magic fish")
        val out = fish.output() ?: return WorkResult(null, "Output for this fish not found")
        return WorkResult(out, "", stack.amount)
    }

    override fun getDisplayRecipes(): List<ItemStack?> {
        val recipes = Fishes.all.flatMap { listOf(Items.fishItem(it), it.outputDisplay()) }
        if (multiplier <= 1) return recipes
        val info = icon(
            Material.KNOWLEDGE_BOOK, "&aMultiplier: x$multiplier",
            "", "&7Combines &f$multiplier &7refineries into one.",
            "&7Each fish in the input slot is one", "&7operation, up to &f$multiplier &7per cycle."
        )
        return listOf(info, null) + recipes
    }

    companion object {
        /*
         * 6-row layout:
         *  B B B G G G G G G
         *  B F B G o o o o G
         *  B B B G o o o o G
         *  O O O G o o o o G
         *  O S O G o o o o G
         *  O O O G G G G G G
         * B = fish border, F = fish, O = status border, S = status, G = output border, o = output
         */
        val LAYOUT = MachineLayout(
            inputs = intArrayOf(10),
            outputs = intArrayOf(13, 14, 15, 16, 22, 23, 24, 25, 31, 32, 33, 34, 40, 41, 42, 43),
            statusSlot = 37,
            decorations = MachineLayout.decorations(
                MachineLayout.ring(10) to MachineLayout.FISH_BORDER,
                MachineLayout.ring(37) to MachineLayout.STATUS_BORDER,
                intArrayOf(3, 4, 5, 6, 7, 8, 12, 17, 21, 26, 30, 35, 39, 44, 48, 49, 50, 51, 52, 53) to MachineLayout.OUTPUT_BORDER
            )
        )
    }
}
