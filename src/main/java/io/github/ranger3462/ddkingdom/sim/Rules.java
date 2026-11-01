package io.github.ranger3462.ddkingdom.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Every number in the Kingdom Stats ruleset (docs/rules/kingdom-stats.txt), as the simulator's {@code DEFAULT_CONFIG}
 * (tools/simulator/web/engine.js). The defaults are {@code data/ddkingdom/realm/sim/rules.json}; {@link #load} lays any
 * overrides over them, the way the simulator's Rules tab does. Field names match the JSON.
 */
public final class Rules {
    static final Gson GSON = new GsonBuilder().create();
    static final String DEFAULTS = "/data/ddkingdom/realm/sim/rules.json";

    public Town town;
    public int capitalTier;
    /** By tier, ruins first. */
    public List<CastleTier> castle;
    public double castleMaxDef;
    /** Food a food node gives by tier, ruins first. */
    public double[] foodNode;
    /** Resources a production node gives by tier, ruins first. */
    public double[] prodNode;
    public Resource resource;
    public Outpost outpost;
    public Upkeep upkeep;
    public int shortagePenalty;
    /** Hiring multiplier by growth rate (fast, normal, slow). */
    public Map<String, Double> growth;
    public double capitalOnlyGrowthMult;
    public Buff buff;
    public Hire hire;
    public Upgrade upgrade;
    public Morale morale;
    public Combat combat;
    public Raid raid;
    public Fortify fortify;
    public ReinforceBorder reinforceBorder;
    public double repairCost;
    public GoldTax goldTax;
    /** Gold lost at the end of every kingdom's turn: {@code share} of the gold its nodes produced that turn, down to 0 at most. */
    public GoldDecay goldDecay;
    /** Gold standing in for food or resources a kingdom lacks when it pays for troops or upgrades (0: it can't). */
    public GoldWildcard goldWildcard;
    /** The Train troops action: gold for a permanent troop strength bonus (null or not enabled: no such action). */
    public Train train;
    /** The dragon's hoard and brood: the Kobolds' end game (null or not enabled: none). */
    public Brood brood;
    /** Council pressure on the player kingdom, the Hearthbound (docs/design/realm-politics.md; null or not enabled: none). */
    public Politics politics;
    /** A node that is a ruin once captured starts a free rebuild to Tier 1 that costs no action and finishes next turn. */
    public boolean freeRebuildOnCapture;
    public Produce produce;
    public ImportFood importFood;
    /** Actions every kingdom gets each turn, before the extra one from each castle at {@link #bonusActionCastleTier}. */
    public int baseActions;
    public int bonusActionCastleTier;
    public NonCombatActions nonCombatActions;
    /** "rotate" (the first to act moves along one each round), "random", or anything else for the map's order. */
    public String turnOrder;
    public Ai ai;

    public static final class Town {
        public double food, gold, prod, goldMult;
        public int defPerTier;
    }

    public static final class CastleTier {
        public double food, mult;
        public int defCap;
    }

    public static final class Resource {
        public int defCap, garrisonCap;
    }

    public static final class Outpost {
        public double food;
        public int defCap, garrisonCap, warnMove;
    }

    public static final class Upkeep {
        public double food, gold;
    }

    public static final class Buff {
        public double costTurns, mult;
        public int duration;
    }

    public static final class Hire {
        public int batchSize, maxBatches;
        public double gold, res;
    }

    public static final class Upgrade {
        public double food, gold, res, castleRes;
        public int timeBase, timePerTier;
    }

    public static final class Morale {
        public int start, recover, attacked, lost;
    }

    public static final class Combat {
        public double defDivisor, retreat, captureDevastation, heroicMult;
        /** Whether a capture knocks the node down a tier. Off: it keeps its tier (kingdoms that raze still knock it down). */
        public boolean captureTierLoss = true;
        /** Dice in battles (null or mode "none": none). */
        public Dice dice;
    }

    /**
     * Each side of a battle rolls a die with {@code sides} sides. "scaled": its power is multiplied by 1 + (roll −
     * middle) × {@code swing}, so a d20 at 0.02 is ×0.81 to ×1.19, the same share of any army. "flat": the roll ×
     * {@code flat} is added to its power, as the mod's d20 rolls were, which matters less the bigger the armies.
     */
    public static final class Dice {
        public String mode = "none";
        public int sides = 20;
        public double swing = 0.02, flat = 1;
    }

    public static final class Raid {
        public double foodPer, goldPer, prodPer, maxTurns, garrisonDmg, devPer10, devMax;
    }

    public static final class Fortify {
        public double cost, devPerStep;
        public int maxSteps;
    }

    public static final class ReinforceBorder {
        public int def;
        public double dev;
        public boolean neutralIsHostile;
    }

    /** Gold paid for each missing food ({@code food}) or resource ({@code res}); 0 for none. */
    /**
     * Train troops: each use adds {@code step} to the kingdom's troop strength, attacking and defending, for {@code gold}
     * times the number of times it has trained (1000, then 2000, ...), at most {@code max} times.
     */
    /**
     * The dragon's hoard: each turn a kingdom with {@code brood} on the map (the Kobolds) puts {@code tithe} of the gold
     * its nodes produce into a hoard it can't spend and the gold tax doesn't touch, until the hoard holds {@code gold}.
     * {@code enabled} turns the whole event on or off. While the hoard holds at least
     * {@code gold}, the dragon has let her brood loose: its troops count {@code strength} times their number and its AI
     * plays as {@code personality}. Raids on its capital can steal from the hoard (the raid resource "hoard"); once the
     * hoard drops below {@code gold}, the brood goes back to sleep until it's full again.
     */
    public static final class Brood {
        public boolean enabled;
        public double gold, strength, tithe;
        public String personality;
    }

    /**
     * The council: groups whose influence leans the player kingdom's AI toward their kind of action. Each group's share
     * of the influence above or below its usual share, times its affinity for an action, adds up to the lean on that
     * action, and the AI's score for it moves by {@code weight} times the lean. Quests raise a group's influence, and it
     * fades back toward its baseline each turn.
     */
    public static final class Politics {
        public boolean enabled;
        /** How far the lean moves an action's score: score + weight × lean × |score|. */
        public double weight;
        /** Share of the way each group's influence moves back to its baseline each of the kingdom's turns. */
        public double decay;
        /** Influence a completed quest adds to its group. */
        public double questInfluence;
        /** Simulator only, standing in for players: quests completed each turn on average. */
        public double simQuests;
        /** Simulator only: the group those quests are for; blank for a random group each time. */
        public String simFavour = "";
        /** By id: each group's baseline influence and its affinity for actions (by action id; missing is 0). */
        public Map<String, Group> groups = new LinkedHashMap<>();
    }

    public static final class Group {
        public double baseline;
        public Map<String, Double> affinity = new LinkedHashMap<>();
    }

    public static final class Train {
        public boolean enabled;
        public double gold, step;
        public int max;
    }

    public static final class GoldWildcard {
        public double food, res;
    }

    public static final class GoldDecay {
        public double share;
    }

    public static final class GoldTax {
        public double rate, free;
    }

    public static final class Produce {
        public double payout;
        /** Turns before a kingdom can Produce Resources again: used on turn 1, it's next allowed on turn 1 + cooldown (0: any time). */
        public int cooldown;
    }

    public static final class ImportFood {
        public boolean enabled;
        public double goldPerFood;
        public int maxPerAction, duration;
    }

    public static final class NonCombatActions {
        public int count;
        /** Also rules out Hire and Send reinforcements for the extra actions. */
        public boolean economyOnly;
    }

    public static final class Ai {
        public double noise, horizon, foodValue, resValue, soldierValue;
        /** By name; a kingdom's {@code ai} picks one, and missing fields fall back to {@link Personality#DEFAULTS}. */
        public Map<String, JsonObject> personalities = new LinkedHashMap<>();
        /**
         * Whether the AI aims for a target army size (each personality's armyRatio and minArmy) and stops hiring once it
         * has it. Off (or null): it hires whenever it can pay and its food income stays at or above 0, so food and gold
         * set how big its army gets, as the rules say.
         */
        public ArmyTarget armyTarget;
        /** What the AI keeps behind on the nodes it holds (null: nothing beyond its reserve). */
        public Guard guard;
    }

    /**
     * Troops the AI leaves on its nodes when it attacks or moves troops out. A node next to land it doesn't hold keeps
     * at least {@code min} (times its personality's {@code guardMult}), and the threat it keeps a reserve against counts
     * enemy garrisons up to {@code reach} links away, at {@code reachShare} of their size past the first link.
     */
    public static final class ArmyTarget {
        public boolean enabled;
    }

    public static final class Guard {
        public boolean enabled;
        public int min, reach;
        public double reachShare;
    }

    /** The default rules. */
    public static Rules defaults() {
        return load(null);
    }

    /** The default rules with {@code overrides} laid over them (null for none). */
    public static Rules load(JsonObject overrides) {
        return GSON.fromJson(merge(defaultsJson(), overrides), Rules.class);
    }

    /** The defaults as JSON, read fresh each time so callers can change it. */
    public static JsonObject defaultsJson() {
        try (InputStream in = Rules.class.getResourceAsStream(DEFAULTS)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + DEFAULTS);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return JsonParser.parseReader(reader).getAsJsonObject();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + DEFAULTS, e);
        }
    }

    /**
     * The simulator's {@code deepMerge}: objects merge key by key, anything else (numbers, arrays) is replaced
     * outright. Returns a new tree.
     */
    public static JsonElement merge(JsonElement base, JsonElement over) {
        if (over == null || over.isJsonNull()) {
            return base == null ? null : base.deepCopy();
        }
        if (base == null || !base.isJsonObject() || !over.isJsonObject()) {
            return over.deepCopy();
        }
        JsonObject out = new JsonObject();
        JsonObject b = base.getAsJsonObject(), o = over.getAsJsonObject();
        for (Entry<String, JsonElement> e : b.entrySet()) {
            out.add(e.getKey(), merge(e.getValue(), o.get(e.getKey())));
        }
        for (Entry<String, JsonElement> e : o.entrySet()) {
            if (!b.has(e.getKey())) {
                out.add(e.getKey(), e.getValue().deepCopy());
            }
        }
        return out;
    }

    /** Overrides from a dotted path and a value, for the command line: {@code combat.retreat=0.3}. */
    public static JsonObject override(JsonObject into, String path, JsonElement value) {
        String[] parts = path.split("\\.");
        JsonObject at = into;
        for (int i = 0; i < parts.length - 1; i++) {
            if (!at.has(parts[i]) || !at.get(parts[i]).isJsonObject()) {
                at.add(parts[i], new JsonObject());
            }
            at = at.getAsJsonObject(parts[i]);
        }
        at.add(parts[parts.length - 1], value);
        return into;
    }
}
