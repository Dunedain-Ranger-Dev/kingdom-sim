package io.github.ranger3462.ddkingdom.sim;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * How an AI kingdom weighs its choices (docs/rules/kingdom-behaviour.txt): the simulator's personality weights, named in
 * {@link Rules.Ai#personalities} and picked by a kingdom's {@code ai}. Fields left out of a personality take
 * {@link #DEFAULTS}.
 */
public final class Personality {
    /** The simulator's {@code PERSONALITY_DEFAULTS}. */
    static final String DEFAULTS = """
            {"aggr": 1, "raid": 0.8, "def": 1, "econ": 1, "mil": 1, "margin": 1.25, "raidMargin": 0, "armyRatio": 1, "minArmy": 30,
             "targets": "any", "reserveMult": 1, "capitalReserve": 0, "retake": 1, "secureNew": 0, "fortifyAttacked": 1,
             "homeRadius": 0, "homeBias": 1, "upgradeAway": 1, "wealthRaidBias": 1, "burst": 0, "burstRatio": 2, "rebalance": 0,
             "leaveBehind": 0, "landValue": 1, "counter": 0, "warOnly": 0, "warHire": 0, "raze": 0}""";

    /** Weights on attacking, raiding, defending, the economy and the military. */
    public double aggr, raid, def, econ, mil;
    /** How much stronger it must be to capture, and to raid (0: the capture margin). */
    public double margin, raidMargin;
    /** The army it wants: this times the enemy troops next to it, and at least {@link #minArmy}. */
    public double armyRatio, minArmy;
    /**
     * Whom it attacks: "any"; "playerOnly"; "home" (its own home nodes only); "raze" (its home nodes and its kingdom's
     * {@code foes}); "avoidMonsters" (other monsters only where weakened).
     */
    public String targets;
    /** Share of the usual reserve it keeps against neighbours, and troops it always keeps in the capital. */
    public double reserveMult, capitalReserve;
    /** Multiplier on retaking nodes it lost. */
    public double retake;
    /** Secures new conquests before expanding again (0 or 1). */
    public double secureNew;
    /** Multiplier on defending nodes attacked in the last 2 rounds. */
    public double fortifyAttacked;
    /** Links from the capital it holds in earnest (0: no home area), and how much more it values that area. */
    public double homeRadius, homeBias;
    /** Whether it upgrades land outside its home area past Tier 1 (0 or 1). */
    public double upgradeAway;
    public double wealthRaidBias;
    /** The old Elemental burst cycle (0 or 1): mass in the capital, strike player land at {@link #burstRatio}, fall back. */
    public double burst, burstRatio;
    /** Spreads troops to where they're needed (0 or 1), leaving at least {@link #leaveBehind} in uncapped nodes. */
    public double rebalance, leaveBehind;
    /** Multiplier on the worth of holding land at all. */
    public double landValue;
    /** Counter-attacks towards whoever attacked it (0 or 1). */
    public double counter;
    /** Only captures, raids (and with {@link #warHire}, hires), taking fights it will lose (0 or 1). */
    public double warOnly, warHire;
    /** Razes: captures to knock tiers down, never rebuilds what it takes, and brings the troops home (0 or 1). */
    public double raze;

    /** The personality named {@code name}, or "balanced" if there's none by that name. */
    public static Personality of(Rules rules, String name) {
        JsonObject chosen = rules.ai.personalities.get(name);
        if (chosen == null) {
            chosen = rules.ai.personalities.get("balanced");
        }
        return Rules.GSON.fromJson(Rules.merge(JsonParser.parseString(DEFAULTS), chosen), Personality.class);
    }
}
