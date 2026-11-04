package io.github.ranger3462.ddkingdom.gametest;

import java.util.List;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmGarrison;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.EntityType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks town garrisons: guards standing at their posts, the fallen replaced at dawn, and raids fought from them. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class GarrisonTests {
    private static final String ANY_TEMPLATE = "test/empty";

    private static Realm town(GameTestHelper helper, String id) {
        ServerLevel level = helper.getLevel();
        Realm realm = new Realm();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        TestArena.loadAround(level, at, 2 * RealmGarrison.POST_RING); // its guards stand and wander beyond the test's own area
        RealmNode town = new RealmNode(id, RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), at);
        realm.add(town);
        realm.setStat(town, "tier", 1);
        realm.addPost(town, level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at));
        realm.addPost(town, level.getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.offset(2, 0, 0)));
        realm.add(new RealmNode(id + "_warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, level.dimension(), at.offset(200, 0, 0)));
        realm.link(id, id + "_warren");
        return realm;
    }

    @GameTest(batch = "realm_guardsStandAtTheirPostsAndTheFallenComeBackAtDawn", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void guardsStandAtTheirPostsAndTheFallenComeBackAtDawn(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = town(helper, "postford");
        RealmNode town = realm.node("postford").orElseThrow();
        RealmGarrison.tick(level, realm, node -> false);
        helper.assertTrue(RealmGarrison.guards(town).isEmpty(), "with no one near, no guards stand");
        RealmGarrison.tick(level, realm, node -> true);
        List<Mob> guards = RealmGarrison.guards(town);
        int full = RealmLiveRaids.guardCount(10, 1);
        helper.assertTrue(guards.size() == full, "a hamlet keeps its " + full + " guards: " + guards.size());
        helper.assertTrue(guards.stream().allMatch(mob -> mob.getPersistentData().getString(RealmGarrison.GARRISON_TAG).equals("postford")),
                "each knows its town");
        helper.assertTrue(guards.get(0).blockPosition().closerThan(town.posts().get(0), 3) && guards.get(1).blockPosition().closerThan(town.posts().get(1), 3),
                "each stands at its post, the posts taken in turn");

        // Off duty, a guard leaves a far-off monster alone, but not one that comes close or goes for a villager.
        Zombie zombie = EntityType.ZOMBIE.create(level);
        zombie.moveTo(town.pos().offset(0, 0, 3).getCenter());
        level.addFreshEntity(zombie);
        helper.assertTrue(RealmGarrison.mayEngage(guards.get(0), zombie), "a guard goes for a monster that comes close");
        // At least one more: other mods add their own on joining too (Kobolds! sets zombies on kobolds).
        helper.assertTrue(zombie.targetSelector.getAvailableGoals().size() > EntityType.ZOMBIE.create(level).targetSelector.getAvailableGoals().size(),
                "and monsters in the world go for guards");
        zombie.moveTo(town.pos().offset(0, 0, RealmGarrison.AGGRO + 8).getCenter());
        helper.assertFalse(RealmGarrison.mayEngage(guards.get(0), zombie), "an off-duty guard leaves a far-off monster alone");
        net.minecraft.world.entity.npc.Villager villager = EntityType.VILLAGER.create(level);
        villager.moveTo(town.pos().offset(1, 0, 3).getCenter());
        level.addFreshEntity(villager);
        zombie.setTarget(villager);
        helper.assertTrue(RealmGarrison.mayEngage(guards.get(0), zombie), "but goes for one after a villager");
        zombie.discard();
        villager.discard();

        // Drawn off its post, it walks back.
        Mob wanderer = guards.get(1);
        wanderer.teleportTo(wanderer.getX() + 5, wanderer.getY(), wanderer.getZ());

        Mob fallen = guards.get(0);
        fallen.kill();
        // Once its spot is ticking (it can be in the chunk next to the test's, still loading) and it's landed.
        helper.runAfterDelay(30, () -> TestArena.when(helper, () -> level.isPositionEntityTicking(wanderer.blockPosition()) && wanderer.onGround(), () -> {
            RealmGarrison.tick(level, realm, node -> true);
            helper.assertTrue(wanderer.getNavigation().isInProgress() || wanderer.blockPosition().closerThan(town.posts().get(1), 3),
                    "a guard off its post heads back: at " + wanderer.blockPosition() + " post " + town.posts().get(1) + " ground " + wanderer.onGround()
                            + " target " + wanderer.getTarget() + " path " + wanderer.getNavigation().getPath() + " ticking " + level.isPositionEntityTicking(wanderer.blockPosition())
                            + " test area " + helper.getBounds());
            helper.assertTrue(town.stat("fallen") == 1 && RealmGarrison.guards(town).size() == full - 1, "a fallen guard isn't replaced at once");
            RealmGarrison.tick(level, realm, node -> true);
            helper.assertTrue(RealmGarrison.guards(town).size() == full - 1, "not even a moment later");
            RealmTurns.takeTurn(realm, net.minecraft.util.RandomSource.create(3), 0L);
            RealmGarrison.tick(level, realm, node -> true);
            helper.assertTrue(town.stat("fallen") == 0 && RealmGarrison.guards(town).size() == RealmGarrison.fullStrength(town),
                    "at dawn it's replaced: " + RealmGarrison.guards(town).size());

            realm.setOwner(town, DungeonFactions.GOBLINS);
            RealmGarrison.tick(level, realm, node -> true);
            helper.assertTrue(RealmGarrison.guards(town).isEmpty(), "a lost town's guards go home");
            helper.succeed();
        }));
    }

    @GameTest(batch = "realm_aRaidIsFoughtByTheGarrisonWhichStaysAfter", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void aRaidIsFoughtByTheGarrisonWhichStaysAfter(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = town(helper, "wardholt");
        RealmNode town = realm.node("wardholt").orElseThrow();
        try {
            RealmGarrison.tick(level, realm, node -> true);
            List<Mob> garrison = RealmGarrison.guards(town);
            Realm.Raid raid = RealmRaids.declare(realm, "wardholt", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.pretendPresent(live, true);
            helper.assertTrue(RealmLiveRaids.guards(live).equals(garrison), "the raid is fought by the guards at their posts, no new ones");
            helper.assertTrue(garrison.stream().allMatch(mob -> mob.getPersistentData().getBoolean(RealmGarrison.ALARM_TAG)), "on alarm");
            Zombie zombie = EntityType.ZOMBIE.create(level);
            helper.assertTrue(RealmGarrison.mayEngage(garrison.get(0), zombie), "they fight anything in a raid");
            RealmLiveRaids.score(live, 0, 200);
            RealmLiveRaids.raiders(live).forEach(Mob::discard);
            for (int wave = 0; wave < RealmLiveRaids.WAVES && RealmLiveRaids.isLive(raid); wave++) {
                RealmLiveRaids.raiders(live).forEach(Mob::discard);
                RealmLiveRaids.tick(level.getServer(), realm, 20);
            }
            helper.assertFalse(RealmLiveRaids.isLive(raid), "the raid is beaten off");
            helper.assertTrue(garrison.stream().allMatch(mob -> !mob.isRemoved() && !mob.getPersistentData().getBoolean(RealmGarrison.ALARM_TAG)),
                    "and the guards stay, back off alarm");
        } finally {
            RealmLiveRaids.clearAll();
            RealmGarrison.dismiss(town);
        }
        helper.succeed();
    }
}
