package com.arena.spawn;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;

/** Freezes fighters during the countdown/pause and blocks escape commands during a match. */
public class FightRestrictionListener implements Listener {

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!MatchManager.isFighting(p.getUniqueId())) return;
        if (!MatchManager.isFrozen() && !MatchManager.isPaused()) return;

        Location from = e.getFrom();
        Location to = e.getTo();
        if (to == null || (from.getX() == to.getX() && from.getZ() == to.getZ())) return;

        Location fixed = to.clone();
        fixed.setX(from.getX());
        fixed.setZ(from.getZ());
        e.setTo(fixed);
    }

    /** Kit 3 has no food, so its fighters must not lose hunger during the match. */
    @EventHandler(ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!MatchManager.isFighting(p.getUniqueId()) || VoteManager.getChosenKit() != 3) return;
        if (e.getFoodLevel() < p.getFoodLevel()) {
            e.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent e) {
        Player p = e.getPlayer();
        if (!MatchManager.isFighting(p.getUniqueId())) return;

        String message = e.getMessage().substring(1).trim();
        if (message.isEmpty()) return;
        String label = message.split("\\s+")[0].toLowerCase();
        int colon = label.indexOf(':');
        if (colon >= 0) label = label.substring(colon + 1);

        if (Settings.blockedCommands.contains(label)) {
            e.setCancelled(true);
            p.sendMessage("§cYou can't use that command during a match.");
        }
    }
}
