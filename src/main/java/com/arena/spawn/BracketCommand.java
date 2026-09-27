package com.arena.spawn;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/** /bracket [view] - everyone; /bracket edit and /bracket reset - operators. */
public class BracketCommand implements TabExecutor {

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Only players can use this command.");
            return true;
        }
        String sub = args.length > 0 ? args[0].toLowerCase() : "view";

        switch (sub) {
            case "view" -> BracketGui.openViewer(p);
            case "edit" -> {
                if (!isEditor(p)) {
                    p.sendMessage("§cYou don't have permission to edit the bracket.");
                    return true;
                }
                BracketGui.openEditor(p);
            }
            case "reset" -> {
                if (!isEditor(p)) {
                    p.sendMessage("§cYou don't have permission to reset the bracket.");
                    return true;
                }
                Bracket.reset();
                p.sendMessage("§aThe tournament was reset and the fight queue cleared.");
            }
            default -> p.sendMessage("§cUsage: /bracket [view" + (isEditor(p) ? " | edit | reset" : "") + "]");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            out.add("view");
            if (sender.isOp() || sender.hasPermission("arena.bracket")) {
                out.add("edit");
                out.add("reset");
            }
            out.removeIf(s -> !s.startsWith(args[0].toLowerCase()));
        }
        return out;
    }

    private boolean isEditor(Player p) {
        return p.isOp() || p.hasPermission("arena.bracket");
    }
}
