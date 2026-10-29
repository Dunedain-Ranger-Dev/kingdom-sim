package io.github.ranger3462.ddkingdom.gametest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import io.github.ranger3462.dungeondelving.dungeon.SpawnerMobs;
import io.github.ranger3462.dungeondelving.dungeon.challenge.DungeonBosses;
import io.github.ranger3462.dungeondelving.entity.Kobold;
import io.github.ranger3462.dungeondelving.entity.KoboldPackmaster;
import io.github.ranger3462.dungeondelving.entity.KoboldScavenger;
import io.github.ranger3462.dungeondelving.entity.ThrownSpear;
import io.github.ranger3462.ddkingdom.realm.RealmFactions;
import io.github.ranger3462.dungeondelving.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class RealmKoboldTests {
    private static final String ANY_TEMPLATE = "test/empty";

    private static <T extends Mob> T spawn(GameTestHelper helper, EntityType<T> type, int x) {
        T mob = type.create(helper.getLevel());
        BlockPos pos = helper.absolutePos(new BlockPos(0, 1, 0)).above(100);
        mob.moveTo(pos.getX() + 0.5 + x, pos.getY(), pos.getZ() + 0.5);
        mob.setNoGravity(true);
        EventHooks.finalizeMobSpawn(mob, helper.getLevel(), helper.getLevel().getCurrentDifficultyAt(pos), MobSpawnType.EVENT, null);
        helper.getLevel().addFreshEntity(mob);
        return mob;
    }

    /** The realm's kobolds raid with real kobolds at the low tiers now, not goblins. */
    @GameTest(template = ANY_TEMPLATE)
    public static void realmKoboldsRaidAsKobolds(GameTestHelper helper) {
        RandomSource random = RandomSource.create(54);
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            SpawnerMobs.pick(helper.getLevel(), random, RealmFactions.mobs("kobolds"), 1)
                    .ifPresent(type -> seen.add(BuiltInRegistries.ENTITY_TYPE.getKey(type.value()).getPath()));
        }
        helper.assertTrue(seen.equals(Set.of("kobold_looter", "kobold_scavenger")), "tier 1 kobold raiders should be kobolds, got " + seen);
        helper.succeed();
    }
}
