package io.github.ranger3462.ddkingdom.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A kingdom in a game: who it is (from its {@link SimMap.KingdomSetup}), its stockpiles, and what the AI remembers. */
public final class Kingdom {
    public final String id, name, color, growth, ai;
    public final boolean player, goblinRule, noFreeRebuild;
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
        public int capturesTried, capturesWon, raidsTried, raidsWon, hired, battleLosses, kills, shortageLosses, foodShortTurns,
                goldShortTurns, buffs, nodesGained, nodesLost, turns, counters, raised, imported;
        /** Attacks the dice turned: won that would have been lost without them, or lost that would have been won. */
        public int upsets;
        public double stolenFood, stolenGold, stolenRes;
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
