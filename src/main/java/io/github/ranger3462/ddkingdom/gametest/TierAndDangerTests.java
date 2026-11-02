package io.github.ranger3462.ddkingdom.gametest;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmLevels;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTiers;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks town tiers (DD-99) and area danger levels (DD-100). */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class TierAndDangerTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void townsSpendWealthToGrowATier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = new Realm();
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), BlockPos.ZERO);
        realm.add(town);
        helper.assertTrue(RealmTiers.tier(town) == 0, "towns start as ruins");
        realm.setStat(town, "wealth", 0);
        helper.assertTrue(RealmTiers.grow(realm) == 0 && town.stat("upgrading") == RealmTiers.days(1), "ruins start resettling for free, for 2 days");
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.GATHER_WEALTH);
        helper.assertTrue(town.stat("wealth") == 0, "and get no support while they do");
        helper.assertTrue(RealmTiers.grow(realm) == 0 && RealmTiers.grow(realm) == 1 && RealmTiers.tier(town) == 1, "two turns later it's a hamlet");
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("grown into a hamlet")), "with news");
        helper.assertTrue(RealmTiers.grow(realm) == 0 && !RealmTiers.upgrading(town), "a town without wealth to spare doesn't grow");
        realm.setStat(town, "wealth", 16);
        int defense = town.stat("defense");
        RealmTiers.grow(realm);
        helper.assertTrue(town.stat("wealth") == 6 && town.stat("upgrading") == RealmTiers.days(2), "with 10 to spend and 5 kept back, it starts: 4 days to a village");
        for (int i = 0; i < 3; i++) {
            RealmTiers.grow(realm);
        }
        helper.assertTrue(RealmTiers.tier(town) == 1, "not done yet");
        helper.assertTrue(RealmTiers.grow(realm) == 1 && RealmTiers.tier(town) == 2 && town.stat("defense") == defense + RealmTiers.DEFENSE_PER_TIER,
                "on the fourth day it's a village, with more defense");
        realm.setStat(town, "wealth", 1000);
        io.github.ranger3462.ddkingdom.realm.RealmEconomy.set(realm, RealmNode.KINGDOM, io.github.ranger3462.ddkingdom.realm.RealmEconomy.FOOD, 1000);
        realm.setStat(town, "damage", 5);
        helper.assertTrue(RealmTiers.grow(realm) == 0 && !RealmTiers.upgrading(town), "not while it's still rebuilding from a raid");
        realm.setStat(town, "damage", 0);
        for (int i = 0; i < 40; i++) {
            RealmTiers.grow(realm);
        }
        helper.assertTrue(RealmTiers.tier(town) == RealmTiers.MAX_TIER, "it tops out as a city");

        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, level.dimension(), new BlockPos(200, 0, 0));
        realm.add(warren);
        RealmRaids.decide(realm, new Realm.Raid(DungeonFactions.GOBLINS, "warren", "riverhold", 0, 0L), 30, 25, false);
        helper.assertTrue(RealmTiers.tier(town) == RealmTiers.MAX_TIER, "a narrow sacking doesn't cost a tier");
        RealmRaids.decide(realm, new Realm.Raid(DungeonFactions.GOBLINS, "warren", "riverhold", 0, 0L), 40, 25, false);
        helper.assertTrue(RealmTiers.tier(town) == RealmTiers.MAX_TIER - 1, "a bad one knocks it back a tier");
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("fell back to a market town")), "with news");

        realm.setStat(town, "tier", 1);
        RealmRaids.decide(realm, new Realm.Raid(DungeonFactions.GOBLINS, "warren", "riverhold", 0, 0L), 40, 25, false);
        helper.assertTrue(RealmTiers.tier(town) == 0 && town.kingdoms(), "a hamlet sacked badly falls to ruins");
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("fell to ruins")), "with news");
        RealmRaids.decide(realm, new Realm.Raid(DungeonFactions.GOBLINS, "warren", "riverhold", 0, 0L), 40, 25, false);
        helper.assertTrue(DungeonFactions.GOBLINS.equals(town.owner()) && realm.log().stream().anyMatch(entry -> entry.text().contains("goblins have taken riverhold")),
                "and the next win takes it");
        RealmTiers.grow(realm);
        helper.assertTrue(RealmTiers.upgrading(town), "the goblins start resettling it");

        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.STRONGHOLD, RealmNode.KINGDOM, false).isEmpty()
                && Realm.ownerProblem(RealmNode.Type.STRONGHOLD, RealmNode.NONE, false).isEmpty(), "a stronghold can be taken or cleared");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void theKingdomMarchesToTakeBackItsTowns(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        RealmNode home = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO);
        realm.add(home);
        realm.setStat(home, "tier", 1);
        RealmNode lost = new RealmNode("millbrook", RealmNode.Type.TOWN, DungeonFactions.GOBLINS, dim, new BlockPos(100, 64, 0));
        realm.add(lost);
        realm.link("riverhold", "millbrook");
        while (realm.kingdomStat("army") < 40) {
            RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MUSTER);
        }
        helper.assertTrue(RealmTurns.marchTarget(realm).orElseThrow().getValue() == lost, "the kingdom looks to the town the goblins hold");
        helper.assertTrue(RealmTurns.kingdomWeights(realm).get(RealmTurns.KingdomAction.MARCH) > 1, "and with half its army of 40 or more against 10 defense, is keen to march");
        RealmTurns.kingdomAct(realm, RealmTurns.KingdomAction.MARCH, 0L);
        Realm.Raid raid = realm.raids().get(0);
        helper.assertTrue(RealmNode.KINGDOM.equals(raid.nation()) && raid.stronghold().equals("riverhold"), "its army marches from riverhold");
        helper.assertTrue(RealmTurns.kingdomWeights(realm).get(RealmTurns.KingdomAction.MARCH) == 0, "one march at a time");
        helper.assertTrue(RealmRaids.declare(realm, "millbrook", 0L).isEmpty(), "and no second raid on it");
        int army = realm.kingdomStat("army");
        helper.assertTrue(RealmRaids.decide(realm, raid, 50, 1, false), "the army wins");
        helper.assertTrue(lost.owner().equals(RealmNode.KINGDOM) && realm.kingdomStat("army") == army - RealmRaids.GARRISON,
                "the ruins are the kingdom's again, garrisoned from the army: " + realm.kingdomStat("army"));
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("millbrook has been taken by the kingdom")), "with news");
        realm.setOwner(lost, DungeonFactions.GOBLINS);
        realm.setStat(lost, "tier", 2);
        Realm.Raid sack = RealmRaids.declare(realm, "millbrook", 0L).orElseThrow();
        helper.assertTrue(RealmNode.KINGDOM.equals(sack.nation()), "/realm raid on a goblin town sends the kingdom");
        helper.assertTrue(RealmRaids.decide(realm, sack, 50, 1, false) && lost.owner().equals(DungeonFactions.GOBLINS) && RealmTiers.tier(lost) == 1,
                "a living town is only sacked, a tier down by a wide margin");
        helper.succeed();
    }

    @GameTest(batch = "realm_aBiggerTownsGuardsAreStrongerAndHigherLevel", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void aBiggerTownsGuardsAreStrongerAndHigherLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = new Realm();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), at);
        realm.add(town);
        realm.add(new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, level.dimension(), at.offset(200, 0, 0)));
        realm.link("riverhold", "warren");
        realm.setStat(town, "tier", 5);
        realm.setStat(town, "wealth", 0);
        Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
        RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
        // Checked each tick until it holds: a guard's level is worked out on its first tick after it's kitted out, which
        // on a busy server can be a few ticks in.
        helper.succeedWhen(() -> {
            // A swordsman: crossbowmen hit softer (so sit a level or two lower), and battlemages aren't TownGuards.
            TownGuard guard = RealmLiveRaids.guards(live).stream().filter(mob -> mob instanceof TownGuard g && !g.isCrossbowman())
                    .map(TownGuard.class::cast).findFirst().orElseThrow();
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(io.github.ranger3462.ddkingdom.entity.GuardGear.armor(
                    io.github.ranger3462.ddkingdom.entity.GuardGear.Role.SWORD, 3)[1].getItem()), "a city's guards wear its best armor, even with no wealth");
            helper.assertTrue(guard.getMaxHealth() == (float) TownGuard.health(RealmRaids.defense(realm, town), 5)
                    && guard.getMaxHealth() > TownGuard.health(RealmRaids.defense(realm, town), 1), "and are tougher: " + guard.getMaxHealth());
            helper.assertTrue("riverhold Guard".equals(guard.getCustomName().getString()), "a guard carries its town's name");
            if (net.neoforged.fml.ModList.get().isLoaded("ddprofessions")) {
                int cityLevel = io.github.ranger3462.ddprofessions.character.MobLevels.level(guard);
                helper.assertTrue(cityLevel >= 10, "a city's guards are well above a villager's level 2: " + cityLevel + " (stats say "
                        + io.github.ranger3462.ddprofessions.character.MobLevels.fromStats(guard) + ": health " + guard.getMaxHealth() + ", attack "
                        + guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) + ", armor " + guard.getArmorValue()
                        + ", ticks " + guard.tickCount + ")");
            }
            helper.assertTrue(RealmLiveRaids.guardCount(10, 5) == 8 && RealmLiveRaids.guardCount(10, 1) == 4 && RealmLiveRaids.guardCount(0, 1) == 2
                    && RealmLiveRaids.guardCount(100, 5) == RealmLiveRaids.MAX_GUARDS, "and more of them turn out: tier + 1 + morale / 5, up to 12");
            RealmLiveRaids.clearAll();
        });
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void dangerRisesAwayFromTheKingdomAndNearStrongholds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = new Realm();
        var dim = level.dimension();
        helper.assertTrue(RealmLevels.level(realm, dim, new BlockPos(5000, 64, 0)) == 0, "a world without a realm has no danger levels");
        realm.add(new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO));
        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, dim, new BlockPos(600, 64, 0));
        realm.add(warren);
        realm.setStat(warren, "military", 40);

        helper.assertTrue(RealmLevels.level(realm, dim, new BlockPos(30, 64, 0)) == 0, "the kingdom's ground is safe");
        int west = RealmLevels.level(realm, dim, new BlockPos(-600, 64, 0));
        helper.assertTrue(west == 3, "600 blocks out, away from the stronghold, it's level 3: " + west);
        int east = RealmLevels.level(realm, dim, new BlockPos(600, 64, 0));
        helper.assertTrue(east == 8, "at the stronghold of military 40, it's 3 + 5: " + east);
        RealmNode tower = new RealmNode("tower", RealmNode.Type.SITE, RealmNode.KINGDOM, dim, new BlockPos(560, 64, 0));
        realm.add(tower);
        int besideTower = RealmLevels.level(realm, dim, new BlockPos(600, 64, 0));
        helper.assertTrue(besideTower == 5, "a kingdom outpost beside it only takes away the distance part: " + besideTower);
        realm.remove("tower");

        realm.setOwner(warren, RealmNode.KINGDOM);
        int taken = RealmLevels.level(realm, dim, new BlockPos(600, 64, 0));
        helper.assertTrue(taken == 0, "once the stronghold's taken, its ground is the kingdom's: " + taken);
        realm.setOwner(warren, RealmNode.NONE);
        int razed = RealmLevels.level(realm, dim, new BlockPos(600, 64, 0));
        helper.assertTrue(razed == 3, "and if it's only cleared, the area calms to the wilds' level: " + razed);
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void levelsMakeMobsTougher(GameTestHelper helper) {
        Zombie zombie = EntityType.ZOMBIE.create(helper.getLevel());
        float health = zombie.getMaxHealth();
        RealmLevels.apply(zombie, 4);
        helper.assertTrue(Math.abs(zombie.getMaxHealth() - health * 1.6f) < 0.01f && zombie.getHealth() == zombie.getMaxHealth(),
                "level 4 gives 60% more health, healed to it: " + zombie.getMaxHealth());
        helper.assertTrue(zombie.getArmorValue() >= 2, "and some armor");
        RealmLevels.apply(zombie, 4);
        helper.assertTrue(Math.abs(zombie.getMaxHealth() - health * 1.6f) < 0.01f, "applying it twice doesn't stack");
        zombie.discard();
        helper.succeed();
    }

    /** Ice and Fire's dragons get their area's level when they appear (KING-17), unless they're someone's. */
    @GameTest(template = ANY_TEMPLATE)
    public static void wildDragonsGetTheirAreasLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Optional<EntityType<?>> type = EntityType.byString("iceandfire:fire_dragon");
        if (type.isEmpty()) {
            helper.succeed(); // Ice and Fire isn't installed.
            return;
        }
        helper.assertTrue(type.get().is(RealmLevels.LEVELLED_ON_JOIN), "fire dragons are levelled when they appear");
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        Realm realm = new Realm();
        // 1000 blocks from the kingdom: level (1000 - 64) / 150 = 6.
        realm.add(new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), at.offset(1000, 0, 0)));
        Mob wild = (Mob) type.get().create(level);
        Mob tamed = (Mob) type.get().create(level);
        wild.moveTo(at.getX(), at.getY(), at.getZ());
        tamed.moveTo(at.getX(), at.getY(), at.getZ());
        ((TamableAnimal) tamed).setOwnerUUID(UUID.randomUUID());
        float health = wild.getMaxHealth();
        RealmLevels.levelJoined(realm, List.of(wild, tamed));
        helper.assertTrue(wild.getPersistentData().getInt(RealmLevels.LEVEL_TAG) == 6, "a wild dragon gets its area's level 6");
        helper.assertTrue(wild.getMaxHealth() > health * 1.8f, "and 90% more health: " + health + " to " + wild.getMaxHealth());
        helper.assertFalse(tamed.getPersistentData().contains(RealmLevels.LEVEL_TAG), "someone's dragon is left alone");
        wild.discard();
        tamed.discard();
        helper.succeed();
    }
}
