package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmGarrison;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmSimTurns;
import io.github.ranger3462.ddkingdom.realm.SimRealm;
import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.Node;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The realm by the simulator's rules (docs/design/realm-cutover.md, phases 2 and 3): saved with the world, and mirrored onto it. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmSimTests {
    private static final String ANY_TEMPLATE = "test/empty";
    private static final long START = 1_000_000L, HOUR = 3_600_000L;

    @GameTest(template = ANY_TEMPLATE)
    public static void theSimulatorsRealmIsSavedWithTheWorld(GameTestHelper helper) {
        Realm realm = new Realm();
        // An old node with a simulator node's id: the new realm places it where the old one stands.
        realm.add(new RealmNode("hb_town", RealmNode.Type.TOWN, RealmNode.KINGDOM, Level.OVERWORLD, new BlockPos(100, 64, -40)));
        SimRealm sim = SimRealm.start(SimRealm.DEFAULT_MAP, 7, START);
        realm.startSim(sim);
        realm.setSimPlace("ko_cap", Level.OVERWORLD, new BlockPos(-300, 70, 25));
        sim.catchUp(START + HOUR * 5, HOUR, 24);
        helper.assertTrue(realm.simPlace("hb_town").map(p -> p.pos().equals(new BlockPos(100, 64, -40))).orElse(false), "placed where the old node stands");

        Realm loaded = realm.reloaded(helper.getLevel().registryAccess());
        helper.assertTrue(loaded.sim().isPresent(), "the simulator's game comes back");
        helper.assertTrue(loaded.sim().get().save().equals(sim.save()), "exactly as it was");
        helper.assertTrue(loaded.simPlace("ko_cap").map(p -> p.pos().equals(new BlockPos(-300, 70, 25)) && p.dimension().equals(Level.OVERWORLD)).orElse(false),
                "and where its nodes stand");
        helper.assertTrue(loaded.simPlace("hb_town").isPresent() && loaded.simPlace("go_cap").isEmpty(), "unplaced nodes stay unplaced");
        helper.assertTrue(new Realm().reloaded(helper.getLevel().registryAccess()).sim().isEmpty(), "a realm never started has none");
        helper.succeed();
    }

    /** A realm started by the simulator's rules with every node placed, in a row. */
    private static Realm placedEverywhere(SimRealm sim) {
        Realm realm = new Realm();
        realm.add(new RealmNode("old_fort", RealmNode.Type.STRONGHOLD, "goblins", Level.OVERWORLD, new BlockPos(0, 64, 500)));
        realm.startSim(sim);
        for (int i = 0; i < sim.game.nodes.size(); i++) {
            realm.setSimPlace(sim.game.nodes.get(i).id, Level.OVERWORLD, new BlockPos(i * 20, 64, 0));
        }
        return realm;
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void theWorldMirrorsTheSimulator(GameTestHelper helper) {
        {
            SimRealm sim = SimRealm.start(SimRealm.DEFAULT_MAP, 3, START);
            Realm realm = placedEverywhere(sim);
            RealmSimTurns.mirror(realm, sim);
            helper.assertTrue(realm.node("old_fort").isEmpty(), "a node not on the simulator's map goes");
            helper.assertTrue(realm.nodes().size() == sim.game.nodes.size(), "every placed node is mirrored");
            RealmNode kobolds = realm.node("ko_cap").orElseThrow(), players = realm.node("hb_cap").orElseThrow();
            helper.assertTrue(kobolds.type == RealmNode.Type.CAPITAL && "kobolds".equals(kobolds.owner()), "a capital is its nation's");
            helper.assertTrue(RealmNode.KINGDOM.equals(players.owner()), "the Hearthbound are the kingdom");
            helper.assertTrue(kobolds.stat("military") == sim.game.node("ko_cap").garrison, "troops are military");
            int edges = sim.game.nodes.stream().mapToInt(n -> sim.game.neighbours(n.id).size()).sum() / 2;
            helper.assertTrue(realm.linkCount() == edges, "the simulator's links, " + realm.linkCount() + " of " + edges);

            // Troops changed in the world go back into the simulator.
            Node town = sim.game.node("hb_town");
            int garrison = town.garrison;
            RealmNode hbTown = realm.node("hb_town").orElseThrow();
            realm.setStat(hbTown, "military", hbTown.stat("military") + 5);
            RealmSimTurns.writeBack(realm, sim);
            helper.assertTrue(town.garrison == garrison + 5, "a change in the world goes back");
            RealmSimTurns.mirror(realm, sim);

            // Devastation breaks blocks, and repairing rebuilds them.
            town.devastation = 0.3;
            RealmSimTurns.mirror(realm, sim);
            helper.assertTrue(hbTown.stat("damage") == 30, "devastation is damage, got " + hbTown.stat("damage"));
            town.devastation = 0.1;
            RealmSimTurns.mirror(realm, sim);
            helper.assertTrue(hbTown.stat("repair") == 20 * io.github.ranger3462.ddkingdom.realm.RealmDamage.BLOCKS_PER_POINT, "less of it is rebuilt");

            // A Hearthbound town turns its troops out as guards, up to the most.
            town.owner = "hb";
            town.tier = Math.max(1, town.tier);
            town.garrison = 30;
            RealmSimTurns.mirror(realm, sim);
            helper.assertTrue(RealmGarrison.fullStrength(hbTown) == io.github.ranger3462.ddkingdom.realm.RealmLiveRaids.MAX_GUARDS, "guards from troops");

            // A held attack is a raid declared at dusk, and a fight's result settles it.
            sim.playersNear = (kid, target) -> true;
            long now = START;
            for (int i = 0; i < 300 && sim.game.held.isEmpty(); i++) {
                now += HOUR;
                sim.catchUp(now, HOUR, 24);
            }
            helper.assertTrue(!sim.game.held.isEmpty(), "someone attacked the Hearthbound");
            RealmSimTurns.mirror(realm, sim);
            Game.HeldBattle held = sim.game.held.get(0);
            Realm.Raid raid = realm.raids().stream().filter(r -> r.town().equals(held.target) && r.stronghold().equals(held.from)).findFirst().orElse(null);
            helper.assertTrue(raid != null && raid.resolveAt() == sim.dusk(held.id), "shown as a raid at dusk");
            RealmRaids.decide(realm, raid, 100, 1, true);
            helper.assertTrue(sim.game.held.stream().noneMatch(b -> b.id == held.id), "the fight settles the held attack");
            helper.assertTrue(realm.raids().stream().noneMatch(r -> r == raid), "and the raid's over");
            if (held.kind != io.github.ranger3462.ddkingdom.sim.Action.Type.RAID) {
                helper.assertTrue(held.kid.equals(sim.game.node(held.target).owner), "a capture won in the world takes the node");
            }
        }
        helper.succeed();
    }
}
