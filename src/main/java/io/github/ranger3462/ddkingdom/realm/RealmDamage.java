package io.github.ranger3462.ddkingdom.realm;

import java.util.Optional;

import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Real raid damage, and rebuilding (DD-97, docs/design/realm.md), on a node's {@link Blueprint}:
 * <ul>
 * <li>A sacked town is owed damage (its {@code damage} stat, from {@link RealmRaids}). Once its area is loaded, that
 * many points of blocks are broken, {@link #BLOCKS_PER_POINT} a point, outside first, then roofs before floors. Only
 * blocks still as the blueprint has them; never one holding things (a chest, a furnace); nothing dropped. The blocks
 * broken are counted (its {@code wrecked} stat).</li>
 * <li>Each turn, a wrecked town with wealth spends 1 to rebuild {@link #REPAIR_PER_TURN} blocks (its {@code repair}
 * stat), put back from the blueprint where there's air now, bottom up. Players can rebuild by hand too.</li>
 * </ul>
 * A few blocks at a time ({@link #BLOCKS_PER_STEP} every half second), so it's no strain on the server.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmDamage {
    public static final int BLOCKS_PER_POINT = 4;
    public static final int REPAIR_PER_TURN = 64;
    public static final int BLOCKS_PER_STEP = 24;

    private RealmDamage() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 10 == 5) {
            step(event.getServer(), Realm.get(event.getServer()));
        }
    }

    /** One step of breaking and rebuilding, for every node with a blueprint, damage or repair owed, and its area loaded. */
    public static void step(MinecraftServer server, Realm realm) {
        for (RealmNode node : realm.nodes()) {
            if (node.stat("damage") <= 0 && node.stat("repair") <= 0) {
                continue;
            }
            Optional<Blueprint> blueprint = Blueprint.of(server, node.id);
            ServerLevel level = server.getLevel(node.dimension());
            if (blueprint.isEmpty() || level == null || !loaded(level, blueprint.get())) {
                continue; // Owed until it loads.
            }
            if (node.stat("damage") > 0) {
                int broken = breakBlocks(level, blueprint.get(), Math.min(BLOCKS_PER_STEP, node.stat("damage") * BLOCKS_PER_POINT));
                realm.addStat(node, "wrecked", broken);
                realm.addStat(node, "damage", broken == 0 ? -node.stat("damage") : -Math.min(node.stat("damage"), (broken + BLOCKS_PER_POINT - 1) / BLOCKS_PER_POINT));
            } else {
                int rebuilt = rebuild(level, blueprint.get(), Math.min(BLOCKS_PER_STEP, node.stat("repair")));
                realm.addStat(node, "repair", rebuilt == 0 ? -node.stat("repair") : -rebuilt);
                realm.addStat(node, "wrecked", -Math.min(node.stat("wrecked"), rebuilt));
            }
        }
    }

    /** Each turn: wrecked towns with wealth pay to rebuild (DD-97), unless they're upgrading. */
    public static void fundRepairs(Realm realm) {
        for (RealmNode node : realm.nodes()) {
            if (!RealmTiers.upgrading(node) && node.stat("wrecked") > 0 && node.stat("wealth") > 0 && node.stat("repair") < node.stat("wrecked")) {
                realm.addStat(node, "wealth", -1);
                realm.addStat(node, "repair", REPAIR_PER_TURN);
                realm.record(Realm.LogKind.TOWN, node.owner(), node.name() + " paid to rebuild.");
            }
        }
    }

    static boolean loaded(ServerLevel level, Blueprint blueprint) {
        BlockPos far = blueprint.origin().offset(blueprint.size().getX() - 1, blueprint.size().getY() - 1, blueprint.size().getZ() - 1);
        return level.hasChunksAt(blueprint.origin(), far);
    }

    /** Breaks up to {@code count} of the blueprint's blocks still standing, outside and high first. Returns how many. */
    public static int breakBlocks(ServerLevel level, Blueprint blueprint, int count) {
        int broken = 0;
        for (int i : blueprint.breakOrder()) {
            if (broken >= count) {
                break;
            }
            BlockPos pos = blueprint.pos(i);
            BlockState there = level.getBlockState(pos);
            if (!there.equals(blueprint.state(i)) || there.hasBlockEntity()) {
                continue;
            }
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.sendParticles(there.ignitedByLava() ? ParticleTypes.LARGE_SMOKE : ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.5,
                    pos.getZ() + 0.5, 3, 0.3, 0.3, 0.3, 0.01);
            broken++;
        }
        if (broken > 0) {
            BlockPos center = blueprint.origin().offset(blueprint.size().getX() / 2, blueprint.size().getY() / 2, blueprint.size().getZ() / 2);
            level.playSound(null, center, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 0.4f, 1.4f);
        }
        return broken;
    }

    /** Puts back up to {@code count} of the blueprint's blocks where there's air now, bottom up. Returns how many. */
    public static int rebuild(ServerLevel level, Blueprint blueprint, int count) {
        int rebuilt = 0;
        for (int i = 0; i < blueprint.volume() && rebuilt < count; i++) {
            BlockState wanted = blueprint.state(i);
            BlockPos pos = blueprint.pos(i);
            if (wanted != null && level.getBlockState(pos).isAir()) {
                level.setBlock(pos, wanted, Block.UPDATE_CLIENTS);
                rebuilt++;
            }
        }
        return rebuilt;
    }
}
