package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiPredicate;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.GameSave;
import io.github.ranger3462.ddkingdom.sim.Node;
import io.github.ranger3462.ddkingdom.sim.Rules;
import io.github.ranger3462.ddkingdom.sim.SimMap;

/**
 * The realm played by the simulator's rules (docs/design/realm-cutover.md, phase 2): an open-ended simulator game on a
 * real-time clock, a round a turn. Attacks on the player kingdom with a player near are held, and settled at dusk, half
 * a turn after they're declared; every other fight is rolled at once. No Minecraft here, so it can be tested on its own;
 * {@link Realm} saves it with the world, and {@link RealmTurns} runs it when {@code realmEngine} is {@code sim}.
 */
public final class SimRealm {
    /** The map the server's world is built on. */
    public static final String DEFAULT_MAP = "large_ruins";
    /** The player kingdom: the only one whose fights wait for players. */
    public static final String PLAYERS = "hb";
    private static final Gson GSON = new Gson();

    public final String mapId;
    private final SimMap map;
    private final Rules rules;
    public final Game game;
    /** When the last round was due, in real time (milliseconds). */
    public long lastTurnMillis;
    /** Each held attack's dusk: when it's settled (real time), by its id. */
    private final Map<Integer, Long> dusk = new LinkedHashMap<>();
    /** What happened, for the realm's log and news, oldest first, until taken. */
    private final List<Event> events = new ArrayList<>();
    /** Which held attacks the host has players for: the attacker's id and its target (set by the host each time; not saved). */
    public BiPredicate<String, Node> playersNear = (kid, target) -> false;

    /**
     * Something that happened: a {@code kind} (the simulator's: capture, raid, fail, held, react, action...) about a
     * kingdom ({@code kid}). {@code news} is for every player to hear.
     */
    public record Event(int round, String kid, String kind, String text, boolean news) {}

    private SimRealm(String mapId, Game game, long lastTurnMillis) {
        this.mapId = mapId;
        this.map = SimMap.named(mapId);
        this.rules = game.rules;
        this.game = game;
        this.lastTurnMillis = lastTurnMillis;
        game.holdBattle = (kid, target) -> PLAYERS.equals(target.owner) && playersNear.test(kid, target);
    }

    /** A fresh realm on a map, its clock starting {@code now}. */
    public static SimRealm start(String mapId, int seed, long now) {
        return new SimRealm(mapId, new Game(SimMap.named(mapId), Rules.defaults(), seed, 0, false), now);
    }

    // ---- The clock ----

    /**
     * Plays the rounds due by real time {@code now}, a round every {@code turnMillis}: none until a turn's length has
     * passed since the last. After a long time away, at most {@code cap} (the rest are skipped). Fights due at dusk are
     * settled first, both before each round and at {@code now}. Returns how many rounds were played.
     */
    public int catchUp(long now, long turnMillis, int cap) {
        long due = (now - lastTurnMillis) / turnMillis;
        int played = 0;
        if (due > 0) {
            int most = Math.max(1, cap);
            if (due > most) {
                long skipped = due - most;
                lastTurnMillis += skipped * turnMillis;
                events.add(new Event(game.round, "", "react", "The realm was quiet for " + skipped + " turns while no one watched.", false));
                due = most;
            }
            for (int i = 0; i < due; i++) {
                long turnTime = lastTurnMillis + turnMillis;
                settleDue(turnTime);
                playRound(turnTime, turnMillis);
                lastTurnMillis = turnTime;
                played++;
            }
        }
        settleDue(now);
        return played;
    }

    /** Milliseconds until the next round, by real time {@code now}. */
    public long untilNext(long now, long turnMillis) {
        return Math.max(0, lastTurnMillis + turnMillis - now);
    }

    /** One round, at real time {@code time}; anything held in it is settled at dusk, half a turn on. */
    void playRound(long time, long turnMillis) {
        Map<String, String> owners = owners();
        game.runRound();
        for (Game.HeldBattle b : game.held) {
            if (dusk.putIfAbsent(b.id, time + turnMillis / 2) == null) {
                Node target = game.node(b.target);
                events.add(new Event(game.round, b.kid, "held", name(b.kid) + " march on " + target.name + "! The fight comes at dusk.", true));
            }
        }
        takeLog();
        changedHands(owners);
    }

    /** Settles, with the dice, every held fight whose dusk has come by {@code now}. */
    public void settleDue(long now) {
        List<Integer> due = new ArrayList<>();
        dusk.forEach((id, at) -> {
            if (at <= now) {
                due.add(id);
            }
        });
        for (int id : due) {
            settle(id, null);
        }
    }

    /** Settles a held fight: with what happened in the world ({@code live}, see Combat.fought), or the dice if null. */
    public void settle(int id, io.github.ranger3462.ddkingdom.sim.Combat.Battle live) {
        Map<String, String> owners = owners();
        dusk.remove(id);
        game.settle(id, live);
        takeLog();
        changedHands(owners);
    }

    // ---- What happened ----

    /** Takes what happened since last time. */
    public List<Event> takeEvents() {
        List<Event> taken = List.copyOf(events);
        events.clear();
        return taken;
    }

    /** The simulator's log, less the bookkeeping of each turn (production, upkeep), into the events. */
    private void takeLog() {
        for (Game.LogEntry e : game.log) {
            if (!"phase".equals(e.kind()) && !"turn".equals(e.kind())) {
                events.add(new Event(e.round(), e.kid() == null ? "" : e.kid(), e.kind(), e.text(), false));
            }
        }
        game.log.clear();
    }

    private Map<String, String> owners() {
        Map<String, String> owners = new LinkedHashMap<>();
        game.nodes.forEach(n -> owners.put(n.id, n.owner));
        return owners;
    }

    /** News of every node that changed hands since {@code before}. */
    private void changedHands(Map<String, String> before) {
        for (Node n : game.nodes) {
            String was = before.get(n.id);
            if (java.util.Objects.equals(was, n.owner)) {
                continue;
            }
            String text = n.owner == null ? n.name + " has been abandoned."
                    : was == null ? name(n.owner) + " have taken " + n.name + "."
                    : name(n.owner) + " have taken " + n.name + " from " + name(was) + ".";
            events.add(new Event(game.round, n.owner == null ? was : n.owner, "capture", text, true));
        }
    }

    private String name(String kid) {
        return game.kingdom(kid) == null ? kid : "the " + game.kingdom(kid).name;
    }

    // ---- Saving ----

    public String save() {
        JsonObject o = new JsonObject();
        o.addProperty("map", mapId);
        o.addProperty("lastTurnMillis", lastTurnMillis);
        o.add("dusk", GSON.toJsonTree(dusk));
        o.add("game", GameSave.save(game));
        return GSON.toJson(o);
    }

    public static SimRealm load(String text) {
        JsonObject o = GSON.fromJson(text, JsonObject.class);
        String mapId = o.get("map").getAsString();
        Game game = GameSave.load(SimMap.named(mapId), Rules.defaults(), o.getAsJsonObject("game"), 0, false);
        SimRealm realm = new SimRealm(mapId, game, o.get("lastTurnMillis").getAsLong());
        o.getAsJsonObject("dusk").entrySet().forEach(e -> realm.dusk.put(Integer.parseInt(e.getKey()), e.getValue().getAsLong()));
        return realm;
    }

    /** When a held fight is settled (real time), or null if there's no such held fight. */
    public Long dusk(int id) {
        return dusk.get(id);
    }

    public SimMap map() {
        return map;
    }

    public Rules rules() {
        return rules;
    }
}
