package io.github.ranger3462.ddkingdom.realm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.Rules;
import io.github.ranger3462.ddkingdom.sim.SimMain;
import io.github.ranger3462.ddkingdom.sim.SimMap;

/** The realm by the simulator's rules (docs/design/realm-cutover.md, phase 2): its clock, held fights and saving. */
class SimRealmTest {
    private static final long HOUR = 3_600_000L, START = 1_000_000L;

    @Test
    void roundsComeOnARealTimeClock() {
        SimRealm realm = SimRealm.start(SimRealm.DEFAULT_MAP, 1, START);
        assertEquals(0, realm.catchUp(START + HOUR - 1, HOUR, 24), "no round before a turn's length has passed");
        assertEquals(1, realm.catchUp(START + HOUR, HOUR, 24));
        assertEquals(2, realm.game.round);
        assertEquals(HOUR, realm.untilNext(START + HOUR, HOUR));
        assertEquals(24, realm.catchUp(START + HOUR * 100, HOUR, 24), "a long time away catches up only the cap");
        assertTrue(realm.takeEvents().stream().anyMatch(e -> e.text().contains("quiet for 75 turns")));
        assertEquals(HOUR, realm.untilNext(START + HOUR * 100, HOUR), "and the clock is caught up after");
    }

    @Test
    void withNoPlayersTheRealmPlaysTheSimulatorsGame() {
        SimRealm realm = SimRealm.start(SimRealm.DEFAULT_MAP, 9, START);
        for (int hour = 1; hour <= 60; hour++) {
            realm.catchUp(START + HOUR * hour, HOUR, 24);
        }
        Game plain = new Game(SimMap.named(SimRealm.DEFAULT_MAP), Rules.defaults(), 9, 0, true);
        for (int i = 0; i < 60; i++) {
            plain.runRound();
        }
        assertEquals(SimMain.fingerprint(plain), SimMain.fingerprint(realm.game));
    }

    @Test
    void aSavedRealmCarriesOn() {
        SimRealm realm = SimRealm.start(SimRealm.DEFAULT_MAP, 4, START);
        realm.playersNear = (kid, target) -> true;
        realm.catchUp(START + HOUR * 30, HOUR, 50);
        SimRealm loaded = SimRealm.load(realm.save());
        assertEquals(realm.save(), loaded.save());
        loaded.playersNear = realm.playersNear;
        realm.catchUp(START + HOUR * 45, HOUR, 50);
        loaded.catchUp(START + HOUR * 45, HOUR, 50);
        assertEquals(SimMain.fingerprint(realm.game), SimMain.fingerprint(loaded.game));
    }

    @Test
    void attacksOnThePlayersWaitForDusk() {
        SimRealm realm = SimRealm.start(SimRealm.DEFAULT_MAP, 1, START);
        realm.playersNear = (kid, target) -> true;
        long now = START;
        for (int i = 0; i < 200 && realm.game.held.isEmpty(); i++) {
            now += HOUR;
            realm.catchUp(now, HOUR, 24);
        }
        assertFalse(realm.game.held.isEmpty(), "someone attacked the Hearthbound");
        Game.HeldBattle held = realm.game.held.get(0);
        assertEquals("hb", realm.game.node(held.target).owner);
        Long dusk = realm.dusk(held.id);
        assertNotNull(dusk);
        assertEquals(now + HOUR / 2, dusk, "dusk is half a turn after the round");
        List<SimRealm.Event> events = realm.takeEvents();
        assertTrue(events.stream().anyMatch(e -> e.news() && "held".equals(e.kind())), "a held attack is news");
        realm.catchUp(dusk - 1, HOUR, 24);
        assertTrue(realm.game.held.stream().anyMatch(b -> b.id == held.id), "not settled before dusk");
        realm.catchUp(dusk, HOUR, 24);
        assertTrue(realm.game.held.stream().noneMatch(b -> b.id == held.id), "settled at dusk");
        assertTrue(realm.takeEvents().stream().anyMatch(e -> List.of("capture", "raid", "fail", "react").contains(e.kind())));
    }

    @Test
    void onlyThePlayersFightsAreHeld() {
        SimRealm realm = SimRealm.start(SimRealm.DEFAULT_MAP, 2, START);
        realm.playersNear = (kid, target) -> true;
        for (int hour = 1; hour <= 80; hour++) {
            realm.catchUp(START + HOUR * hour, HOUR, 24);
            realm.game.held.forEach(b -> assertEquals("hb", realm.game.node(b.target).owner));
        }
    }
}
