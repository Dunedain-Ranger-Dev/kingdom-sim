package io.github.ranger3462.ddkingdom.sim;

/**
 * The battle formulas (docs/rules/kingdom-stats.txt, Capture Node, Raid and Heroic Conquest), shared by the game, the
 * AI's look-ahead and the calculator.
 */
public final class Combat {
    private Combat() {}

    /**
     * What a battle did. {@code ap} and {@code dp} are the two sides' power; {@code survivors} are the attacker's troops
     * left (those that retreat, on a loss).
     */
    public record Battle(boolean win, double ap, double dp, int survivors, int attackerLosses, int defenderRemaining, int defenderLosses) {}

    /**
     * The dice in a battle: each side's power is multiplied, then added to. {@link #NONE} is the battle as the rules
     * give it, which the AI's look-ahead uses: it plans for the average fight.
     */
    public record Dice(int attackRoll, int defenseRoll, double attackMult, double attackAdd, double defenseMult, double defenseAdd) {
        public static final Dice NONE = new Dice(0, 0, 1, 0, 1, 0);

        public boolean rolled() {
            return attackRoll != 0;
        }

        /** Rolls for a battle by the rules' {@link Rules.Dice}: "scaled", "flat", or anything else for none. */
        public static Dice roll(Rules.Dice dice, Rng rng) {
            if (dice == null || !("scaled".equals(dice.mode) || "flat".equals(dice.mode))) {
                return NONE;
            }
            int a = 1 + (int) Math.floor(rng.next() * dice.sides), d = 1 + (int) Math.floor(rng.next() * dice.sides);
            double middle = (dice.sides + 1) / 2.0;
            if ("scaled".equals(dice.mode)) {
                return new Dice(a, d, 1 + (a - middle) * dice.swing, 0, 1 + (d - middle) * dice.swing, 0);
            }
            return new Dice(a, d, 1, a * dice.flat, 1, d * dice.flat);
        }
    }

    /** The town stat multiplier: min(tier, 1) × (1 + 0.5 × (tier − 1)), and nothing for ruins. */
    public static double tierMult(int tier) {
        return tier <= 0 ? 0 : Math.min(tier, 1) * (1 + 0.5 * (tier - 1));
    }

    /** Defender Power = Garrison × strength × (1 + Final Defense / 20) + Final Defense. */
    public static double defenderPower(Rules rules, int garrison, double finalDefense, double strength) {
        return garrison * strength * (1 + finalDefense / rules.combat.defDivisor) + finalDefense;
    }

    /**
     * A capture (or, {@code heroic}, a Heroic Conquest) by {@code troops} against a defender of power {@code dp} holding
     * {@code garrison}. Power is troops × multiplier (heroic ×4, faction strength), and survivors are turned back into
     * troops by dividing by it. Ties go to the defender.
     */
    public static Battle capture(Rules rules, int troops, int garrison, double dp, boolean heroic, double strength) {
        return capture(rules, troops, garrison, dp, heroic, strength, Dice.NONE);
    }

    /** A capture with the {@code dice} applied to both sides' power. */
    public static Battle capture(Rules rules, int troops, int garrison, double dp, boolean heroic, double strength, Dice dice) {
        double mult = (heroic ? rules.combat.heroicMult : 1) * strength;
        double ap = troops * mult * dice.attackMult() + dice.attackAdd();
        dp = dp * dice.defenseMult() + dice.defenseAdd();
        if (ap > dp) {
            double raw = Math.sqrt(ap * ap - dp * dp);
            int survivors = (int) Math.min(troops, Math.floor(raw / (mult * dice.attackMult())));
            return new Battle(true, ap, dp, survivors, troops - survivors, 0, garrison);
        }
        int retreat = (int) Math.floor(troops * rules.combat.retreat);
        double survivingPower = Math.sqrt(dp * dp - ap * ap);
        int defenderLeft = dp > 0 ? (int) Math.floor(garrison * survivingPower / dp) : garrison;
        return new Battle(false, ap, dp, retreat, troops - retreat, defenderLeft, garrison - defenderLeft);
    }

    /**
     * A raid: the attacker loses ½ × (Attacker Power − √(Attacker Power² − Defender Power²)) and the defender a quarter
     * of its garrison; a failed raid is a failed capture.
     */
    public static Battle raid(Rules rules, int troops, int garrison, double dp, double strength) {
        return raid(rules, troops, garrison, dp, strength, Dice.NONE);
    }

    /** A raid with the {@code dice} applied to both sides' power. */
    public static Battle raid(Rules rules, int troops, int garrison, double dp, double strength, Dice dice) {
        double ap = troops * strength * dice.attackMult() + dice.attackAdd();
        double rolledDp = dp * dice.defenseMult() + dice.defenseAdd();
        if (ap > rolledDp) {
            double losses = 0.5 * (ap - Math.sqrt(ap * ap - rolledDp * rolledDp)) / (strength * dice.attackMult());
            int survivors = (int) Math.max(0, Math.floor(troops - losses));
            int defenderLoss = (int) Math.floor(garrison * rules.raid.garrisonDmg);
            return new Battle(true, ap, rolledDp, survivors, troops - survivors, garrison - defenderLoss, defenderLoss);
        }
        return capture(rules, troops, garrison, dp, false, strength, dice);
    }
}
