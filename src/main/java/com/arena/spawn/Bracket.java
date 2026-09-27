package com.arena.spawn;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Single-elimination tournament. An operator builds round 1 in the editor GUI (the "draft"),
 * starts it, and from then on the system does the rest: every fight of the current round is put
 * in the fight queue, results are recorded when matches end, and when a round is finished the next
 * round is created from the winners (an odd player out gets a bye). A drawn fight is replayed.
 */
public final class Bracket {

    public static final class Match {
        public final String a;
        public final String b; // null = bye
        public String winner;
        public boolean live;

        Match(String a, String b) {
            this.a = a;
            this.b = b;
        }

        public boolean isBye() {
            return b == null;
        }

        boolean has(String name) {
            return name != null && (a.equalsIgnoreCase(name) || (b != null && b.equalsIgnoreCase(name)));
        }

        String canonical(String name) {
            return a.equalsIgnoreCase(name) ? a : b;
        }

        String other(String name) {
            return a.equalsIgnoreCase(name) ? b : a;
        }
    }

    private static final List<List<Match>> rounds = new ArrayList<>();
    private static final List<String[]> draft = new ArrayList<>();
    private static boolean active;
    private static String champion;

    // ------------------------------------------------------------ state for the GUI

    public static boolean isActive() {
        return active;
    }

    public static String getChampion() {
        return champion;
    }

    public static List<List<Match>> rounds() {
        return Collections.unmodifiableList(rounds);
    }

    public static List<String[]> draft() {
        return draft;
    }

    public static void addDraft(String a, String b) {
        draft.add(new String[]{a, b});
    }

    public static void removeDraft(int index) {
        if (index >= 0 && index < draft.size()) draft.remove(index);
    }

    public static void clearDraft() {
        draft.clear();
    }

    public static boolean draftContains(String name) {
        for (String[] pair : draft) {
            if (pair[0].equalsIgnoreCase(name) || pair[1].equalsIgnoreCase(name)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------ lifecycle

    /** Locks the draft as round 1 and queues its fights. Returns false if the draft is empty. */
    public static boolean start() {
        if (draft.isEmpty()) return false;

        rounds.clear();
        champion = null;
        List<Match> first = new ArrayList<>();
        for (String[] pair : draft) first.add(new Match(pair[0], pair[1]));
        rounds.add(first);
        active = true;

        TournamentManager.clear();
        for (Match m : first) TournamentManager.add(m.a, m.b);

        Bukkit.broadcast(Component.text("§6§l[Tournament] §eThe tournament has started! §7Type §f/bracket §7to see the bracket."));
        announceRound(1, first);
        TournamentManager.notifyNext();
        BracketGui.refreshAll();
        return true;
    }

    public static void reset() {
        rounds.clear();
        active = false;
        champion = null;
        TournamentManager.clear();
        BracketGui.refreshAll();
    }

    // ------------------------------------------------------------ hooks from the match flow

    /** A queued fight between these two has just begun. */
    public static void onStart(String a, String b) {
        Match m = findPending(a, b);
        if (m != null) {
            m.live = true;
            BracketGui.refreshAll();
        }
    }

    /** A fight ended with a winner and a loser (either name may be null if that player is gone). */
    public static void onResult(String winner, String loser) {
        if (!active || rounds.isEmpty()) return;
        Match m = findLive(winner, loser);
        if (m == null) m = findPending(winner, loser);
        if (m == null) return;

        String won = null;
        if (winner != null && m.has(winner)) {
            won = m.canonical(winner);
        } else if (loser != null && m.has(loser)) {
            won = m.other(loser);
        }
        if (won == null) return;

        m.winner = won;
        m.live = false;
        completeRoundIfDone();
        BracketGui.refreshAll();
    }

    /** A fight ended without a winner: it goes back to the end of the queue and is played again. */
    public static void onDraw(String a, String b) {
        if (!active) return;
        Match m = findLive(a, b);
        if (m == null) return;
        m.live = false;
        TournamentManager.add(m.a, m.b);
        Bukkit.broadcast(Component.text("§6[Tournament] §eDraw between §f" + m.a + " §eand §f" + m.b
                + "§e - the fight will be replayed."));
        BracketGui.refreshAll();
    }

    // ------------------------------------------------------------ internals

    private static Match findLive(String x, String y) {
        List<Match> current = current();
        if (current == null) return null;
        for (Match m : current) {
            if (m.winner == null && m.live && (m.has(x) || m.has(y))) return m;
        }
        return null;
    }

    private static Match findPending(String x, String y) {
        List<Match> current = current();
        if (current == null) return null;
        for (Match m : current) {
            if (m.winner == null && !m.isBye() && (m.has(x) || m.has(y))) return m;
        }
        return null;
    }

    private static List<Match> current() {
        return rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
    }

    private static void completeRoundIfDone() {
        List<Match> current = current();
        if (current == null) return;
        List<String> winners = new ArrayList<>();
        for (Match m : current) {
            if (m.winner == null) return; // round still in progress
            winners.add(m.winner);
        }

        if (winners.size() == 1) {
            champion = winners.get(0);
            active = false;
            Bukkit.broadcast(Component.text("§6§l[Tournament] §f" + champion + " §ewon the tournament!"));
            Title title = Title.title(
                    Component.text("§6§l" + champion),
                    Component.text("§eis the tournament champion!"),
                    Title.Times.times(Duration.ofMillis(400), Duration.ofSeconds(5), Duration.ofSeconds(1)));
            for (Player p : Bukkit.getOnlinePlayers()) p.showTitle(title);
            return;
        }

        List<Match> next = new ArrayList<>();
        for (int i = 0; i < winners.size(); i += 2) {
            if (i + 1 < winners.size()) {
                next.add(new Match(winners.get(i), winners.get(i + 1)));
            } else {
                Match bye = new Match(winners.get(i), null);
                bye.winner = winners.get(i);
                next.add(bye);
            }
        }
        rounds.add(next);
        for (Match m : next) {
            if (!m.isBye()) TournamentManager.add(m.a, m.b);
        }
        announceRound(rounds.size(), next);
    }

    private static void announceRound(int number, List<Match> matches) {
        Bukkit.broadcast(Component.text("§6§l[Tournament] §eRound " + number + ":"));
        for (Match m : matches) {
            Bukkit.broadcast(Component.text(m.isBye()
                    ? "  §f" + m.a + " §7has a bye and goes through"
                    : "  §f" + m.a + " §7vs §f" + m.b));
        }
    }
}
