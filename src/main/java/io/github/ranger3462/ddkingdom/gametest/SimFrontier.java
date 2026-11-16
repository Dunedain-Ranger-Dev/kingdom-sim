package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmSimTurns;
import io.github.ranger3462.ddkingdom.realm.SimRealm;
import io.github.ranger3462.ddkingdom.sim.Action;
import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.Node;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * A realm by the simulator's rules for a test (docs/design/realm-cutover.md): the server's map, with the Hearthbound's
 * town of Harborside ({@link #TOWN}) at the test's spot, holding {@link #troops} troops (its guards), and a goblin farm
 * beside it ({@link #FROM}) with 10. {@link #raid} has the goblins raid the town at dusk, mirrored as the realm's raid.
 */
final class SimFrontier {
    static final String TOWN = "hb_town", FROM = "hb_farm";
    /** Its troops (and so its guards) when it's made: as many as a hamlet used to turn out. */
    static final int TROOPS = 4;
    final SimRealm sim;
    final Realm realm;
    final RealmNode town;
    final BlockPos at;
    Game.HeldBattle held;

    private SimFrontier(GameTestHelper helper, int tier) {
        ServerLevel level = helper.getLevel();
        at = helper.absolutePos(new BlockPos(1, 2, 1));
        TestArena.loadAround(level, at, RealmLiveRaids.RING + 8); // raiders come from beyond the test's own area
        sim = SimRealm.start(SimRealm.DEFAULT_MAP, 1, 0L);
        realm = new Realm();
        realm.startSim(sim);
        realm.setSimPlace(TOWN, level.dimension(), at);
        realm.setSimPlace(FROM, level.dimension(), at.offset(200, 0, 0));
        Node town = sim.game.node(TOWN);
        town.owner = SimRealm.PLAYERS;
        town.tier = tier;
        town.garrison = TROOPS;
        town.defense = 1;
        Node from = sim.game.node(FROM);
        from.owner = "go";
        from.garrison = 10;
        RealmSimTurns.mirror(realm, sim);
        this.town = realm.node(TOWN).orElseThrow();
    }

    /** A Hearthbound hamlet (tier 1) on the test's ground, beside a goblin farm with 10 troops. */
    static SimFrontier of(GameTestHelper helper) {
        return new SimFrontier(helper, 1);
    }

    static SimFrontier of(GameTestHelper helper, int tier) {
        return new SimFrontier(helper, tier);
    }

    /** The goblins raid the town with all 10, at dusk: the realm's raid on it. */
    Realm.Raid raid() {
        held = sim.declare("go", Action.raid(FROM, TOWN, 10, "gold"), 0L);
        RealmSimTurns.mirror(realm, sim);
        return realm.raids().stream().filter(r -> r.town().equals(TOWN)).findFirst().orElseThrow();
    }

    /** The raid fought live, as it is at dusk with a player there. */
    RealmLiveRaids.LiveRaid fight(Realm.Raid raid, ServerLevel level) {
        sim.fightLive(held.id);
        return RealmLiveRaids.start(realm, raid, level);
    }

    /** Whether the goblins won the raid (it's been settled). */
    boolean raidersWon() {
        return sim.game.kingdom("go").stats.raidsWon > 0;
    }

    boolean settled() {
        return sim.game.held.stream().noneMatch(b -> b.id == held.id);
    }
}
