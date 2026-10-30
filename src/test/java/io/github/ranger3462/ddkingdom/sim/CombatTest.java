package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The battle and tier formulas against hand-worked examples (the simulator's test.js). */
class CombatTest {
    private final Rules rules = Rules.defaults();

    @Test
    void tierMultipliers() {
        assertEquals(0, Combat.tierMult(0));
        assertEquals(1, Combat.tierMult(1));
        assertEquals(1.5, Combat.tierMult(2));
        assertEquals(3, Combat.tierMult(5));
    }

    @Test
    void captureSurvivorsAreTheSquareRootOfTheDifference() {
        Combat.Battle r = Combat.capture(rules, 50, 20, 30, false, 1);
        assertTrue(r.win());
        assertEquals(40, r.survivors()); // √(50² − 30²)
        assertEquals(0, r.defenderRemaining());
    }

    @Test
    void tiesGoToTheDefender() {
        Combat.Battle r = Combat.capture(rules, 30, 20, 30, false, 1);
        assertFalse(r.win());
        assertEquals(7, r.survivors()); // 25% retreat
        assertEquals(0, r.defenderRemaining());
    }

    @Test
    void aFailedCaptureRetreatsAQuarter() {
        Combat.Battle r = Combat.capture(rules, 18, 20, 30, false, 1);
        assertFalse(r.win());
        assertEquals(4, r.survivors());
        assertEquals(16, r.defenderRemaining()); // 20 × √(30² − 18²) / 30
    }

    @Test
    void heroicConquestDividesSurvivorsByFour() {
        Combat.Battle r = Combat.capture(rules, 10, 0, 30, true, 1);
        assertTrue(r.win());
        assertEquals(6, r.survivors()); // ⌊√(40² − 30²) / 4⌋
    }

    @Test
    void aRaidCostsTheDefenderAQuarter() {
        Combat.Battle r = Combat.raid(rules, 50, 20, 30, 1);
        assertTrue(r.win());
        assertEquals(45, r.survivors()); // ½ × (50 − 40) lost
        assertEquals(15, r.defenderRemaining());
    }

    @Test
    void defenseCapsAndCastleMultipliers() {
        Game game = new Game(SimMap.named("four_corners"), rules, 1, 40, true);
        assertEquals(10, game.finalDefense(game.node("dw_cap"))); // a capital's cap: tier 5 × 2
        assertEquals(9, game.finalDefense(game.node("dw_castle"))); // a T1 castle: 6 × 1.5
    }
}
