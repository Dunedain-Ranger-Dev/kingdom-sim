package io.github.ranger3462.ddkingdom.realm;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.sim.Combat;
import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.Kingdom;
import io.github.ranger3462.ddkingdom.sim.Node;
import io.github.ranger3462.ddkingdom.sim.NodeType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * The realm's turns by the simulator's rules (docs/design/realm-cutover.md, phases 2 and 3), when {@code realmEngine}
 * is {@code sim}: a round of the realm's {@link SimRealm} every {@code realmTurnMinutes}, caught up after time away as
 * the old turns are.
 * <p>
 * The simulator's state is <b>mirrored</b> onto the realm's own nodes after every round and fight, so everything in
 * the world reads it as before: guards, danger, the realm menu and map icons, live raids and champions. Each placed
 * simulator node becomes a realm node of the same id ({@link #type}, {@link #owner}), with its tier, troops (its
 * {@code military}; a Hearthbound town turns that many out as its {@code guards}, up to {@link RealmLiveRaids#MAX_GUARDS}),
 * defense and morale; nodes not on the simulator's map go. A raid on a node's devastation breaks that many blocks of
 * its blueprint, and repairing it rebuilds them. Troops changed in the world (a champion killed at a capital, an
 * operator's {@code /realm node set}) go back into the simulator.
 * <p>
 * An attack on the player kingdom waits for dusk if a player is within {@link RealmLiveRaids#PRESENCE} blocks of the
 * place it's on, and shows as a raid declared on it. At dusk, if a player is there, it's fought live, and the fight
 * settles the simulator's battle ({@link #decided}); if not, it's rolled.
 */
public final class RealmSimTurns {
    /** The realm faction each of the simulator's is, where the ids differ (the rest keep theirs). */
    private static final Map<String, String> OWNERS = Map.of(SimRealm.PLAYERS, RealmNode.KINGDOM, "go", "goblins", "or", "orcs", "ko", "kobolds",
            "un", "undead", "el", "elementals", "dw", "dwarves");
    /** Stats the mirror keeps, to see what changed in the world since. */
    private static final String MIRRORED_MILITARY = "sim_military", MIRRORED_DEVASTATION = "sim_devastation";

    private RealmSimTurns() {}

    /** Plays what's due by real time {@code now}, if the realm's been started by the simulator's rules. */
    public static void tick(MinecraftServer server, Realm realm, long now) {
        Optional<SimRealm> started = realm.sim();
        if (started.isEmpty()) {
            return;
        }
        SimRealm sim = started.get();
        sim.playersNear = (kid, target) -> playerNear(server, realm, target);
        writeBack(realm, sim);
        int before = sim.game.round;
        mirror(realm, sim); // so held fights show as raids before dusk
        fightAtDusk(server, realm, sim, now);
        sim.catchUp(now, RealmTurns.turnMillis(), KingdomConfig.REALM_CATCH_UP_TURNS.get());
        boolean changed = record(realm, sim);
        if (changed || sim.game.round != before) {
            if (sim.game.round != before) {
                RealmGarrison.newDay(realm); // the fallen are replaced each round
            }
            realm.turn = sim.game.round - 1;
            mirror(realm, sim);
        }
    }

    /** Plays {@code count} rounds now, whatever the clock says (for /realm sim turn). */
    public static void playNow(MinecraftServer server, Realm realm, SimRealm sim, int count) {
        sim.playersNear = (kid, target) -> playerNear(server, realm, target);
        writeBack(realm, sim);
        long now = System.currentTimeMillis();
        for (int i = 0; i < count; i++) {
            sim.settleDue(Long.MAX_VALUE); // forced rounds don't wait for dusk
            sim.playRound(now, RealmTurns.turnMillis());
            RealmGarrison.newDay(realm);
        }
        sim.lastTurnMillis = now;
        record(realm, sim);
        realm.turn = sim.game.round - 1;
        mirror(realm, sim);
    }

    /** Whether a player is near the place a simulator node stands, in the world. */
    static boolean playerNear(MinecraftServer server, Realm realm, Node target) {
        Optional<Realm.Place> place = realm.simPlace(target.id);
        if (place.isEmpty()) {
            return false;
        }
        ServerLevel level = server.getLevel(place.get().dimension());
        return level != null && RealmLiveRaids.playerAt(level, place.get().pos(), RealmLiveRaids.PRESENCE);
    }

    // ---- Dusk: live fights ----

    /** Starts a live fight for each held attack whose dusk has come with a player there; the rest are rolled by catching up. */
    private static void fightAtDusk(MinecraftServer server, Realm realm, SimRealm sim, long now) {
        for (Game.HeldBattle b : sim.due(now)) {
            Optional<Realm.Raid> raid = raidFor(realm, b);
            Optional<RealmNode> town = realm.node(b.target);
            if (raid.isEmpty() || town.isEmpty()) {
                continue;
            }
            ServerLevel level = server.getLevel(town.get().dimension());
            if (level != null && level.isLoaded(town.get().pos()) && RealmLiveRaids.playerAt(level, town.get().pos(), RealmLiveRaids.PRESENCE)) {
                sim.fightLive(b.id);
                RealmLiveRaids.start(realm, raid.get(), level);
            }
        }
    }

    private static Optional<Realm.Raid> raidFor(Realm realm, Game.HeldBattle b) {
        return realm.raids.stream().filter(r -> r.stronghold().equals(b.from) && r.town().equals(b.target)).findFirst();
    }

    /**
     * Settles a raid on the simulator's realm, if it is one of its held fights (for {@link RealmRaids#decide}): from a
     * live fight's scores ({@code fought}), or by the simulator's dice if it was rolled after all. Returns whether it was.
     */
    static boolean decided(Realm realm, Realm.Raid raid, int attack, int defense, boolean fought) {
        if (!realm.mirrored || realm.sim().isEmpty()) {
            return false;
        }
        SimRealm sim = realm.sim().get();
        realm.raids.remove(raid);
        Optional<Game.HeldBattle> held = sim.game.held.stream().filter(b -> b.from.equals(raid.stronghold()) && b.target.equals(raid.town())).findFirst();
        if (held.isPresent()) {
            Game.HeldBattle b = held.get();
            sim.settle(b.id, fought ? fought(b, sim.game.node(b.target).garrison, attack, defense) : null);
            record(realm, sim);
            mirror(realm, sim);
        }
        realm.setDirty();
        return true;
    }

    /**
     * A live fight as the simulator's battle: the attackers win if they scored more. Each side keeps its share of the
     * scores (the winners at least one); a won capture leaves no defenders.
     */
    static Combat.Battle fought(Game.HeldBattle b, int garrison, int attack, int defense) {
        boolean win = attack > defense;
        double share = attack + defense <= 0 ? 0.5 : attack / (double) (attack + defense);
        int survivors = (int) Math.round(b.troops * share), left = (int) Math.round(garrison * (1 - share));
        return Combat.fought(win, win ? Math.max(1, survivors) : survivors, win ? left : Math.max(1, left));
    }

    // ---- The mirror ----

    /** The realm faction a simulator kingdom is ({@link RealmNode#NONE} for no one). */
    public static String owner(String kid) {
        return kid == null ? RealmNode.NONE : OWNERS.getOrDefault(kid, kid);
    }

    /** The realm node type a simulator node mirrors as. */
    public static RealmNode.Type type(NodeType type) {
        return switch (type) {
            case CAPITAL -> RealmNode.Type.CAPITAL;
            case TOWN -> RealmNode.Type.TOWN;
            case CASTLE -> RealmNode.Type.STRONGHOLD;
            case OUTPOST -> RealmNode.Type.SITE;
            case FOOD, PRODUCTION -> RealmNode.Type.RESOURCE;
        };
    }

    private static String kind(NodeType type) {
        return switch (type) {
            case OUTPOST -> "watchtower";
            case FOOD -> RealmEconomy.Kind.FARMLAND.id();
            case PRODUCTION -> RealmEconomy.Kind.MINE.id();
            default -> "";
        };
    }

    /** Troops changed in the world since the last mirror (a champion killed, an operator's set) go back into the simulator. */
    public static void writeBack(Realm realm, SimRealm sim) {
        for (Node n : sim.game.nodes) {
            Optional<RealmNode> node = realm.node(n.id);
            if (node.isPresent() && node.get().stats().containsKey(MIRRORED_MILITARY)) {
                int delta = node.get().stat("military") - node.get().stat(MIRRORED_MILITARY);
                if (delta != 0) {
                    n.garrison = Math.max(0, n.garrison + delta);
                }
            }
        }
    }

    /** Copies the simulator's state onto the realm's nodes, links, purses and raids. */
    public static void mirror(Realm realm, SimRealm sim) {
        realm.mirrored = true;
        Game game = sim.game;
        Map<String, Integer> away = new LinkedHashMap<>();
        game.held.forEach(b -> away.merge(b.from, b.troops, Integer::sum));
        // Nodes: the placed simulator nodes, and nothing else.
        Set<String> ids = new HashSet<>();
        game.nodes.forEach(n -> ids.add(n.id));
        for (RealmNode node : List.copyOf(realm.nodes())) {
            if (!ids.contains(node.id) || realm.simPlace(node.id).isEmpty()) {
                realm.remove(node.id);
            }
        }
        for (Node n : game.nodes) {
            Optional<Realm.Place> place = realm.simPlace(n.id);
            if (place.isEmpty()) {
                continue;
            }
            RealmNode node = placed(realm, n, place.get());
            node.setName(n.name);
            if (!owner(n.owner).equals(node.owner())) {
                node.setOwner(owner(n.owner));
            }
            if (!kind(n.type).equals(node.kind()) && !kind(n.type).isEmpty()) {
                node.setKind(kind(n.type));
            }
            int military = n.garrison + away.getOrDefault(n.id, 0);
            node.setStat("military", military);
            node.setStat(MIRRORED_MILITARY, military);
            node.setStat("guards", Math.min(RealmLiveRaids.MAX_GUARDS, n.garrison)); // a town's guards are its troops (RealmGarrison)
            node.setStat("tier", n.tier);
            node.setStat("defense", n.defense);
            node.setStat("fortification", n.defense);
            node.setStat("morale", n.morale);
            node.setStat("upgrading", n.upgrade != null ? n.upgrade.turnsLeft : 0);
            // Devastation is broken blocks: more of it breaks more of the blueprint, less rebuilds it.
            int devastation = (int) Math.round(n.devastation * 100), was = node.stat(MIRRORED_DEVASTATION);
            if (devastation > was) {
                node.setStat("damage", node.stat("damage") + devastation - was);
            } else if (devastation < was) {
                node.setStat("repair", node.stat("repair") + (was - devastation) * RealmDamage.BLOCKS_PER_POINT);
            }
            node.setStat(MIRRORED_DEVASTATION, devastation);
        }
        // Links: the simulator's, between placed nodes.
        Set<String> want = new HashSet<>();
        for (Node n : game.nodes) {
            for (String other : game.neighbours(n.id)) {
                if (realm.node(n.id).isPresent() && realm.node(other).isPresent()) {
                    want.add(n.id.compareTo(other) < 0 ? n.id + "|" + other : other + "|" + n.id);
                }
            }
        }
        for (String[] link : realm.links()) {
            if (!want.remove(link[0].compareTo(link[1]) < 0 ? link[0] + "|" + link[1] : link[1] + "|" + link[0])) {
                realm.unlink(link[0], link[1]);
            }
        }
        want.forEach(link -> realm.link(link.split("\\|")[0], link.split("\\|")[1]));
        // Purses: each faction's food, and its gold as trade; the kingdom's army.
        for (Kingdom k : game.kingdoms) {
            Map<String, Integer> purse = realm.purses.computeIfAbsent(owner(k.id), key -> new LinkedHashMap<>());
            purse.put(RealmEconomy.FOOD, (int) Math.round(k.food));
            purse.put(RealmEconomy.TRADE, (int) Math.round(k.gold));
        }
        realm.kingdom.put("army", game.army(SimRealm.PLAYERS));
        // Raids: a held fight is a raid declared on its target, until it's settled.
        realm.raids.removeIf(r -> game.held.stream().noneMatch(b -> b.from.equals(r.stronghold()) && b.target.equals(r.town())));
        for (Game.HeldBattle b : game.held) {
            Long dusk = sim.dusk(b.id);
            if (dusk != null && realm.node(b.target).isPresent() && realm.node(b.from).isPresent()
                    && realm.raids.stream().noneMatch(r -> r.stronghold().equals(b.from) && r.town().equals(b.target))) {
                realm.raids.add(new Realm.Raid(owner(b.kid), b.from, b.target, b.round, dusk));
            }
        }
        realm.setDirty();
    }

    /** The realm node a placed simulator node mirrors onto: made, or remade if its type has changed (keeping its guards' posts and look). */
    private static RealmNode placed(Realm realm, Node n, Realm.Place place) {
        RealmNode.Type type = type(n.type);
        Optional<RealmNode> old = realm.node(n.id);
        if (old.isPresent() && old.get().type == type) {
            if (!old.get().pos().equals(place.pos()) || !old.get().dimension().equals(place.dimension())) {
                realm.move(old.get(), place.dimension(), place.pos());
            }
            return old.get();
        }
        RealmNode node = new RealmNode(n.id, type, owner(n.owner), place.dimension(), place.pos());
        if (old.isPresent()) {
            old.get().posts().forEach(node::addPost);
            node.setGuardLook(old.get().guardSkin(), old.get().guardColor());
            realm.remove(n.id);
        }
        realm.add(node);
        return node;
    }

    /** The simulator's events into the realm log, by kind; returns whether there were any. */
    private static boolean record(Realm realm, SimRealm sim) {
        boolean any = false;
        for (SimRealm.Event e : sim.takeEvents()) {
            Realm.LogKind kind = switch (e.kind()) {
                case "capture" -> Realm.LogKind.CAPTURE;
                case "raid", "fail" -> Realm.LogKind.BATTLE;
                case "held" -> Realm.LogKind.MARCH;
                case "shortage" -> Realm.LogKind.SUPPLY;
                default -> null;
            };
            if (kind == null && !e.news()) {
                continue; // the routine of each turn (hiring, upgrading, producing) stays out of the log
            }
            realm.recordSim(kind == null ? Realm.LogKind.OTHER : kind, owner(e.kid().isEmpty() ? null : e.kid()), e.text(), e.news());
            any = true;
        }
        return any;
    }
}
