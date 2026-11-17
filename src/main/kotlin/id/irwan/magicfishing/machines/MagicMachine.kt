package id.irwan.magicfishing.machines

import id.irwan.magicfishing.icon
import io.github.thebusybiscuit.slimefun4.api.items.ItemGroup
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItem
import io.github.thebusybiscuit.slimefun4.api.items.SlimefunItemStack
import io.github.thebusybiscuit.slimefun4.api.recipes.RecipeType
import io.github.thebusybiscuit.slimefun4.core.attributes.EnergyNetComponent
import io.github.thebusybiscuit.slimefun4.core.handlers.BlockBreakHandler
import io.github.thebusybiscuit.slimefun4.core.networks.energy.EnergyNetComponentType
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils
import me.mrCookieSlime.CSCoreLibPlugin.Configuration.Config
import me.mrCookieSlime.Slimefun.Objects.handlers.BlockTicker
import me.mrCookieSlime.Slimefun.api.BlockStorage
import me.mrCookieSlime.Slimefun.api.inventory.BlockMenu
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.inventory.ItemStack
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * What a machine can make right now: one [output] per operation, and at most [maxBatch] operations
 * at once (e.g. the number of fish in the input slot). Compressed machines run up to their multiplier.
 */
class WorkResult(val output: ItemStack?, val message: String, val maxBatch: Int = 1)

/**
 * A machine that can have compressed versions (multiple machines combined into one).
 * [multiplier] is 1 for normal machines.
 */
interface Compressible {
    val multiplier: Int
}

/**
 * Electric machine whose inputs are NEVER consumed.
 * Uses [energy] J per Slimefun tick and produces 1 output every [seconds] seconds.
 *
 * Compressed versions ([multiplier] > 1) keep the same speed. When a cycle starts, the batch size is
 * fixed: min(multiplier, [WorkResult.maxBatch], free output space). Energy per tick and the output of a
 * full cycle are multiplied by that batch, so one tick does the work of up to [multiplier] machines.
 */
