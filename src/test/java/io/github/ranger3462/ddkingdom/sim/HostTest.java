package io.github.ranger3462.ddkingdom.sim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** What a host (the mod's realm) needs from a game: saving and loading it, playing it forever, and holding fights for players. */
class HostTest {
    private static final SimMap MAP = SimMap.named("large_ruins");

    @Test
    void aSavedGamePlaysOnAsIfNeverStopped() {
        Rules rules = Rules.defaults();
        Game straight = new Game(MAP, rules, 3, 0, true);
        for (int i = 0; i < 60; i++) {
            straight.runRound();
        }
        Game saved = new Game(MAP, rules, 3, 0, true);
        for (int i = 0; i < 25; i++) {
            saved.runRound();
        }
        for (int i = 0; i < 4; i++) {
            saved.step(); // and part of a round, so it isn't the first kingdom's turn next
        }
        Game loaded = GameSave.fromText(MAP, rules, GameSave.toText(saved), 0, true);
        while (loaded.round <= 60) {
            loaded.step();
        }
        assertEquals(SimMain.fingerprint(straight), SimMain.fingerprint(loaded));
        assertEquals(GameSave.toText(straight), GameSave.toText(loaded)); // stats, memory and the random numbers too
    }

    @Test
    void anOpenEndedGameGoesOnForever() {
        Game game = new Game(MAP, Rules.defaults(), 5, 0, true);
        for (int i = 0; i < 400; i++) {
            game.runRound();
        }
        assertEquals(401, game.round);
        assertTrue(game.history.size() <= Game.HISTORY_KEPT);
        for (Kingdom k : game.kingdoms) {
            assertTrue(k.food >= 0 && k.gold >= 0 && k.res >= 0, k.id + " stockpiles");
            assertTrue(k.minor || game.capital(k.id) != null, k.id + " keeps its capital");
        }
    }

    /** A game holding every attack on the Hearthbound, run until one is held. */
    private static Game holding() {
        Game game = new Game(MAP, Rules.defaults(), 1, 0, true);
        game.holdBattle = (kid, target) -> "hb".equals(target.owner);
        for (int i = 0; i < 2000 && game.held.isEmpty(); i++) {
            game.step();
        }
        assertFalse(game.held.isEmpty(), "someone attacked the Hearthbound");
        return game;
    }

    @Test
    void aHeldAttackWaitsWithItsTroopsAway() {
        Game game = holding();
        Game.HeldBattle b = game.held.get(0);
        Node target = game.node(b.target);
        assertEquals("hb", target.owner);
        int army = game.army(b.kid), garrison = target.garrison;
        int fromNow = game.node(b.from).garrison;
        // Away, but still the attacker's army.
        int onNodes = game.ownedNodes(b.kid).stream().mapToInt(n -> n.garrison).sum();
        assertEquals(army, onNodes + game.held.stream().filter(h -> h.kid.equals(b.kid)).mapToInt(h -> h.troops).sum());
        game.settle(b.id);
        assertTrue(game.held.stream().noneMatch(h -> h.id == b.id));
        assertTrue(target.garrison != garrison || !"hb".equals(target.owner) || game.node(b.from).garrison != fromNow, "the fight happened");
    }

    @Test
    void aFightWonInTheWorldTakesTheNode() {
        Game game = holding();
        Game.HeldBattle b = null;
        for (int i = 0; i < 5000 && b == null; i++) {
            b = game.held.stream().filter(h -> h.kind != Action.Type.RAID).findFirst().orElse(null);
            if (b == null) {
                game.held.stream().map(h -> h.id).toList().forEach(game::settle); // raids: by the dice
                game.step();
            }
        }
        assertNotNull(b, "a capture on the Hearthbound was held");
        Node target = game.node(b.target);
        int lost = game.kingdom("hb").stats.nodesLost;
        game.settle(b.id, Combat.fought(true, Math.min(5, b.troops), 99));
        assertEquals(b.kid, target.owner);
        assertTrue(target.garrison <= Math.min(5, b.troops));
        assertEquals(lost + 1, game.kingdom("hb").stats.nodesLost);
    }

    @Test
    void aFightLostInTheWorldKeepsTheNode() {
        Game game = holding();
        Game.HeldBattle b = game.held.get(0);
        Node target = game.node(b.target);
        int army = game.army(b.kid);
        game.settle(b.id, Combat.fought(false, 0, 3));
        assertEquals("hb", target.owner);
        assertEquals(Math.min(3, target.garrison), target.garrison);
        assertEquals(army - b.troops, game.army(b.kid), "every attacker fell");
    }

    @Test
    void heldAttacksAreSavedToo() {
        Game game = holding();
        Rules rules = game.rules;
        Game loaded = GameSave.fromText(MAP, rules, GameSave.toText(game), 0, true);
        assertEquals(game.held.size(), loaded.held.size());
        Game.HeldBattle a = game.held.get(0), b = loaded.held.get(0);
        assertEquals(a.id, b.id);
        assertEquals(a.kind, b.kind);
        assertEquals(a.troops, b.troops);
        assertEquals(a.target, b.target);
        assertEquals(game.army(a.kid), loaded.army(a.kid));
        assertNull(loaded.holdBattle); // the host sets it again
        loaded.settle(b.id);
        assertNotNull(loaded.node(b.target).owner);
    }
}
