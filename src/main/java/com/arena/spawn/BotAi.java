package com.arena.spawn;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.ThrownPotion;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionType;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Adaptive melee bot. There is no fixed "if health < X then heal" script: every few ticks the bot
 * scores every option it currently has (approach, circle-strafe, back off to recover, and every item
 * of its kit) from what it perceives - distance, both health values, how fast it is losing or winning
 * the damage race, whether it is burning or webbed - and from a running model of THIS opponent
 * (how aggressive they are, how often they block, whether they use a bow), then does the best one.
 *
 * Skill (the difficulty level) does not unlock or lock items. Every level can use every item of its
 * kit; a higher level perceives the danger earlier, has less noise in its choices, decides more often,
 * reacts and turns faster, misses less and times its jump-crits better.
 *
 * Human-like combat: it never snaps onto the target, only swings when roughly facing it and after a
 * reaction delay, jump-crits, strafes (changing direction when hit), backs off after a hit to space
 * itself, and retreats to recover with items or regeneration when it is losing.
 *
 * Kit items: 1/2 steaks (+ kit 2 shield and axe), 3 splash healing, 4 golden apples, bow, cobweb,
 * lava, water, cobblestone, pickaxe, 5 buff potions, fire resistance, pearls, potions, apples, totem.
 * Bots never heal by themselves: everything comes from an item the bot visibly holds and uses.
 * Outside a match, and with the level OFF, bots stay frozen, invulnerable dummies.
 */
public class BotAi implements Listener {

    public enum Level {
        OFF(0.0, 0.0),
        EASY(0.35, 0.19),
        NORMAL(0.65, 0.21),
        HARD(0.95, 0.23);

        final double skill;
        final double speed;

        Level(double skill, double speed) {
            this.skill = skill;
            this.speed = speed;
        }
    }

    private enum Stance { APPROACH, CIRCLE, BACKOFF }

    private static final class Option {
        final double score;
        final Runnable run;

        Option(double score, Runnable run) {
            this.score = score;
            this.run = run;
        }
    }

    private static class State {
        int kit;
        int steaks, gapples, healPots, arrows, webs, lava, water, pearls, cobble;
        boolean speedPot, strengthPot, fireResPot, totem, pickaxe;
        long nextHealTick, nextBowTick, nextWebTick, nextLavaTick, nextPearlTick, nextBlockTick, nextCobbleTick;

        // opponent model (running averages)
        double oppAggression = 0.5;
        double oppBlockRate;
        double oppRangedRate;
        double incoming;
        double outgoing;
        Vector lastOppPos;

        // behaviour
        Stance stance = Stance.APPROACH;
        long nextDecisionTick;
        int circleDir = 1;
        long nextCircleSwitch;
        long spacingUntil;
        boolean critPlan = true;
        long critDeadline;
        long nextJumpTick;
        long nextAttackTick;
        long blockingUntilTick;
        long usingUntilTick;
        boolean using;
        boolean itemShown;
        long showTick;
        long effectTick;
        ItemStack shownItem;
        Runnable pending;
        ItemStack weapon;
        long inReachSinceTick = -1;
        long noControlUntilMs;
        boolean immovable = true;
    }

    private static Level level = Level.NORMAL;
    private static long tick;
    private static final Map<UUID, State> states = new HashMap<>();

    public static void start(JavaPlugin plugin) {
        plugin.getServer().getScheduler().runTaskTimer(plugin, BotAi::tick, 1L, 1L);
    }

    public static Level getLevel() {
        return level;
    }