abstract class MagicMachine(
    group: ItemGroup,
    item: SlimefunItemStack,
    recipeType: RecipeType,
    recipe: Array<ItemStack?>,
    private val energy: Int,
    private val seconds: Int,
    protected val layout: MachineLayout,
    final override val multiplier: Int = 1
) : SlimefunItem(group, item, recipeType, recipe), EnergyNetComponent, Compressible {

    constructor(
        group: ItemGroup,
        item: SlimefunItemStack,
        recipe: Array<ItemStack?>,
        energy: Int,
        seconds: Int,
        layout: MachineLayout
    ) : this(group, item, RecipeType.ENHANCED_CRAFTING_TABLE, recipe, energy, seconds, layout, 1)

    init {
        MachineLayout.registry[id] = layout
        MachineMenu(id, itemName)

        addItemHandler(
            object : BlockTicker() {
                override fun isSynchronized(): Boolean = false
                override fun tick(b: Block, item: SlimefunItem, data: Config) {
                    process(b)
                }
            },
            object : BlockBreakHandler(false, false) {
                override fun onPlayerBreak(e: BlockBreakEvent, item: ItemStack, drops: MutableList<ItemStack>) {
                    val loc = e.block.location
                    BlockStorage.getInventory(e.block)?.dropItems(loc, *layout.inputs, *layout.outputs)
                }
            }
        )
    }

    protected abstract fun findOutput(menu: BlockMenu): WorkResult

    /**
     * Called before progress goes from [before] to [after] (out of [needed] ticks).
     * Return an error message to pause the machine, or null to continue.
     */
    protected open fun consumeProgress(menu: BlockMenu, before: Int, after: Int, needed: Int): String? = null

    /** Extra status lore lines for the current progress. */
    protected open fun statusLore(progress: Int, needed: Int): Array<String> = emptyArray()

    override fun getEnergyComponentType(): EnergyNetComponentType = EnergyNetComponentType.CONSUMER

    /** Room for 2 ticks at full batch; computed as Long and clamped so it can't overflow. */
    override fun getCapacity(): Int = (energy.toLong() * multiplier * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun ticksNeeded(): Int {
        val rate = max(1, Slimefun.getTickerTask().tickRate)
        return max(1, (seconds * 20.0 / rate).roundToInt())
    }

    /**
     * Progress (and the batch of the current cycle) is stored in Slimefun's BlockStorage, so it survives
     * restarts and chunk unloads (Aquarium pellets already used are not lost) and is removed with the block.
     */
    private fun getInt(loc: Location, key: String): Int = BlockStorage.getLocationInfo(loc, key)?.toIntOrNull() ?: 0

    private fun setInt(loc: Location, key: String, value: Int) {
        BlockStorage.addBlockInfo(loc, key, value.toString())
    }

    private fun setStatus(menu: BlockMenu, material: Material, text: String, vararg lore: String) {
        if (menu.hasViewer()) menu.replaceExistingItem(layout.statusSlot, icon(material, text, *lore))
    }

    /** How many [output]s fit in the output slots (capped at [limit]); never more than real free space. */
    private fun fitCount(menu: BlockMenu, output: ItemStack, limit: Int): Int {
        val maxStack = output.maxStackSize
        val needed = output.amount.toLong() * limit
        var space = 0L
        for (slot in layout.outputs) {
            val stack = menu.getItemInSlot(slot)
            space += when {
                stack == null || stack.type.isAir -> maxStack.toLong()
                stack.amount < maxStack && SlimefunUtils.isItemSimilar(stack, output, true, false) -> (maxStack - stack.amount).toLong()
                else -> 0L
            }
            if (space >= needed) return limit
        }
        return (space / output.amount).toInt().coerceIn(0, limit)
    }

    /** Pushes [count] times [output], split into valid stacks (never above the max stack size). */
    private fun pushOutput(menu: BlockMenu, output: ItemStack, count: Int) {
        val maxStack = output.maxStackSize
        var left = output.amount.toLong() * count
        while (left > 0) {
            val stack = output.clone()
            stack.amount = min(left, maxStack.toLong()).toInt()
            left -= stack.amount
            menu.pushItem(stack, *layout.outputs)
        }
    }

    private fun process(b: Block) {
        val menu = BlockStorage.getInventory(b) ?: return
        val loc = b.location
        val needed = ticksNeeded()

        val result = findOutput(menu)
        val output = result.output
        if (output == null) {
            if (getInt(loc, PROGRESS_KEY) != 0) setInt(loc, PROGRESS_KEY, 0)
            setStatus(menu, Material.RED_STAINED_GLASS_PANE, "&c${result.message}")
            return
        }

        // Operations allowed by the inputs right now (never more than the multiplier).
        val allowed = min(multiplier, result.maxBatch.coerceAtLeast(1))
        val current = getInt(loc, PROGRESS_KEY).coerceAtMost(needed)

        // The batch is fixed when a cycle starts; it can only shrink if inputs are removed later.
        val batch = if (current == 0) {
            val fit = fitCount(menu, output, allowed)
            if (fit <= 0) {
                setStatus(menu, Material.YELLOW_STAINED_GLASS_PANE, "&eOutput is full")
                return
            }
            if (multiplier > 1) setInt(loc, BATCH_KEY, fit)
            fit
        } else if (multiplier > 1) {
            min(getInt(loc, BATCH_KEY).coerceAtLeast(1), allowed)
        } else {
            1
        }

        val cost = (energy.toLong() * batch).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (getCharge(loc) < cost) {
            setStatus(menu, Material.ORANGE_STAINED_GLASS_PANE, "&6Not enough energy", "&7Needs $cost J per tick")
            return
        }

        val next = current + 1
        var produce = batch
        if (next >= needed) {
            produce = fitCount(menu, output, batch)
            if (produce <= 0) {
                setStatus(menu, Material.YELLOW_STAINED_GLASS_PANE, "&eOutput is full")
                return
            }
        }
        consumeProgress(menu, current, next.coerceAtMost(needed), needed)?.let {
            setStatus(menu, Material.RED_STAINED_GLASS_PANE, "&c$it", "&7Progress: &f${current * 100 / needed}%", *statusLore(current, needed))
            return
        }

        removeCharge(loc, cost)
        if (next >= needed) {
            pushOutput(menu, output, produce)
            setInt(loc, PROGRESS_KEY, 0)
        } else {
            setInt(loc, PROGRESS_KEY, next)
        }

        val now = if (next >= needed) 0 else next
        val batchLore = if (multiplier > 1) arrayOf("&7Batch: &f$batch&7/&f$multiplier") else emptyArray()
        setStatus(
            menu, Material.LIME_STAINED_GLASS_PANE, "&aWorking",
            "&7Progress: &f${now * 100 / needed}%", *batchLore, *statusLore(now, needed)
        )
    }

    companion object {
        private const val PROGRESS_KEY = "mf-progress"
        private const val BATCH_KEY = "mf-batch"
    }
}
