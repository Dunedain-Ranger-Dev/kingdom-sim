package io.github.ranger3462.ddkingdom.realm;

import java.util.Comparator;
import java.util.Optional;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.challenge.DungeonBosses;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;

/**
 * Nation capitals (DD-101, docs/design/realm.md): each monster nation's home base. A capital can never be taken or
 * cleared, and keeps the land around it dangerous (see {@link RealmLevels}).
 * <p>
 * While a nation holds strongholds, its capital stays behind them. Once it's <b>pushed back</b> (every stronghold lost),
 * the capital <b>rallies</b>: every {@link #RALLY_TURNS} turns it sends its <b>champion</b>, one of the nation's dungeon
 * bosses (tier by the capital's military), to retake the nearest stronghold it doesn't hold, or if there's none, against
 * the nearest kingdom town, as a raid fought or rolled like any other ({@link RealmLiveRaids} brings the champion with
 * the last wave). Retaking a stronghold ends the rally: the nation is back in the field. Players can also go to the capital and call the
 * champion out ({@code /challenge}). Killing the champion sets the nation back (a third of the capital's military);
 * a champion that wins, or kills its challenger, lets it rebuild faster.
 * <p>
 * The kingdom can have a capital too: its army can march out from it, and while it stands the kingdom's champion leads
 * every march ({@link #CHAMPION_BONUS} to its attack).
 * <p>
 * Every capital is a city (DD-104): it has a town's defense, wealth and morale, and can be sacked but never taken
 * ({@link RealmRaids#CAPITAL_DEFENSE}). A nation's new troops muster there until its garrison is back to {@link #GARRISON},
 * and the survivors of a stronghold it loses fall back to it.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmCapitals {
    public static final int RALLY_TURNS = 3;
    /** The garrison a nation keeps at home: its new troops muster there until it's this strong. */
    public static final int GARRISON = 30;
    /** A champion adds this to a rolled raid's attack. */
    public static final int CHAMPION_BONUS = 10;
    /** What a champion's win, or a challenger's death, adds to its capital's military. */
    public static final int VICTORY_MILITARY = 5;
    /** How near a player must be to a capital to challenge it. */
    public static final int CHALLENGE_RANGE = 48;
    /** Saved on a champion: the capital it fights for. */
    public static final String CHAMPION_TAG = KingdomSim.MODID + ":champion";

    private RealmCapitals() {}

    public static Optional<RealmNode> capital(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.CAPITAL && nation.equals(node.owner())).findFirst();
    }

    /** Whether a monster nation has lost every stronghold and fights from its capital. */
    public static boolean pushedBack(Realm realm, String nation) {
        return !RealmNode.KINGDOM.equals(nation) && capital(realm, nation).isPresent() && RealmTurns.strongholds(realm, nation).isEmpty();
    }

    /** Whether the kingdom has a capital, and so a champion to lead its marches. */
    public static boolean kingdomChampion(Realm realm) {
        return capital(realm, RealmNode.KINGDOM).isPresent();
    }

    /** A capital's champion tier: 1, plus 1 per 20 military, up to 4. */
    public static int championTier(RealmNode capital) {
        return Mth.clamp(1 + capital.stat("military") / 20, 1, 4);
    }

    /** The kingdom town nearest a capital, in its dimension. */
    public static Optional<RealmNode> nearestTown(Realm realm, RealmNode capital) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.TOWN && node.kingdoms() && node.dimension().equals(capital.dimension()))
                .min(Comparator.comparingDouble(node -> node.pos().distSqr(capital.pos())));
    }

    /** The stronghold nearest a capital, in its dimension, that its nation could retake. */
    public static Optional<RealmNode> nearestLostStronghold(Realm realm, RealmNode capital) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.STRONGHOLD && RealmRaids.capturable(node, capital.owner())
                && node.dimension().equals(capital.dimension())).min(Comparator.comparingDouble(node -> node.pos().distSqr(capital.pos())));
    }

    /** Where a capital's champion marches: the nearest stronghold to retake, else the nearest town. */
    public static Optional<RealmNode> championTarget(Realm realm, RealmNode capital) {
        Optional<RealmNode> stronghold = nearestLostStronghold(realm, capital);
        return stronghold.isPresent() ? stronghold : nearestTown(realm, capital);
    }

    /** Each turn: pushed-back capitals rally, and every {@link #RALLY_TURNS} send their champion. Returns how many marched. */
    public static int rally(Realm realm, long time) {
        int marched = 0;
        for (RealmNode capital : realm.nodes().stream().filter(node -> node.type == RealmNode.Type.CAPITAL).toList()) {
            String nation = capital.owner();
            boolean raiding = realm.raids.stream().anyMatch(raid -> raid.stronghold().equals(capital.id));
            if (!pushedBack(realm, nation) || raiding) {
                continue;
            }
            realm.addStat(capital, "rally", 1);
            Optional<RealmNode> target = championTarget(realm, capital);
            if (capital.stat("rally") < RALLY_TURNS || target.isEmpty()) {
                continue;
            }
            realm.setStat(capital, "rally", 0);
            realm.raids.add(new Realm.Raid(nation, capital.id, target.get().id, realm.turn, time + RealmTurns.turnMillis() / 2));
            String goal = RealmRaids.capturable(target.get(), capital.owner()) ? "marches to retake " + target.get().name() : "marches on " + target.get().name();
            realm.news(Realm.LogKind.MARCH, nation, "The " + nation + " rally at " + capital.name() + ": their champion " + goal + " at dusk!");
            marched++;
        }
        return marched;
    }

    /** Whether a raid is a champion's (sent from a capital). */
    public static boolean isChampionRaid(Realm realm, Realm.Raid raid) {
        return realm.node(raid.stronghold()).map(node -> node.type == RealmNode.Type.CAPITAL).orElse(false);
    }

    /** Spawns a capital's champion at a spot: one of its nation's bosses for its tier, tagged as the capital's. */
    public static Mob spawnChampion(ServerLevel level, BlockPos pos, RealmNode capital) {
        Mob champion = DungeonBosses.spawnChampion(level, pos, championTier(capital), RealmFactions.bossFaction(capital.owner()));
        champion.getPersistentData().putString(CHAMPION_TAG, capital.id);
        String name = RealmFactions.get(capital.owner()).champion();
        champion.setCustomName(Component.literal((name.isEmpty() ? "" : name + ", ") + "Champion of the " + capital.owner()).withStyle(ChatFormatting.DARK_RED));
        champion.setCustomNameVisible(true);
        return champion;
    }

    /** Called once a champion raid is decided, to settle the capital: a win lets it rebuild faster. */
    static void championRaidDecided(Realm realm, RealmNode capital, boolean attackersWon) {
        if (attackersWon) {
            realm.addStat(capital, "military", VICTORY_MILITARY);
            realm.news(Realm.LogKind.PREPARE, capital.owner(), "Emboldened, the " + capital.owner() + " of " + capital.name() + " rebuild faster.");
        }
    }

    /** A champion's death sets its nation back; a challenger's death to one emboldens it. */
    @SubscribeEvent
    public static void onDeath(LivingDeathEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel level)) {
            return;
        }
        Realm realm = Realm.get(level.getServer());
        if (event.getEntity().getPersistentData().contains(CHAMPION_TAG)) {
            realm.node(event.getEntity().getPersistentData().getString(CHAMPION_TAG)).ifPresent(capital -> {
                int lost = capital.stat("military") / 3;
                realm.addStat(capital, "military", -lost);
                realm.setStat(capital, "rally", 0);
                Entity killer = event.getSource().getEntity();
                String who = killer instanceof ServerPlayer player ? player.getName().getString() : "The kingdom";
                realm.news(Realm.LogKind.BATTLE, capital.owner(), who + " slew the champion of the " + capital.owner() + ": " + capital.name() + " loses " + lost + " military.");
            });
        } else if (event.getEntity() instanceof ServerPlayer && event.getSource().getEntity() != null
                && event.getSource().getEntity().getPersistentData().contains(CHAMPION_TAG)) {
            realm.node(event.getSource().getEntity().getPersistentData().getString(CHAMPION_TAG)).ifPresent(capital -> {
                realm.addStat(capital, "military", VICTORY_MILITARY);
                realm.news(Realm.LogKind.BATTLE, capital.owner(), "The champion of the " + capital.owner() + " has slain a challenger. " + capital.name() + " grows bolder.");
            });
        }
        RealmTurns.tellNews(level.getServer(), realm);
    }

    /** {@code /challenge}: at a pushed-back capital, call its champion out. Anyone can use it. */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("challenge").executes(context -> {
            ServerPlayer player = context.getSource().getPlayerOrException();
            ServerLevel level = player.serverLevel();
            Realm realm = Realm.get(level.getServer());
            Optional<RealmNode> capital = realm.nodes().stream()
                    .filter(node -> node.type == RealmNode.Type.CAPITAL && node.monsters() && node.dimension().equals(level.dimension())
                            && node.pos().closerThan(player.blockPosition(), CHALLENGE_RANGE))
                    .findFirst();
            String problem = challengeProblem(realm, level, capital);
            if (problem != null) {
                context.getSource().sendFailure(Component.literal(problem));
                return 0;
            }
            BlockPos at = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, capital.get().pos());
            spawnChampion(level, at, capital.get());
            realm.news(Realm.LogKind.BATTLE, capital.get().owner(), player.getName().getString() + " has challenged the " + capital.get().owner() + " at " + capital.get().name() + ". Their champion answers!");
            RealmTurns.tellNews(level.getServer(), realm);
            return 1;
        }));
    }

    /** Why the capital can't be challenged now, or null if it can. */
    static String challengeProblem(Realm realm, ServerLevel level, Optional<RealmNode> capital) {
        if (capital.isEmpty()) {
            return "You must be at a monster capital (within " + CHALLENGE_RANGE + " blocks) to challenge it.";
        }
        if (!pushedBack(realm, capital.get().owner())) {
            return "The " + capital.get().owner() + " still hold strongholds: take those first, and their champion will have to answer.";
        }
        boolean champion = !level.getEntitiesOfClass(Mob.class, new net.minecraft.world.phys.AABB(capital.get().pos()).inflate(CHALLENGE_RANGE * 2),
                mob -> capital.get().id.equals(mob.getPersistentData().getString(CHAMPION_TAG))).isEmpty();
        return champion ? "Their champion is already out." : null;
    }
}