    public static boolean setLevel(String name) {
        try {
            level = Level.valueOf(name.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Called when a bot is dressed for a match: stocks its virtual inventory with what the kit contains. */
    public static void setKit(UUID id, int kit) {
        State s = new State();
        s.kit = kit;
        switch (kit) {
            case 1, 2 -> s.steaks = 6;
            case 3 -> s.healPots = 35;
            case 4 -> {
                s.gapples = 8;
                s.arrows = 32;
                s.webs = 8;
                s.lava = 2;
                s.water = 2;
                s.cobble = 128;
                s.pickaxe = true;
            }
            case 5 -> {
                s.gapples = 16;
                s.healPots = 27;
                s.pearls = 16;
                s.speedPot = true;
                s.strengthPot = true;
                s.fireResPot = true;
                s.totem = true;
            }
            default -> { }
        }
        states.put(id, s);
    }

    public static void reset(UUID id) {
        states.remove(id);
    }

    // ------------------------------------------------------------------ events

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!BotManager.isBot(id)) return;
        State s = states.computeIfAbsent(id, k -> new State());
        s.noControlUntilMs = System.currentTimeMillis() + 350;
        s.incoming += e.getFinalDamage();
        if (ThreadLocalRandom.current().nextDouble() < 0.4 + 0.4 * level.skill) {
            s.circleDir = -s.circleDir; // dodge: change strafing direction after being hit
        }
    }

    /** Totem of undying (kit 5): a lethal hit is cancelled once and the bot gets the totem effects. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!BotManager.isBot(id) || !(e.getEntity() instanceof LivingEntity bot)) return;
        State s = states.get(id);
        if (s == null || !s.totem || e.getFinalDamage() < bot.getHealth() + bot.getAbsorptionAmount()) return;

        e.setCancelled(true);
        s.totem = false;
        bot.setHealth(1.0);
        bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 900, 1));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 100, 1));
        bot.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 800, 0));
    }

    /** Shield (kit 2): hits landing while the shield is raised do nothing, unless the attacker uses an axe. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onShieldHit(EntityDamageByEntityEvent e) {
        UUID id = e.getEntity().getUniqueId();
        if (!BotManager.isBot(id)) return;
        State s = states.get(id);
        if (s == null || s.kit != 2 || tick >= s.blockingUntilTick) return;

        if (e.getDamager() instanceof LivingEntity attacker && attacker.getEquipment() != null
                && attacker.getEquipment().getItemInMainHand().getType().name().endsWith("_AXE")) {
            s.blockingUntilTick = 0;
            s.nextBlockTick = tick + 100;
            return;
        }
        e.setCancelled(true);
    }

    // ------------------------------------------------------------------ main loop

    private static void tick() {
        tick++;
        for (UUID id : BotManager.ids()) {
            LivingEntity bot = BotManager.get(id);
            if (bot == null || bot.isDead()) continue;

            boolean active = level != Level.OFF
                    && MatchManager.isFighting(id)
                    && MatchManager.isFightStarted()
                    && !MatchManager.isPaused()
                    && !MatchManager.isFrozen();

            State s = states.computeIfAbsent(id, k -> new State());
            bot.setInvulnerable(!MatchManager.isFighting(id)); // lobby bots cannot be hurt
            if (s.immovable == active) {
                s.immovable = !active;
                BotManager.setImmovable(bot, s.immovable);
            }
            if (!active) {
                keepStill(bot, id);
                s.inReachSinceTick = -1;
                continue;
            }

            UUID p1 = MatchManager.getPlayer1();
            UUID p2 = MatchManager.getPlayer2();
            UUID otherId = id.equals(p1) ? p2 : p1;
            LivingEntity target = otherId != null ? BotManager.get(otherId) : null;
            if (target == null || target.isDead() || !target.getWorld().equals(bot.getWorld())) continue;

            finishUse(bot, s);
            act(bot, target, s);
        }
    }

    /** Outside a fight a bot never walks: no sideways motion, and it is put back at its spot if it was moved. */
    private static void keepStill(LivingEntity bot, UUID id) {
        Vector v = bot.getVelocity();
        if (v.getX() != 0 || v.getZ() != 0) {
            v.setX(0);
            v.setZ(0);
            bot.setVelocity(v);
        }
        Location home = BotManager.home(id);
        if (home != null && !MatchManager.isFighting(id) && home.getWorld().equals(bot.getWorld())
                && bot.getLocation().distanceSquared(home) > 2.25) {
            bot.teleport(home);
        }
    }

    // ------------------------------------------------------------------ perception

    /** Everything the bot knows about the situation this tick. */
    private static final class View {
        double dist;
        double aimError;
        double botHp, botMax, oppHp;
        double oppFacingMe;
        boolean oppBlocking, oppHasAxe, oppBow;
        boolean burning, inWeb, onGround;
        boolean regenerating;
        double closingSpeed;
    }

    private static View perceive(LivingEntity bot, LivingEntity target, State s, double aimError) {
        View v = new View();
        Location bl = bot.getLocation();
        Location tl = target.getLocation();
        v.dist = bl.distance(tl);
        v.aimError = aimError;
        v.botHp = bot.getHealth();
        v.botMax = bot.getMaxHealth();
        v.oppHp = target.getHealth();
        v.burning = bot.getFireTicks() > 0;
        v.onGround = bot.isOnGround();
        v.inWeb = bl.getBlock().getType() == Material.COBWEB;
        v.regenerating = bot.hasPotionEffect(PotionEffectType.REGENERATION) || bot.getAbsorptionAmount() > 0;

        if (target instanceof Player p) {
            v.oppBlocking = p.isBlocking();
        }
        EntityEquipment eq = target.getEquipment();
        if (eq != null) {
            Material hand = eq.getItemInMainHand().getType();
            v.oppHasAxe = hand.name().endsWith("_AXE");
            v.oppBow = hand == Material.BOW || hand == Material.CROSSBOW;
        }

        Vector oppLook = target.getEyeLocation().getDirection().setY(0);
        Vector toBot = bl.toVector().subtract(tl.toVector()).setY(0);
        v.oppFacingMe = oppLook.lengthSquared() > 0 && toBot.lengthSquared() > 0
                ? Math.toDegrees(oppLook.angle(toBot)) : 90;

        Vector oppPos = tl.toVector();
        if (s.lastOppPos != null && toBot.lengthSquared() > 0) {
            Vector moved = oppPos.clone().subtract(s.lastOppPos).setY(0);
            v.closingSpeed = moved.dot(toBot.clone().normalize());
        }
        s.lastOppPos = oppPos;
        return v;
    }

    /** Updates what the bot has learned about this particular opponent. */
    private static void learn(State s, View v) {
        double a = 0.015;
        s.oppAggression += ((v.closingSpeed > 0.04 || v.dist < 3.2) ? 1.0 - s.oppAggression : -s.oppAggression) * a;
        s.oppBlockRate += ((v.oppBlocking ? 1.0 : 0.0) - s.oppBlockRate) * a * 2;
        s.oppRangedRate += ((v.oppBow ? 1.0 : 0.0) - s.oppRangedRate) * a * 2;
        s.incoming *= 0.985;
        s.outgoing *= 0.985;
    }

    // ------------------------------------------------------------------ acting

    private static void act(LivingEntity bot, LivingEntity target, State s) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double skill = level.skill;
        Location bl = bot.getLocation();
        Location tl = target.getLocation();
        Vector flat = tl.toVector().subtract(bl.toVector());
        flat.setY(0);
        double flatDist = flat.length();
        if (flatDist < 0.01) return;
        boolean busy = tick < s.usingUntilTick;

        // Turn toward the target at a limited, slightly noisy rate instead of snapping onto it
        double wantedYaw = Math.toDegrees(Math.atan2(-flat.getX(), flat.getZ()));
        double wantedPitch = -Math.toDegrees(Math.atan2(
                target.getEyeLocation().getY() - bot.getEyeLocation().getY(), flatDist));
        double yawDiff = wrap180(wantedYaw - bl.getYaw());
        double pitchDiff = wantedPitch - bl.getPitch();
        double turn = (14 + 30 * skill) * (0.7 + 0.6 * rng.nextDouble());
        float newYaw = (float) (bl.getYaw() + Math.max(-turn, Math.min(turn, yawDiff)));
        float newPitch = (float) (bl.getPitch() + Math.max(-turn / 2, Math.min(turn / 2, pitchDiff)));
        bot.setRotation(newYaw, newPitch);
        double aimError = Math.abs(wrap180(wantedYaw - newYaw));

        View view = perceive(bot, target, s, aimError);
        learn(s, view);

        // Decide what to do, several times a second (faster on higher skill)
        if (!busy && tick >= s.nextDecisionTick) {
            s.nextDecisionTick = tick + Math.round(9 - 7 * skill) + rng.nextInt(3);
            decide(bot, target, s, view, rng);
            busy = tick < s.usingUntilTick;
        }

        move(bot, s, view, newYaw, busy, rng);
        fight(bot, target, s, view, busy, rng);
    }

