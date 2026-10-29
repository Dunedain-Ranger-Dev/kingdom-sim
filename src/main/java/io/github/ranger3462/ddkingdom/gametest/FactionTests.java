package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmFactions;
import io.github.ranger3462.ddkingdom.realm.RealmLevels;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the realm factions' traits (DD-102): odds, whom they attack, securing, raising the dead, blight and bursts. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class FactionTests {
    private static final String ANY_TEMPLATE = "test/empty";
    private static final String KOBOLDS = "kobolds";
    private static final String ELEMENTALS = "elementals";

    private static RealmNode node(String id, RealmNode.Type type, String owner, int x) {
        return new RealmNode(id, type, owner, Level.OVERWORLD, new BlockPos(x * 50, 64, 0));
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void theOddsAndTheFactionsLoad(GameTestHelper helper) {
        helper.assertTrue(RealmFactions.winChance(0) == 0.475, "an even fight is a little under half (a tie holds): " + RealmFactions.winChance(0));
        helper.assertTrue(RealmFactions.winChance(7) > 0.75 && RealmFactions.winChance(6) < 0.75 && RealmFactions.winChance(12) > 0.9
                && RealmFactions.winChance(-20) == 0 && RealmFactions.winChance(20) == 1, "and the odds rise with the margin");
        RealmFactions.Traits goblins = RealmFactions.get(DungeonFactions.GOBLINS);
        helper.assertTrue(goblins.growth() == 1.4 && goblins.champion().equals("Nixa the Tinker-Queen") && goblins.capital().equals("Gristlehold"),
                "the goblins' file loads: " + goblins);
        helper.assertTrue(RealmFactions.nations().contains(KOBOLDS) && RealmFactions.nations().contains(ELEMENTALS) && Realm.validOwner(KOBOLDS)
                && !RealmFactions.nations().contains(RealmNode.KINGDOM), "the kobolds and elementals are nations; the kingdom isn't");
        helper.assertTrue(!RealmFactions.mobs(KOBOLDS).isEmpty() && RealmFactions.bossFaction(KOBOLDS).equals(DungeonFactions.GOBLINS)
                && RealmFactions.mobs(DungeonFactions.ORCS).equals(DungeonFactions.mobs(DungeonFactions.ORCS)),
                "nations without their own mobs raid with stand-ins; the rest with their dungeon mobs");
        helper.assertTrue(RealmFactions.get(RealmNode.KINGDOM).attackBar() == 0.75, "the kingdom marches only when fairly sure");
        helper.succeed();
    }

    /** Goblins leave a strong orc warren alone but pounce on a weak one; orcs go for anything. */
    @GameTest(template = ANY_TEMPLATE)
    public static void whomEachNationAttacks(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode warren = node("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 0);
        RealmNode camp = node("camp", RealmNode.Type.STRONGHOLD, DungeonFactions.ORCS, 1);
        realm.add(warren);
        realm.add(camp);
        realm.link("warren", "camp");
        realm.setStat(warren, "military", 20);
        realm.setStat(camp, "fortification", 14);
        helper.assertTrue(RealmTurns.raidPlan(realm, DungeonFactions.GOBLINS).isEmpty(),
                "goblins don't take on other monsters at even odds (" + RealmFactions.winChance(20 - 14) + ")");
        realm.setStat(camp, "fortification", 5);
        helper.assertTrue(RealmTurns.raidPlan(realm, DungeonFactions.GOBLINS).map(plan -> plan.target() == camp).orElse(false),
                "but they do once its defense is weak");
        realm.setStat(camp, "military", 20);
        realm.setStat(warren, "fortification", 20);
        helper.assertTrue(RealmTurns.raidPlan(realm, DungeonFactions.ORCS).map(plan -> plan.target() == warren).orElse(false),
                "orcs go for anyone, the chance aside (weighed when they choose)");
        helper.assertTrue(RealmRaids.attack(realm, DungeonFactions.ORCS, camp) == 24, "and hit 20% harder: " + RealmRaids.attack(realm, DungeonFactions.ORCS, camp));
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.ORCS).get(RealmTurns.NationAction.RAID) > 0,
                "at 24 against 20 they're keen");
        realm.setStat(warren, "fortification", 30);
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.ORCS).get(RealmTurns.NationAction.RAID) == 0,
                "but not at 24 against 30, below their bar");

        // Kobolds stay near home: two links from their capital.
        Realm peaks = new Realm();
        peaks.add(node("vyrnhallow", RealmNode.Type.CAPITAL, KOBOLDS, 0));
        peaks.add(node("den", RealmNode.Type.STRONGHOLD, KOBOLDS, 1));
        peaks.add(node("near", RealmNode.Type.SITE, RealmNode.NONE, 2));
        peaks.add(node("mid", RealmNode.Type.STRONGHOLD, KOBOLDS, 3));
        peaks.add(node("far", RealmNode.Type.SITE, RealmNode.NONE, 4));
        peaks.link("vyrnhallow", "den");
        peaks.link("den", "near");
        peaks.link("near", "mid");
        peaks.link("mid", "far");
        peaks.nodes().forEach(place -> peaks.setStat(place, "military", 40));
        helper.assertTrue(RealmTurns.raidPlan(peaks, KOBOLDS).map(plan -> plan.target().id.equals("near")).orElse(false),
                "kobolds take the site near home, not the one four links out");
        helper.succeed();
    }

    /** Goblins secure what they take before raiding again; the undead raise the fallen and blight their land. */
    @GameTest(template = ANY_TEMPLATE)
    public static void securingRaisingTheDeadAndBlight(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode warren = node("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, 0);
        RealmNode ruin = node("ruin", RealmNode.Type.SITE, RealmNode.NONE, 1);
        RealmNode hamlet = node("hamlet", RealmNode.Type.TOWN, RealmNode.KINGDOM, 2);
        realm.add(warren);
        realm.add(ruin);
        realm.add(hamlet);
        realm.link("warren", "ruin");
        realm.link("warren", "hamlet");
        realm.setStat(hamlet, "tier", 1);
        realm.setStat(warren, "military", 40);
        Realm.Raid take = RealmRaids.declare(realm, "ruin", 0L).orElseThrow();
        RealmRaids.decide(realm, take, 50, 1, false);
        helper.assertTrue(ruin.owner().equals(DungeonFactions.GOBLINS) && ruin.stat("securing") == RealmRaids.SECURING_TURNS && ruin.lostBy().equals(RealmNode.NONE),
                "the goblins take the ruin and set to securing it");
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.RAID) == 0
                && RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.FORTIFY) >= 7, "so they fortify, not raid");
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.FORTIFY, 0L);
        RealmTurns.nationAct(realm, DungeonFactions.GOBLINS, RealmTurns.NationAction.FORTIFY, 0L);
        helper.assertTrue(RealmTurns.nationWeights(realm, DungeonFactions.GOBLINS).get(RealmTurns.NationAction.RAID) > 0, "two turns on, they're back raiding");

        realm.setOwner(warren, DungeonFactions.UNDEAD);
        realm.setOwner(ruin, DungeonFactions.UNDEAD);
        int before = warren.stat("military");
        Realm.Raid sack = RealmRaids.declare(realm, "hamlet", 0L).orElseThrow();
        int defense = hamlet.stat("defense");
        RealmRaids.decide(realm, sack, 60, 1, false);
        int lost = (defense - hamlet.stat("defense")) + 1;
        helper.assertTrue(warren.stat("military") == before - 1 + RealmRaids.plunder(Math.min(10, 2 + 59 / 4))
                + (int) Math.round(lost * RealmFactions.get(DungeonFactions.UNDEAD).raiseDead()),
                "the undead sack the hamlet and raise a third of the fallen: " + before + " to " + warren.stat("military"));

        int calm = RealmLevels.level(realm, Level.OVERWORLD, ruin.pos());
        for (int turn = 0; turn < 3; turn++) {
            RealmTurns.nationAct(realm, DungeonFactions.UNDEAD, RealmTurns.NationAction.FORTIFY, 0L);
        }
        helper.assertTrue(ruin.stat("blight") == 3 && RealmLevels.level(realm, Level.OVERWORLD, ruin.pos()) > calm,
                "the land they hold grows more dangerous each turn: blight " + ruin.stat("blight"));
        realm.setOwner(ruin, RealmNode.KINGDOM);
        helper.assertTrue(ruin.stat("blight") == 0 && ruin.lostBy().equals(DungeonFactions.UNDEAD), "and calms once it's taken from them");
        helper.succeed();
    }

    /** The elementals wait at their capital until they're twice the nearest target, then attack in a burst. */
    @GameTest(template = ANY_TEMPLATE)
    public static void elementalsAttackInBursts(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode spire = node("spire", RealmNode.Type.CAPITAL, ELEMENTALS, 0);
        RealmNode hamlet = node("hamlet", RealmNode.Type.TOWN, RealmNode.KINGDOM, 1);
        realm.add(spire);
        realm.add(hamlet);
        realm.link("spire", "hamlet");
        realm.setStat(hamlet, "tier", 1);
        realm.setStat(hamlet, "defense", 20);
        realm.setStat(spire, "military", 30);
        helper.assertTrue(RealmTurns.raidPlan(realm, ELEMENTALS).isPresent()
                && RealmTurns.nationWeights(realm, ELEMENTALS).get(RealmTurns.NationAction.RAID) == 0, "at 30 against 22, they wait");
        realm.setStat(spire, "military", 45);
        helper.assertTrue(RealmTurns.nationWeights(realm, ELEMENTALS).get(RealmTurns.NationAction.RAID) > 0, "at twice its strength, they march");
        RealmTurns.nationAct(realm, ELEMENTALS, RealmTurns.NationAction.RAID, 0L);
        helper.assertTrue(spire.stat("bursting") == 1 && realm.raids().size() == 1, "and the burst begins");
        realm.setStat(hamlet, "defense", 40);
        RealmTurns.nationAct(realm, ELEMENTALS, RealmTurns.NationAction.BUILD_MILITARY, 0L);
        helper.assertTrue(spire.stat("bursting") == 0, "when the odds turn, they fall back home");
        helper.succeed();
    }
}
