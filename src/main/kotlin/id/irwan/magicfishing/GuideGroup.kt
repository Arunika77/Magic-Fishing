package id.irwan.magicfishing

import io.github.thebusybiscuit.slimefun4.api.items.groups.NestedItemGroup
import io.github.thebusybiscuit.slimefun4.api.items.groups.SubItemGroup
import io.github.thebusybiscuit.slimefun4.api.player.PlayerProfile
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuide
import io.github.thebusybiscuit.slimefun4.core.guide.SlimefunGuideMode
import io.github.thebusybiscuit.slimefun4.core.services.sounds.SoundEffect
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun
import io.github.thebusybiscuit.slimefun4.implementation.guide.SurvivalSlimefunGuide
import io.github.thebusybiscuit.slimefun4.utils.ChestMenuUtils
import io.github.thebusybiscuit.slimefun4.utils.SlimefunUtils
import me.mrCookieSlime.CSCoreLibPlugin.general.Inventory.ChestMenu
import net.md_5.bungee.api.chat.ClickEvent
import net.md_5.bungee.api.chat.HoverEvent
import net.md_5.bungee.api.chat.TextComponent
import net.md_5.bungee.api.chat.hover.content.Text
import org.bukkit.ChatColor
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

/**
 * The Magic Fishing guide category. Same layout as Slimefun's [NestedItemGroup], plus a GitHub
 * button in the top middle slot and a "Compressed Items" button in slot 40.
 *
 * With a [parent] it is a sub menu (e.g. Compressed Items): it is not listed in the Slimefun main
 * menu, it has no extra buttons and its back button returns to the parent.
 */
class GuideGroup(
    key: NamespacedKey,
    item: ItemStack,
    private val parent: GuideGroup? = null
) : NestedItemGroup(key, item) {

    private val groups = ArrayList<SubItemGroup>()

    /** The Compressed Items menu opened from slot 40 (root menu only). */
    var compressed: GuideGroup? = null

    override fun isVisible(p: Player, profile: PlayerProfile, mode: SlimefunGuideMode): Boolean =
        parent == null && mode == SlimefunGuideMode.SURVIVAL_MODE

    private fun hasVisibleGroups(p: Player): Boolean = groups.any { it.isVisibleInNested(p) }

    override fun addSubGroup(group: SubItemGroup) {
        super.addSubGroup(group)
        if (group !in groups) groups.add(group)
    }

    override fun removeSubGroup(group: SubItemGroup) {
        super.removeSubGroup(group)
        groups.remove(group)
    }

    override fun open(p: Player, profile: PlayerProfile, mode: SlimefunGuideMode) {
        openPage(p, profile, mode, 1)
    }

    private fun openPage(p: Player, profile: PlayerProfile, mode: SlimefunGuideMode, page: Int) {
        val history = profile.guideHistory
        if (mode == SlimefunGuideMode.SURVIVAL_MODE) history.add(this, page)

        val menu = ChestMenu(Slimefun.getLocalization().getMessage(p, "guide.title.main"))
        val guide = Slimefun.getRegistry().getSlimefunGuide(mode) as SurvivalSlimefunGuide

        menu.setEmptySlotsClickable(false)
        menu.addMenuOpeningHandler { pl -> SoundEffect.GUIDE_BUTTON_CLICK_SOUND.playFor(pl) }
        guide.createHeader(p, profile, menu)

        menu.addItem(
            1,
            ChestMenuUtils.getBackButton(p, "", ChatColor.GRAY.toString() + Slimefun.getLocalization().getMessage(p, "guide.back.guide"))
        ) { _, _, _, _ ->
            if (parent != null) SlimefunGuide.openItemGroup(profile, parent, mode, 1)
            else SlimefunGuide.openMainMenu(profile, mode, history.mainMenuPage)
            false
        }

        val compressedMenu = compressed?.takeIf { parent == null && it.hasVisibleGroups(p) }
        if (parent == null) {
            // GitHub button (top middle).
            menu.addItem(4, GITHUB_BUTTON) { pl, _, _, _ ->
                pl.closeInventory()
                sendGithubLink(pl)
                false
            }
        }
        if (compressedMenu != null) {
            menu.addItem(COMPRESSED_SLOT, compressedMenu.getItem(p)) { _, _, _, _ ->
                SlimefunGuide.openItemGroup(profile, compressedMenu, mode, 1)
                false
            }
        }

        // Group slots 9..44, keeping slot 40 free for the Compressed Items button.
        val slots = (9 until 9 + GROUP_SIZE).filter { compressedMenu == null || it != COMPRESSED_SLOT }
        val visible = groups.filter { it.isVisibleInNested(p) }
        val pages = maxOf(1, (visible.size + slots.size - 1) / slots.size)
        val current = page.coerceIn(1, pages)
        visible.drop((current - 1) * slots.size).take(slots.size).forEachIndexed { i, group ->
            menu.addItem(slots[i], group.getItem(p)) { _, _, _, _ ->
                SlimefunGuide.openItemGroup(profile, group, mode, 1)
                false
            }
        }

        menu.addItem(46, ChestMenuUtils.getPreviousButton(p, current, pages)) { _, _, _, _ ->
            if (current > 1) openPage(p, profile, mode, current - 1)
            false
        }
        menu.addItem(52, ChestMenuUtils.getNextButton(p, current, pages)) { _, _, _, _ ->
            if (current < pages) openPage(p, profile, mode, current + 1)
            false
        }

        menu.open(p)
    }

    companion object {
        private const val GROUP_SIZE = 36
        private const val COMPRESSED_SLOT = 40

        /** Mybezo skin head, used as the Compressed Items icon. */
        private const val COMPRESSED_TEXTURE = "82600941fab88efc41bb132d873d3b5c5f35f7720ebd9375476c1a18955bb03e"

        fun compressedIcon(): ItemStack {
            val head = SlimefunUtils.getCustomHead(COMPRESSED_TEXTURE)
            val meta = head.itemMeta ?: return head
            meta.setDisplayName(color("&aCompressed Items"))
            meta.lore = listOf("", color("&7Machines combined into one"), color("&7(x8, x16, x32, x64)"))
            head.itemMeta = meta
            return head
        }

        private val GITHUB_BUTTON: ItemStack = icon(
            Material.WRITABLE_BOOK, "&bMagic Fishing on GitHub",
            "", "&7Source code, updates, wiki", "&7and bug reports.",
            "", "&eClick &7to get the link in chat"
        )

        fun sendGithubLink(player: Player) {
            val link = TextComponent(color("&b&n$GITHUB_URL"))
            link.clickEvent = ClickEvent(ClickEvent.Action.OPEN_URL, GITHUB_URL)
            link.hoverEvent = HoverEvent(HoverEvent.Action.SHOW_TEXT, Text(color("&7Click to open")))
            player.spigot().sendMessage(TextComponent(color("&bMagic Fishing &7» ")), link)
        }
    }
}
