package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Whole games on the shipped maps. */
class GameTest {
    @Test
    void everyMapPlaysThrough() {
        Rules rules = Rules.defaults();
        for (String map : new String[] { "four_corners", "continent_settled", "continent_ruins", "continent_dwarves", "large_ruins", "large_settled" }) {
            Game game = SimMain.play(SimMap.named(map), rules, 1, 40);
            assertEquals(41, game.round, map);
            for (Kingdom k : game.kingdoms) {
                assertTrue(k.food >= 0 && k.gold >= 0 && k.res >= 0, map + " " + k.id + " stockpiles");
                assertTrue(game.capital(k.id) != null, map + " " + k.id + " keeps its capital");
            }
        }
    }

    @Test
    void aSeedPlaysTheSameGameEveryTime() {
        SimMap map = SimMap.named("large_ruins");
        Rules rules = Rules.defaults();
        assertEquals(SimMain.fingerprint(SimMain.play(map, rules, 7, 30)), SimMain.fingerprint(SimMain.play(map, rules, 7, 30)));
    }

    @Test
    void everyActionIsUsed() {
        Game game = SimMain.play(SimMap.named("large_ruins"), Rules.defaults(), 1, 40);
        for (Kingdom k : game.kingdoms) {
            assertEquals(0, k.stats.actions.getOrDefault("pass", 0), k.id + " passed");
        }
    }

    @Test
    void produceWaitsOutItsCooldown() {
        com.google.gson.JsonObject over = new com.google.gson.JsonObject();
        Rules.override(over, "produce.cooldown", new com.google.gson.JsonPrimitive(5));
        Game game = SimMain.play(SimMap.named("large_ruins"), Rules.load(over), 1, 40);
        for (Kingdom k : game.kingdoms) {
            assertTrue(k.stats.actions.getOrDefault("produce", 0) <= 8, k.id + " produced more than once every 5 turns");
        }
    }

    @Test
    void overridesLayOverTheDefaults() {
        com.google.gson.JsonObject over = new com.google.gson.JsonObject();
        Rules.override(over, "combat.retreat", new com.google.gson.JsonPrimitive(0.5));
        Rules rules = Rules.load(over);
        assertEquals(0.5, rules.combat.retreat);
        assertEquals(20, rules.combat.defDivisor); // untouched
    }
}
