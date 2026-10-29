package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * A town's blueprint (DD-97, docs/design/realm.md): a snapshot of every block in a box around it, saved with the world
 * (one data file per town). Raids break the blocks it holds and rebuilding puts them back, so a hand-built town is never
 * lost for good. Re-saving it (when the town's builds change on purpose) replaces it.
 * <p>
 * The order raids break blocks in: scattered, but leaning to the outside first (walls, palisades: nearest the box's edge),
 * and higher before lower (roofs before floors). Rebuilding goes bottom up, so nothing is placed without what holds it up.
 */
public final class Blueprint extends SavedData {
    private BlockPos origin = BlockPos.ZERO;
    private Vec3i size = Vec3i.ZERO;
    private final List<BlockState> palette = new ArrayList<>();
    /** The palette index of each position in the box (x fastest, then z, then y), or -1 where there's air. */
    private int[] blocks = new int[0];
    /** Positions with a block, in the order raids break them (computed when needed). */
    private int[] breakOrder;

    public static String fileName(String townId) {
        return "dungeondelving_blueprint_" + townId;
    }

    /** A town's blueprint, if it has one. */
    public static java.util.Optional<Blueprint> of(MinecraftServer server, String townId) {
        Blueprint blueprint = server.overworld().getDataStorage().get(new SavedData.Factory<>(Blueprint::new, Blueprint::load, null), fileName(townId));
        return java.util.Optional.ofNullable(blueprint).filter(found -> found.blocks.length > 0);
    }

    /** Saves a new blueprint of the box from {@code origin}, {@code size} big, replacing any the town had. */
    public static Blueprint capture(MinecraftServer server, String townId, ServerLevel level, BlockPos origin, Vec3i size) {
        Blueprint blueprint = server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Blueprint::new, Blueprint::load, null),
                fileName(townId));
        blueprint.fill(level, origin, size);
        return blueprint;
    }

    void fill(ServerLevel level, BlockPos origin, Vec3i size) {
        this.origin = origin.immutable();
        this.size = size;
        palette.clear();
        Map<BlockState, Integer> indices = new HashMap<>();
        blocks = new int[size.getX() * size.getY() * size.getZ()];
        for (int i = 0; i < blocks.length; i++) {
            BlockState state = level.getBlockState(pos(i));
            blocks[i] = state.isAir() ? -1 : indices.computeIfAbsent(state, added -> {
                palette.add(added);
                return palette.size() - 1;
            });
        }
        breakOrder = null;
        setDirty();
    }

    public BlockPos origin() {
        return origin;
    }

    public Vec3i size() {
        return size;
    }

    public int volume() {
        return blocks.length;
    }

    /** How many blocks (not air) it holds. */
    public int blockCount() {
        return (int) IntStream.of(blocks).filter(index -> index >= 0).count();
    }

    /** The world position of index {@code i}. */
    public BlockPos pos(int i) {
        int x = i % size.getX();
        int z = (i / size.getX()) % size.getZ();
        int y = i / (size.getX() * size.getZ());
        return origin.offset(x, y, z);
    }

    /** The block the blueprint has at index {@code i}, or null for air. */
    public BlockState state(int i) {
        return blocks[i] < 0 ? null : palette.get(blocks[i]);
    }

    /** Whether a world position is inside the box. */
    public boolean contains(BlockPos pos) {
        BlockPos local = pos.subtract(origin);
        return local.getX() >= 0 && local.getY() >= 0 && local.getZ() >= 0 && local.getX() < size.getX() && local.getY() < size.getY()
                && local.getZ() < size.getZ();
    }

    /**
     * The positions with a block, scattered: each gets a score (its distance in from the edge, less half its height,
     * plus a fixed random amount up to {@link #SCATTER}), lowest first. So the outside and the tops still go first on the
     * whole, without breaking in straight lines.
     */
    public int[] breakOrder() {
        if (breakOrder == null) {
            breakOrder = IntStream.range(0, blocks.length).filter(i -> blocks[i] >= 0).boxed().sorted(Comparator.<Integer>comparingInt(this::breakScore)
                    .thenComparingInt(Mth::murmurHash3Mixer)).mapToInt(Integer::intValue).toArray();
        }
        return breakOrder;
    }

    /** How much the break order is shuffled: a block's score gets up to this much added at random. */
    public static final int SCATTER = 12;

    private int breakScore(int i) {
        int y = i / (size.getX() * size.getZ());
        return edgeDistance(i) * 2 - y / 2 + Math.floorMod(Mth.murmurHash3Mixer(i * 31 + 7), SCATTER);
    }

    /** How far index {@code i} is from the box's nearest side, across (not up). */
    private int edgeDistance(int i) {
        int x = i % size.getX();
        int z = (i / size.getX()) % size.getZ();
        return Math.min(Math.min(x, size.getX() - 1 - x), Math.min(z, size.getZ() - 1 - z));
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putLong("origin", origin.asLong());
        tag.putIntArray("size", new int[] { size.getX(), size.getY(), size.getZ() });
        ListTag paletteTag = new ListTag();
        palette.forEach(state -> paletteTag.add(NbtUtils.writeBlockState(state)));
        tag.put("palette", paletteTag);
        tag.putIntArray("blocks", blocks);
        return tag;
    }

    static Blueprint load(CompoundTag tag, HolderLookup.Provider registries) {
        Blueprint blueprint = new Blueprint();
        blueprint.origin = BlockPos.of(tag.getLong("origin"));
        int[] size = tag.getIntArray("size");
        blueprint.size = size.length == 3 ? new Vec3i(size[0], size[1], size[2]) : Vec3i.ZERO;
        var blocks = registries.lookupOrThrow(Registries.BLOCK);
        for (Tag entry : tag.getList("palette", Tag.TAG_COMPOUND)) {
            blueprint.palette.add(NbtUtils.readBlockState(blocks, (CompoundTag) entry));
        }
        blueprint.blocks = tag.getIntArray("blocks");
        return blueprint;
    }
}
