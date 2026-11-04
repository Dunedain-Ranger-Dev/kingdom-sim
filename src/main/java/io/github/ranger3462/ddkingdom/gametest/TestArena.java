package io.github.ranger3462.ddkingdom.gametest;

import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;

/**
 * Room well away from the other game tests, for tests that leave mobs alone for a while. Tests run a few blocks apart,
 * so mobs left near one get discarded by its neighbours' clean-up, walk off to heal their neighbours' hurt bosses, or
 * end up inside their neighbours' placed rooms. So each arena is at a fixed spot far west of the test grid, one per
 * slot. The spot doesn't depend on where the test itself landed: when it did, a change to the test order could put two
 * tests' arenas on top of each other.
 */
final class TestArena {
    /** Where slot 0's arena is: a chunk this far west of the world's centre, well past the test grid. */
    private static final int DISTANCE = 4096;
    /** How far apart two slots' arenas are. */
    private static final int SLOT_SPACING = 256;
    /** How far a box's walls are from its middle; it stays inside one chunk. */
    static final int BOX_RADIUS = 7;

    private TestArena() {}

    /** The middle of a slot's chunk, far from the test grid, {@code height} blocks above the test. Every test needs its own slot. */
    static BlockPos away(GameTestHelper helper, int slot, int height) {
        ChunkPos chunk = new ChunkPos(new BlockPos(-DISTANCE - slot * SLOT_SPACING, 0, 0));
        return new BlockPos(chunk.getMiddleBlockX(), helper.absolutePos(BlockPos.ZERO).getY() + height, chunk.getMiddleBlockZ());
    }

    /** Loads the chunk at a position now and keeps it loaded, with its mobs ticking, until {@link #unload}. */
    static void load(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, true);
        level.getChunk(chunk.x, chunk.z);
    }

    /**
     * Keeps every chunk within {@code radius} blocks of a position loaded, with its mobs ticking. A test's own area is
     * only a few blocks across, so a town's guards (at posts round it) can stand in a chunk next door, where mobs don't
     * tick unless something keeps it loaded; whether they do depends on where the test lands. Left loaded: the test
     * server stops when the tests are done.
     */
    static void loadAround(ServerLevel level, BlockPos pos, int radius) {
        ChunkPos from = new ChunkPos(pos.offset(-radius, 0, -radius)), to = new ChunkPos(pos.offset(radius, 0, radius));
        for (int x = from.x; x <= to.x; x++) {
            for (int z = from.z; z <= to.z; z++) {
                level.setChunkForced(x, z, true);
                level.getChunk(x, z);
            }
        }
    }

    /**
     * Runs {@code then} once {@code ready} holds, checking every tick: for a step that needs the world to catch up first
     * (a chunk next to the test's own starting to tick, a mob landing). The test's own timeout still applies.
     */
    static void when(GameTestHelper helper, java.util.function.BooleanSupplier ready, Runnable then) {
        if (ready.getAsBoolean()) {
            then.run();
        } else {
            helper.runAfterDelay(1, () -> when(helper, ready, then));
        }
    }

    static void unload(ServerLevel level, BlockPos pos) {
        ChunkPos chunk = new ChunkPos(pos);
        level.setChunkForced(chunk.x, chunk.z, false);
    }

    /**
     * A closed stone box around a position, {@code height} blocks tall inside, with its chunk loaded: mobs in it can't
     * wander off or fall, and the sun can't burn them. Returns the spot on its floor in the middle.
     */
    static BlockPos box(GameTestHelper helper, int slot, int height) {
        BlockPos pos = away(helper, slot, 100);
        ServerLevel level = helper.getLevel();
        load(level, pos);
        for (BlockPos block : BlockPos.betweenClosed(pos.offset(-BOX_RADIUS, -1, -BOX_RADIUS), pos.offset(BOX_RADIUS, height, BOX_RADIUS))) {
            boolean shell = block.getY() == pos.getY() - 1 || block.getY() == pos.getY() + height
                    || Math.abs(block.getX() - pos.getX()) == BOX_RADIUS || Math.abs(block.getZ() - pos.getZ()) == BOX_RADIUS;
            level.setBlock(block, shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        return pos;
    }

    /** Discards every mob in a {@link #box}, takes the box down, and lets its chunk unload. */
    static void clearBox(GameTestHelper helper, BlockPos pos, int height) {
        ServerLevel level = helper.getLevel();
        AABB inside = new AABB(pos.offset(-BOX_RADIUS, -1, -BOX_RADIUS).getCenter(), pos.offset(BOX_RADIUS, height, BOX_RADIUS).getCenter()).inflate(1);
        level.getEntitiesOfClass(Mob.class, inside).forEach(Mob::discard);
        for (BlockPos block : BlockPos.betweenClosed(pos.offset(-BOX_RADIUS, -1, -BOX_RADIUS), pos.offset(BOX_RADIUS, height, BOX_RADIUS))) {
            level.setBlock(block, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        unload(level, pos);
    }
}
