package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
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

/** Checks the realm's economy (DD-103): purses of food and trade, what fills them, what they pay for, and resource nodes. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class EconomyTests {
    private static final String ANY_TEMPLATE = "test/empty";

    private static RealmNode node(String id, RealmNode.Type type, String owner, int x) {
        return new RealmNode(id, type, owner, Level.OVERWORLD, new BlockPos(x * 50, 64, 0));
    }

    /** Towns, strongholds and resources fill their holder's purse each turn; troops eat; trade speeds everything up. */
    @GameTest(template = ANY_TEMPLATE)
    public static void holdingsFillThePurse(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode town = node("town", RealmNode.Type.TOWN, RealmNode.KINGDOM, 0);
        RealmNode fields = node("fields", RealmNode.Type.RESOURCE, RealmNode.KINGDOM, 1);
        RealmNode market = node("market", RealmNode.Type.RESOURCE, RealmNode.KINGDOM, 2);
        realm.add(town);
        realm.add(fields);
        realm.add(market);
        realm.setKind(market, "port");
        realm.setStat(town, "tier", 2);
        helper.assertTrue(fields.kind().equals("farmland") && RealmEconomy.kind(market).orElseThrow() == RealmEconomy.Kind.MARKET,
                "a new resource is farmland until told otherwise, and a port is a market");
        RealmEconomy.Income income = RealmEconomy.income(realm, RealmNode.KINGDOM);
        helper.assertTrue(income.food() == 2 + 3 && income.trade() == 2 + 3 && income.upkeep() == 0, "a village and two resources bring in: " + income);
        RealmEconomy.collect(realm);
        helper.assertTrue(RealmEconomy.food(realm, RealmNode.KINGDOM) == RealmEconomy.START + 5 && RealmEconomy.trade(realm, RealmNode.KINGDOM) == RealmEconomy.START + 5,
                "and a turn's takings go in the purse");

        RealmEconomy.set(realm, RealmNode.KINGDOM, RealmEconomy.TRADE, 50);
        helper.assertTrue(RealmEconomy.factor(realm, RealmNode.KINGDOM) == 2.0, "50 trade doubles what actions give");
        int army = realm.kingdomStat("army");
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MUSTER);
        helper.assertTrue(realm.kingdomStat("army") == army + 6 && RealmEconomy.trade(realm, RealmNode.KINGDOM) == 50 - RealmEconomy.ACTION_COST,
                "so a rich kingdom musters twice the troops, and pays for it");
        RealmEconomy.set(realm, RealmNode.KINGDOM, RealmEconomy.TRADE, 0);
        helper.assertTrue(RealmEconomy.spend(realm, RealmNode.KINGDOM, 4) == 2, "a broke one gets half");

        realm.reloaded(helper.getLevel().registryAccess());
        helper.assertTrue(realm.reloaded(helper.getLevel().registryAccess()).purse(RealmNode.KINGDOM).get(RealmEconomy.FOOD) == RealmEconomy.START + 5,
                "purses are saved");
        helper.succeed();
    }

    /** Troops that can't be fed desert; towns need food to grow. */
    @GameTest(template = ANY_TEMPLATE)
    public static void hungerAndGrowth(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode warren = node("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 0);
        realm.add(warren);
        realm.setStat(warren, "military", 100);
        RealmEconomy.set(realm, DungeonFactions.GOBLINS, RealmEconomy.FOOD, 0);
        helper.assertTrue(RealmEconomy.upkeep(realm, DungeonFactions.GOBLINS) == 5, "100 troops eat 5 food a turn");
        RealmEconomy.collect(realm);
        helper.assertTrue(warren.stat("military") < 100 && RealmEconomy.food(realm, DungeonFactions.GOBLINS) == 0
                && realm.log().stream().anyMatch(entry -> entry.kind() == Realm.LogKind.SUPPLY), "with only the warren's own food coming in, some desert: " + warren.stat("military"));

        RealmNode lair = node("lair", RealmNode.Type.CAPITAL, "orcs", 1);
        realm.add(lair);
        realm.setStat(lair, "military", RealmEconomy.CAPITAL_FOOD * RealmEconomy.TROOPS_PER_FOOD);
        helper.assertTrue(RealmEconomy.income(realm, "orcs").food() >= RealmEconomy.upkeep(realm, "orcs"),
                "a capital alone feeds a garrison of " + lair.stat("military"));

        RealmNode town = node("town", RealmNode.Type.TOWN, RealmNode.KINGDOM, 2);
        realm.add(town);
        realm.setStat(town, "tier", 1);
        realm.setStat(town, "wealth", 40);
        RealmEconomy.set(realm, RealmNode.KINGDOM, RealmEconomy.FOOD, RealmTiers.food(1) - 1);
        RealmTiers.grow(realm);
        helper.assertTrue(!RealmTiers.upgrading(town), "a town won't grow without the food for it");
        RealmEconomy.set(realm, RealmNode.KINGDOM, RealmEconomy.FOOD, RealmTiers.food(1));
        RealmTiers.grow(realm);
        helper.assertTrue(RealmTiers.upgrading(town) && RealmEconomy.food(realm, RealmNode.KINGDOM) == 0, "with it, it grows and eats it");
        helper.succeed();
    }

    /** Resources are fought over: anyone can take them, the kingdom marches on them, and mines, camps and arcane sites work where they stand. */
    @GameTest(template = ANY_TEMPLATE)
    public static void resourcesAreWorthFightingFor(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode town = node("town", RealmNode.Type.TOWN, RealmNode.KINGDOM, 0);
        RealmNode mine = node("mine", RealmNode.Type.RESOURCE, RealmNode.KINGDOM, 1);
        RealmNode camp = node("camp", RealmNode.Type.RESOURCE, DungeonFactions.GOBLINS, 2);
        RealmNode warren = node("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 3);
        RealmNode hamlet = node("hamlet", RealmNode.Type.TOWN, DungeonFactions.GOBLINS, 4);
        realm.add(town);
        realm.add(mine);
        realm.add(camp);
        realm.add(warren);
        realm.add(hamlet);
        realm.setKind(mine, "mine");
        realm.setKind(camp, "lumber");
        realm.link("town", "mine");
        realm.link("town", "camp");
        realm.link("town", "hamlet");
        realm.link("camp", "warren");
        realm.setStat(town, "tier", 1);
        realm.setStat(hamlet, "tier", 1);
        int before = RealmRaids.defense(realm, town);
        helper.assertTrue(RealmEconomy.mineDefense(realm, town) == RealmEconomy.MINE_DEFENSE, "the town's own mine adds to its defense");
        helper.assertTrue(RealmRaids.capturable(camp, RealmNode.KINGDOM) && RealmRaids.capturable(mine, DungeonFactions.GOBLINS),
                "the kingdom and the monsters can both take resources");
        helper.assertTrue(RealmTurns.marchTarget(realm).orElseThrow().getValue() == camp, "the kingdom marches on the goblins' camp before sacking a town");

        int fortification = warren.stat("fortification");
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.FORTIFY, 0L);
        int plain = RealmEconomy.spend(new Realm(), DungeonFactions.GOBLINS, 1);
        helper.assertTrue(warren.stat("fortification") == fortification + plain + 1, "a lumber camp adds to the goblins' fortifying");

        realm.setOwner(mine, DungeonFactions.GOBLINS);
        helper.assertTrue(RealmRaids.defense(realm, town) == before - RealmEconomy.MINE_DEFENSE, "lose the mine, lose its defense");

        RealmNode spire = node("spire", RealmNode.Type.CAPITAL, "elementals", 6);
        RealmNode ley = node("ley", RealmNode.Type.RESOURCE, "elementals", 7);
        realm.add(spire);
        realm.add(ley);
        realm.setKind(ley, "arcane");
        helper.assertTrue(RealmEconomy.income(realm, "elementals").trade() == 3 + 2, "the elementals get twice the trade from an arcane site");
        helper.assertTrue(RealmEconomy.arcane(realm, "elementals") == RealmEconomy.ARCANE_CHAMPION, "and it strengthens their champion");

        RealmEconomy.set(realm, RealmNode.KINGDOM, RealmEconomy.TRADE, 20);
        int goblinTrade = RealmEconomy.trade(realm, DungeonFactions.GOBLINS);
        realm.setStat(warren, "military", 60);
        realm.link("warren", "town");
        Realm.Raid sack = RealmRaids.declare(realm, "town", 0L).orElseThrow();
        RealmRaids.decide(realm, sack, 60, 1, false);
        helper.assertTrue(RealmEconomy.trade(realm, RealmNode.KINGDOM) < 20 && RealmEconomy.trade(realm, DungeonFactions.GOBLINS) > goblinTrade,
                "a sack carries off the kingdom's trade");
        helper.succeed();
    }
}
