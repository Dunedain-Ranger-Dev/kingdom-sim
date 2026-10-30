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
    public WealthTax wealthTax;
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
        public double food, wealth, prod, wealthMult;
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
        public double food, wealth;
    }

    public static final class Buff {
        public double costTurns, mult;
        public int duration;
    }

    public static final class Hire {
        public int batchSize, maxBatches;
        public double wealth, res;
    }

    public static final class Upgrade {
        public double food, wealth, res, castleRes;
        public int timeBase, timePerTier;
    }

    public static final class Morale {
        public int start, recover, attacked, lost;
    }

    public static final class Combat {
        public double defDivisor, retreat, captureDevastation, heroicMult;
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
        public double foodPer, wealthPer, prodPer, maxTurns, garrisonDmg, devPer10, devMax;
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

    public static final class WealthTax {
        public double rate, free;
    }

    public static final class Produce {
        public double payout;
    }

    public static final class ImportFood {
        public boolean enabled;
        public double wealthPerFood;
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
