package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Dice in battles (Rules.Dice). */
class DiceTest {
    private final Rules rules = Rules.defaults();

    private static Rules.Dice dice(String mode) {
        Rules.Dice d = new Rules.Dice();
        d.mode = mode;
        return d;
    }

    @Test
    void scaledD20ByDefault() {
        assertEquals("scaled", rules.combat.dice.mode);
        assertEquals(20, rules.combat.dice.sides);
        assertEquals(0.02, rules.combat.dice.swing);
        assertTrue(Combat.Dice.roll(rules.combat.dice, new Rng(1)).rolled());
        assertEquals(Combat.Dice.NONE, Combat.Dice.roll(dice("none"), new Rng(1)));
    }

    @Test
    void scaledRollsSwingPowerBy2PercentAPoint() {
        Combat.Dice low = new Combat.Dice(1, 20, 1 + (1 - 10.5) * 0.02, 0, 1 + (20 - 10.5) * 0.02, 0);
        // 50 against 30 still wins on the worst rolls: 50 × 0.81 = 40.5 against 30 × 1.19 = 35.7.
        Combat.Battle r = Combat.capture(rules, 50, 20, 30, false, 1, low);
        assertTrue(r.win());
        assertEquals(23, r.survivors()); // ⌊√(40.5² − 35.7²) / 0.81⌋: power back to troops at the rolled strength
        // 40 against 30 doesn't: 32.4 against 35.7.
        assertFalse(Combat.capture(rules, 40, 20, 30, false, 1, low).win());
    }

    @Test
    void anEvenFightIsAboutACoinFlip() {
        Rng rng = new Rng(1);
        int wins = 0;
        for (int i = 0; i < 10_000; i++) {
            if (Combat.capture(rules, 30, 20, 30, false, 1, Combat.Dice.roll(dice("scaled"), rng)).win()) {
                wins++;
            }
        }
        assertTrue(wins > 4300 && wins < 4800, "wins " + wins); // ties still go to the defender: 47.5%
    }

    @Test
    void flatRollsAddToPower() {
        Combat.Dice d = Combat.Dice.roll(dice("flat"), new Rng(3));
        assertEquals(1, d.attackMult());
        assertEquals(d.attackRoll(), d.attackAdd());
        assertTrue(d.attackRoll() >= 1 && d.attackRoll() <= 20 && d.defenseRoll() >= 1 && d.defenseRoll() <= 20);
    }
}