    // ------------------------------------------------------------------ decision making

    private static void decide(LivingEntity bot, LivingEntity target, State s, View v, ThreadLocalRandom rng) {
        double skill = level.skill;
        double noise = 0.30 * (1 - skill) + 0.03;
        double aw = 0.55 + 0.45 * skill; // how reliably the bot notices that an item is worth using

        double hpFrac = v.botHp / v.botMax;
        double winning = (s.outgoing + 1.0) / (s.outgoing + s.incoming + 2.0);
        double expectedIncoming = s.incoming * 0.4;
        double danger = clamp01(1.0 - (v.botHp - expectedIncoming) / (v.botMax * 0.55));
        danger = clamp01(danger * (0.8 + 0.4 * (1.0 - winning)));
        if (hpFrac > 0.85) danger = 0;

        boolean healItem = s.healPots > 0 || s.gapples > 0 || s.steaks > 0;
        double far = clamp01((v.dist - 4.0) / 8.0);
        double aggr = s.oppAggression;

        List<Option> opts = new ArrayList<>();

        // --- how to move
        double approach = v.dist > 2.4 ? 0.55 + 0.2 * (1.0 - aggr) + 0.2 * (winning - 0.5) : 0.15;
        approach *= 1.0 - 0.7 * danger;
        opts.add(new Option(approach, () -> s.stance = Stance.APPROACH));

        double circle = v.dist < 4.6 ? 0.5 + 0.25 * aggr + 0.2 * (1.0 - winning) + (v.oppFacingMe < 35 ? 0.12 : 0) : 0.05;
        opts.add(new Option(circle, () -> s.stance = Stance.CIRCLE));

        double back = v.dist < 7 ? danger * 0.95 * (v.regenerating ? 1.0 : 0.65) * (healItem ? 0.55 : 1.0) : 0.05;
        opts.add(new Option(back, () -> s.stance = Stance.BACKOFF));

        // --- healing: the most suitable item for the moment (fast ones when the opponent is close)
        if (danger > 0 && tick >= s.nextHealTick) {
            double near = v.dist < 5 ? aggr : 0;
            if (s.healPots > 0) {
                opts.add(new Option(danger * aw * 1.0, () -> useHealPotion(bot, s)));
            }
            if (s.gapples > 0) {
                opts.add(new Option(danger * aw * (0.85 + 0.25 * far - 0.35 * near), () -> eatGoldenApple(bot, s)));
            }
            if (s.steaks > 0) {
                opts.add(new Option(danger * aw * (0.6 + 0.25 * far - 0.35 * near), () -> eatSteak(bot, s)));
            }
        }

        // --- emergencies
        if (v.burning) {
            if (s.fireResPot) {
                opts.add(new Option(0.95 * aw, () -> {
                    s.fireResPot = false;
                    drink(bot, s, PotionType.LONG_FIRE_RESISTANCE, () ->
                            bot.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 3600, 0)));
                }));
            }
            if (s.water > 0 && bot.getLocation().getBlock().getType().isAir()) {
                opts.add(new Option(0.95 * aw, () -> useWater(bot, s)));
            }
        }
        if (v.inWeb && s.pickaxe) {
            opts.add(new Option(0.98, () -> clearWeb(bot, s)));
        }

        // --- buffs and mobility
        if (s.speedPot) {
            opts.add(new Option(aw * 0.9 * clamp01((v.dist - 6) / 10), () -> {
                s.speedPot = false;
                drink(bot, s, PotionType.STRONG_SWIFTNESS, () ->
                        bot.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 3600, 1)));
            }));
        }
        if (s.strengthPot) {
            opts.add(new Option(aw * 0.9 * clamp01((v.dist - 6) / 10), () -> {
                s.strengthPot = false;
                drink(bot, s, PotionType.STRONG_STRENGTH, () ->
                        bot.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 3600, 1)));
            }));
        }
        if (s.pearls > 0 && tick >= s.nextPearlTick && v.dist > 10) {
            opts.add(new Option(aw * clamp01((v.dist - 10) / 14) * (0.6 + 0.4 * (1.0 - s.oppRangedRate)),
                    () -> usePearl(bot, target, s)));
        }

        // --- offence with kit items
        if (s.arrows > 0 && tick >= s.nextBowTick && v.dist > 6 && v.dist < 26 && v.aimError < 25) {
            opts.add(new Option(aw * (0.35 + 0.35 * clamp01((v.dist - 6) / 12) + 0.25 * (1.0 - aggr)),
                    () -> useBow(bot, target, s, rng)));
        }
        if (s.webs > 0 && tick >= s.nextWebTick && v.dist > 2.5 && v.dist < 5.5) {
            opts.add(new Option(aw * (0.25 + 0.35 * aggr + (v.oppFacingMe < 40 ? 0.1 : 0)),
                    () -> useWeb(bot, target, s)));
        }
        if (s.lava > 0 && tick >= s.nextLavaTick && v.dist > 3 && v.dist < 6.5 && v.botHp > 10) {
            opts.add(new Option(aw * skill * (0.15 + 0.3 * aggr), () -> useLava(bot, target, s)));
        }
        if (s.cobble > 0 && tick >= s.nextCobbleTick && v.dist > 4 && (s.oppRangedRate > 0.4 || danger > 0.6)) {
            opts.add(new Option(aw * (0.3 + 0.4 * s.oppRangedRate), () -> useCobble(bot, target, s)));
        }

        // --- defence with the shield (kit 2); useless against an axe
        if (s.kit == 2 && tick >= s.nextBlockTick && v.dist < 4.2 && !v.oppHasAxe) {
            double attackSoon = 0.25 + 0.5 * aggr * (v.oppFacingMe < 40 ? 1.0 : 0.4);
            opts.add(new Option(aw * attackSoon * (v.oppBlocking ? 0.3 : 1.0), () -> {
                s.nextBlockTick = tick + 30 + ThreadLocalRandom.current().nextInt(20);
                s.blockingUntilTick = tick + 12;
            }));
        }

        Option best = null;
        double bestScore = 0.12;
        for (Option o : opts) {
            double score = o.score + (rng.nextDouble() - 0.5) * noise;
            if (score > bestScore) {
                bestScore = score;
                best = o;
            }
        }
        if (best != null) best.run.run();
    }

    // ------------------------------------------------------------------ movement and fighting

    /** Forward / sideways input relative to where the bot is facing, chosen by the current stance. */
    private static void move(LivingEntity bot, State s, View v, float yaw, boolean busy, ThreadLocalRandom rng) {
        if (System.currentTimeMillis() < s.noControlUntilMs) return; // let knockback happen

        if (tick >= s.nextCircleSwitch) {
            s.nextCircleSwitch = tick + 10 + rng.nextInt(18);
            if (rng.nextDouble() < 0.5) s.circleDir = -s.circleDir;
        }
        Stance stance = tick < s.spacingUntil ? Stance.BACKOFF : s.stance;

        double f;
        double r;
        switch (stance) {
            case CIRCLE -> {
                r = s.circleDir;
                f = v.dist > 3.2 ? 0.6 : (v.dist < 2.0 ? -0.5 : 0.1);
            }
            case BACKOFF -> {
                f = -1.0;
                r = 0.3 * s.circleDir;
            }
            default -> {
                f = v.dist < 4.0 && rng.nextDouble() < 0.1 ? 0.2 : (v.dist < 4.0 ? 0.75 : 1.0); // slight hesitation up close
                r = v.dist < 5 ? 0.15 * s.circleDir : 0;
            }
        }
        if (v.dist <= 2.0 && f > 0) f = 0; // do not walk into the opponent

        double speed = level.speed * (busy ? 0.35 : 1.0);
        PotionEffect speedEffect = bot.getPotionEffect(PotionEffectType.SPEED);
        if (speedEffect != null) speed *= 1.0 + 0.2 * (speedEffect.getAmplifier() + 1);
        if (v.inWeb) speed *= 0.2;

        double yawRad = Math.toRadians(yaw);
        Vector forward = new Vector(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vector right = new Vector(-forward.getZ(), 0, forward.getX());
        Vector velocity = bot.getVelocity();
        velocity.setX((forward.getX() * f + right.getX() * r) * speed);
        velocity.setZ((forward.getZ() * f + right.getZ() * r) * speed);
        if (v.onGround && f > 0 && blockedAhead(bot.getLocation(), forward)) {
            velocity.setY(0.42);
        }
        bot.setVelocity(velocity);
    }

    private static void fight(LivingEntity bot, LivingEntity target, State s, View v, boolean busy,
                              ThreadLocalRandom rng) {
        double skill = level.skill;
        double reach = 2.6 + 0.45 * skill;
        boolean inReach = v.dist <= reach;
        if (!inReach) {
            s.inReachSinceTick = -1;
        } else if (s.inReachSinceTick < 0) {
            s.inReachSinceTick = tick;
        }
        int reaction = (int) Math.round(7 - 5 * skill);
        boolean reacted = s.inReachSinceTick >= 0 && tick - s.inReachSinceTick >= reaction;
        boolean shielding = tick < s.blockingUntilTick;

        // Jump for a critical hit just before the next swing is ready
        boolean crit = !v.onGround && bot.getVelocity().getY() < 0;
        if (s.critPlan && inReach && v.onGround && !busy && s.nextAttackTick - tick <= 6 && tick >= s.nextJumpTick) {
            Vector jump = bot.getVelocity();
            jump.setY(0.42);
            bot.setVelocity(jump);
            s.critDeadline = tick + 14;
            s.nextJumpTick = tick + 25;
        }
        boolean waitingForCrit = s.critPlan && tick < s.critDeadline && !crit;

        double aimTolerance = 35 - 18 * skill;
        boolean retreating = s.stance == Stance.BACKOFF && v.dist > 2.2;
        if (!busy && !shielding && !retreating && inReach && reacted && v.aimError <= aimTolerance
                && !waitingForCrit && tick >= s.nextAttackTick) {
            s.nextAttackTick = tick + Math.round(15 - 4 * skill) + rng.nextInt((int) Math.round(7 - 4 * skill) + 1);
            bot.swingMainHand();
            if (rng.nextDouble() >= 0.30 - 0.25 * skill) {
                strike(bot, target, s, crit, v);
            }
            s.critPlan = rng.nextDouble() < skill * 0.9;
            if (rng.nextDouble() < skill * 0.5) {
                s.spacingUntil = tick + 4 + rng.nextInt(4); // step back after the hit to keep spacing
            }
        }
    }

    private static void strike(LivingEntity bot, LivingEntity target, State s, boolean crit, View v) {
        ItemStack weapon = bot.getEquipment() != null ? bot.getEquipment().getItemInMainHand() : null;
        double damage;
        boolean axeAgainstShield = s.kit == 2 && target instanceof Player tp && tp.isBlocking();
        if (axeAgainstShield) {
            damage = 9.0;
            ((Player) target).setCooldown(Material.SHIELD, 100);
            ((Player) target).clearActiveItem();
        } else {
            damage = damageOf(weapon);
        }
        PotionEffect strength = bot.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null) damage += 3.0 * (strength.getAmplifier() + 1);
        if (crit) damage *= 1.5;

        target.damage(damage, bot);
        s.outgoing += damage;
        int fire = weapon != null ? weapon.getEnchantmentLevel(Enchantment.FIRE_ASPECT) : 0;
        if (fire > 0) target.setFireTicks(80 * fire);
    }

    // ------------------------------------------------------------------ items

    private static void useHealPotion(LivingEntity bot, State s) {
        s.healPots--;
        s.nextHealTick = tick + 20;
        ItemStack potion = potionStack(Material.SPLASH_POTION, PotionType.STRONG_HEALING);
        startUse(bot, s, potion, 6, () -> {
            ThrownPotion thrown = bot.launchProjectile(ThrownPotion.class, new Vector(0, -0.4, 0));
            thrown.setItem(potion);
            ArenaBlockListener.trackEntity(thrown);
        });
    }

    private static void eatGoldenApple(LivingEntity bot, State s) {
        s.gapples--;
        s.nextHealTick = tick + 20;
        startUse(bot, s, new ItemStack(Material.GOLDEN_APPLE), 32, () -> {
            bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 1));
            bot.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, 2400, 0));
        });
    }

    private static void eatSteak(LivingEntity bot, State s) {
        s.steaks--;
        s.nextHealTick = tick + 20;
        startUse(bot, s, new ItemStack(Material.COOKED_BEEF), 32, () ->
                bot.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 200, 0)));
    }

    private static void drink(LivingEntity bot, State s, PotionType type, Runnable effect) {
        startUse(bot, s, potionStack(Material.POTION, type), 32, effect);
    }

    private static void useWater(LivingEntity bot, State s) {
        s.water--;
        startUse(bot, s, new ItemStack(Material.WATER_BUCKET), 6, () -> {
            Block feet = bot.getLocation().getBlock();
            if (feet.getType().isAir()) {
                ArenaBlockListener.trackBlock(feet);
                feet.setType(Material.WATER);
            }
            bot.setFireTicks(0);
        });
    }

    private static void clearWeb(LivingEntity bot, State s) {
        startUse(bot, s, new ItemStack(Material.DIAMOND_PICKAXE), 8, () -> {
            Block feet = bot.getLocation().getBlock();
            if (feet.getType() == Material.COBWEB) {
                ArenaBlockListener.trackBlock(feet);
                feet.setType(Material.AIR);
            }
        });
    }

    private static void usePearl(LivingEntity bot, LivingEntity target, State s) {
        s.pearls--;
        s.nextPearlTick = tick + 300;
        startUse(bot, s, new ItemStack(Material.ENDER_PEARL), 6, () -> {
            Vector to = target.getLocation().toVector().subtract(bot.getLocation().toVector());
            double range = Math.sqrt(to.getX() * to.getX() + to.getZ() * to.getZ());
            double speed = 1.5;
            double angle = 0.5 * Math.asin(Math.min(1.0, range * 0.03 / (speed * speed)));
            Vector horizontal = new Vector(to.getX(), 0, to.getZ()).normalize();
            Vector velocity = horizontal.multiply(speed * Math.cos(angle)).setY(speed * Math.sin(angle) + 0.1);
            EnderPearl pearl = bot.launchProjectile(EnderPearl.class, velocity);
            ArenaBlockListener.trackEntity(pearl);
        });
    }

    private static void useBow(LivingEntity bot, LivingEntity target, State s, ThreadLocalRandom rng) {
        s.arrows--;
        s.nextBowTick = tick + 40;
        startUse(bot, s, new ItemStack(Material.BOW), 20, () -> {
            Vector aim = target.getEyeLocation().toVector().subtract(bot.getEyeLocation().toVector());
            double dist = aim.length();
            aim.setY(aim.getY() + dist * 0.03); // compensate arrow drop
            aim.normalize().multiply(2.8);
            double spread = 0.02 + (0.30 - 0.25 * level.skill) * 0.4;
            aim.add(new Vector((rng.nextDouble() - 0.5) * spread, (rng.nextDouble() - 0.5) * spread,
                    (rng.nextDouble() - 0.5) * spread));
            Arrow arrow = bot.launchProjectile(Arrow.class, aim);
            arrow.setDamage(3.5); // Power II bow
            arrow.setPickupStatus(AbstractArrow.PickupStatus.DISALLOWED);
            ArenaBlockListener.trackEntity(arrow);
        });
    }

    private static void useWeb(LivingEntity bot, LivingEntity target, State s) {
        s.webs--;
        s.nextWebTick = tick + 160;
        startUse(bot, s, new ItemStack(Material.COBWEB), 4, () -> {
            Block feet = target.getLocation().getBlock();
            if (feet.getType().isAir()) {
                ArenaBlockListener.trackBlock(feet);
                feet.setType(Material.COBWEB);
            }
        });
    }

    private static void useLava(LivingEntity bot, LivingEntity target, State s) {
        s.lava--;
        s.nextLavaTick = tick + 400;
        startUse(bot, s, new ItemStack(Material.LAVA_BUCKET), 6, () -> {
            Vector away = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
            if (away.lengthSquared() < 0.01) return;
            Block spot = target.getLocation().clone().add(away.normalize()).getBlock();
            if (spot.getType().isAir() && !spot.getRelative(0, -1, 0).getType().isAir()) {
                ArenaBlockListener.trackBlock(spot);
                spot.setType(Material.LAVA);
            }
        });
    }

    /** A cobblestone wall piece between the bot and an archer / the opponent. */
    private static void useCobble(LivingEntity bot, LivingEntity target, State s) {
        s.cobble--;
        s.nextCobbleTick = tick + 100;
        startUse(bot, s, new ItemStack(Material.COBBLESTONE), 4, () -> {
            Vector toward = target.getLocation().toVector().subtract(bot.getLocation().toVector()).setY(0);
            if (toward.lengthSquared() < 0.01) return;
            Block front = bot.getLocation().clone().add(toward.normalize().multiply(1.5)).getBlock();
            if (front.getType().isAir()) {
                ArenaBlockListener.trackBlock(front);
                front.setType(Material.COBBLESTONE);
            }
        });
    }

    /**
     * Using an item takes time like it does for a player: reach for it in the hotbar (select delay),
     * use it (throw, drink, draw the bow...), then switch back to the weapon (swap-back delay).
     * The bot cannot attack during any of these phases; higher skill is only slightly faster.
     */
    private static void startUse(LivingEntity bot, State s, ItemStack shown, int ticks, Runnable effect) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double skill = level.skill;
        int select = (int) Math.round(5 - 3 * skill) + rng.nextInt(3);
        int swapBack = (int) Math.round(4 - 2 * skill) + rng.nextInt(2);

        EntityEquipment eq = bot.getEquipment();
        if (eq != null && s.weapon == null) s.weapon = eq.getItemInMainHand().clone();
        s.using = true;
        s.shownItem = shown;
        s.itemShown = false;
        s.showTick = tick + select;
        s.effectTick = s.showTick + ticks;
        s.usingUntilTick = s.effectTick + swapBack;
        s.pending = effect;
    }

    private static void finishUse(LivingEntity bot, State s) {
        if (!s.using) return;
        EntityEquipment eq = bot.getEquipment();

        if (!s.itemShown && tick >= s.showTick) {
            if (eq != null) eq.setItemInMainHand(s.shownItem);
            s.itemShown = true;
        }
        if (s.pending != null && tick >= s.effectTick) {
            Runnable effect = s.pending;
            s.pending = null;
            try {
                effect.run();
            } catch (RuntimeException ex) {
                Bukkit.getLogger().warning("[ArenaPlugin] Bot item use failed: " + ex);
            }
        }
        if (tick >= s.usingUntilTick) {
            if (eq != null && s.weapon != null) eq.setItemInMainHand(s.weapon);
            s.weapon = null;
            s.shownItem = null;
            s.using = false;
        }
    }

    private static ItemStack potionStack(Material material, PotionType type) {
        ItemStack item = new ItemStack(material);
        PotionMeta meta = (PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        return item;
    }

    // ------------------------------------------------------------------ helpers

    private static double clamp01(double x) {
        return Math.max(0.0, Math.min(1.0, x));
    }

    private static double wrap180(double angle) {
        angle %= 360;
        if (angle >= 180) angle -= 360;
        if (angle < -180) angle += 360;
        return angle;
    }

    private static boolean blockedAhead(Location bl, Vector dir) {
        Location front = bl.clone().add(dir.clone().multiply(0.7));
        return !front.getBlock().isPassable() && front.clone().add(0, 1, 0).getBlock().isPassable()
                && front.clone().add(0, 2, 0).getBlock().isPassable();
    }

    private static double damageOf(ItemStack weapon) {
        if (weapon == null) return 1.0;
        double base = switch (weapon.getType()) {
            case NETHERITE_SWORD -> 8.0;
            case DIAMOND_SWORD -> 7.0;
            default -> 1.0;
        };
        int sharp = weapon.getEnchantmentLevel(Enchantment.SHARPNESS);
        return sharp > 0 ? base + 0.5 * sharp + 0.5 : base;
    }
}
