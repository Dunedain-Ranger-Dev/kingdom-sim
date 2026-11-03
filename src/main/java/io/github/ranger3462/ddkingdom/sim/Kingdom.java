package io.github.ranger3462.ddkingdom.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A kingdom in a game: who it is (from its {@link SimMap.KingdomSetup}), its stockpiles, and what the AI remembers. */
public final class Kingdom {
    public final String id, name, color, growth, ai;
    public final boolean player, goblinRule, noFreeRebuild, razes, brood, minor;
    public final double strength, homeDefStrength, upkeepMult, raiseDead;
    public final Double defStrength;
    public final List<String> foes;
    public final int counterPatience;

    public double food, gold, res;
    /** Turns of the growth buff left. */
    public int buffTurns;
    public final List<Contract> imports = new ArrayList<>();
    /** Node id to the round this kingdom lost it, so it knows what to retake. */
    public final Map<String, Integer> lost = new LinkedHashMap<>();
    /** Who it's counter-attacking, if anyone. */
    public Counter counter;
    /** Falling back home after a counter-attack. */
    public boolean falling;
    /** The burst cycle: "gather" or "attack". */
    public String mode = "gather";
    public int cooldownUntil;
    /** Times it has used Train troops. */
    public int trained;
    /** Gold in the dragon's hoard (Rules.Brood). */
    public double hoard;
    /** Whether its brood is awake: the hoard is full. */
    public boolean awake;
    /** A minor faction: the round it can next appear, and whether it's on the map now. */
    public int nextSpawn;
    public boolean present;
    /** The round it last used Produce Resources (null: never), for its cooldown. */
    public Integer lastProduced;
    public final Stats stats = new Stats();

    /** A food import: {@code food} a turn for {@code turnsLeft} more turns. */
    public static final class Contract {
        public final int food;
        public int turnsLeft;

        Contract(int food, int turnsLeft) {
            this.food = food;
            this.turnsLeft = turnsLeft;
        }
    }

    public record Counter(String kid, int since) {}

    /** What it did over a game, for batch reports. */
    public static final class Stats {
        public final Map<String, Integer> actions = new LinkedHashMap<>();
        /** Captures and raids it launched, by whom they were aimed at ("none" for unowned land). */
        public final Map<String, Integer> attacksOn = new LinkedHashMap<>();
        public int capturesTried, capturesWon, raidsTried, raidsWon, hired, battleLosses, kills, shortageLosses, foodShortTurns,
                goldShortTurns, buffs, nodesGained, nodesLost, turns, counters, raised, imported;
        /** Attacks the dice turned: won that would have been lost without them, or lost that would have been won. */
        public int upsets;
        /** The brood: times it woke, the round it first did (0: never), and turns it spent awake. */
        public int broodWoke, firstWoke, turnsAwake;
        /** A minor faction: times it appeared, and turns it was on the map. */
        public int spawns, turnsPresent;
        /** Gold raids took from its hoard, and that it took from others' hoards. */
        public double hoardLost, stolenHoard;
        public double stolenFood, stolenGold, stolenRes;
        /** Gold lost to the end-of-turn decay (Rules.GoldDecay). */
        public double goldDecayed;
    }

    Kingdom(SimMap.KingdomSetup s) {
        id = s.id;
        name = s.name;
        color = s.color;
        growth = s.growth;
        ai = s.ai;
        player = s.player;
        goblinRule = s.goblinRule;
        noFreeRebuild = s.noFreeRebuild;
        razes = s.razes;
        brood = s.brood;
        minor = s.minor;
        strength = s.strength == 0 ? 1 : s.strength;
        defStrength = s.defStrength;
        homeDefStrength = s.homeDefStrength;
        upkeepMult = s.upkeepMult;
        raiseDead = s.raiseDead;
        foes = List.copyOf(s.foes);
        counterPatience = s.counterPatience;
        food = s.food;
        gold = s.gold;
        res = s.res;
    }

    public double importedFood() {
        int total = 0;
        for (Contract c : imports) {
            total += c.food;
        }
        return total;
    }
}
