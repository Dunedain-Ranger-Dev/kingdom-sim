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
                assertTrue(k.minor || game.capital(k.id) != null, map + " " + k.id + " keeps its capital"); // minor factions have none
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
    void goldDecaysAtTheEndOfEachTurn() {
        com.google.gson.JsonObject over = new com.google.gson.JsonObject();
        Rules.override(over, "goldDecay.share", new com.google.gson.JsonPrimitive(0.2));
        Rules rules = Rules.load(over);
        Game game = new Game(SimMap.named("large_ruins"), rules, 1, 40, true);
        game.step();
        Kingdom first = game.kingdoms.get(0);
        // The capital produced 300 gold, so 60 went at the end of the turn (if the stockpile had that much).
        assertEquals(Math.min(60, first.gold + first.stats.goldDecayed), first.stats.goldDecayed, 0.001);
        SimMain.play(SimMap.named("large_ruins"), rules, 1, 40).kingdoms
                .forEach(k -> assertTrue(k.gold >= 0 && (k.minor || k.stats.goldDecayed > 0), k.id)); // minor factions earn no gold
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
