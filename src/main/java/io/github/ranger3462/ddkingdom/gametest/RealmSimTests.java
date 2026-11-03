package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.SimRealm;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The realm by the simulator's rules (docs/design/realm-cutover.md, phase 2), saved with the world. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmSimTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void theSimulatorsRealmIsSavedWithTheWorld(GameTestHelper helper) {
        Realm realm = new Realm();
        // An old node with a simulator node's id: the new realm places it where the old one stands.
        realm.add(new RealmNode("hb_town", RealmNode.Type.TOWN, RealmNode.KINGDOM, Level.OVERWORLD, new BlockPos(100, 64, -40)));
        SimRealm sim = SimRealm.start(SimRealm.DEFAULT_MAP, 7, 1_000_000L);
        realm.startSim(sim);
        realm.setSimPlace("ko_cap", Level.OVERWORLD, new BlockPos(-300, 70, 25));
        sim.catchUp(1_000_000L + 3_600_000L * 5, 3_600_000L, 24);
        helper.assertTrue(realm.simPlace("hb_town").map(p -> p.pos().equals(new BlockPos(100, 64, -40))).orElse(false), "placed where the old node stands");

        Realm loaded = realm.reloaded(helper.getLevel().registryAccess());
        helper.assertTrue(loaded.sim().isPresent(), "the simulator's game comes back");
        helper.assertTrue(loaded.sim().get().save().equals(sim.save()), "exactly as it was");
        helper.assertTrue(loaded.simPlace("ko_cap").map(p -> p.pos().equals(new BlockPos(-300, 70, 25)) && p.dimension().equals(Level.OVERWORLD)).orElse(false),
                "and where its nodes stand");
        helper.assertTrue(loaded.simPlace("hb_town").isPresent() && loaded.simPlace("go_cap").isEmpty(), "unplaced nodes stay unplaced");
        helper.assertTrue(new Realm().reloaded(helper.getLevel().registryAccess()).sim().isEmpty(), "a realm never started has none");
        helper.succeed();
    }
}
