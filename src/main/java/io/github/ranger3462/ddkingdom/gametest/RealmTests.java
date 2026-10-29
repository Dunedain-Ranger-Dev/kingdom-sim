package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks the realm's map (DD-94): nodes, owners, links, borders, and saving. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void nodesLinkIntoBorders(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode town = node("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM);
        RealmNode inland = node("capital", RealmNode.Type.TOWN, RealmNode.KINGDOM);
        RealmNode warren = node("redfang_warren", RealmNode.Type.STRONGHOLD, DungeonFactions.GOBLINS);
        RealmNode ford = node("old_ford", RealmNode.Type.SITE, RealmNode.NONE);
        for (RealmNode node : new RealmNode[] { town, inland, warren, ford }) {
            helper.assertTrue(realm.add(node), node.id + " is added");
        }
        helper.assertFalse(realm.add(node("riverhold", RealmNode.Type.TOWN, RealmNode.KINGDOM)), "an id can't be used twice");
        helper.assertTrue(town.stat("defense") == 10 && warren.stat("military") == 10, "new nodes start with their type's stats");

        helper.assertTrue(realm.link("riverhold", "redfang_warren") && realm.link("capital", "riverhold") && realm.link("old_ford", "redfang_warren"),
                "links are made");
        helper.assertFalse(realm.link("redfang_warren", "riverhold"), "a link is the same either way round");
        helper.assertFalse(realm.link("riverhold", "riverhold") || realm.link("riverhold", "nowhere"), "no links to itself or to nothing");
        helper.assertTrue(realm.onBorder(town) && realm.onBorder(warren), "a town linked to a stronghold is on the border, and so is the stronghold");
        helper.assertFalse(realm.onBorder(inland), "a town only linked to towns isn't");
        helper.assertFalse(realm.onBorder(ford), "an unheld site is no one's border");

        realm.setOwner(ford, RealmNode.KINGDOM);
        helper.assertTrue(realm.onBorder(ford), "once the kingdom holds the ford, it's on the border with the warren");

        helper.assertTrue(realm.remove("redfang_warren"), "a node is removed");
        helper.assertTrue(realm.neighbors("riverhold").size() == 1 && !realm.onBorder(town), "and its links go with it");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void ownersFollowTheRules(GameTestHelper helper) {
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.TOWN, DungeonFactions.ORCS).isEmpty(), "a nation can hold a town");
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.STRONGHOLD, RealmNode.KINGDOM).isPresent(), "strongholds are a nation's");
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.STRONGHOLD, DungeonFactions.UNDEAD).isEmpty(), "undead can hold a stronghold");
        helper.assertTrue(Realm.ownerProblem(RealmNode.Type.SITE, "dragons").isPresent(), "an unknown nation can't hold anything");
        helper.assertTrue(Realm.defaultOwner(RealmNode.Type.STRONGHOLD).isEmpty(), "a stronghold needs its nation named");
        helper.succeed();
    }

    @GameTest(template = ANY_TEMPLATE)
    public static void theRealmIsSavedWithTheWorld(GameTestHelper helper) {
        Realm realm = new Realm();
        RealmNode tower = node("grey_tower", RealmNode.Type.SITE, RealmNode.KINGDOM);
        realm.add(tower);
        realm.add(node("barrow", RealmNode.Type.STRONGHOLD, DungeonFactions.UNDEAD));
        realm.link("grey_tower", "barrow");
        realm.setKind(tower, "watchtower");
        realm.rename(tower, "The Grey Tower");
        realm.setStat(tower, "heat", 7);
        Realm loaded = realm.reloaded(helper.getLevel().registryAccess());
        RealmNode back = loaded.node("grey_tower").orElseThrow();
        helper.assertTrue(back.name().equals("The Grey Tower") && back.kind().equals("watchtower") && back.stat("heat") == 7
                && back.pos().equals(tower.pos()) && back.owner().equals(RealmNode.KINGDOM), "a node comes back as it was");
        helper.assertTrue(loaded.linked("barrow", "grey_tower") && loaded.onBorder(back), "and so do its links");
        helper.assertTrue(Realm.get(helper.getLevel().getServer()) != null, "the world has a realm");
        helper.succeed();
    }

    private static RealmNode node(String id, RealmNode.Type type, String owner) {
        return new RealmNode(id, type, owner, Level.OVERWORLD, new BlockPos(id.length() * 10, 64, 0));
    }
}
