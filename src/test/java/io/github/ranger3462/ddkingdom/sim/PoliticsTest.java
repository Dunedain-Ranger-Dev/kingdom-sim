package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Council pressure (Rules.Politics). */
class PoliticsTest {
    @Test
    void noLeanAtTheBaselines() {
        Game game = new Game(SimMap.named("large_ruins"), Rules.defaults(), 1, 40, true);
        for (Action.Type type : Action.Type.values()) {
            assertEquals(0, game.lean(type), type.id);
        }
    }

    @Test
    void questsLeanTowardTheirGroup() {
        Game game = new Game(SimMap.named("large_ruins"), Rules.defaults(), 1, 40, true);
        game.questDone("merchants");
        game.questDone("merchants");
        assertTrue(game.lean(Action.Type.PRODUCE) > 0);
        assertTrue(game.lean(Action.Type.UPGRADE) > 0);
        assertTrue(game.lean(Action.Type.CAPTURE) < 0);
        game.questDone("adventurers");
        game.questDone("adventurers");
        game.questDone("adventurers");
        assertTrue(game.lean(Action.Type.CAPTURE) > 0);
    }

    @Test
    void influenceFadesBackToTheBaseline() {
        com.google.gson.JsonObject over = new com.google.gson.JsonObject();
        Rules.override(over, "politics.decay", new com.google.gson.JsonPrimitive(0.5));
        Game game = new Game(SimMap.named("large_ruins"), Rules.load(over), 1, 40, true);
        game.questDone("watch");
        assertEquals(25, game.influence.get("watch"));
        game.runRound(); // the Hearthbound's turn: halfway back to 20
        assertEquals(22.5, game.influence.get("watch"), 0.001);
    }
}
