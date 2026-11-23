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

    @GameTest(batch = "realm_aBiggerTownsGuardsAreStrongerAndHigherLevel", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void aBiggerTownsGuardsAreStrongerAndHigherLevel(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        SimFrontier frontier = SimFrontier.of(helper, 5);
        TestArena.loadAround(level, frontier.at, 2 * io.github.ranger3462.ddkingdom.realm.RealmGarrison.POST_RING); // its guards stand round it, beyond the test's own area
        frontier.sim.game.node(SimFrontier.TOWN).garrison = 8;
        frontier.sim.game.node(SimFrontier.TOWN).defense = 2; // 10 with its troops
        Realm realm = frontier.realm;
        RealmNode town = frontier.town;
        Realm.Raid raid = frontier.raid();
        realm.setStat(town, "wealth", 0);
        RealmLiveRaids.LiveRaid live = frontier.fight(raid, level);
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
            helper.assertTrue((town.name() + " Guard").equals(guard.getCustomName().getString()), "a guard carries its town's name");
            if (net.neoforged.fml.ModList.get().isLoaded("ddprofessions")) {
                int cityLevel = io.github.ranger3462.ddprofessions.character.MobLevels.level(guard);
                helper.assertTrue(cityLevel >= 10, "a city's guards are well above a villager's level 2: " + cityLevel + " (stats say "
                        + io.github.ranger3462.ddprofessions.character.MobLevels.fromStats(guard) + ": health " + guard.getMaxHealth() + ", attack "
                        + guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) + ", armor " + guard.getArmorValue()
                        + ", ticks " + guard.tickCount + ", at " + guard.blockPosition() + " ticking " + level.isPositionEntityTicking(guard.blockPosition()) + ", test area " + helper.getBounds() + ")");
            }
            helper.assertTrue(RealmLiveRaids.guards(live).size() == 8, "its 8 troops turn out");
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
    public static void aFriendlyFactionsLandIsSafe(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        realm.add(new RealmNode("haven", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO));
        RealmNode hold = new RealmNode("karak", RealmNode.Type.CAPITAL, "dwarves", dim, new BlockPos(3000, 64, 0));
        realm.add(hold);
        realm.setStat(hold, "military", 90);
        helper.assertTrue(io.github.ranger3462.ddkingdom.realm.RealmFactions.friendly("dwarves") && !io.github.ranger3462.ddkingdom.realm.RealmFactions.friendly("orcs"),
                "the dwarves are friendly; the orcs aren't");
        helper.assertTrue(RealmLevels.level(realm, dim, new BlockPos(3000, 64, 0)) == 0, "a dwarven hold is safe ground, far from the kingdom and strong as it is");
        realm.setOwner(hold, DungeonFactions.ORCS);
        helper.assertTrue(RealmLevels.level(realm, dim, new BlockPos(3000, 64, 0)) >= RealmLevels.CAPITAL_FLOOR, "an orc capital there isn't");
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
