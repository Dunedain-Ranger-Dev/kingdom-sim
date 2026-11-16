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

}
