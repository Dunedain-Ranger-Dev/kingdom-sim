package io.github.ranger3462.ddkingdom.realm;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.SpawnerMobs;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.registry.KingdomRegistries;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Live raids (DD-98, docs/design/realm.md). When a raid's dusk comes and a player is at the town, it isn't rolled: it's
 * fought. The attacking nation's mobs come in {@link #WAVES} waves from all round the town (its dungeon faction's mobs,
 * of a tier by the stronghold's military), against the town's guards ({@link TownGuard}s: more for a bigger town and better morale, better
 * armed for more wealth, tougher for a better defense; a town's are its standing garrison, see {@link RealmGarrison}, and
 * go back to their posts after) and whoever's there. A bell rings and the town's villagers hide
 * indoors until the fight's over.
 * <p>
 * The damage each side does decides it: what the raiders deal to guards, villagers and players, plus what's left of
 * them when time runs out, against what they take. Beat every wave and the town holds; run out the clock behind, and it's
 * sacked, as a rolled raid would be (with block damage by the margin); let every guard fall, and it's sacked at once.
 * <p>
 * Players fight for the town unless they declare for the raiders ({@code /realm side raiders}, noted: standing will cost
 * them, later). There's no friendly fire: the town's side can't hurt its guards and villagers, the raiders' side can't hurt
 * raiders, and the raiders leave their own side alone. If every player leaves, the fight waits (no new waves) and, after
 * {@link #ALONE_LIMIT}, is rolled after all.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmLiveRaids {
    public static final int WAVES = 3;
    /** How near a player must be (blocks) for a raid to be fought, not rolled; and how far they can go before it's abandoned. */
    public static final int PRESENCE = 48;
    public static final int ABANDON = PRESENCE + 32;
    /** Where the raiders come from: this far out from the town's middle. */
    public static final int RING = 24;
    /** How long a fight can last (ticks), and how long without a player before it's rolled instead. */
    public static final int TIME_LIMIT = 20 * 60 * 5;
    public static final int ALONE_LIMIT = 20 * 30;
    /** Scores are scaled down by this before they're weighed like a rolled raid's numbers. */
    public static final int SCORE_SCALE = 5;
    private static final String RAIDER = KingdomSim.MODID + ":raider";

    private static final Map<String, LiveRaid> ACTIVE = new HashMap<>();

    /** One raid being fought. */
    public static final class LiveRaid {
        final Realm.Raid raid;
        final ServerLevel level;
        final BlockPos center;
        final int tier;
        final int waveSize;
        int wave;
        final Set<UUID> raiders = new HashSet<>();
        /** In the order they turned out: swordsmen, crossbowmen, battlemages. */
        final Set<UUID> guards = new java.util.LinkedHashSet<>();
        /** Players who declared for the raiders. */
        final Set<UUID> turncoats = new HashSet<>();
        float attackScore;
        float defenseScore;
        int age;
        int alone;
        /** For tests: count a player as there (fake players aren't in the level's player list). */
        boolean pretendPresent;

        LiveRaid(Realm.Raid raid, ServerLevel level, BlockPos center, int tier, int waveSize) {
            this.raid = raid;
            this.level = level;
            this.center = center;
            this.tier = tier;
            this.waveSize = waveSize;
        }

        public int wave() {
            return wave;
        }

        public float attackScore() {
            return attackScore;
        }

        public float defenseScore() {
            return defenseScore;
        }

        public Set<UUID> raiders() {
            return raiders;
        }

        public Set<UUID> turncoats() {
            return turncoats;
        }
    }

    private RealmLiveRaids() {}

    public static boolean isLive(Realm.Raid raid) {
        LiveRaid live = ACTIVE.get(raid.town());
        return live != null && live.raid.equals(raid);
    }

    public static Optional<LiveRaid> live(String town) {
        return Optional.ofNullable(ACTIVE.get(town));
    }

    /** The fight a player is near enough to take part in, if any. */
    public static Optional<LiveRaid> near(ServerPlayer player) {
        return ACTIVE.values().stream().filter(live -> live.level == player.level() && player.blockPosition().closerThan(live.center, ABANDON)).findFirst();
    }

    /** Puts a player on the raiders' side of a fight ({@code true}) or back on the town's. */
    public static void side(LiveRaid live, UUID player, boolean raiders) {
        if (raiders) {
            live.turncoats.add(player);
        } else {
            live.turncoats.remove(player);
        }
        for (UUID id : live.raiders) {
            if (live.level.getEntity(id) instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUUID().equals(player) && raiders) {
                mob.setTarget(null);
            }
        }
        for (UUID id : live.guards) {
            if (live.level.getEntity(id) instanceof Mob mob && mob.getTarget() != null && mob.getTarget().getUUID().equals(player) && !raiders) {
                mob.setTarget(null);
            }
        }
    }

    /** Whether a player is near enough to a town to fight its raid. */
    public static boolean playerAt(ServerLevel level, BlockPos center, int range) {
        return level.players().stream().anyMatch(player -> !player.isSpectator() && player.blockPosition().closerThan(center, range));
    }

    /** A stronghold's raiding tier, by its military: 1 below 15, up to 4 from 45. */
    public static int tier(int military) {
        return Mth.clamp(1 + military / 15, 1, 4);
    }

    /**
     * How a town's guards split: battlemages (a quarter, from {@link #MAGE_TIER}), crossbowmen (a third of the rest, and
     * at least one in any squad of two or more), then swordsmen.
     */
    public static int[] roles(int guards, int tier) {
        int mages = mageSpawner != null && tier >= MAGE_TIER ? Math.max(1, guards / 4) : 0;
        int crossbows = guards - mages >= 2 ? Math.max(1, (guards - mages) / 3) : 0;
        return new int[] { guards - mages - crossbows, crossbows, mages };
    }

    /**
     * How many guards a town turns out: its tier, plus 1, plus 1 per 5 morale (a new hamlet at the usual 10 morale sends
     * 4; a city, 8), up to {@link #MAX_GUARDS}.
     */
    public static int guardCount(int morale, int tier) {
        return Mth.clamp(1 + tier + Math.max(0, morale) / 5, 1, MAX_GUARDS);
    }

    public static final int MAX_GUARDS = 12;
    /** Towns from this tier up send battlemages (a quarter of their guards), when Iron's Spells is installed. */
    public static final int MAGE_TIER = 2;
    /** Spawns a battlemage at a spot; set by the Iron's Spells integration, null without it. */
    public static java.util.function.BiFunction<ServerLevel, BlockPos, net.minecraft.world.entity.Mob> mageSpawner;

    /** Starts fighting a raid at its town: guards turn out and the first wave comes. */
    public static LiveRaid start(Realm realm, Realm.Raid raid, ServerLevel level) {
        RealmNode town = realm.node(raid.town()).orElseThrow();
        int military = realm.node(raid.stronghold()).map(node -> node.stat("military")).orElse(10);
        LiveRaid live = new LiveRaid(raid, level, town.pos(), tier(military), Mth.clamp(3 + military / 8, 3, 12));
        ACTIVE.put(raid.town(), live);
        int tier = town.settlement() ? RealmTiers.tier(town) : 1;
        // A town fights with its standing garrison (fewer if some fell since dawn). A kingdom-held stronghold's is a small
        // one, turned out just for the raid; ruins and unheld places have no guards at all.
        if (town.settlement()) {
            RealmGarrison.turnOut(level, realm, town).forEach(mob -> live.guards.add(mob.getUUID()));
        }
        int guards = !town.settlement() && town.kingdoms() ? guardCount(town.stat("morale"), tier) : 0;
        int[] split = roles(guards, tier);
        int crossbows = split[1];
        int mages = split[2];
        java.util.Optional<net.minecraft.world.item.DyeColor> color = java.util.Optional.ofNullable(
                net.minecraft.world.item.DyeColor.byName(town.guardColor(), null));
        for (int i = 0; i < guards; i++) {
            BlockPos at = surface(level, town.pos(), 4, level.getRandom());
            net.minecraft.world.entity.Mob mob;
            if (i >= guards - mages) {
                mob = mageSpawner.apply(level, at);
            } else {
                TownGuard guard = KingdomRegistries.TOWN_GUARD.get().spawn(level, at, MobSpawnType.EVENT);
                if (guard != null) {
                    guard.setCrossbowman(i >= guards - mages - crossbows);
                }
                mob = guard;
            }
            if (mob instanceof RealmGuard guard) {
                guard.setSkin(TownGuard.pickSkin(town.guardSkin(), level.getRandom()));
                guard.equip(town.name(), town.stat("wealth"), RealmRaids.defense(realm, town), tier, color);
                live.guards.add(mob.getUUID());
            }
        }
        level.playSound(null, town.pos(), net.minecraft.sounds.SoundEvents.BELL_BLOCK, net.minecraft.sounds.SoundSource.BLOCKS, 2.0f, 1.0f);
        hideVillagers(live);
        realm.news(Realm.LogKind.BATTLE, raid.nation(), town.name() + " is under attack by the " + RealmFactions.get(raid.nation()).displayName() + "! Its guards turn out.");
        nextWave(realm, live);
        return live;
    }

    private static void nextWave(Realm realm, LiveRaid live) {
        live.wave++;
        RandomSource random = live.level.getRandom();
        for (int i = 0; i < live.waveSize; i++) {
            Optional<net.minecraft.core.Holder<EntityType<?>>> type = SpawnerMobs.pick(live.level, random, RealmFactions.mobs(live.raid.nation()), live.tier);
            if (type.isEmpty()) {
                continue;
            }
            float angle = random.nextFloat() * Mth.TWO_PI;
            BlockPos at = surface(live.level, live.center.offset((int) (Mth.cos(angle) * RING), 0, (int) (Mth.sin(angle) * RING)), 2, random);
            if (type.get().value().spawn(live.level, at, MobSpawnType.EVENT) instanceof Mob mob) {
                mob.setPersistenceRequired();
                mob.getPersistentData().putString(RAIDER, live.raid.town());
                live.raiders.add(mob.getUUID());
            }
        }
        if (live.wave == WAVES) {
            realm.node(live.raid.stronghold()).filter(node -> node.type == RealmNode.Type.CAPITAL).ifPresent(capital -> {
                float angle = random.nextFloat() * Mth.TWO_PI;
                BlockPos at = surface(live.level, live.center.offset((int) (Mth.cos(angle) * RING), 0, (int) (Mth.sin(angle) * RING)), 2, random);
                Mob champion = RealmCapitals.spawnChampion(live.level, at, capital);
                champion.getPersistentData().putString(RAIDER, live.raid.town());
                live.raiders.add(champion.getUUID());
                String name = RealmFactions.get(capital.owner()).champion();
                realm.news(Realm.LogKind.BATTLE, capital.owner(), (name.isEmpty() ? "The champion of the " + capital.owner() : name + ", champion of the " + RealmFactions.get(capital.owner()).displayName())
                        + ", leads the last wave!");
            });
        }
        realm.news(Realm.LogKind.BATTLE, live.raid.nation(), "Wave " + live.wave + " of " + WAVES + " comes for " + realm.node(live.raid.town()).map(RealmNode::name).orElse(live.raid.town()) + ".");
    }

    /**
     * Sends the town's villagers indoors, as a rung bell does, and keeps them there while the fight lasts (the alarm's
     * renewed each second; once it stops, they come out again a little after).
     */
    static void hideVillagers(LiveRaid live) {
        long now = live.level.getGameTime();
        for (net.minecraft.world.entity.npc.Villager villager : live.level.getEntitiesOfClass(net.minecraft.world.entity.npc.Villager.class,
                new net.minecraft.world.phys.AABB(live.center).inflate(PRESENCE))) {
            villager.getBrain().setMemory(net.minecraft.world.entity.ai.memory.MemoryModuleType.HEARD_BELL_TIME, now);
        }
    }

    /** A standing spot on the ground near {@code around}. */
    private static BlockPos surface(ServerLevel level, BlockPos around, int spread, RandomSource random) {
        int x = around.getX() + random.nextInt(spread * 2 + 1) - spread;
        int z = around.getZ() + random.nextInt(spread * 2 + 1) - spread;
        return new BlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z), z);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!ACTIVE.isEmpty() && event.getServer().getTickCount() % 20 == 10) {
            tick(event.getServer(), Realm.get(event.getServer()), 20);
        }
    }

    /** Moves every fight on by {@code ticks}: steers idle raiders, sends the next wave, ends fights that are won, lost or left. */
    public static void tick(MinecraftServer server, Realm realm, int ticks) {
        for (Iterator<LiveRaid> it = ACTIVE.values().iterator(); it.hasNext();) {
            LiveRaid live = it.next();
            live.age += ticks;
            live.alone = live.pretendPresent || playerAt(live.level, live.center, ABANDON) ? 0 : live.alone + ticks;
            hideVillagers(live);
            int standing = 0;
            int guards = 0;
            for (UUID id : live.guards) {
                if (live.level.getEntity(id) instanceof LivingEntity guard && guard.isAlive()) {
                    guards++;
                }
            }
            for (UUID id : live.raiders) {
                if (live.level.getEntity(id) instanceof Mob mob && mob.isAlive()) {
                    standing++;
                    if (mob.getTarget() == null && !mob.blockPosition().closerThan(live.center, 4)) {
                        mob.getNavigation().moveTo(live.center.getX() + 0.5, live.center.getY(), live.center.getZ() + 0.5, 1.0);
                    }
                }
            }
            if (live.alone >= ALONE_LIMIT) {
                clear(live);
                it.remove();
                realm.news(Realm.LogKind.BATTLE, live.raid.nation(), "Everyone left " + realm.node(live.raid.town()).map(RealmNode::name).orElse(live.raid.town()) + "'s fight: it was decided by the odds.");
                RealmRaids.resolve(realm, live.raid, 1 + live.level.getRandom().nextInt(20), 1 + live.level.getRandom().nextInt(20));
            } else if (live.alone > 0) {
                // No one's there: the fight waits (its mobs may not even be loaded, so they can't be counted).
            } else if (!live.guards.isEmpty() && guards == 0 && standing > 0) {
                // Every guard down: overrun. A place that had none (ruins, an unheld stronghold) fights on with whoever's there.
                it.remove();
                finish(realm, live, standing, true);
            } else if (standing == 0 && live.wave < WAVES) {
                nextWave(realm, live);
            } else if (standing == 0 || live.age >= TIME_LIMIT) {
                it.remove();
                finish(realm, live, standing, false);
            }
        }
        RealmTurns.tellNews(server, realm);
    }

    /**
     * A fight is over: weigh the scores like a rolled raid's numbers, and clear the field. Every wave beaten, the town
     * holds; every guard fallen ({@code overrun}), it's sacked.
     */
    static void finish(Realm realm, LiveRaid live, int raidersLeft, boolean overrun) {
        float attack = live.attackScore + raidersLeft * 10;
        clear(live);
        int scaledAttack = Math.round(attack / SCORE_SCALE);
        int scaledDefense = Math.round(live.defenseScore / SCORE_SCALE);
        if (raidersLeft == 0) {
            scaledAttack = Math.min(scaledAttack, scaledDefense);
        } else if (overrun) {
            realm.news(Realm.LogKind.BATTLE, live.raid.nation(), "The guards of " + realm.node(live.raid.town()).map(RealmNode::name).orElse(live.raid.town()) + " have fallen!");
            scaledAttack = Math.max(scaledAttack, scaledDefense + 1);
        }
        RealmRaids.decide(realm, live.raid, scaledAttack, scaledDefense, true);
        if (!live.turncoats.isEmpty()) {
            realm.news(Realm.LogKind.BATTLE, live.raid.nation(), live.turncoats.size() + (live.turncoats.size() == 1 ? " adventurer" : " adventurers") + " fought for the " + live.raid.nation()
                    + ". The kingdom won't forget.");
        }
    }

    /** Clears the field: the raiders go, and so do guards turned out just for the raid; a town's garrison goes back to its posts. */
    private static void clear(LiveRaid live) {
        clear(live, false);
    }

    /** As {@link #clear(LiveRaid)}, sending the town's garrison home too if {@code everyone}. */
    private static void clear(LiveRaid live, boolean everyone) {
        for (UUID id : live.raiders) {
            Entity entity = live.level.getEntity(id);
            if (entity != null) {
                entity.discard();
            }
        }
        for (UUID id : live.guards) {
            Entity entity = live.level.getEntity(id);
            if (entity != null && (everyone || !entity.getPersistentData().contains(RealmGarrison.GARRISON_TAG))) {
                entity.discard();
            }
        }
        RealmGarrison.standDown(live.raid.town(), live.level);
    }

    /** No friendly fire: each side's hits on its own side are cancelled, from players and mobs alike. */
    @SubscribeEvent
    public static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (ACTIVE.isEmpty() || event.getSource().getEntity() == null) {
            return;
        }
        Entity attacker = event.getSource().getEntity();
        for (LiveRaid live : ACTIVE.values()) {
            Boolean attackerRaids = side(live, attacker);
            Boolean victimRaids = side(live, event.getEntity());
            if (attackerRaids != null && attackerRaids.equals(victimRaids) && attacker != event.getEntity()) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /** The raiders leave their own side's players alone, and the guards leave the town's. */
    @SubscribeEvent
    public static void onChangeTarget(LivingChangeTargetEvent event) {
        if (ACTIVE.isEmpty() || event.getNewAboutToBeSetTarget() == null) {
            return;
        }
        for (LiveRaid live : ACTIVE.values()) {
            Boolean mobRaids = side(live, event.getEntity());
            if (mobRaids != null && mobRaids.equals(side(live, event.getNewAboutToBeSetTarget()))) {
                event.setCanceled(true);
                return;
            }
        }
    }

    /**
     * Which side of a fight an entity is on: {@code true} for the raiders (their mobs, and players who declared for
     * them), {@code false} for the town (its guards, villagers near it, and every other player near it), null if neither.
     */
    static Boolean side(LiveRaid live, Entity entity) {
        if (entity.level() != live.level) {
            return null;
        }
        if (live.raiders.contains(entity.getUUID()) || live.turncoats.contains(entity.getUUID())) {
            return true;
        }
        if (live.guards.contains(entity.getUUID())) {
            return false;
        }
        if ((entity instanceof ServerPlayer || entity instanceof AbstractVillager) && entity.blockPosition().closerThan(live.center, ABANDON)) {
            return false;
        }
        return null;
    }

    /** Counts damage for a fight: what the raiders' side takes is the town's score, and what the town's side takes is the raiders'. */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        LivingEntity victim = event.getEntity();
        Entity attacker = event.getSource().getEntity();
        float damage = event.getNewDamage();
        for (LiveRaid live : ACTIVE.values()) {
            Boolean victimRaids = side(live, victim);
            if (Boolean.TRUE.equals(victimRaids) && live.raiders.contains(victim.getUUID())) {
                live.defenseScore += damage;
            } else if (Boolean.FALSE.equals(victimRaids) && attacker != null && Boolean.TRUE.equals(side(live, attacker))) {
                live.attackScore += damage;
            }
        }
    }

    /** Counts damage as if it were dealt in a fight, for tests. */
    public static void score(LiveRaid live, float attack, float defense) {
        live.attackScore += attack;
        live.defenseScore += defense;
    }

    /** Counts a player as at the fight, for tests. */
    public static void pretendPresent(LiveRaid live, boolean present) {
        live.pretendPresent = present;
    }

    /** The live raid's guards, for tests. */
    public static List<Mob> guards(LiveRaid live) {
        return live.guards.stream().map(live.level::getEntity).filter(entity -> entity instanceof Mob).map(entity -> (Mob) entity).toList();
    }

    /** Ends every fight at once, for tests. */
    public static void clearAll() {
        ACTIVE.values().forEach(live -> clear(live, true));
        ACTIVE.clear();
    }

    /** The live raids' raiders, for tests. */
    public static List<Mob> raiders(LiveRaid live) {
        return live.raiders.stream().map(live.level::getEntity).filter(entity -> entity instanceof Mob).map(entity -> (Mob) entity).toList();
    }
}
