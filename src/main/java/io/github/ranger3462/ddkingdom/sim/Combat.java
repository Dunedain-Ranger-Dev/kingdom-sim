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
    public record Battle(boolean win, double ap, double dp, int survivors, int attackerLosses, int defenderRemaining, int defenderLosses) {
        /**
         * This battle's numbers held to {@code troops} attacking and a {@code garrison} defending, with losses to match;
         * a won {@code capture} leaves no defenders.
         */
        Battle against(int troops, int garrison, boolean capture) {
            int s = Math.max(0, Math.min(troops, survivors)), d = win && capture ? 0 : Math.max(0, Math.min(garrison, defenderRemaining));
            return new Battle(win, ap, dp, s, troops - s, d, garrison - d);
        }
    }

    /**
     * A battle fought out in the world, to settle a held attack ({@link Game#settle}): whether the attackers won, how
     * many of them are left, and how many defenders. A won capture leaves no defenders, whatever's given.
     */
    public static Battle fought(boolean win, int survivors, int defendersLeft) {
        return new Battle(win, 0, 0, survivors, 0, defendersLeft, 0);
    }

    /**
     * The dice in a battle: each side's power is multiplied, then added to. {@link #NONE} is the battle as the rules
     * give it.
     */
    public record Dice(int attackRoll, int defenseRoll, double attackMult, double attackAdd, double defenseMult, double defenseAdd) {
        public static final Dice NONE = new Dice(0, 0, 1, 0, 1, 0);

        public boolean rolled() {
            return attackRoll != 0;
        }

        /** Whether the rules roll dice in battles: mode "scaled" or "flat". */
        public static boolean on(Rules.Dice dice) {
            return dice != null && ("scaled".equals(dice.mode) || "flat".equals(dice.mode));
        }

        /** Rolls for a battle by the rules' {@link Rules.Dice}. */
        public static Dice roll(Rules.Dice dice, Rng rng) {
            if (!on(dice)) {
                return NONE;
            }
            int a = 1 + (int) Math.floor(rng.next() * dice.sides), d = 1 + (int) Math.floor(rng.next() * dice.sides);
            return of(dice, a, d);
        }

        /** The dice when the attacker rolls {@code a} and the defender {@code d}. */
        public static Dice of(Rules.Dice dice, int a, int d) {
            double middle = (dice.sides + 1) / 2.0;
            if ("scaled".equals(dice.mode)) {
                return new Dice(a, d, 1 + (a - middle) * dice.swing, 0, 1 + (d - middle) * dice.swing, 0);
            }
            return new Dice(a, d, 1, a * dice.flat, 1, d * dice.flat);
        }
    }

    /**
     * What a battle can be expected to do over every roll of the dice, for the AI to weigh: the chance the attacker
     * wins, the troops each side can expect to lose, and the attacker's troops left when it wins. Without dice it's
     * the one battle the rules give.
     */
    public record Odds(double win, double attackerLosses, double defenderLosses, double winSurvivors) {}

    /** The odds of a capture ({@code raid} false) or a raid. */
    public static Odds odds(Rules rules, boolean raid, int troops, int garrison, double dp, boolean heroic, double strength) {
        Rules.Dice dice = rules.combat.dice;
        if (!Dice.on(dice)) {
            Battle b = raid ? raid(rules, troops, garrison, dp, strength) : capture(rules, troops, garrison, dp, heroic, strength);
            return new Odds(b.win() ? 1 : 0, b.attackerLosses(), b.defenderLosses(), b.win() ? b.survivors() : 0);
        }
        int wins = 0;
        double attackerLosses = 0, defenderLosses = 0, winSurvivors = 0;
        for (int a = 1; a <= dice.sides; a++) {
            for (int d = 1; d <= dice.sides; d++) {
                Dice roll = Dice.of(dice, a, d);
                Battle b = raid ? raid(rules, troops, garrison, dp, strength, roll) : capture(rules, troops, garrison, dp, heroic, strength, roll);
                attackerLosses += b.attackerLosses();
                defenderLosses += b.defenderLosses();
                if (b.win()) {
                    wins++;
                    winSurvivors += b.survivors();
                }
            }
        }
        double rolls = dice.sides * dice.sides;
        return new Odds(wins / rolls, attackerLosses / rolls, defenderLosses / rolls, wins == 0 ? 0 : winSurvivors / wins);
    }

    /**
     * How many times the defender's power the attacker's must be to win at least {@code chance} of the time, with
     * scaled dice: a win needs power × attacker's roll multiplier above defense × defender's, so the answer is the
     * matching ratio of the two multipliers. Zero for other dice, which don't scale (or none).
     */
    public static double marginFor(Rules.Dice dice, double chance) {
        if (dice == null || !"scaled".equals(dice.mode)) {
            return 0;
        }
        double[] ratios = new double[dice.sides * dice.sides];
        int i = 0;
        for (int a = 1; a <= dice.sides; a++) {
            for (int d = 1; d <= dice.sides; d++) {
                Dice roll = Dice.of(dice, a, d);
                ratios[i++] = roll.defenseMult() / roll.attackMult();
            }
        }
        java.util.Arrays.sort(ratios);
        int k = Math.max(1, Math.min(ratios.length, (int) Math.ceil(chance * ratios.length - 1e-9)));
        return ratios[k - 1];
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
