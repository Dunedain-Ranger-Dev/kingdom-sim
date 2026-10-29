package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Predicate;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.registry.KingdomRegistries;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Town garrisons: a kingdom town above ruins keeps its guards ({@link RealmLiveRaids#guardCount}, the same as turn out for
 * a raid) standing at their <b>posts</b> whenever a player is near it. The posts are the town's own ({@code /realm guards
 * <town> post add}), or a ring round its middle. A guard leaves its post to fight a raid on the town, a monster that comes
 * within {@link #AGGRO} blocks of it, one attacking a villager, or whatever hits it, and walks back once that's done (or
 * once it's chased too far). Monsters go for guards in turn (see {@link #onJoin}).
 * <p>
 * Guards that fall stay fallen until dawn (the town's {@code fallen} stat; the next turn replaces them), so a raid's
 * losses carry over to the next one. Guards that only unloaded, with the town out of range or the server stopping, come
 * back as soon as a player is near again. A town that's lost, ruined or demoralised sends home the guards it can't keep.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmGarrison {
    /** Saved on a garrison guard: it keeps a post (and only engages as above). */
    public static final String GARRISON_TAG = KingdomSim.MODID + ":garrison";
    /** Saved on a garrison guard while its town is raided: it fights anything. */
    public static final String ALARM_TAG = KingdomSim.MODID + ":alarm";
    /** How far out the ring of posts is, for a town that hasn't set its own. */
    public static final int POST_RING = 8;
    /** How far a guard will chase from its post (outside a raid) before it gives up and goes back. */
    public static final int LEASH = 32;
    /** How near a monster has to come for a guard off alarm to go for it. */
    public static final int AGGRO = 12;
    /** How near a guard has to be to its post to count as on it. */
    private static final double ON_POST = 2.5;

    /** Each town's guards, by slot: a slot's role and post stay the same, and it's empty while its guard is gone. */
    private static final Map<RealmNode, Garrison> GARRISONS = new WeakHashMap<>();

    private static final class Garrison {
        final ServerLevel level;
        Mob[] slots = new Mob[0];
        String kit = "";
        String skin = "";
        boolean alarmed;

        Garrison(ServerLevel level) {
            this.level = level;
        }
    }

    private RealmGarrison() {}

    /** How many guards a town turns out in full: its raid turnout if it's a kingdom town above ruins, else none. */
    public static int fullStrength(RealmNode town) {
        int tier = RealmTiers.tier(town);
        return town.settlement() && town.kingdoms() && tier > 0 ? RealmLiveRaids.guardCount(town.stat("morale"), tier) : 0;
    }

    /** How many it keeps now: its full strength, less those fallen since dawn. */
    public static int strength(RealmNode town) {
        return Math.max(0, fullStrength(town) - town.stat("fallen"));
    }

    /** A town's guards in the world now. */
    public static List<Mob> guards(RealmNode town) {
        Garrison garrison = GARRISONS.get(town);
        List<Mob> guards = new ArrayList<>();
        if (garrison != null) {
            for (Mob mob : garrison.slots) {
                if (mob != null && !mob.isRemoved()) {
                    guards.add(mob);
                }
            }
        }
        return guards;
    }

    /** Where a slot's guard stands: the town's own posts in turn, or a ring round its middle. */
    public static BlockPos post(ServerLevel level, RealmNode town, int slot, int slots) {
        if (!town.posts().isEmpty()) {
            return town.posts().get(slot % town.posts().size());
        }
        float angle = slot * Mth.TWO_PI / Math.max(1, slots);
        int x = town.pos().getX() + Math.round(Mth.cos(angle) * POST_RING);
        int z = town.pos().getZ() + Math.round(Mth.sin(angle) * POST_RING);
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 5) {
            return;
        }
        Realm realm = Realm.get(event.getServer());
        for (ServerLevel level : event.getServer().getAllLevels()) {
            tick(level, realm, town -> RealmLiveRaids.playerAt(level, town.pos(), RealmLiveRaids.PRESENCE));
        }
    }

    /** Keeps every town's garrison in a level: counts the fallen, sends home extras, fills empty posts where {@code present}, and steers guards back to their posts. */
    public static void tick(ServerLevel level, Realm realm, Predicate<RealmNode> present) {
        for (RealmNode town : realm.nodes()) {
            if (town.settlement() && town.dimension().equals(level.dimension())) {
                keep(level, realm, town, present.test(town) && level.isLoaded(town.pos()));
            }
        }
    }

    private static void keep(ServerLevel level, Realm realm, RealmNode town, boolean present) {
        Garrison garrison = GARRISONS.get(town);
        int full = fullStrength(town);
        if (garrison == null) {
            if (full == 0 || !present) {
                return;
            }
            garrison = new Garrison(level);
            GARRISONS.put(town, garrison);
        }
        // The fallen stay fallen until dawn; the unloaded just come back.
        for (int i = 0; i < garrison.slots.length; i++) {
            Mob mob = garrison.slots[i];
            if (mob != null && mob.isRemoved()) {
                if (mob.getRemovalReason() == Entity.RemovalReason.KILLED) {
                    realm.addStat(town, "fallen", 1);
                }
                garrison.slots[i] = null;
            }
        }
        if (garrison.slots.length != full) {
            for (int i = full; i < garrison.slots.length; i++) {
                discard(garrison.slots[i]);
            }
            garrison.slots = java.util.Arrays.copyOf(garrison.slots, full);
        }
        int keep = strength(town);
        int alive = 0;
        for (int i = 0; i < garrison.slots.length; i++) {
            if (garrison.slots[i] != null && ++alive > keep) {
                discard(garrison.slots[i]);
                garrison.slots[i] = null;
            }
        }
        if (!present) {
            return;
        }
        int tier = RealmTiers.tier(town);
        Optional<DyeColor> color = Optional.ofNullable(DyeColor.byName(town.guardColor(), null));
        String kit = town.name() + "|" + town.stat("wealth") + "|" + RealmRaids.defense(realm, town) + "|" + tier + "|" + town.guardColor();
        boolean rekit = !kit.equals(garrison.kit);
        boolean reskin = !town.guardSkin().equals(garrison.skin);
        garrison.kit = kit;
        garrison.skin = town.guardSkin();
        int[] roles = RealmLiveRaids.roles(full, tier);
        alive = Math.min(alive, keep);
        for (int i = 0; i < garrison.slots.length; i++) {
            Mob mob = garrison.slots[i];
            boolean fresh = false;
            if (mob == null && alive < keep) {
                mob = spawn(level, town, i, roles, post(level, town, i, full));
                if (mob == null) {
                    continue;
                }
                garrison.slots[i] = mob;
                alive++;
                fresh = true;
            }
            if (mob == null) {
                continue;
            }
            if (mob instanceof RealmGuard guard) {
                if (fresh || reskin) {
                    guard.setSkin(TownGuard.pickSkin(town.guardSkin(), level.getRandom()));
                }
                if (fresh || rekit) {
                    guard.equip(town.name(), town.stat("wealth"), RealmRaids.defense(realm, town), tier, color);
                }
            }
            mob.getPersistentData().putBoolean(ALARM_TAG, garrison.alarmed);
            if (!garrison.alarmed) {
                steer(mob, post(level, town, i, full));
            }
        }
    }

    /** A new guard for a slot, at its post: a battlemage, crossbowman or swordsman by the slot's role. */
    private static Mob spawn(ServerLevel level, RealmNode town, int slot, int[] roles, BlockPos post) {
        Mob mob;
        if (slot >= roles[0] + roles[1] && RealmLiveRaids.mageSpawner != null) {
            mob = RealmLiveRaids.mageSpawner.apply(level, post);
        } else {
            TownGuard guard = KingdomRegistries.TOWN_GUARD.get().spawn(level, post, MobSpawnType.EVENT);
            if (guard != null) {
                guard.setCrossbowman(slot >= roles[0]);
            }
            mob = guard;
        }
        if (mob != null) {
            mob.getPersistentData().putString(GARRISON_TAG, town.id);
        }
        return mob;
    }

    /** Off duty: give up a chase that's gone too far or is over, and walk back to the post. */
    private static void steer(Mob mob, BlockPos post) {
        LivingEntity target = mob.getTarget();
        if (target != null && (!target.isAlive() || target.distanceToSqr(post.getCenter()) > LEASH * LEASH)) {
            mob.setTarget(null);
            target = null;
        }
        if (target == null && !mob.blockPosition().closerThan(post, ON_POST) && !mob.getNavigation().isInProgress()) {
            mob.getNavigation().moveTo(post.getX() + 0.5, post.getY(), post.getZ() + 0.5, 1.0);
        }
    }

    private static void discard(Mob mob) {
        if (mob != null && !mob.isRemoved()) {
            mob.discard();
        }
    }

    /**
     * Turns a town's garrison out for a raid: every guard it keeps is placed now (whoever's there), and fights anything
     * until {@link #standDown}. Returns them.
     */
    public static List<Mob> turnOut(ServerLevel level, Realm realm, RealmNode town) {
        keep(level, realm, town, true);
        Garrison garrison = GARRISONS.get(town);
        if (garrison != null) {
            garrison.alarmed = true;
            guards(town).forEach(mob -> mob.getPersistentData().putBoolean(ALARM_TAG, true));
        }
        return guards(town);
    }

    /** The raid's over: the town's guards go back to their posts. */
    public static void standDown(String townId, ServerLevel level) {
        GARRISONS.forEach((town, garrison) -> {
            if (town.id.equals(townId) && garrison.level == level) {
                garrison.alarmed = false;
                guards(town).forEach(mob -> mob.getPersistentData().putBoolean(ALARM_TAG, false));
            }
        });
    }

    /** Dawn: the fallen are replaced. */
    public static void newDay(Realm realm) {
        for (RealmNode town : realm.nodes()) {
            if (town.stat("fallen") > 0) {
                realm.setStat(town, "fallen", 0);
            }
        }
    }

    /** Whether a garrison guard takes on a target: always in a raid; otherwise a mob that's close, or after a villager or it. */
    public static boolean mayEngage(Mob guard, LivingEntity target) {
        if (!guard.getPersistentData().contains(GARRISON_TAG) || guard.getPersistentData().getBoolean(ALARM_TAG)) {
            return true;
        }
        return target.distanceToSqr(guard) <= AGGRO * AGGRO
                || target instanceof Mob mob && (mob.getTarget() instanceof AbstractVillager || mob.getTarget() == guard);
    }

    /** Monsters go for town guards they see, as they would a villager. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (event.getEntity() instanceof PathfinderMob mob && mob instanceof Enemy && !(mob instanceof RealmGuard)) {
            mob.targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(mob, LivingEntity.class, 10, true, false, target -> target instanceof RealmGuard));
        }
    }

    /** A villager attacked by a mob: the guards of its town go for the attacker. */
    @SubscribeEvent
    public static void onVillagerHurt(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof AbstractVillager villager) || !(event.getSource().getEntity() instanceof Mob attacker)
                || attacker instanceof RealmGuard || GARRISONS.isEmpty()) {
            return;
        }
        GARRISONS.forEach((town, garrison) -> {
            if (garrison.level == villager.level() && villager.blockPosition().closerThan(town.pos(), RealmLiveRaids.PRESENCE)) {
                guards(town).stream().filter(guard -> guard.getTarget() == null).forEach(guard -> guard.setTarget(attacker));
            }
        });
    }

    /** Sends a town's garrison home, for tests. */
    public static void dismiss(RealmNode town) {
        Garrison garrison = GARRISONS.remove(town);
        if (garrison != null) {
            for (Mob mob : garrison.slots) {
                discard(mob);
            }
        }
    }
}
