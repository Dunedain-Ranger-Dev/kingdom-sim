package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.dungeondelving.dungeon.challenge.DungeonBosses;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmCapitals;
import io.github.ranger3462.ddkingdom.realm.RealmLevels;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks nation capitals (DD-101). */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class CapitalTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void capitalsCantBeTakenAndStayDangerous(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.CAPITAL, RealmNode.KINGDOM, false).isPresent()
                && Realm.ownerProblem(RealmNode.Type.CAPITAL, RealmNode.NONE, false).isPresent(), "a capital can't be taken or cleared");
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.CAPITAL, DungeonFactions.ORCS, true).isEmpty(), "but a nation can found one");
        Realm realm = new Realm();
        realm.add(new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO));
        RealmNode capital = new RealmNode("grimhold", RealmNode.Type.CAPITAL, DungeonFactions.ORCS, dim, new BlockPos(150, 64, 0));
        realm.add(capital);
        realm.setStat(capital, "military", 0);
        int level = RealmLevels.level(realm, dim, new BlockPos(150, 64, 0));
        helper.assertTrue(level >= RealmLevels.CAPITAL_FLOOR, "even with no military, a capital's heart is dangerous: " + level);
        helper.assertTrue(realm.nations().contains(DungeonFactions.ORCS), "a nation with only its capital still acts");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void theKingdomsCapitalMarchesOnTheRuinsAroundIt(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.CAPITAL, RealmNode.KINGDOM, true).isEmpty(), "the kingdom can found a capital");
        Realm realm = new Realm();
        RealmNode capital = new RealmNode("crownhold", RealmNode.Type.CAPITAL, RealmNode.KINGDOM, dim, BlockPos.ZERO);
        realm.add(capital);
        RealmNode ruins = new RealmNode("ashford", RealmNode.Type.TOWN, RealmNode.NONE, dim, new BlockPos(100, 64, 0));
        realm.add(ruins);
        realm.link("crownhold", "ashford");
        realm.setStat(ruins, "defense", 30);
        helper.assertTrue(!RealmCapitals.pushedBack(realm, RealmNode.KINGDOM) && !realm.nations().contains(RealmNode.KINGDOM),
                "the kingdom's capital never rallies like a nation's");
        helper.assertTrue(RealmTurns.marchTarget(realm).orElseThrow().getValue() == ruins, "with only its capital, the kingdom looks to the ruins beside it");
        helper.assertTrue(RealmTurns.kingdomWeights(realm).get(RealmTurns.KingdomAction.MARCH) == 0,
                "but while it's outmatched (" + RealmRaids.kingdomAttack(realm) + " against " + RealmRaids.defense(realm, ruins) + "), it doesn't march");
        int marginAtBar = 0;
        while (io.github.ranger3462.ddkingdom.realm.RealmFactions.winChance(marginAtBar) < io.github.ranger3462.ddkingdom.realm.RealmFactions.get(RealmNode.KINGDOM).attackBar()) {
            marginAtBar++;
        }
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MUSTER);
        helper.assertTrue(RealmTurns.kingdomWeights(realm).get(RealmTurns.KingdomAction.MARCH) == 0 || RealmRaids.kingdomAttack(realm) - 30 >= marginAtBar,
                "a bare edge isn't enough: it marches only when fairly sure (" + marginAtBar + " ahead)");
        while (RealmRaids.kingdomAttack(realm) - 30 < marginAtBar) {
            RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MUSTER);
        }
        helper.assertTrue(RealmRaids.kingdomAttack(realm) == realm.kingdomStat("army") / 2 + RealmCapitals.CHAMPION_BONUS, "its champion leads the march");
        helper.assertTrue(RealmTurns.kingdomWeights(realm).get(RealmTurns.KingdomAction.MARCH) > 0, "once it's strong enough, it marches");
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MARCH, 0L);
        Realm.Raid raid = realm.raids().get(0);
        helper.assertTrue(raid.stronghold().equals("crownhold") && realm.log().stream().anyMatch(entry -> entry.text().contains("led by its champion")),
                "from its capital, led by its champion");
        helper.assertTrue(RealmRaids.decide(realm, raid, 50, 1, false) && ruins.kingdoms(), "and takes the ruins");
        int treasury = io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, RealmNode.KINGDOM);
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.GATHER_WEALTH);
        helper.assertTrue(io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, RealmNode.KINGDOM) == treasury + 2,
                "the capital pays into the kingdom's trade as well as the town");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void aPushedBackNationRalliesAndSendsItsChampion(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        RealmNode riverhold = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO);
        realm.add(riverhold);
        realm.setStat(riverhold, "tier", 1);
        RealmNode capital = new RealmNode("grimhold", RealmNode.Type.CAPITAL, DungeonFactions.ORCS, dim, new BlockPos(400, 64, 0));
        realm.add(capital);
        RealmNode camp = new RealmNode("camp", RealmNode.Type.STRONGHOLD, DungeonFactions.ORCS, dim, new BlockPos(200, 64, 0));
        realm.add(camp);
        for (int turn = 0; turn < RealmCapitals.RALLY_TURNS + 1; turn++) {
            helper.assertTrue(RealmCapitals.rally(realm, 0L) == 0, "while it holds a stronghold, its capital waits");
        }
        realm.setOwner(camp, RealmNode.KINGDOM);
        helper.assertTrue(RealmCapitals.pushedBack(realm, DungeonFactions.ORCS), "with its last stronghold taken, it's pushed back");
        int marched = 0;
        for (int turn = 0; turn < RealmCapitals.RALLY_TURNS; turn++) {
            marched += RealmCapitals.rally(realm, 0L);
        }
        helper.assertTrue(marched == 1 && realm.raids().size() == 1, "after " + RealmCapitals.RALLY_TURNS + " turns rallying, its champion marches");
        Realm.Raid raid = realm.raids().get(0);
        helper.assertTrue(raid.town().equals("camp") && RealmCapitals.isChampionRaid(realm, raid), "to retake its nearest stronghold: " + raid.town());
        helper.assertTrue(RealmCapitals.rally(realm, 0L) == 0, "one champion at a time");

        int before = capital.stat("military");
        helper.assertTrue(RealmRaids.resolve(realm, raid, 20, 1), "the champion wins");
        helper.assertTrue(DungeonFactions.ORCS.equals(camp.owner()) && camp.stat("military") == RealmRaids.GARRISON,
                "and the orcs hold the stronghold again, garrisoned: " + camp.owner() + " " + camp.stat("military"));
        helper.assertTrue(capital.stat("military") == before - RealmRaids.GARRISON + RealmCapitals.VICTORY_MILITARY,
                "the garrison comes from the capital, and the win lets it rebuild faster: " + capital.stat("military"));
        helper.assertTrue(!RealmCapitals.pushedBack(realm, DungeonFactions.ORCS) && realm.log().stream()
                .anyMatch(entry -> entry.news() && entry.text().contains("orcs have taken camp")), "back in the field, with news");

        realm.setOwner(camp, RealmNode.KINGDOM);
        Realm.Raid declared = RealmRaids.declare(realm, "camp", 0L).orElseThrow();
        helper.assertTrue(declared.stronghold().equals("grimhold"), "a lost stronghold with no neighbour to attack it can be raided by the capital");
        RealmRaids.decide(realm, declared, 0, 100, false);
        realm.remove("camp");
        for (int turn = 0; turn < RealmCapitals.RALLY_TURNS; turn++) {
            RealmCapitals.rally(realm, 0L);
        }
        helper.assertTrue(realm.raids().size() == 1 && realm.raids().get(0).town().equals("riverhold"), "with no stronghold left to retake, it marches on the nearest town");
        RealmRaids.resolve(realm, realm.raids().get(0), 20, 1);
        helper.assertTrue(realm.node("riverhold").orElseThrow().owner().equals(RealmNode.KINGDOM), "a town above ruins is sacked, not taken");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void nationsTakeBackWhatBordersThemBeforeRaidingTowns(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO);
        realm.add(town);
        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, dim, new BlockPos(300, 64, 0));
        realm.add(warren);
        RealmNode fort = new RealmNode("old_fort", RealmNode.Type.STRONGHOLD, RealmNode.KINGDOM, dim, new BlockPos(200, 64, 0));
        realm.add(fort);
        RealmNode mine = new RealmNode("mine", RealmNode.Type.SITE, RealmNode.KINGDOM, dim, new BlockPos(250, 64, 0));
        realm.add(mine);
        realm.link("warren", "riverhold");
        realm.link("warren", "old_fort");
        realm.link("warren", "mine");
        var target = io.github.ranger3462.ddkingdom.realm.RealmTurns.raidTarget(realm, DungeonFactions.GOBLINS).orElseThrow();
        helper.assertTrue(target.getValue().id.equals("mine"), "the nearest place to take comes before the town: " + target.getValue().id);

        realm.setStat(mine, "fortification", 3);
        Realm.Raid raid = new Realm.Raid(DungeonFactions.GOBLINS, "warren", "mine", 0, 0L);
        helper.assertTrue(RealmRaids.defense(realm, mine) == 3 + realm.kingdomStat("army") / RealmRaids.ARMY_SHARE, "a held site defends with its fortification and the army");
        helper.assertFalse(RealmRaids.decide(realm, raid, 1, 50, false), "driven off");
        helper.assertTrue(mine.owner().equals(RealmNode.KINGDOM), "it stays the kingdom's");
        helper.assertTrue(RealmRaids.decide(realm, raid, 50, 1, false) && mine.owner().equals(DungeonFactions.GOBLINS), "a win takes it");
        helper.assertTrue(io.github.ranger3462.ddkingdom.realm.RealmTurns.raidTarget(realm, DungeonFactions.GOBLINS).orElseThrow().getValue().id
                .equals("old_fort"), "then the next nearest");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void killingTheChampionSetsTheNationBack(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = Realm.get(level.getServer());
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        RealmNode capital = new RealmNode("test_capital_" + Math.abs(at.asLong() % 100000), RealmNode.Type.CAPITAL, DungeonFactions.ORCS, level.dimension(), at);
        realm.add(capital);
        try {
            realm.setStat(capital, "military", 60);
            Mob champion = RealmCapitals.spawnChampion(level, at, capital);
            helper.assertTrue(DungeonBosses.isBoss(champion) && champion.getCustomName().getString().contains("Champion of the orcs"),
                    "the champion is one of its nation's bosses");
            champion.kill();
            helper.assertTrue(capital.stat("military") == 40, "killing it costs the capital a third of its military: " + capital.stat("military"));
            helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("slew the champion of the orcs")), "with news");
        } finally {
            realm.remove(capital.id);
            level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(at).inflate(16)).forEach(Mob::discard);
        }
        helper.succeed();
    }
}
