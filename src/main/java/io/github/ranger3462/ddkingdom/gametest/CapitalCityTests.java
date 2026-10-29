package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmCapitals;
import io.github.ranger3462.ddkingdom.realm.RealmEconomy;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTiers;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks capitals as cities (DD-104): sacked but never taken, doubly defended, and home to a nation's garrison. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class CapitalCityTests {
    private static final String ANY_TEMPLATE = "test/empty";

    private static RealmNode node(String id, RealmNode.Type type, String owner, int x) {
        return new RealmNode(id, type, owner, Level.OVERWORLD, new BlockPos(x * 50, 64, 0));
    }

    /** A monster capital is a city the kingdom can sack, at double defense, but never take. */
    @GameTest(template = ANY_TEMPLATE)
    public static void capitalsAreSackedNotTaken(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode town = node("town", RealmNode.Type.TOWN, RealmNode.KINGDOM, 0);
        RealmNode warren = node("warren", RealmNode.Type.CAPITAL, DungeonFactions.GOBLINS, 1);
        realm.add(town);
        realm.add(warren);
        realm.link("town", "warren");
        realm.setStat(town, "tier", 1);
        helper.assertTrue(warren.settlement() && RealmTiers.tier(warren) == RealmTiers.MAX_TIER && warren.stat("defense") > 0 && warren.stat("wealth") > 0,
                "a capital is a city, with a town's stats");
        helper.assertTrue(!RealmRaids.capturable(warren, RealmNode.KINGDOM), "it can't be taken");
        int own = warren.stat("defense") + warren.stat("military") / 2;
        helper.assertTrue(RealmRaids.defense(realm, warren) == own * RealmRaids.CAPITAL_DEFENSE, "its defense, garrison and all, is doubled: " + RealmRaids.defense(realm, warren));
        helper.assertTrue(RealmTurns.marchTarget(realm).isEmpty(), "the kingdom won't march on it while it's far stronger");

        realm.addKingdomStat("army", 400);
        helper.assertTrue(RealmTurns.marchTarget(realm).orElseThrow().getValue() == warren, "but will once it's very sure of winning");
        Realm.Raid raid = RealmRaids.declare(realm, "warren", 0L).orElseThrow();
        int troops = warren.stat("military");
        int wealth = warren.stat("wealth");
        int trade = RealmEconomy.trade(realm, RealmNode.KINGDOM);
        RealmRaids.decide(realm, raid, 300, 1, false);
        helper.assertTrue(DungeonFactions.GOBLINS.equals(warren.owner()) && RealmTiers.tier(warren) == RealmTiers.MAX_TIER, "sacked, it's still theirs, and still a city");
        helper.assertTrue(warren.stat("military") < troops && warren.stat("wealth") < wealth && RealmEconomy.trade(realm, RealmNode.KINGDOM) > trade,
                "but it lost troops and wealth, and the kingdom carried off trade");
        helper.succeed();
    }

    /** The kingdom's capital can be sacked by a stronghold linked to it, and stays the kingdom's. */
    @GameTest(template = ANY_TEMPLATE)
    public static void landfallCanBeSacked(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode landfall = node("landfall", RealmNode.Type.CAPITAL, RealmNode.KINGDOM, 0);
        RealmNode fort = node("fort", RealmNode.Type.STRONGHOLD, DungeonFactions.ORCS, 1);
        realm.add(landfall);
        realm.add(fort);
        realm.link("landfall", "fort");
        realm.setStat(fort, "military", 30);
        helper.assertTrue(RealmTurns.raidPlan(realm, DungeonFactions.ORCS).isEmpty(), "the orcs won't try it on poor odds");
        realm.setStat(fort, "military", 400);
        helper.assertTrue(RealmTurns.raidPlan(realm, DungeonFactions.ORCS).orElseThrow().target() == landfall, "only on very good odds");
        Realm.Raid raid = RealmRaids.declare(realm, "landfall", 0L).orElseThrow();
        int defense = landfall.stat("defense");
        RealmRaids.decide(realm, raid, 300, 1, false);
        helper.assertTrue(landfall.kingdoms() && landfall.stat("defense") < defense, "sacked, but still the kingdom's");
        helper.succeed();
    }

    /** New troops muster at home until its garrison is back to strength, and a lost stronghold's survivors fall back there. */
    @GameTest(template = ANY_TEMPLATE)
    public static void troopsMusterAndFallBackHome(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode capital = node("lair", RealmNode.Type.CAPITAL, DungeonFactions.GOBLINS, 0);
        RealmNode fort = node("fort", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 1);
        realm.add(capital);
        realm.add(fort);
        realm.setStat(capital, "military", 10);
        realm.setStat(fort, "military", 20);
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.BUILD_MILITARY, 0L);
        helper.assertTrue(capital.stat("military") > 10 && fort.stat("military") == 20, "a weak garrison is refilled first");
        realm.setStat(capital, "military", RealmCapitals.GARRISON);
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.BUILD_MILITARY, 0L);
        helper.assertTrue(fort.stat("military") > 20 && capital.stat("military") == RealmCapitals.GARRISON, "then the strongholds grow");

        int home = capital.stat("military");
        int survivors = fort.stat("military") / 2;
        realm.setOwner(fort, RealmNode.KINGDOM);
        helper.assertTrue(capital.stat("military") == home + survivors && fort.stat("military") == 0, "half a lost stronghold's troops fall back home");

        capital.stats().remove("defense");
        capital.stats().remove("tier");
        RealmNode loaded = realm.reloaded(helper.getLevel().registryAccess()).node("lair").orElseThrow();
        helper.assertTrue(loaded.stat("defense") == 20 && RealmTiers.tier(loaded) == RealmTiers.MAX_TIER, "an old capital becomes a city when loaded");
        helper.succeed();
    }
}
