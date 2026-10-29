package io.github.ranger3462.ddkingdom.realm;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Area danger levels (DD-100, docs/design/realm.md). Where the realm has nodes, hostile mobs that spawn there get a
 * level from the map, which makes them tougher ({@link #apply}):
 * <ul>
 * <li>Within {@link #SAFE_RADIUS} of a kingdom-held node, distance adds nothing: the kingdom keeps its own ground.</li>
 * <li>Further out, it rises by one every {@link #STEP} blocks from the nearest kingdom node.</li>
 * <li>Near a monster stronghold, it rises more, by the stronghold's military, fading out to {@link #STRONGHOLD_REACH};
 * a kingdom outpost beside it doesn't make that ground safe. Once the stronghold is taken it's no longer the
 * monsters', so its area calms down.</li>
 * </ul>
 * The danger level is the area's; a mob's own "Lv" (its nameplate, {@code MobLevels}) comes from its stats, so a
 * levelled mob shows a higher one.
 * Players see the danger change as they cross into an area, and {@code /realm danger} shows it. Worlds and dimensions
 * without realm nodes aren't touched. Raiders (spawned for a raid) and mobs from spawners or commands aren't levelled.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmLevels {
    public static final int MAX_LEVEL = 10;
    public static final int SAFE_RADIUS = 64;
    public static final int STEP = 150;
    public static final int STRONGHOLD_REACH = 240;
    /** A nation's capital is always dangerous: at least this many levels at its heart, fading out to its reach. */
    public static final int CAPITAL_FLOOR = 6;
    public static final int CAPITAL_REACH = 360;
    /** The most a blighting nation's land grows in danger, at a place it holds: a level per point on top of the rest, fading out to {@link #STRONGHOLD_REACH}. */
    public static final int MAX_BLIGHT = 5;
    /** A stronghold adds a level per this much military, at its heart. */
    public static final int MILITARY_PER_LEVEL = 8;
    /** Per level: +15% health, +10% damage, +0.5 armor. */
    public static final double HEALTH_PER_LEVEL = 0.15;
    public static final double DAMAGE_PER_LEVEL = 0.10;
    public static final double ARMOR_PER_LEVEL = 0.5;
    public static final String LEVEL_TAG = KingdomSim.MODID + ":area_level";
    private static final ResourceLocation MODIFIER = KingdomSim.id("area_level");
    private static final Set<MobSpawnType> LEVELLED = EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT, MobSpawnType.JOCKEY, MobSpawnType.STRUCTURE);
    private static final Map<UUID, Integer> LAST_SEEN = new HashMap<>();

    private RealmLevels() {}

    /** The danger level at a spot: 0 near the kingdom, rising with distance, and more near monster strongholds. */
    public static int level(Realm realm, ResourceKey<Level> dimension, BlockPos pos) {
        double nearestKingdom = Double.MAX_VALUE;
        double strongholdBonus = 0;
        double blight = 0;
        boolean anyNode = false;
        for (RealmNode node : realm.nodes()) {
            if (!node.dimension().equals(dimension)) {
                continue;
            }
            anyNode = true;
            double distance = Math.sqrt(node.pos().distSqr(pos));
            if (node.monsters() && node.stat("blight") > 0 && distance < STRONGHOLD_REACH) {
                blight = Math.max(blight, node.stat("blight") * (1 - distance / STRONGHOLD_REACH));
            }
            if (node.kingdoms()) {
                nearestKingdom = Math.min(nearestKingdom, distance);
            } else if (node.monsters() && node.type == RealmNode.Type.STRONGHOLD && distance < STRONGHOLD_REACH) {
                strongholdBonus = Math.max(strongholdBonus, (double) node.stat("military") / MILITARY_PER_LEVEL * (1 - distance / STRONGHOLD_REACH));
            } else if (node.monsters() && node.type == RealmNode.Type.CAPITAL && distance < CAPITAL_REACH) {
                double heart = Math.max(CAPITAL_FLOOR, (double) node.stat("military") / MILITARY_PER_LEVEL);
                strongholdBonus = Math.max(strongholdBonus, heart * (1 - distance / CAPITAL_REACH));
            }
        }
        if (!anyNode) {
            return 0;
        }
        double distanceLevels = nearestKingdom == Double.MAX_VALUE ? MAX_LEVEL : Math.max(0, nearestKingdom - SAFE_RADIUS) / STEP;
        return Mth.clamp((int) Math.floor(distanceLevels + strongholdBonus + blight), 0, MAX_LEVEL);
    }

    /** Makes a mob tougher for its level: more health (healed to it), damage and armor. */
    public static void apply(Mob mob, int level) {
        mob.getPersistentData().putInt(LEVEL_TAG, level);
        if (level <= 0) {
            return;
        }
        modify(mob, Attributes.MAX_HEALTH, HEALTH_PER_LEVEL * level, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modify(mob, Attributes.ATTACK_DAMAGE, DAMAGE_PER_LEVEL * level, AttributeModifier.Operation.ADD_MULTIPLIED_BASE);
        modify(mob, Attributes.ARMOR, ARMOR_PER_LEVEL * level, AttributeModifier.Operation.ADD_VALUE);
        mob.setHealth(mob.getMaxHealth());
        io.github.ranger3462.ddkingdom.integration.professions.ProfessionsLink.refreshLevel(mob);
    }

    private static void modify(Mob mob, Holder<Attribute> attribute, double amount, AttributeModifier.Operation operation) {
        AttributeInstance instance = mob.getAttribute(attribute);
        if (instance != null) {
            instance.addOrReplacePermanentModifier(new AttributeModifier(MODIFIER, amount, operation));
        }
    }

    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        Mob mob = event.getEntity();
        if (!(mob instanceof Enemy) || !LEVELLED.contains(event.getSpawnType()) || mob.getPersistentData().contains(LEVEL_TAG)) {
            return;
        }
        ServerLevel level = event.getLevel().getLevel();
        Realm realm = Realm.get(level.getServer());
        apply(mob, level(realm, level.dimension(), BlockPos.containing(event.getX(), event.getY(), event.getZ())));
    }

    /** Tells players the danger when it changes where they are (checked every two seconds). */
    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 40 != 0) {
            return;
        }
        Realm realm = Realm.get(event.getServer());
        if (realm.nodes().isEmpty()) {
            return;
        }
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            int level = level(realm, player.level().dimension(), player.blockPosition());
            Integer last = LAST_SEEN.put(player.getUUID(), level);
            if (last != null && last != level) {
                player.displayClientMessage(describe(level), true);
            }
        }
    }

    /** "Danger 3: the wilds", colored by how bad. */
    public static Component describe(int level) {
        String where = level == 0 ? "the kingdom's peace" : level <= 2 ? "the borderlands" : level <= 5 ? "the wilds" : "deadly lands";
        ChatFormatting color = level == 0 ? ChatFormatting.GREEN : level <= 2 ? ChatFormatting.YELLOW : level <= 5 ? ChatFormatting.GOLD : ChatFormatting.RED;
        return Component.literal("Danger " + level + ": " + where).withStyle(color);
    }
}
