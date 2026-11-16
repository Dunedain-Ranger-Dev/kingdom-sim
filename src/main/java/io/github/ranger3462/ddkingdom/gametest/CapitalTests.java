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
