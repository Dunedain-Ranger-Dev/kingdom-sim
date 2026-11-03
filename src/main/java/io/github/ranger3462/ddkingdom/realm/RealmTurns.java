package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.RandomSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * The realm's turns (DD-95, docs/design/realm.md). A turn comes every {@link Config#REALM_TURN_MINUTES} real minutes (a
 * Minecraft day by default), on a real-time clock that keeps counting while the server is off; on startup, missed turns
 * are taken, up to {@link Config#REALM_CATCH_UP_TURNS}, and the rest skipped.
 * <p>
 * Each turn, the kingdom and each monster nation that holds a stronghold picks one action, weighted by its situation:
 * <ul>
 * <li>The kingdom: reinforce its borders (border towns gain defense), muster (its army grows), gather wealth (towns
 * grow richer, and the treasury fills), or march (its army raids a town a monster nation holds, linked to one of its
 * own: the nearest in ruins, to take it back, or else the weakest, to sack it). Threatened borders make reinforcing
 * likelier; a small army, mustering; an empty treasury, gathering; an army stronger than the town, marching.</li>
 * <li>A monster nation: build its military, raid, or fortify its strongholds (and its towns' defenses). A raid goes
 * first for the nearest place its strongholds border that it could take (a stronghold or site it doesn't hold, or a
 * town in ruins), and only then the weakest town they border (to sack it). It only raids once the raiding stronghold
 * is at least a match for its target (defense and the kingdom army's share), building up until then; the stronger it
 * is, the likelier a raid. It raids one place at a time, and a sacking's plunder adds to its military. Each town it holds also gains 1 wealth a turn, to grow with.</li>
 * </ul>
 * A town that's upgrading ({@link RealmTiers#upgrading}) gets none of this.
 * Raids are declared here and resolved half a turn later, at dusk ({@link RealmRaids}). What each side did goes in the
 * realm log; raids are news, told to every player online.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmTurns {
    public enum KingdomAction { REINFORCE, MUSTER, GATHER_WEALTH, MARCH }

    public enum NationAction { BUILD_MILITARY, RAID, FORTIFY }

    private RealmTurns() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 == 0) {
            Realm realm = Realm.get(event.getServer());
            long now = System.currentTimeMillis();
            if (KingdomConfig.simEngine()) {
                RealmSimTurns.tick(event.getServer(), realm, now); // the simulator's rules (docs/design/realm-cutover.md)
                tellNews(event.getServer(), realm);
                return;
            }
            RandomSource random = event.getServer().overworld().getRandom();
            catchUp(realm, now, random);
            RealmRaids.resolveDue(event.getServer(), realm, now, random);
            tellNews(event.getServer(), realm);
        }
    }

    /** Tells every player online the realm's news (raids declared and resolved). */
    public static void tellNews(MinecraftServer server, Realm realm) {
        for (Realm.LogEntry entry : realm.takeNews()) {
            server.getPlayerList().broadcastSystemMessage(Component.literal(entry.text()).withStyle(ChatFormatting.RED), false);
        }
    }

    public static long turnMillis() {
        return KingdomConfig.REALM_TURN_MINUTES.get() * 60_000L;
    }

    /**
     * Takes the turns that are due by real time {@code now}: none until a turn's length has passed since the last. After
     * a long time away, at most the catch-up cap; the rest are skipped. Returns how many were taken.
     */
    public static int catchUp(Realm realm, long now, RandomSource random) {
        long length = turnMillis();
        if (realm.nodes().isEmpty()) {
            realm.lastTurnMillis = now; // No realm set up in this world: nothing to do, and no backlog when one is.
            return 0;
        }
        if (realm.lastTurnMillis == 0) {
            realm.lastTurnMillis = now; // The clock starts with the realm's first tick.
            realm.setDirty();
            return 0;
        }
        long due = (now - realm.lastTurnMillis) / length;
        if (due <= 0) {
            return 0;
        }
        int cap = KingdomConfig.REALM_CATCH_UP_TURNS.get();
        if (due > Math.max(1, cap)) {
            long skipped = due - Math.max(1, cap);
            realm.lastTurnMillis += skipped * length;
            realm.record("The realm was quiet for " + skipped + " turns while no one watched.");
            due = Math.max(1, cap);
        }
        for (int i = 0; i < due; i++) {
            long turnTime = realm.lastTurnMillis + length;
            RealmRaids.resolveDue(realm, turnTime, random); // Raids from the turn before resolve before this one starts.
            takeTurn(realm, random, turnTime);
            realm.lastTurnMillis = turnTime;
        }
        realm.setDirty();
        return (int) due;
    }

    /** Milliseconds until the next turn, by real time {@code now}. */
    public static long untilNext(Realm realm, long now) {
        return realm.lastTurnMillis == 0 ? turnMillis() : Math.max(0, realm.lastTurnMillis + turnMillis() - now);
    }

    /** One turn at real time {@code time}: the kingdom acts, then each monster nation. */
    public static void takeTurn(Realm realm, RandomSource random, long time) {
        realm.turn++;
        RealmGarrison.newDay(realm);
        RealmDamage.fundRepairs(realm);
        RealmTiers.grow(realm);
        RealmEconomy.collect(realm);
        kingdomAct(realm, pick(kingdomWeights(realm), random), time);
        for (String nation : realm.nations()) {
            nationAct(realm, nation, pick(nationWeights(realm, nation), random), time);
        }
        RealmCapitals.rally(realm, time);
    }

    // --- The kingdom ---

    /** The kingdom's towns (and capital) on the border. */
    public static List<RealmNode> borderTowns(Realm realm) {
        return realm.nodes().stream().filter(node -> node.settlement() && node.kingdoms() && realm.onBorder(node)).toList();
    }

    /** The kingdom's towns that can take support this turn: every one not upgrading. */
    private static List<RealmNode> supported(List<RealmNode> towns) {
        return towns.stream().filter(town -> !RealmTiers.upgrading(town)).toList();
    }

    /**
     * Where the kingdom would march, and from which of its towns: the nearest town in ruins a monster nation holds,
     * linked to one of its towns (to take it back), then the weakest resource node it doesn't hold (to take it), or
     * failing that the weakest-defended town (to sack it), or a monster capital it's very sure of beating.
     */
    public static Optional<Map.Entry<RealmNode, RealmNode>> marchTarget(Realm realm) {
        List<Map.Entry<RealmNode, RealmNode>> held = new ArrayList<>();
        List<Map.Entry<RealmNode, RealmNode>> land = new ArrayList<>();
        for (RealmNode from : realm.nodes()) {
            if (kingdomSettlement(from)) {
                realm.neighbors(from.id).stream().filter(node -> node.settlement() && !node.kingdoms()).forEach(node -> held.add(Map.entry(from, node)));
                realm.neighbors(from.id).stream().filter(node -> node.type == RealmNode.Type.RESOURCE && !node.kingdoms()).forEach(node -> land.add(Map.entry(from, node)));
            }
        }
        Optional<Map.Entry<RealmNode, RealmNode>> ruins = held.stream().filter(entry -> RealmRaids.capturable(entry.getValue(), RealmNode.KINGDOM))
                .min(Comparator.comparingDouble(entry -> entry.getKey().pos().distSqr(entry.getValue().pos())));
        if (ruins.isPresent()) {
            return ruins;
        }
        Optional<Map.Entry<RealmNode, RealmNode>> resource = land.stream().min(Comparator.comparingInt(entry -> RealmRaids.defense(realm, entry.getValue())));
        return resource.isPresent() ? resource
                : held.stream().filter(entry -> entry.getValue().monsters() && (entry.getValue().type == RealmNode.Type.TOWN
                        || RealmFactions.winChance(RealmRaids.kingdomAttack(realm) - RealmRaids.defense(realm, entry.getValue())) >= RealmRaids.CAPITAL_ODDS))
                        .min(Comparator.comparingInt(entry -> RealmRaids.defense(realm, entry.getValue())));
    }

    /** Where the kingdom's army can march from: its towns and its capital. */
    public static boolean kingdomSettlement(RealmNode node) {
        return (node.type == RealmNode.Type.TOWN || node.type == RealmNode.Type.CAPITAL) && node.kingdoms();
    }

    /** The military of every monster stronghold touching the kingdom's border. */
    public static int threat(Realm realm) {
        return borderTowns(realm).stream().flatMap(town -> realm.neighbors(town.id).stream())
                .filter(node -> node.type == RealmNode.Type.STRONGHOLD && node.monsters()).distinct().mapToInt(node -> node.stat("military")).sum();
    }

    public static Map<KingdomAction, Integer> kingdomWeights(Realm realm) {
        List<RealmNode> border = borderTowns(realm);
        int threat = threat(realm);
        int defense = border.stream().mapToInt(town -> town.stat("defense")).sum() + realm.kingdomStat("army");
        Map<KingdomAction, Integer> weights = new LinkedHashMap<>();
        weights.put(KingdomAction.REINFORCE, border.isEmpty() ? 0 : 2 + (threat > defense ? 4 : 0));
        weights.put(KingdomAction.MUSTER, 2 + (realm.kingdomStat("army") < threat / 2 ? 2 : 0));
        weights.put(KingdomAction.GATHER_WEALTH, 2 + (RealmEconomy.trade(realm, RealmNode.KINGDOM) < 10 ? 2 : 0));
        Optional<Map.Entry<RealmNode, RealmNode>> march = marchTarget(realm);
        boolean marching = realm.raids.stream().anyMatch(raid -> RealmNode.KINGDOM.equals(raid.nation()));
        // Only when it's fairly sure to win (its attack bar, DD-102): the keener the more it outmatches the target.
        int margin = march.map(target -> RealmRaids.kingdomAttack(realm) - RealmRaids.defense(realm, target.getValue())).orElse(0);
        boolean confident = RealmFactions.winChance(margin) >= RealmFactions.get(RealmNode.KINGDOM).attackBar();
        weights.put(KingdomAction.MARCH, march.isEmpty() || marching || !confident ? 0 : 1 + margin / 3);
        return weights;
    }

    /** The kingdom's action on a turn, as if at real time 0 (a march resolves at once). */
    public static void kingdomAct(Realm realm, KingdomAction action) {
        kingdomAct(realm, action, 0L);
    }

    /** The kingdom's action on the turn at real time {@code time}: a march declared then resolves half a turn later. */
    public static void kingdomAct(Realm realm, KingdomAction action, long time) {
        switch (action) {
            case REINFORCE -> {
                List<RealmNode> border = supported(borderTowns(realm));
                int gained = RealmEconomy.spend(realm, RealmNode.KINGDOM, 2) + RealmEconomy.lumber(realm, RealmNode.KINGDOM);
                border.forEach(town -> realm.addStat(town, "defense", gained));
                realm.record(Realm.LogKind.PREPARE, RealmNode.KINGDOM, "The kingdom reinforced its border: " + names(border) + " each gained " + gained + " defense.");
            }
            case MUSTER -> {
                realm.addKingdomStat("army", RealmEconomy.spend(realm, RealmNode.KINGDOM, 3));
                realm.record(Realm.LogKind.PREPARE, RealmNode.KINGDOM, "The kingdom mustered troops: its army is now " + realm.kingdomStat("army") + ".");
            }
            case GATHER_WEALTH -> {
                List<RealmNode> towns = supported(realm.nodes().stream().filter(node -> node.settlement() && node.kingdoms()).toList());
                towns.forEach(town -> realm.addStat(town, "wealth", 1));
                RealmEconomy.add(realm, RealmNode.KINGDOM, RealmEconomy.TRADE, towns.size());
                realm.record(Realm.LogKind.PREPARE, RealmNode.KINGDOM, "The kingdom gathered wealth: its trade is now " + RealmEconomy.trade(realm, RealmNode.KINGDOM) + ".");
            }
            case MARCH -> marchTarget(realm).ifPresent(target -> {
                Realm.Raid raid = new Realm.Raid(RealmNode.KINGDOM, target.getKey().id, target.getValue().id, realm.turn, time + turnMillis() / 2);
                realm.raids.add(raid);
                realm.news(Realm.LogKind.MARCH, RealmNode.KINGDOM, RealmRaids.who(realm, raid, target.getKey()) + " will " + raidVerb(target.getValue(), RealmNode.KINGDOM) + " at dusk!");
            });
        }
    }

    // --- Monster nations ---

    /** A nation's strongholds. */
    public static List<RealmNode> strongholds(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.STRONGHOLD && nation.equals(node.owner())).toList();
    }

    /** The towns a nation holds. */
    public static List<RealmNode> towns(Realm realm, String nation) {
        return realm.nodes().stream().filter(node -> node.type == RealmNode.Type.TOWN && nation.equals(node.owner())).toList();
    }

    /** A nation's whole military: every stronghold's. */
    public static int military(Realm realm, String nation) {
        return strongholds(realm, nation).stream().mapToInt(node -> node.stat("military")).sum();
    }

    /** A raid a nation could make: from where, on what, and its chance of winning (before any champion). */
    public record RaidPlan(RealmNode from, RealmNode target, double chance) {}

    /** Points an action gives a faction, paid for: its base, by its growth and wealth (RealmEconomy#spend). */
    public static int points(Realm realm, String faction, int base) {
        return RealmEconomy.spend(realm, faction, base);
    }

    /**
     * Where a nation would raid, by its traits (DD-102): from any of its strongholds (and its capital, for a nation
     * that attacks in bursts), on a linked place it could take or a town it could sack. Its {@code targets} say whose;
     * a {@code home_radius} keeps it within that many links of its capital. The best is the likeliest win, ahead of that
     * places it could take, and ahead of those places it lost (by how keen it is to retake them).
     */
    public static Optional<RaidPlan> raidPlan(Realm realm, String nation) {
        RealmFactions.Traits traits = RealmFactions.get(nation);
        List<RealmNode> sources = new ArrayList<>(strongholds(realm, nation));
        Optional<RealmNode> capital = RealmCapitals.capital(realm, nation);
        if (traits.burstRatio() > 0) {
            capital.ifPresent(sources::add);
        }
        Set<String> home = traits.homeRadius() > 0 && capital.isPresent() ? within(realm, capital.get(), traits.homeRadius()) : null;
        RaidPlan best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (RealmNode from : sources) {
            for (RealmNode target : realm.neighbors(from.id)) {
                if (nation.equals(target.owner()) || (home != null && !home.contains(target.id))) {
                    continue;
                }
                boolean takeable = RealmRaids.capturable(target, nation);
                if (!takeable && !target.settlement()) {
                    continue;
                }
                double chance = RealmFactions.winChance(RealmRaids.attack(realm, nation, from) - RealmRaids.defense(realm, target));
                if (!wouldAttack(traits, target, chance) || (target.type == RealmNode.Type.CAPITAL && chance < RealmRaids.CAPITAL_ODDS)) {
                    continue;
                }
                double score = chance + (takeable ? 0.3 : 0) + (nation.equals(target.lostBy()) ? traits.retake() * 0.5 : 0)
                        - from.pos().distSqr(target.pos()) * 1e-9;
                if (score > bestScore) {
                    bestScore = score;
                    best = new RaidPlan(from, target, chance);
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether a faction's {@code targets} let it attack this node's owner, at this chance. */
    static boolean wouldAttack(RealmFactions.Traits traits, RealmNode target, double chance) {
        boolean unheld = !target.kingdoms() && !target.monsters();
        return switch (traits.targets()) {
            case ANYONE -> true;
            case KINGDOM -> target.kingdoms() || unheld;
            case KINGDOM_FIRST -> target.kingdoms() || unheld || chance >= traits.weakBar();
            case MONSTERS -> target.monsters();
        };
    }

    /** The nodes within {@code links} links of a node, itself included. */
    private static Set<String> within(Realm realm, RealmNode from, int links) {
        Set<String> seen = new java.util.HashSet<>(Set.of(from.id));
        List<RealmNode> edge = List.of(from);
        for (int i = 0; i < links; i++) {
            List<RealmNode> next = new ArrayList<>();
            for (RealmNode node : edge) {
                realm.neighbors(node.id).stream().filter(other -> seen.add(other.id)).forEach(next::add);
            }
            edge = next;
        }
        return seen;
    }

    /** Where a nation would raid, and from which stronghold: {@link #raidPlan}'s places. */
    public static Optional<Map.Entry<RealmNode, RealmNode>> raidTarget(Realm realm, String nation) {
        return raidPlan(realm, nation).map(plan -> Map.entry(plan.from(), plan.target()));
    }

    /** What a raid by {@code by} on a node means, for the news: taking it, or raiding it. */
    static String raidVerb(RealmNode target, String by) {
        return RealmRaids.capturable(target, by) ? "march on " + target.name() + " to take it" : "raid " + target.name();
    }

    /** Whether a nation is still securing a place it took (it holds one with turns of {@code securing} left). */
    static boolean securing(Realm realm, String nation) {
        return RealmFactions.get(nation).fortifyAfterCapture() && realm.nodes().stream().anyMatch(node -> nation.equals(node.owner()) && node.stat("securing") > 0);
    }

    /**
     * Whether a nation's best raid is one it would make: its chance is at least its {@code attack_bar}. A nation that
     * attacks in bursts only starts once its capital's military is {@code burst_ratio} times the target's defense,
     * then keeps going while its odds hold.
     */
    static boolean ready(Realm realm, String nation, Optional<RaidPlan> plan) {
        RealmFactions.Traits traits = RealmFactions.get(nation);
        if (plan.isEmpty() || plan.get().chance() < traits.attackBar()) {
            return false;
        }
        if (traits.burstRatio() <= 0) {
            return true;
        }
        Optional<RealmNode> capital = RealmCapitals.capital(realm, nation);
        return capital.isPresent() && (capital.get().stat("bursting") > 0
                || capital.get().stat("military") >= traits.burstRatio() * Math.max(1, RealmRaids.defense(realm, plan.get().target())));
    }

    public static Map<NationAction, Integer> nationWeights(Realm realm, String nation) {
        RealmFactions.Traits traits = RealmFactions.get(nation);
        Optional<RaidPlan> plan = raidPlan(realm, nation);
        boolean raiding = realm.raids.stream().anyMatch(raid -> raid.nation().equals(nation));
        boolean ready = ready(realm, nation, plan);
        boolean securing = securing(realm, nation);
        int fortification = strongholds(realm, nation).stream().mapToInt(node -> node.stat("fortification")).min().orElse(0);
        Map<NationAction, Integer> weights = new LinkedHashMap<>();
        weights.put(NationAction.BUILD_MILITARY, 3 + (ready ? 0 : 4));
        // The better its odds over its bar, the keener; an aggressive nation more so.
        int keenness = ready ? (int) Math.max(1, Math.round((1 + (plan.get().chance() - traits.attackBar()) * 10) * traits.aggression())) : 0;
        weights.put(NationAction.RAID, raiding || securing ? 0 : keenness);
        weights.put(NationAction.FORTIFY, 1 + (fortification < 5 ? 1 : 0) + (securing ? 6 : 0));
        return weights;
    }

    /**
     * A nation's action on the turn at real time {@code time}: a raid declared then resolves half a turn later. Before
     * it acts, its places count down their securing, and a blighting nation's land grows more dangerous.
     */
    public static void nationAct(Realm realm, String nation, NationAction action, long time) {
        RealmFactions.Traits traits = RealmFactions.get(nation);
        for (RealmNode node : realm.nodes()) {
            if (nation.equals(node.owner())) {
                if (node.stat("securing") > 0) {
                    realm.addStat(node, "securing", -1);
                }
                if (traits.blight() > 0 && node.stat("blight") < RealmLevels.MAX_BLIGHT) {
                    realm.setStat(node, "blight", Math.min(RealmLevels.MAX_BLIGHT, node.stat("blight") + traits.blight()));
                }
            }
        }
        String name = traits.displayName();
        List<RealmNode> strongholds = strongholds(realm, nation);
        List<RealmNode> towns = supported(towns(realm, nation));
        towns.forEach(town -> realm.addStat(town, "wealth", 1));
        Optional<RealmNode> capital = RealmCapitals.capital(realm, nation);
        capital.ifPresent(home -> realm.addStat(home, "wealth", 1));
        if (action != NationAction.RAID && traits.burstRatio() > 0 && capital.isPresent() && capital.get().stat("bursting") > 0) {
            realm.setStat(capital.get(), "bursting", 0);
            realm.news(Realm.LogKind.MARCH, nation, "The " + name + " fall back to " + capital.get().name() + " to gather their strength.");
        }
        switch (action) {
            case BUILD_MILITARY -> {
                int gained = points(realm, nation, 2);
                if (strongholds.isEmpty()) {
                    // Pushed back: the nation rebuilds at its capital.
                    capital.ifPresent(home -> {
                        realm.addStat(home, "military", gained);
                        realm.record(Realm.LogKind.PREPARE, nation, "The " + name + " gathered strength at " + home.name() + ".");
                    });
                    return;
                }
                if (capital.isPresent() && capital.get().stat("military") < RealmCapitals.GARRISON) {
                    // New troops muster at home first, until its garrison is back to strength (DD-104).
                    realm.addStat(capital.get(), "military", gained);
                    realm.record(Realm.LogKind.PREPARE, nation, "The " + name + " mustered new troops at " + capital.get().name() + ": its garrison is now " + capital.get().stat("military") + ".");
                    return;
                }
                strongholds.forEach(node -> realm.addStat(node, "military", gained));
                realm.record(Realm.LogKind.PREPARE, nation, "The " + name + " built up their military: " + names(strongholds) + " each gained " + gained + ".");
            }
            case FORTIFY -> {
                int gained = points(realm, nation, 1) + RealmEconomy.lumber(realm, nation);
                strongholds.forEach(node -> realm.addStat(node, "fortification", gained));
                towns.forEach(town -> realm.addStat(town, "defense", gained));
                capital.ifPresent(home -> realm.addStat(home, "defense", gained));
                realm.record(Realm.LogKind.PREPARE, nation, "The " + name + " fortified " + names(java.util.stream.Stream.concat(strongholds.stream(), towns.stream()).toList()) + ".");
            }
            case RAID -> raidPlan(realm, nation).ifPresent(plan -> {
                if (traits.burstRatio() > 0 && capital.isPresent() && capital.get().stat("bursting") == 0) {
                    realm.setStat(capital.get(), "bursting", 1);
                    realm.news(Realm.LogKind.MARCH, nation, "Storms gather over " + capital.get().name() + ": the " + name + " are on the march!");
                }
                Realm.Raid raid = new Realm.Raid(nation, plan.from().id, plan.target().id, realm.turn, time + turnMillis() / 2);
                realm.raids.add(raid);
                realm.news(Realm.LogKind.MARCH, nation, RealmRaids.who(realm, raid, plan.from()) + " will " + raidVerb(plan.target(), nation) + " at dusk!");
            });
        }
    }

    // --- Shared ---

    /** A weighted random pick; the first action if every weight is zero. */
    static <T> T pick(Map<T, Integer> weights, RandomSource random) {
        int total = weights.values().stream().mapToInt(Integer::intValue).sum();
        int roll = total <= 0 ? 0 : random.nextInt(total);
        for (Map.Entry<T, Integer> entry : weights.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                return entry.getKey();
            }
        }
        return weights.keySet().iterator().next();
    }

    private static String names(List<RealmNode> nodes) {
        return nodes.isEmpty() ? "nowhere" : String.join(", ", nodes.stream().map(RealmNode::name).toList());
    }
}
