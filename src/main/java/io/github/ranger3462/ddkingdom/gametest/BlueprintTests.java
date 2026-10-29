package io.github.ranger3462.ddkingdom.gametest;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.Blueprint;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmDamage;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Checks town blueprints (DD-97): real raid damage from them, and rebuilding. */
@GameTestHolder(KingdomSim.MODID)
@PrefixGameTestTemplate(false)
public class BlueprintTests {
    private static final String ANY_TEMPLATE = "test/empty";

    @GameTest(template = ANY_TEMPLATE)
    public static void raidsBreakTheBlueprintAndRebuildingRestoresIt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1)).above(140);
        Vec3i size = new Vec3i(7, 5, 7);
        String id = "test_town_" + Math.abs(origin.asLong() % 100000);
        try {
            house(level, origin);
            Blueprint blueprint = Blueprint.capture(level.getServer(), id, level, origin, size);
            int walls = 4 * 6 * 4; // A 7x7 ring of planks (24 blocks a layer), four high.
            helper.assertTrue(blueprint.blockCount() == 49 + walls + 1, "the blueprint holds the floor, walls and chest: " + blueprint.blockCount());
            helper.assertTrue(Blueprint.of(level.getServer(), id).isPresent(), "and is saved with the world");

            BlockPos glass = origin.offset(3, 2, 3);
            level.setBlock(glass, Blocks.GLASS.defaultBlockState(), Block.UPDATE_CLIENTS); // Placed after: not in the blueprint.
            int[] order = blueprint.breakOrder();
            int firstOuter = 0;
            int firstHeight = 0;
            int lastHeight = 0;
            for (int n = 0; n < 24; n++) {
                BlockPos at = blueprint.pos(order[n]).subtract(origin);
                firstOuter += at.getX() == 0 || at.getZ() == 0 || at.getX() == 6 || at.getZ() == 6 ? 1 : 0;
                firstHeight += at.getY();
                lastHeight += blueprint.pos(order[order.length - 1 - n]).subtract(origin).getY();
            }
            helper.assertTrue(firstOuter >= 18, "raids break mostly the outside first: " + firstOuter + " of 24");
            helper.assertTrue(firstHeight > lastHeight, "and the top before the bottom, on the whole");
            BlockPos a = blueprint.pos(order[0]).subtract(origin);
            boolean inLine = true;
            for (int n = 1; n < 8; n++) {
                BlockPos at = blueprint.pos(order[n]).subtract(origin);
                inLine &= at.getY() == a.getY() && (at.getX() == a.getX() || at.getZ() == a.getZ());
            }
            helper.assertFalse(inLine, "but scattered, not along one line");

            helper.assertTrue(RealmDamage.breakBlocks(level, blueprint, 10) == 10, "ten blocks are broken");
            RealmDamage.breakBlocks(level, blueprint, 100_000);
            helper.assertTrue(level.getBlockState(origin.offset(3, 1, 3)).is(Blocks.CHEST), "a chest is never broken");
            helper.assertTrue(level.getBlockState(glass).is(Blocks.GLASS), "nor anything not in the blueprint");
            helper.assertTrue(level.getBlockState(origin.offset(0, 1, 0)).isAir(), "the house is wrecked");

            int rebuilt = RealmDamage.rebuild(level, blueprint, 100_000);
            helper.assertTrue(rebuilt == 49 + walls, "rebuilding puts back every block that was broken: " + rebuilt);
            helper.assertTrue(level.getBlockState(origin.offset(0, 4, 0)).is(Blocks.OAK_PLANKS) && level.getBlockState(glass).is(Blocks.GLASS),
                    "the walls are back, and the glass is left alone");

            // Through the realm: a sacked town owed 3 points of damage loses 12 blocks once loaded, and pays to rebuild.
            Realm realm = new Realm();
            RealmNode town = new RealmNode(id, RealmNode.Type.TOWN, RealmNode.KINGDOM, level.dimension(), origin.offset(3, 1, 3));
            realm.add(town);
            realm.setStat(town, "damage", 3);
            for (int i = 0; i < 5; i++) {
                RealmDamage.step(level.getServer(), realm);
            }
            helper.assertTrue(town.stat("damage") == 0 && town.stat("wrecked") == 12, "3 damage breaks 12 blocks; wrecked " + town.stat("wrecked"));
            RealmDamage.fundRepairs(realm); // What each turn does first.
            helper.assertTrue(town.stat("wealth") == 9 && town.stat("repair") == RealmDamage.REPAIR_PER_TURN, "the town pays 1 wealth to rebuild");
            for (int i = 0; i < 5; i++) {
                RealmDamage.step(level.getServer(), realm);
            }
            helper.assertTrue(town.stat("wrecked") == 0 && town.stat("repair") == 0, "and rebuilds what was wrecked");
        } finally {
            for (BlockPos pos : BlockPos.betweenClosed(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
        helper.succeed();
    }

    /** A 7x7 stone floor, plank walls four high around it, and a chest inside. */
    private static void house(ServerLevel level, BlockPos origin) {
        for (int x = 0; x < 7; x++) {
            for (int z = 0; z < 7; z++) {
                level.setBlock(origin.offset(x, 0, z), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
                if (x == 0 || z == 0 || x == 6 || z == 6) {
                    for (int y = 1; y <= 4; y++) {
                        level.setBlock(origin.offset(x, y, z), Blocks.OAK_PLANKS.defaultBlockState(), Block.UPDATE_CLIENTS);
                    }
                }
            }
        }
        level.setBlock(origin.offset(3, 1, 3), Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
    }
}
