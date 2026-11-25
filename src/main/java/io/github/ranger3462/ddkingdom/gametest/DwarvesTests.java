package io.github.ranger3462.ddkingdom.gametest;

import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.integration.deepmines.DeepMinesLink;
import io.github.ranger3462.ddkingdom.realm.DwarvenQuests;
import io.github.ranger3462.ddkingdom.realm.DwarvenQuests.Gate;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmStanding;
import io.github.ranger3462.ddkingdom.realm.SimPlaces;
import io.github.ranger3462.ddkingdom.registry.KingdomRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The dwarves (KING-18): their capital, standing raised by Mithril quests, and the mine gate into Deep Mines. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class DwarvesTests {
    private static final String ANY_TEMPLATE = "test/empty";

    /** The realm's map puts the dwarves' capital, Karak Varn, in the world for players to find (KING-19). */
    @GameTest(template = ANY_TEMPLATE)
    public static void theDwarvesHaveACapital(GameTestHelper helper) {
        var places = SimPlaces.named("large_ruins");
        helper.assertTrue(places.containsKey("dw_cap"), "the map places the dwarves' capital: " + places.keySet().stream().filter(k -> k.startsWith("dw")).toList());
        helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(KingdomSim.id("mine_gate")) && KingdomRegistries.MINE_GATE_ITEM.get() != null,
                "and there's a mine gate to build into it");
        helper.succeed();
    }

    /** Standing starts neutral, and every few Mithril brought to the dwarves finishes a quest and raises it (KING-20). */
    @GameTest(template = ANY_TEMPLATE)
    public static void mithrilQuestsRaiseStanding(GameTestHelper helper) {
        RealmStanding standing = new RealmStanding();
        UUID player = UUID.randomUUID();
        helper.assertTrue(standing.standing(player, RealmStanding.DWARVES) == 0 && DwarvenQuests.rank(0).equals("neutral"), "everyone starts neutral");
        var raw = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath("irons_spellbooks", "raw_mithril"));
        helper.assertTrue(raw.isPresent() && new ItemStack(raw.get()).is(DwarvenQuests.MITHRIL), "raw Mithril counts as the dwarves' Mithril");
        helper.assertTrue(!new ItemStack(Items.IRON_INGOT).is(DwarvenQuests.MITHRIL), "iron doesn't");

        int perQuest = KingdomConfig.MITHRIL_PER_QUEST.get();
        int reward = KingdomConfig.STANDING_PER_MITHRIL_QUEST.get();
        ItemStack some = new ItemStack(raw.get(), perQuest - 1);
        helper.assertTrue(DwarvenQuests.handIn(standing, player, some) == 0 && some.isEmpty(), "too little Mithril is taken, but finishes nothing");
        helper.assertTrue(standing.standing(player, RealmStanding.DWARVES) == 0 && standing.progress(player, RealmStanding.DWARVES) == perQuest - 1, "yet");
        ItemStack more = new ItemStack(raw.get(), perQuest + 2);
        int done = DwarvenQuests.handIn(standing, player, more);
        helper.assertTrue(done == 2 && standing.standing(player, RealmStanding.DWARVES) == 2 * reward, "the rest finishes two quests: " + done);
        helper.assertTrue(standing.progress(player, RealmStanding.DWARVES) == 1, "and the one left over counts towards the next");
        helper.assertTrue(standing.standing(UUID.randomUUID(), RealmStanding.DWARVES) == 0, "and it's that player's alone");
        helper.succeed();
    }

    /** The mine gate only opens at the dwarves' capital, with Deep Mines installed, for players in good enough standing (KING-21). */
    @GameTest(template = ANY_TEMPLATE)
    public static void theGateIsAtTheCapitalAndMindsStanding(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        helper.assertTrue(!DwarvenQuests.atCapital(realm, dim, BlockPos.ZERO), "with no dwarven capital, the gate stays shut");
        realm.add(new RealmNode("karak", RealmNode.Type.CAPITAL, RealmStanding.DWARVES, dim, new BlockPos(3000, 64, 0)));
        helper.assertTrue(DwarvenQuests.atCapital(realm, dim, new BlockPos(3020, 70, 10)), "a gate in the capital opens");
        helper.assertTrue(!DwarvenQuests.atCapital(realm, dim, new BlockPos(3500, 70, 0)), "one far from it doesn't");

        helper.assertTrue(DwarvenQuests.gate(false, true, 50, 0) == Gate.NOT_AT_CAPITAL, "away from the capital: shut");
        helper.assertTrue(DwarvenQuests.gate(true, false, 50, 0) == Gate.SEALED, "without Deep Mines: sealed");
        helper.assertTrue(DwarvenQuests.gate(true, true, 0, 0) == Gate.ENTER, "neutral is enough by default");
        helper.assertTrue(DwarvenQuests.gate(true, true, 4, 5) == Gate.TURNED_AWAY && DwarvenQuests.gate(true, true, 5, 5) == Gate.ENTER,
                "below the standing needed, turned away; at it, let in");
        helper.assertTrue(DeepMinesLink.installed(), "Deep Mines is loaded in the tests");
        helper.succeed();
    }
}
