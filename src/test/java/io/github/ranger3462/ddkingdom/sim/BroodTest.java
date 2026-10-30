package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** The dragon's hoard and brood (Rules.Brood). */
class BroodTest {
    private static Rules rules(String path, Object value) {
        com.google.gson.JsonObject over = new com.google.gson.JsonObject();
        Rules.override(over, path, com.google.gson.JsonParser.parseString(String.valueOf(value)));
        return Rules.load(over);
    }

    @Test
    void theBroodFollowsTheHoard() {
        Game game = new Game(SimMap.named("large_ruins"), Rules.defaults(), 1, 40, true);
        Kingdom kobolds = game.kingdom("ko");
        assertTrue(game.hoards(kobolds));
        assertEquals(1, game.strength("ko"));
        kobolds.hoard = 12000;
        game.updateBrood(kobolds);
        assertTrue(kobolds.awake);
        assertEquals(2, game.strength("ko"));
        assertEquals("dragon", game.rules.brood.personality);
        // A raid on the capital can take from the hoard, at the rate gold is taken; nowhere else can.
        Node capital = game.capital("ko");
        Game.Steal steal = game.raidSteal(capital, "hoard", 50);
        assertEquals("hoard", steal.key());
        assertEquals(1000, steal.amount());
        assertEquals(0, game.raidSteal(game.node("ko_town"), "hoard", 50).amount());
        kobolds.hoard = 11000;
        game.updateBrood(kobolds);
        assertFalse(kobolds.awake);
        assertEquals(1, game.strength("ko"));
    }

    @Test
    void theTitheStopsAtTheTarget() {
        Game game = SimMain.play(SimMap.named("large_ruins"), rules("brood.gold", 3000), 1, 60);
        Kingdom kobolds = game.kingdom("ko");
        assertEquals(3000, kobolds.hoard, 0.001);
        assertTrue(kobolds.awake);
    }

    @Test
    void theEventCanBeTurnedOff() {
        Game game = SimMain.play(SimMap.named("large_ruins"), rules("brood.enabled", false), 1, 40);
        Kingdom kobolds = game.kingdom("ko");
        assertEquals(0, kobolds.hoard);
        assertFalse(kobolds.awake);
    }
}
