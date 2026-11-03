package io.github.ranger3462.ddkingdom.realm;

import java.util.Optional;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.sim.Node;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The realm's turns by the simulator's rules (docs/design/realm-cutover.md, phase 2), when {@code realmEngine} is
 * {@code sim}: a round of the realm's {@link SimRealm} every {@code realmTurnMinutes}, caught up after time away as
 * the old turns are. An attack on the player kingdom waits for dusk if a player is within {@link RealmLiveRaids#PRESENCE}
 * blocks of the place it's on; for now it's still rolled then (live fights are phase 3). Captures, raids and held
 * attacks go in the realm log; places changing hands and attacks held for dusk are news.
 */
public final class RealmSimTurns {
    private RealmSimTurns() {}

    /** Plays what's due by real time {@code now}, if the realm's been started by the simulator's rules. */
    public static void tick(MinecraftServer server, Realm realm, long now) {
        Optional<SimRealm> started = realm.sim();
        if (started.isEmpty()) {
            return;
        }
        SimRealm sim = started.get();
        sim.playersNear = (kid, target) -> playerNear(server, realm, target);
        int before = sim.game.round;
        sim.catchUp(now, RealmTurns.turnMillis(), KingdomConfig.REALM_CATCH_UP_TURNS.get());
        boolean changed = record(realm, sim);
        if (changed || sim.game.round != before) {
            realm.turn = sim.game.round - 1;
            realm.setDirty();
        }
    }

    /** Plays {@code count} rounds now, whatever the clock says (for /realm sim turn). */
    public static void playNow(MinecraftServer server, Realm realm, SimRealm sim, int count) {
        sim.playersNear = (kid, target) -> playerNear(server, realm, target);
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            sim.settleDue(Long.MAX_VALUE); // forced rounds don't wait for dusk
            sim.playRound(now, RealmTurns.turnMillis());
        }
        sim.lastTurnMillis = now;
        record(realm, sim);
        realm.turn = sim.game.round - 1;
        realm.setDirty();
    }

    /** Whether a player is near the place a simulator node stands, in the world. */
    static boolean playerNear(MinecraftServer server, Realm realm, Node target) {
        Optional<Realm.Place> place = realm.simPlace(target.id);
        if (place.isEmpty()) {
            return false;
        }
        ServerLevel level = server.getLevel(place.get().dimension());
        return level != null && level.players().stream()
                .anyMatch(player -> !player.isSpectator() && player.blockPosition().closerThan(place.get().pos(), RealmLiveRaids.PRESENCE));
    }

    /** The simulator's events into the realm log, by kind; returns whether there were any. */
    private static boolean record(Realm realm, SimRealm sim) {
        boolean any = false;
        for (SimRealm.Event e : sim.takeEvents()) {
            Realm.LogKind kind = switch (e.kind()) {
                case "capture" -> Realm.LogKind.CAPTURE;
                case "raid", "fail" -> Realm.LogKind.BATTLE;
                case "held" -> Realm.LogKind.MARCH;
                case "shortage" -> Realm.LogKind.SUPPLY;
                default -> null;
            };
            if (kind == null && !e.news()) {
                continue; // the routine of each turn (hiring, upgrading, producing) stays out of the log
            }
            realm.recordSim(kind == null ? Realm.LogKind.OTHER : kind, e.kid(), e.text(), e.news());
            any = true;
        }
        return any;
    }
}
