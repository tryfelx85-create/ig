package com.arena.spawn;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A boss bar at the top of the screen for everyone who is NOT fighting (spectators and
 * players waiting in the lobby). It shows what the fighters are voting for during the kit
 * vote, and their health and the time left during the fight. It works independently of
 * the sidebar scoreboard.
 */
public class ViewerHud {

    private static final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS);
    private static final Set<UUID> shownTo = new HashSet<>();

    public static void start(JavaPlugin plugin) {
        Bukkit.getScheduler().runTaskTimer(plugin, ViewerHud::update, 0L, 5L);
    }

    public static void clear() {
        for (UUID id : shownTo) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) p.hideBossBar(bar);
        }
        shownTo.clear();
    }

    private static void update() {
        UUID id1 = MatchManager.getPlayer1();
        UUID id2 = MatchManager.getPlayer2();
        LivingEntity e1 = id1 != null ? BotManager.get(id1) : null;
        LivingEntity e2 = id2 != null ? BotManager.get(id2) : null;

        if (!MatchManager.isMatchActive() || e1 == null || e2 == null) {
            clear();
            return;
        }

        String n1 = BotManager.nameOf(e1);
        String n2 = BotManager.nameOf(e2);

        if (!MatchManager.isFightStarted()) {
            if (!VoteManager.isResolved()) {
                bar.name(Component.text("§eKit vote §7| §f" + n1 + "§7: " + voteText(id1)
                        + " §7| §f" + n2 + "§7: " + voteText(id2) + " §7| §e" + VoteManager.getVoteSecondsLeft() + "s"));
                bar.color(BossBar.Color.YELLOW);
                bar.progress(clamp(VoteManager.getVoteSecondsLeft() / (float) Math.max(1, Settings.voteSeconds)));
            } else {
                bar.name(Component.text("§aKit chosen: §f" + VoteManager.kitName(VoteManager.getChosenKit())
                        + " §7| §f" + n1 + " §7vs §f" + n2 + " §7- starting..."));
                bar.color(BossBar.Color.GREEN);
                bar.progress(1f);
            }
        } else {
            int left = MatchFlow.getSecondsLeft();
            String time = left > 0 ? String.format(" §7| §e%d:%02d", left / 60, left % 60) : "";
            bar.name(Component.text("§f" + n1 + " §c❤" + hp(e1) + " §7vs §f" + n2 + " §c❤" + hp(e2) + time));
            bar.color(BossBar.Color.RED);
            bar.progress(left > 0 && Settings.matchTimeLimit > 0
                    ? clamp(left / (float) Settings.matchTimeLimit) : 1f);
        }

        for (Player p : Bukkit.getOnlinePlayers()) {
            boolean viewer = !MatchManager.isFighting(p.getUniqueId());
            if (viewer && shownTo.add(p.getUniqueId())) {
                p.showBossBar(bar);
            } else if (!viewer && shownTo.remove(p.getUniqueId())) {
                p.hideBossBar(bar);
            }
        }
        shownTo.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private static String voteText(UUID fighter) {
        Integer vote = fighter != null ? VoteManager.getVote(fighter) : null;
        return vote != null ? "§a" + VoteManager.kitName(vote) : "§8?";
    }

    private static String hp(LivingEntity e) {
        return String.format("%.1f", Math.max(0, e.getHealth()));
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
