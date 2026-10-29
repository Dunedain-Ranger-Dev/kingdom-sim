package io.github.ranger3462.ddkingdom.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks live raids (DD-98): waves against a town's guards, scored by the damage each side does. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class LiveRaidTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(batch = "realm_wavesComeAndATownThatBeatsThemHolds", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void wavesComeAndATownThatBeatsThemHolds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            helper.assertTrue(RealmRaids.declare(realm, "riverhold", 0L).isEmpty(), "one raid on a town at a time");
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.pretendPresent(live, true);
            helper.assertTrue(RealmLiveRaids.isLive(raid) && live.wave() == 1, "the fight starts with wave 1");
            helper.assertTrue(RealmRaids.resolveDue(realm, Long.MAX_VALUE, net.minecraft.util.RandomSource.create(1)) == 0,
                    "a raid being fought isn't rolled");
            helper.assertTrue(live.raiders().size() == 4, "a military of 10 sends waves of 4: " + live.raiders().size());

            // Damage counts: a player hurting a raider is the town's.
            FakePlayer player = FakePlayerFactory.get(level, new GameProfile(UUID.randomUUID(), "defender"));
            player.moveTo(realm.node("riverhold").orElseThrow().pos().getCenter());
            Mob raider = RealmLiveRaids.raiders(live).get(0);
            raider.hurt(level.damageSources().playerAttack(player), 4.0f);
            helper.assertTrue(live.defenseScore() >= 3.9f, "hurting a raider counts for the town: " + live.defenseScore());

            // No friendly fire: the town's side can't hurt its guards; declared for the raiders, a player can't hurt raiders.
            Mob guard = RealmLiveRaids.guards(live).get(0);
            float guardHealth = guard.getHealth();
            guard.hurt(level.damageSources().playerAttack(player), 4.0f);
            helper.assertTrue(guard.getHealth() == guardHealth, "a defender can't hurt the town's guards");
            RealmLiveRaids.side(live, player.getUUID(), true);
            float raiderHealth = raider.getHealth();
            raider.hurt(level.damageSources().playerAttack(player), 2.0f);
            helper.assertTrue(raider.getHealth() == raiderHealth, "nor can a turncoat hurt the raiders");
            raider.invulnerableTime = 0;
            guard.hurt(level.damageSources().playerAttack(player), 4.0f);
            helper.assertTrue(guard.getHealth() < guardHealth && live.attackScore() > 0, "but a turncoat can hurt the guards, for the raiders");
            RealmLiveRaids.side(live, player.getUUID(), false);
            guard.heal(100);

            for (int wave = 1; wave <= RealmLiveRaids.WAVES; wave++) {
                RealmLiveRaids.raiders(live).forEach(Mob::discard);
                RealmLiveRaids.tick(level.getServer(), realm, 20);
            }
            helper.assertFalse(RealmLiveRaids.isLive(raid), "after the last wave falls, the fight is over");
            helper.assertTrue(realm.raids().isEmpty() && realm.node("riverhold").orElseThrow().stat("damage") == 0, "and the town held");
            helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("pitched fight")), "the news says it was fought");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_runningOutTheClockBehindIsASacking", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void runningOutTheClockBehindIsASacking(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.pretendPresent(live, true);
            RealmLiveRaids.score(live, 200, 20);
            List<Mob> raiders = List.copyOf(RealmLiveRaids.raiders(live));
            RealmLiveRaids.tick(level.getServer(), realm, RealmLiveRaids.TIME_LIMIT);
            helper.assertFalse(RealmLiveRaids.isLive(raid), "the fight ends when time runs out");
            RealmNode town = realm.node("riverhold").orElseThrow();
            helper.assertTrue(town.stat("damage") > 0 && town.stat("wealth") < 10, "raiders far ahead sack the town: " + town.stats());
            // This raid's own raiders: other tests' raids run close by at the same time.
            helper.assertTrue(!raiders.isEmpty() && raiders.stream().allMatch(Mob::isRemoved), "and the raiders are cleared away");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_guardsTurnOutByMoraleArmedByWealthToughByDefense", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void guardsTurnOutByMoraleArmedByWealthToughByDefense(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            RealmNode town = realm.node("riverhold").orElseThrow();
            realm.setStat(town, "morale", 3);
            realm.setStat(town, "wealth", 25);
            realm.setStat(town, "defense", 30);
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            List<Mob> guards = RealmLiveRaids.guards(live);
            helper.assertTrue(guards.size() == 2 && guards.stream().allMatch(guard -> guard instanceof TownGuard),
                    "low morale turns out 2 town guards: " + guards.size());
            Mob guard = guards.get(0);
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(io.github.ranger3462.ddkingdom.entity.GuardGear.armor(
                    io.github.ranger3462.ddkingdom.entity.GuardGear.Role.SWORD, 3)[1].getItem()) && guard.getMainHandItem().is(Items.DIAMOND_SWORD),
                    "a wealthy town arms them in its best armor and diamond swords");
            int defense = RealmRaids.defense(realm, town);
            helper.assertTrue(guard.getMaxHealth() == (float) TownGuard.health(defense, 1) && guard.getMaxHealth() > 20,
                    "a strong defense makes them tougher: " + guard.getMaxHealth());
            helper.assertTrue(RealmLiveRaids.guardCount(10, 1) == 4 && TownGuard.armorTier(10) == 2 && TownGuard.armorTier(2) == 0,
                    "a usual town turns out 4 in iron; a poor one, leather");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_guardsWearTheirTownsLookAndVillagersHide", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void guardsWearTheirTownsLookAndVillagersHide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            RealmNode town = realm.node("riverhold").orElseThrow();
            realm.setGuardLook(town, "guard_3", "blue");
            net.minecraft.world.entity.npc.Villager villager = net.minecraft.world.entity.EntityType.VILLAGER.create(level);
            villager.moveTo(town.pos().getCenter());
            level.addFreshEntity(villager);
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            TownGuard guard = (TownGuard) RealmLiveRaids.guards(live).get(0);
            helper.assertTrue("guard_3".equals(guard.skinName()), "the town's named skin: " + guard.skinName());
            var trim = guard.getItemBySlot(EquipmentSlot.CHEST).get(net.minecraft.core.component.DataComponents.TRIM);
            helper.assertTrue(trim != null && trim.material().is(net.minecraft.world.item.armortrim.TrimMaterials.LAPIS),
                    "blue trims the iron armor in lapis");
            helper.assertTrue(guard.getOffhandItem().get(net.minecraft.core.component.DataComponents.BASE_COLOR) == net.minecraft.world.item.DyeColor.BLUE,
                    "and paints the shield blue");
            helper.assertTrue(villager.getBrain().getMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HEARD_BELL_TIME).isPresent(),
                    "the bell sends villagers to hide");
            villager.discard();

            helper.assertTrue(TownGuard.pickSkin("", level.getRandom()).startsWith("guard_"), "by default, guards wear our set");
            helper.assertTrue(TownGuard.pickSkin(TownGuard.SKIN_MIXED, level.getRandom()).startsWith("minecraft:"), "or the built-in faces");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_whenTheGuardsFallTheTownIsSacked", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void whenTheGuardsFallTheTownIsSacked(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.pretendPresent(live, true);
            RealmLiveRaids.score(live, 0, 100); // The town was even ahead on damage.
            RealmLiveRaids.guards(live).forEach(Mob::discard);
            RealmLiveRaids.tick(level.getServer(), realm, 20);
            helper.assertFalse(RealmLiveRaids.isLive(raid), "with every guard down, the fight is over");
            helper.assertTrue(realm.node("riverhold").orElseThrow().stat("damage") > 0, "and the town is sacked");
            helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.text().contains("have fallen")), "the news says the guards fell");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_ruinsWithNoGuardsAreFoughtByWhoeversThere", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void ruinsWithNoGuardsAreFoughtByWhoeversThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        realm.setStat(realm.node("riverhold").orElseThrow(), "tier", 0);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.pretendPresent(live, true);
            helper.assertTrue(RealmLiveRaids.guards(live).isEmpty(), "ruins have no guards");
            RealmLiveRaids.tick(level.getServer(), realm, 20);
            helper.assertTrue(RealmLiveRaids.isLive(raid), "but with a player there, the fight goes on");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_aFightWithNoOneThereWaitsThenIsRolled", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void aFightWithNoOneThereWaitsThenIsRolled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.raiders(live).forEach(Mob::discard);
            RealmLiveRaids.tick(level.getServer(), realm, 20);
            helper.assertTrue(RealmLiveRaids.isLive(raid) && live.wave() == 1, "with no one there, no new wave comes");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(batch = "realm_aFightNoOneStaysForIsRolled", template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void aFightNoOneStaysForIsRolled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = frontier(helper);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.start(realm, raid, level);
            RealmLiveRaids.tick(level.getServer(), realm, RealmLiveRaids.ALONE_LIMIT);
            helper.assertFalse(RealmLiveRaids.isLive(raid), "with no one there, the fight is given up");
            helper.assertTrue(realm.raids().isEmpty() && realm.log().stream().anyMatch(entry -> entry.text().contains("decided by the odds")),
                    "and rolled instead");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    /** A town on the test's ground, bordering a goblin warren of military 10. */
    private static Realm frontier(GameTestHelper helper) {
        Realm realm = new Realm();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, helper.getLevel().dimension(), at);
        realm.add(town);
        realm.setStat(town, "tier", 1);
        realm.add(new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, helper.getLevel().dimension(), at.offset(200, 0, 0)));
        realm.link("riverhold", "warren");
        return realm;
    }
}
