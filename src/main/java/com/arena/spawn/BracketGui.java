package com.arena.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Bracket screens (chest GUIs):
 *  - EDITOR (operators): the matches of round 1, add / remove / random fill / start.
 *  - PICKER (operators): choose two players (or bots) to make a match.
 *  - VIEWER (everyone): the bracket round by round with player heads, results and the live fight.
 */
public class BracketGui implements Listener {

    private enum Screen { EDITOR, PICKER, VIEWER }

    private static final int MATCHES_PER_PAGE = 5;

    private static final class Holder implements InventoryHolder {
        final Screen screen;
        Inventory inventory;
        int page;
        int round;
        String selected;
        List<String> candidates = new ArrayList<>();

        Holder(Screen screen) {
            this.screen = screen;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    // ------------------------------------------------------------ opening

    public static void openViewer(Player p) {
        Holder h = open(p, Screen.VIEWER, "§6§l⚔ Tournament Bracket");
        h.round = Math.max(0, Bracket.rounds().size() - 1);
        renderViewer(h);
    }

    public static void openEditor(Player p) {
        Holder h = open(p, Screen.EDITOR, "§6§lBracket Editor §8- Round 1");
        renderEditor(h);
    }

    private static Holder open(Player p, Screen screen, String title) {
        Holder h = new Holder(screen);
        h.inventory = Bukkit.createInventory(h, 54, legacy(title));
        p.openInventory(h.inventory);
        return h;
    }

    /** Re-draws every open viewer (called whenever the bracket changes). */
    public static void refreshAll() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            InventoryHolder holder = p.getOpenInventory().getTopInventory().getHolder();
            if (holder instanceof Holder h && h.screen == Screen.VIEWER) {
                renderViewer(h);
            }
        }
    }

    // ------------------------------------------------------------ item helpers

    private static Component legacy(String text) {
        return LegacyComponentSerializer.legacySection().deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(legacy(name));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(legacy(line));
            meta.lore(lines);
        }
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack glow(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack pane(Material material) {
        return item(material, " ");
    }

    /** Player head for an online player, a zombie head for bots and players who are offline. */
    private static ItemStack head(String name, String display, String... lore) {
        Player online = Bukkit.getPlayerExact(name);
        if (online == null) {
            return item(Material.ZOMBIE_HEAD, display, lore);
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setOwningPlayer(online);
        meta.displayName(legacy(display));
        if (lore.length > 0) {
            List<Component> lines = new ArrayList<>();
            for (String line : lore) lines.add(legacy(line));
            meta.lore(lines);
        }
        head.setItemMeta(meta);
        return head;
    }

    private static UUID uuidOf(String name) {
        LivingEntity e = BotManager.find(name);
        return e != null ? e.getUniqueId() : null;
    }

    private static String tagLine(String name) {
        UUID id = uuidOf(name);
        return id != null ? "§7Tag: §f" + TagManager.getTagText(id) : "§7Tag: §8unknown";
    }

    private static boolean isEditor(Player p) {
        return p.isOp() || p.hasPermission("arena.bracket");
    }

    // ------------------------------------------------------------ editor

    private static void renderEditor(Holder h) {
        Inventory inv = h.inventory;
        inv.clear();
        List<String[]> draft = Bracket.draft();

        for (int i = 0; i < 45 && i < draft.size(); i++) {
            String[] pair = draft.get(i);
            inv.setItem(i, item(Material.IRON_SWORD, "§e§lMatch " + (i + 1),
                    "§f" + pair[0] + " §7vs §f" + pair[1], "", "§cClick to remove"));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));

        inv.setItem(45, item(Material.LIME_DYE, "§a§lAdd match", "§7Pick two players or bots"));
        inv.setItem(46, item(Material.ENDER_EYE, "§b§lRandom fill", "§7Pair everyone who is left at random"));
        inv.setItem(47, item(Material.BARRIER, "§c§lClear all", "§7Remove every match from round 1"));
        inv.setItem(49, item(Material.BOOK, "§6§lRound 1 setup",
                "§7Matches: §f" + draft.size(), "§7Players: §f" + draft.size() * 2,
                "", "§8The other rounds are made automatically", "§8from the winners."));
        inv.setItem(52, glow(item(Material.NETHER_STAR, "§6§lStart tournament",
                "§7Locks round 1 and puts its fights in the queue",
                Bracket.isActive() ? "§cA tournament is running - use /bracket reset first" : "§aReady when you are")));
        inv.setItem(53, item(Material.OAK_DOOR, "§7Close"));
    }

    private static void clickEditor(Player p, Holder h, int slot) {
        if (slot >= 0 && slot < 45) {
            if (slot < Bracket.draft().size()) {
                Bracket.removeDraft(slot);
                renderEditor(h);
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                Holder picker = open(p, Screen.PICKER, "§6§lPick two players");
                renderPicker(picker);
            }
            case 46 -> {
                List<String> left = candidates();
                Collections.shuffle(left);
                for (int i = 0; i + 1 < left.size(); i += 2) {
                    Bracket.addDraft(left.get(i), left.get(i + 1));
                }
                if (left.size() % 2 == 1) {
                    p.sendMessage("§e" + left.get(left.size() - 1) + " was left without a pair.");
                }
                renderEditor(h);
            }
            case 47 -> {
                Bracket.clearDraft();
                renderEditor(h);
            }
            case 52 -> {
                if (Bracket.isActive()) {
                    p.sendMessage("§cA tournament is already running. Use §f/bracket reset §cfirst.");
                } else if (!Bracket.start()) {
                    p.sendMessage("§cAdd at least one match first.");
                } else {
                    p.closeInventory();
                }
            }
            case 53 -> p.closeInventory();
            default -> { }
        }
    }

    // ------------------------------------------------------------ picker

    private static boolean eligible(UUID id) {
        return !TagManager.isModers(id) && !TagManager.isSpectatorTag(id) && !TagManager.isDefeated(id);
    }

    /** Players and bots who can be put into a match and are not in one already. */
    private static List<String> candidates() {
        List<String> out = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (eligible(p.getUniqueId()) && !Bracket.draftContains(p.getName())) out.add(p.getName());
        }
        for (String bot : BotManager.names()) {
            LivingEntity e = BotManager.findBot(bot);
            if (e != null && eligible(e.getUniqueId()) && !Bracket.draftContains(bot)) out.add(bot);
        }
        return out;
    }

