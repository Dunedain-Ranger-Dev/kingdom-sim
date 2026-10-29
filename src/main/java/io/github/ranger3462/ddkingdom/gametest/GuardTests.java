package io.github.ranger3462.ddkingdom.gametest;

import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.dungeondelving.economy.Bank;
import io.github.ranger3462.dungeondelving.economy.Coins;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmGuard;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks guard roles and stronghold news (DD-98, DD-100), and coins (DD-93). */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class GuardTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE, timeoutTicks = 200)
    public static void townsSendSwordsmenCrossbowmenAndBattlemages(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Realm realm = new Realm();
        BlockPos at = helper.absolutePos(new BlockPos(1, 2, 1));
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), at);
        realm.add(town);
        realm.add(new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, level.dimension(), at.offset(200, 0, 0)));
        realm.link("riverhold", "warren");
        realm.setStat(town, "tier", 3);
        try {
            Realm.Raid raid = RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();
            RealmLiveRaids.LiveRaid live = RealmLiveRaids.start(realm, raid, level);
            List<Mob> guards = RealmLiveRaids.guards(live);
            int[] split = RealmLiveRaids.roles(RealmLiveRaids.guardCount(10, 3), 3);
            helper.assertTrue(guards.size() == 6 && guards.stream().allMatch(guard -> guard instanceof RealmGuard), "a tier 3 town sends 6 guards: " + guards.size());
            long crossbowmen = guards.stream().filter(guard -> guard instanceof TownGuard townGuard && townGuard.isCrossbowman()).count();
            helper.assertTrue(crossbowmen == split[1] && crossbowmen >= 1, "some with crossbows: " + crossbowmen);
            helper.assertTrue(guards.stream().filter(guard -> guard instanceof TownGuard townGuard && townGuard.isCrossbowman())
                    .allMatch(guard -> guard.getMainHandItem().is(Items.CROSSBOW) && guard.getOffhandItem().isEmpty()), "who carry crossbows, not shields");
            long mages = guards.stream().filter(guard -> !(guard instanceof TownGuard)).count();
            helper.assertTrue(RealmLiveRaids.mageSpawner == null || (mages == split[2] && mages >= 1),
                    "and, with Iron's Spells, battlemages: " + mages);
            helper.assertTrue(guards.get(0) instanceof TownGuard first && !first.isCrossbowman(), "swordsmen turn out first");
            helper.assertTrue(RealmLiveRaids.roles(4, 1)[2] == 0, "a hamlet has no battlemages");
        } finally {
            RealmLiveRaids.clearAll();
        }
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void strongholdsChangingHandsIsNews(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, helper.getLevel().dimension(), BlockPos.ZERO);
        realm.add(warren);
        realm.setOwner(warren, RealmNode.KINGDOM);
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.news() && entry.text().contains("taken by the kingdom: the land around it calms")),
                "taking a stronghold calms the land");
        realm.setOwner(warren, DungeonFactions.ORCS);
        helper.assertTrue(realm.log().stream().anyMatch(entry -> entry.news() && entry.text().contains("orcs have taken warren: the land around it grows more dangerous")),
                "monsters taking it back makes it dangerous again");
        helper.succeed();
    }
}
