package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the realm's turns (DD-95): the real-time clock and its catch-up cap, and each side's actions. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmTurnTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void turnsComeOnARealTimeClock(GameTestHelper helper) {
        Realm realm = frontier();
        RandomSource random = RandomSource.create(1);
        long length = RealmTurns.turnMillis();
        long start = 1_000_000L;
        helper.assertTrue(RealmTurns.catchUp(realm, start, random) == 0 && realm.turn() == 0, "the clock starts, with no turn yet");
        helper.assertTrue(RealmTurns.catchUp(realm, start + length - 1, random) == 0, "no turn before a turn's length has passed");
        helper.assertTrue(RealmTurns.catchUp(realm, start + length, random) == 1 && realm.turn() == 1, "one turn after one length");
        helper.assertTrue(RealmTurns.untilNext(realm, start + length) == length, "and the next is a whole length away");
        int cap = KingdomConfig.REALM_CATCH_UP_TURNS.get();
        int taken = RealmTurns.catchUp(realm, start + length * (cap + 50L), random);
        helper.assertTrue(taken == cap, "a long time away catches up only " + cap + " turns, took " + taken);
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("quiet for")), "and the log says how many were skipped");
        helper.assertTrue(RealmTurns.untilNext(realm, start + length * (cap + 50L)) == length, "the clock is caught up after");

        Realm empty = new Realm();
        RealmTurns.catchUp(empty, start, random);
        helper.assertTrue(RealmTurns.catchUp(empty, start + length * 5, random) == 0 && empty.turn() == 0, "a world with no realm takes no turns");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void eachSideActsOnItsSituation(GameTestHelper helper) {
        Realm realm = frontier();
        RealmNode riverhold = realm.node("riverhold").orElseThrow();
        RealmNode warren = realm.node("warren").orElseThrow();
        helper.assertTrue(RealmTurns.borderTowns(realm).size() == 2 && RealmTurns.threat(realm) == 10, "two border towns face 10 goblin military");

        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.REINFORCE);
        helper.assertTrue(riverhold.stat("defense") == 12 && realm.node("capital").orElseThrow().stat("defense") == 10,
                "reinforcing strengthens border towns only");
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MUSTER);
        helper.assertTrue(realm.kingdomStat("army") == 13, "mustering grows the army");
        int trade = io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, RealmNode.KINGDOM);
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.GATHER_WEALTH);
        helper.assertTrue(io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, RealmNode.KINGDOM) == trade + 3 && riverhold.stat("wealth") == 11,
                "gathering wealth fills the kingdom's trade and the towns");

        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.BUILD_MILITARY, 0L);
        helper.assertTrue(warren.stat("military") == 13,
                "building military strengthens the strongholds, faster for the fast-growing goblins: " + warren.stat("military"));
        realm.setStat(warren, "military", 12);
        realm.setStat(realm.node("millbrook").orElseThrow(), "defense", 30);
        realm.setStat(riverhold, "defense", 30);
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.RAID) == 0
                && RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.BUILD_MILITARY) == 7,
                "outmatched by every town, the goblins build up rather than raid");
        realm.setStat(riverhold, "defense", 12);
        realm.setStat(realm.node("millbrook").orElseThrow(), "defense", 8);
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.RAID) > 0,
                "a match for millbrook (12 against 8 and the army's 3), they may raid");
        helper.assertTrue(RealmTurns.raidTarget(realm, DungeonFactions.GOBLINS).orElseThrow().getValue().id.equals("millbrook"),
                "the goblins would raid the weaker town (millbrook, 8 defense against riverhold's 12)");
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.RAID, 0L);
        helper.assertTrue(realm.raids().size() == 1 && realm.raids().get(0).town().equals("millbrook"), "a raid is declared");
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.RAID) == 0,
                "and no second raid while one is pending");

        Realm reloaded = realm.reloaded(helper.getLevel().registryAccess());
        helper.assertTrue(reloaded.raids().size() == 1 && reloaded.kingdomStat("army") == 13 && !reloaded.log().isEmpty(),
                "raids, the kingdom's stats and the log are saved");

        RealmNode millbrook = realm.node("millbrook").orElseThrow();
        int wealth = millbrook.stat("wealth");
        int taken = Math.min(wealth, 2 + 30 / 4);
        RealmRaids.decide(realm, realm.raids().get(0), 40, 10, false);
        helper.assertTrue(warren.stat("military") == 12 - 1 + RealmRaids.plunder(taken),
                "a sacking's plunder swells the raiders' military: " + warren.stat("military"));

        Realm alone = new Realm();
        alone.add(node("hermitage", RealmNode.Type.TOWN, RealmNode.KINGDOM, 0));
        helper.assertTrue(RealmTurns.kingdomWeights(alone).get(RealmTurns.KingdomAction.REINFORCE) == 0, "with no border, the kingdom never reinforces");
        for (int i = 0; i < 20; i++) {
            RealmTurns.takeTurn(alone, RandomSource.create(i), 0L);
        }
        helper.assertTrue(alone.turn() == 20 && alone.nations().isEmpty(), "turns run with no monster nation at all");
        helper.succeed();
    }

    /** Raids resolve at dusk by a roll (DD-96). */
    @GameTest(template = ANY_TEMPLATE)
    public static void raidsResolveAtDuskByARoll(GameTestHelper helper) {
        Realm realm = frontier();
        realm.setStat(realm.node("riverhold").orElseThrow(), "defense", 30); // So millbrook is the one raided.
        RealmNode millbrook = realm.node("millbrook").orElseThrow();
        RealmNode warren = realm.node("warren").orElseThrow();
        long length = RealmTurns.turnMillis();
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.RAID, 1000L);
        Realm.Raid raid = realm.raids().get(0);
        helper.assertTrue(raid.resolveAt() == 1000L + length / 2, "a raid resolves half a turn after its dawn");
        helper.assertTrue(realm.takeNews().size() == 1, "and its declaring is news");
        helper.assertTrue(RealmRaids.resolveDue(realm, raid.resolveAt() - 1, RandomSource.create(1)) == 0 && realm.raids().size() == 1,
                "not resolved before dusk");

        // Defense: millbrook's 10 plus a quarter of the army's 10 (2) = 12. The warren's military is 10.
        helper.assertTrue(RealmRaids.defense(realm, millbrook) == 12, "a town's defense counts a quarter of the army");
        helper.assertTrue(RealmRaids.resolve(realm, raid, 20, 1), "30 against 13: the goblins win");
        helper.assertTrue(millbrook.stat("defense") == 6 && millbrook.stat("wealth") == 4 && millbrook.stat("morale") == 8 && millbrook.stat("damage") == 17,
                "the town loses defense (17/4), wealth (2 + 17/4) and morale (2), and is owed 17 damage; got " + millbrook.stats());
        helper.assertTrue(warren.stat("military") == 10 - 1 + RealmRaids.plunder(6) && realm.raids().isEmpty(),
                "the attackers lose a little but carry off plunder (6 wealth), and the raid is over");
        helper.assertTrue(realm.takeNews().get(0).text().contains("sacked"), "and players hear it");

        realm.setStat(warren, "military", 9);
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.RAID, 2000L);
        helper.assertFalse(RealmRaids.resolve(realm, realm.raids().get(0), 1, 20), "10 against 26: the town holds");
        helper.assertTrue(warren.stat("military") == 3 && millbrook.stat("morale") == 9, "the goblins lose 2 + 16/4, and the town takes heart");

        realm.setStat(warren, "military", 30);
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.RAID, 3000L);
        realm.remove("millbrook");
        helper.assertTrue(RealmRaids.resolveDue(realm, Long.MAX_VALUE, RandomSource.create(2)) == 1 && realm.raids().isEmpty(),
                "a raid on a town that's gone comes to nothing");

        Realm caught = frontier();
        RealmTurns.catchUp(caught, 10_000L, RandomSource.create(3));
        caught.setStat(caught.node("warren").orElseThrow(), "military", 200);
        RealmTurns.catchUp(caught, 10_000L + length * 6, RandomSource.create(4));
        helper.assertTrue(caught.raids().stream().allMatch(r -> r.resolveAt() > 10_000L + length * 5),
                "catching up resolves each turn's raids before the next turn; only the last turn's can still be waiting");
        helper.succeed();
    }

    /** Two border towns and an inland capital, against one goblin warren. */
    private static Realm frontier() {
        Realm realm = new Realm();
        realm.add(node("capital", RealmNode.Type.TOWN, RealmNode.KINGDOM, 0));
        realm.add(node("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, 1));
        realm.add(node("millbrook", RealmNode.Type.TOWN, RealmNode.KINGDOM, 2));
        realm.add(node("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 3));
        realm.link("capital", "riverhold");
        realm.link("capital", "millbrook");
        realm.link("riverhold", "warren");
        realm.link("millbrook", "warren");
        realm.nodes().stream().filter(node -> node.type == RealmNode.Type.TOWN).forEach(town -> realm.setStat(town, "tier", 1));
        return realm;
    }

    private static RealmNode node(String id, RealmNode.Type type, String owner, int x) {
        return new RealmNode(id, type, owner, Level.OVERWORLD, new BlockPos(x * 50, 64, 0));
    }
}