    private static void renderPicker(Holder h) {
        Inventory inv = h.inventory;
        inv.clear();
        h.candidates = candidates();
        int pages = Math.max(1, (h.candidates.size() + 44) / 45);
        h.page = Math.max(0, Math.min(h.page, pages - 1));

        for (int i = 0; i < 45; i++) {
            int index = h.page * 45 + i;
            if (index >= h.candidates.size()) break;
            String name = h.candidates.get(index);
            boolean selected = name.equalsIgnoreCase(h.selected);
            ItemStack shown = head(name, (selected ? "§a§l" : "§f") + name, tagLine(name),
                    selected ? "§aSelected - click another player" : "§7Click to select");
            inv.setItem(i, selected ? glow(shown) : shown);
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));
        if (h.page > 0) inv.setItem(45, item(Material.ARROW, "§e◀ Previous page"));
        inv.setItem(47, item(Material.PAPER, "§6Selected: §f" + (h.selected != null ? h.selected : "nobody"),
                "§7Click two players to make a match"));
        inv.setItem(49, item(Material.OAK_DOOR, "§7Back to the editor"));
        if (h.page < pages - 1) inv.setItem(53, item(Material.ARROW, "§eNext page ▶"));
    }

    private static void clickPicker(Player p, Holder h, int slot) {
        if (slot >= 0 && slot < 45) {
            int index = h.page * 45 + slot;
            if (index >= h.candidates.size()) return;
            String name = h.candidates.get(index);
            if (h.selected == null) {
                h.selected = name;
                renderPicker(h);
            } else if (h.selected.equalsIgnoreCase(name)) {
                h.selected = null;
                renderPicker(h);
            } else {
                Bracket.addDraft(h.selected, name);
                openEditor(p);
            }
            return;
        }
        switch (slot) {
            case 45 -> {
                if (h.page > 0) {
                    h.page--;
                    renderPicker(h);
                }
            }
            case 53 -> {
                h.page++;
                renderPicker(h);
            }
            case 49 -> openEditor(p);
            default -> { }
        }
    }

    // ------------------------------------------------------------ viewer

    private static void renderViewer(Holder h) {
        Inventory inv = h.inventory;
        inv.clear();
        for (int i = 0; i < 45; i++) inv.setItem(i, pane(Material.GRAY_STAINED_GLASS_PANE));
        for (int i = 45; i < 54; i++) inv.setItem(i, pane(Material.BLACK_STAINED_GLASS_PANE));

        List<List<Bracket.Match>> rounds = Bracket.rounds();
        if (rounds.isEmpty()) {
            inv.setItem(22, item(Material.BARRIER, "§c§lNo tournament yet",
                    "§7The bracket appears here as soon as", "§7an operator starts the tournament."));
            return;
        }

        h.round = Math.max(0, Math.min(h.round, rounds.size() - 1));
        List<Bracket.Match> matches = rounds.get(h.round);
        int pages = Math.max(1, (matches.size() + MATCHES_PER_PAGE - 1) / MATCHES_PER_PAGE);
        h.page = Math.max(0, Math.min(h.page, pages - 1));

        for (int row = 0; row < MATCHES_PER_PAGE; row++) {
            int index = h.page * MATCHES_PER_PAGE + row;
            if (index >= matches.size()) break;
            Bracket.Match m = matches.get(index);
            int base = row * 9;
            inv.setItem(base + 1, playerItem(m.a, m));
            inv.setItem(base + 2, versusItem(m));
            inv.setItem(base + 3, m.isBye() ? item(Material.BARRIER, "§7BYE", "§8No opponent") : playerItem(m.b, m));
            inv.setItem(base + 5, statusItem(m));
        }

        if (h.round > 0) inv.setItem(45, item(Material.ARROW, "§e◀ Round " + h.round));
        if (h.page > 0) inv.setItem(47, item(Material.SPECTRAL_ARROW, "§7▲ Previous page"));
        inv.setItem(49, item(Material.BOOK, "§6§lRound " + (h.round + 1) + " §7/ §f" + rounds.size(),
                "§7Matches: §f" + matches.size(), "§7Page " + (h.page + 1) + "/" + pages));
        if (h.page < pages - 1) inv.setItem(51, item(Material.SPECTRAL_ARROW, "§7▼ Next page"));
        if (h.round < rounds.size() - 1) inv.setItem(53, item(Material.ARROW, "§eRound " + (h.round + 2) + " ▶"));

        if (Bracket.getChampion() != null) {
            inv.setItem(8, glow(item(Material.NETHER_STAR, "§6§lChampion", "§e" + Bracket.getChampion())));
        }
    }

    private static ItemStack playerItem(String name, Bracket.Match m) {
        boolean won = m.winner != null && m.winner.equalsIgnoreCase(name);
        boolean lost = m.winner != null && !won;
        String status = won ? "§aAdvances" : lost ? "§cEliminated" : m.live ? "§eIn fight right now" : "§7Waiting";
        String display = won ? "§a§l" + name : lost ? "§c§m" + name : "§f§l" + name;
        ItemStack it = head(name, display, tagLine(name), status);
        return won || (m.live && m.winner == null) ? glow(it) : it;
    }

    private static ItemStack versusItem(Bracket.Match m) {
        if (m.isBye()) return item(Material.PAPER, "§7Bye");
        if (m.live) return glow(item(Material.DIAMOND_SWORD, "§e§lIN FIGHT"));
        if (m.winner != null) return item(Material.IRON_SWORD, "§7Finished");
        return item(Material.WOODEN_SWORD, "§7VS");
    }

    private static ItemStack statusItem(Bracket.Match m) {
        if (m.isBye()) return item(Material.PAPER, "§7Bye", "§8Goes straight through");
        if (m.winner != null) return item(Material.GOLD_INGOT, "§a§lWinner", "§f" + m.winner);
        if (m.live) return item(Material.BLAZE_POWDER, "§e§lLive now");
        return item(Material.CLOCK, "§7Not played yet");
    }

    private static void clickViewer(Holder h, int slot) {
        switch (slot) {
            case 45 -> {
                h.round--;
                h.page = 0;
            }
            case 53 -> {
                h.round++;
                h.page = 0;
            }
            case 47 -> h.page--;
            case 51 -> h.page++;
            default -> {
                return;
            }
        }
        renderViewer(h);
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof Holder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() == null || e.getClickedInventory() != e.getInventory()) return;

        if (h.screen != Screen.VIEWER && !isEditor(p)) {
            p.closeInventory();
            return;
        }
        int slot = e.getRawSlot();
        switch (h.screen) {
            case EDITOR -> clickEditor(p, h, slot);
            case PICKER -> clickPicker(p, h, slot);
            case VIEWER -> clickViewer(h, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof Holder) {
            e.setCancelled(true);
        }
    }
}
