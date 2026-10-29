package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmMap;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmRaids;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the realm menu's data: what's sent, and which places are in trouble. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmMapTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void theMenuShowsWhereTheKingdomIsInTrouble(GameTestHelper helper) {
        var dim = helper.getLevel().dimension();
        Realm realm = new Realm();
        RealmNode safe = new RealmNode("haven", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, new BlockPos(-300, 64, 0));
        realm.add(safe);
        realm.setStat(safe, "tier", 2);
        RealmNode town = new RealmNode("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM, dim, BlockPos.ZERO);
        realm.add(town);
        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, dim, new BlockPos(200, 64, 0));
        realm.add(warren);
        RealmNode lost = new RealmNode("millbrook", RealmNode.Type.TOWN, DungeonFactions.GOBLINS, dim, new BlockPos(300, 64, 0));
        realm.add(lost);
        realm.link("haven", "riverhold");
        realm.link("riverhold", "warren");
        realm.link("warren", "millbrook");
        realm.setStat(warren, "military", 60);
        realm.setStat(town, "morale", 2);
        realm.setStat(town, "upgrading", 3);
        RealmRaids.declare(realm, "riverhold", 0L).orElseThrow();

        helper.assertTrue(RealmMap.warnings(realm, safe).isEmpty(), "a safe town behind the border has no warnings: " + RealmMap.warnings(realm, safe));
        var warnings = String.join(" | ", RealmMap.warnings(realm, town));
        helper.assertTrue(warnings.contains("Raid at dusk: the goblins of warren") && warnings.contains("In ruins") && warnings.contains("Low morale")
                && warnings.contains("Outmatched: warren has 60 military"), "the border town's troubles: " + warnings);
        helper.assertTrue(RealmMap.notes(realm, town).get(0).contains("3 days left"), "its upgrade is a note");
        helper.assertTrue(RealmMap.warnings(realm, lost).equals(java.util.List.of("Taken by the goblins")), "a town lost to the goblins is a warning");
        helper.assertTrue(RealmMap.warnings(realm, warren).isEmpty(), "the monsters' own places aren't the kingdom's trouble");

        CompoundTag tag = RealmMap.build(realm);
        helper.assertTrue(tag.getList("nodes", Tag.TAG_COMPOUND).size() == 4 && tag.getList("links", Tag.TAG_COMPOUND).size() == 3
                && tag.getList("raids", Tag.TAG_COMPOUND).size() == 1, "every place, link and raid is sent");
        CompoundTag goblins = tag.getList("nations", Tag.TAG_COMPOUND).getCompound(0);
        helper.assertTrue(goblins.getString("id").equals(DungeonFactions.GOBLINS) && goblins.getInt("military") == 60 && goblins.getInt("towns") == 1,
                "with how each nation stands");
        helper.assertTrue(RealmMap.build(realm).equals(tag), "two builds of an unchanged realm match, so it's only re-sent on a change");
        helper.succeed();
    }

    /** The menu shows stats in plain words, says how each nation plays, and its news keeps what kind of thing each entry was. */
    @GameTest(template = ANY_TEMPLATE)
    public static void theMenuSpeaksPlainly(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode hamlet = new RealmNode("hamlet", RealmNode.Type.TOWN, RealmNode.KINGDOM, net.minecraft.world.level.Level.OVERWORLD, new BlockPos(0, 64, 0));
        RealmNode warren = new RealmNode("warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS, net.minecraft.world.level.Level.OVERWORLD,
                new BlockPos(100, 64, 0));
        realm.add(hamlet);
        realm.add(warren);
        realm.link("hamlet", "warren");
        realm.setStat(hamlet, "tier", 1);
        realm.setStat(hamlet, "defense", 10);
        realm.setStat(warren, "securing", 2);
        java.util.List<RealmMap.Fact> facts = RealmMap.facts(realm, hamlet);
        helper.assertTrue(facts.get(0).label().equals("Size") && facts.stream().anyMatch(fact -> fact.label().equals("Defense") && fact.hint().startsWith("10 its own")),
                "a town's facts lead with its size and show its defense: " + facts);
        helper.assertTrue(RealmMap.facts(realm, warren).stream().anyMatch(fact -> fact.label().equals("Securing") && fact.value().equals("2 turns")),
                "a stronghold says it's securing");
        String goblins = RealmMap.about(io.github.ranger3462.ddkingdom.realm.RealmFactions.get(DungeonFactions.GOBLINS));
        helper.assertTrue(goblins.contains("Grows fast") && goblins.contains("45%+") && goblins.contains("Secures what it takes"), "goblins: " + goblins);
        CompoundTag nation = realm.nations().isEmpty() ? new CompoundTag() : RealmMap.build(realm).getList("nations", Tag.TAG_COMPOUND).getCompound(0);
        helper.assertTrue(nation.getString("about").equals(goblins) && !nation.getString("leader").isEmpty(), "the nation's card carries it: " + nation);
        realm.setOwner(hamlet, DungeonFactions.GOBLINS);
        Realm.LogEntry taken = realm.reloaded(helper.getLevel().registryAccess()).log().getLast();
        helper.assertTrue(taken.kind() == Realm.LogKind.CAPTURE && taken.who().equals(DungeonFactions.GOBLINS) && taken.news(),
                "a capture is logged as one, by whom, and kept through a save: " + taken);
        helper.assertTrue(RealmMap.build(realm).getList("log", Tag.TAG_COMPOUND).getCompound(0).getString("kind").equals("CAPTURE"), "and sent to the menu");
        helper.succeed();
    }
}
