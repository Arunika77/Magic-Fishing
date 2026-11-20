package id.irwan.magicfishing

import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import org.bukkit.Bukkit
import org.bukkit.inventory.ItemStack

/**
 * NetworksExpansion (plugin "Networks") encoders build their recipe lists once, from the Slimefun
 * items that are enabled at that moment. Depending on load order, Magic Fishing's recipes can be
 * missing, so blueprints can't be encoded. This adds our recipes to those lists (by reflection, no
 * hard dependency) once the server has fully started, skipping recipes that are already there.
 */
object NetworksCompat {

    private const val HELPERS = "com.balugaq.netex.api.helpers"

    /** Slimefun recipe type -> NetworksExpansion recipe list class. */
    private val TARGETS = mapOf(
        RecipeType.ENHANCED_CRAFTING_TABLE to "$HELPERS.SupportedCraftingTableRecipes",
        RecipeType.MAGIC_WORKBENCH to "$HELPERS.SupportedMagicWorkbenchRecipes",
        RecipeType.COMPRESSOR to "$HELPERS.SupportedCompressorRecipes"
    )

    /** Delay after start-up, so Slimefun and every addon have finished loading their items. */
    private const val DELAY_TICKS = 200L

    fun schedule(plugin: MagicFishing) {
        Bukkit.getScheduler().runTaskLater(plugin, Runnable { register(plugin) }, DELAY_TICKS)
    }

    private fun register(plugin: MagicFishing) {
        if (Bukkit.getPluginManager().getPlugin("Networks")?.isEnabled != true) return
        var added = 0
        try {
            val ourItems = Slimefun.getRegistry().allSlimefunItems.filter { it.addon === Addon && !it.isDisabled }
            for ((type, className) in TARGETS) {
                val items = ourItems.filter { it.recipeType == type }
                if (items.isEmpty()) continue
                val clazz = try {
                    Class.forName(className)
                } catch (_: ClassNotFoundException) {
                    continue
                }
                @Suppress("UNCHECKED_CAST")
                val recipes = clazz.getMethod("getRecipes").invoke(null) as Map<Array<ItemStack?>, ItemStack>
                val addRecipe = clazz.getMethod("addRecipe", Array<ItemStack>::class.java, ItemStack::class.java)
                val present = recipes.values.mapNotNullTo(HashSet()) { SlimefunItem.getByItem(it)?.id }

                for (item in items) {
                    if (item.id in present) continue
                    val input = arrayOfNulls<ItemStack>(9)
                    item.recipe.take(9).forEachIndexed { i, stack -> input[i] = stack?.clone() }
                    addRecipe.invoke(null, input, item.recipeOutput.clone())
                    added++
                }
            }
        } catch (e: Exception) {
            plugin.logger.warning("Could not add Magic Fishing recipes to Networks encoders: ${e.javaClass.simpleName}: ${e.message}")
            return
        }
        if (added > 0) plugin.logger.info("Added $added Magic Fishing recipes to Networks encoders.")
    }
}
