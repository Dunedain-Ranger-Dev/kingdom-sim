package io.github.ranger3462.ddkingdom.sim;

import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

/**
 * A game saved between turns, and loaded back to carry on exactly where it left off: the same map and rules with the
 * same seed play on as if never stopped. Only what changes during a game is saved (each node's state, each kingdom's
 * stockpiles and memory, the turn order and whose turn it is, the random numbers' places, held attacks); the map and rules are given
 * again on loading. The log and history aren't saved.
 */
public final class GameSave {
    /** The save format; a save of another version won't load. */
    public static final int VERSION = 1;
    private static final Gson GSON = new Gson();

    private GameSave() {}

    /** The game as it stands, between turns. */
    public static JsonObject save(Game g) {
        if (g.cur != null) {
            throw new IllegalStateException("A game can only be saved between turns.");
        }
        JsonObject o = new JsonObject();
        o.addProperty("version", VERSION);
        o.addProperty("seed", g.seed);
        o.addProperty("round", g.round);
        o.addProperty("turnPos", g.turnPos);
        o.add("order", GSON.toJsonTree(g.order)); // it rotates each round
        JsonObject rng = new JsonObject();
        rng.addProperty("main", g.rand.state());
        rng.addProperty("quest", g.questRng.state());
        rng.addProperty("spawn", g.spawnRng.state());
        o.add("rng", rng);
        o.add("influence", GSON.toJsonTree(g.influence));
        JsonObject kingdoms = new JsonObject();
        for (Kingdom k : g.kingdoms) {
            JsonObject ko = new JsonObject();
            ko.addProperty("food", k.food);
            ko.addProperty("gold", k.gold);
            ko.addProperty("res", k.res);
            ko.addProperty("buffTurns", k.buffTurns);
            ko.add("imports", GSON.toJsonTree(k.imports));
            ko.add("lost", GSON.toJsonTree(k.lost));
            ko.add("counter", GSON.toJsonTree(k.counter));
            ko.addProperty("falling", k.falling);
            ko.addProperty("mode", k.mode);
            ko.addProperty("cooldownUntil", k.cooldownUntil);
            ko.addProperty("trained", k.trained);
            ko.addProperty("hoard", k.hoard);
            ko.addProperty("awake", k.awake);
            ko.addProperty("nextSpawn", k.nextSpawn);
            ko.addProperty("present", k.present);
            ko.addProperty("lastProduced", k.lastProduced);
            ko.add("stats", GSON.toJsonTree(k.stats));
            kingdoms.add(k.id, ko);
        }
        o.add("kingdoms", kingdoms);
        JsonObject nodes = new JsonObject();
        for (Node n : g.nodes) {
            JsonObject no = new JsonObject();
            no.addProperty("tier", n.tier);
            no.addProperty("owner", n.owner);
            no.addProperty("garrison", n.garrison);
            no.addProperty("defense", n.defense);
            no.addProperty("morale", n.morale);
            no.addProperty("devastation", n.devastation);
            no.add("upgrade", GSON.toJsonTree(n.upgrade));
            no.addProperty("attackedRound", n.attackedRound);
            no.addProperty("capturedRound", n.capturedRound);
            nodes.add(n.id, no);
        }
        o.add("nodes", nodes);
        o.add("held", GSON.toJsonTree(g.held));
        o.addProperty("nextHeld", g.nextHeld);
        return o;
    }

    /**
     * A saved game, carried on with {@code map} and {@code rules} (those it was started with). {@code maxRounds} and
     * {@code quiet} are as for a new {@link Game}.
     */
    public static Game load(SimMap map, Rules rules, JsonObject o, int maxRounds, boolean quiet) {
        int version = o.get("version").getAsInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("A save of version " + version + "; this reads version " + VERSION + ".");
        }
        Game g = new Game(map, rules, o.get("seed").getAsInt(), maxRounds, quiet);
        g.round = o.get("round").getAsInt();
        g.turnPos = o.get("turnPos").getAsInt();
        g.order = new java.util.ArrayList<>(GSON.<java.util.List<String>>fromJson(o.get("order"), new TypeToken<java.util.List<String>>() {}.getType()));
        JsonObject rng = o.getAsJsonObject("rng");
        g.rand.state(rng.get("main").getAsInt());
        g.questRng.state(rng.get("quest").getAsInt());
        g.spawnRng.state(rng.get("spawn").getAsInt());
        g.influence.clear();
        g.influence.putAll(GSON.fromJson(o.get("influence"), new TypeToken<Map<String, Double>>() {}.getType()));
        JsonObject kingdoms = o.getAsJsonObject("kingdoms");
        if (!kingdoms.keySet().equals(g.K.keySet())) {
            throw new IllegalArgumentException("The save's kingdoms " + kingdoms.keySet() + " aren't this game's " + g.K.keySet() + ".");
        }
        for (Kingdom k : g.kingdoms) {
            JsonObject ko = kingdoms.getAsJsonObject(k.id);
            k.food = ko.get("food").getAsDouble();
            k.gold = ko.get("gold").getAsDouble();
            k.res = ko.get("res").getAsDouble();
            k.buffTurns = ko.get("buffTurns").getAsInt();
            k.imports.clear();
            k.imports.addAll(GSON.fromJson(ko.get("imports"), new TypeToken<java.util.List<Kingdom.Contract>>() {}.getType()));
            k.lost.clear();
            k.lost.putAll(GSON.fromJson(ko.get("lost"), new TypeToken<Map<String, Integer>>() {}.getType()));
            k.counter = isNull(ko.get("counter")) ? null : GSON.fromJson(ko.get("counter"), Kingdom.Counter.class);
            k.falling = ko.get("falling").getAsBoolean();
            k.mode = ko.get("mode").getAsString();
            k.cooldownUntil = ko.get("cooldownUntil").getAsInt();
            k.trained = ko.get("trained").getAsInt();
            k.hoard = ko.get("hoard").getAsDouble();
            k.awake = ko.get("awake").getAsBoolean();
            k.nextSpawn = ko.get("nextSpawn").getAsInt();
            k.present = ko.get("present").getAsBoolean();
            k.lastProduced = isNull(ko.get("lastProduced")) ? null : ko.get("lastProduced").getAsInt();
            k.stats = GSON.fromJson(ko.get("stats"), Kingdom.Stats.class);
        }
        JsonObject nodes = o.getAsJsonObject("nodes");
        if (!nodes.keySet().equals(g.N.keySet())) {
            throw new IllegalArgumentException("The save's nodes aren't this map's.");
        }
        for (Node n : g.nodes) {
            JsonObject no = nodes.getAsJsonObject(n.id);
            n.tier = no.get("tier").getAsInt();
            n.owner = isNull(no.get("owner")) ? null : no.get("owner").getAsString();
            n.garrison = no.get("garrison").getAsInt();
            n.defense = no.get("defense").getAsInt();
            n.morale = no.get("morale").getAsInt();
            n.devastation = no.get("devastation").getAsDouble();
            n.upgrade = isNull(no.get("upgrade")) ? null : GSON.fromJson(no.get("upgrade"), Node.Upgrade.class);
            n.attackedRound = isNull(no.get("attackedRound")) ? null : no.get("attackedRound").getAsInt();
            n.capturedRound = isNull(no.get("capturedRound")) ? null : no.get("capturedRound").getAsInt();
        }
        g.held.clear();
        g.held.addAll(GSON.fromJson(o.get("held"), new TypeToken<java.util.List<Game.HeldBattle>>() {}.getType()));
        g.nextHeld = o.get("nextHeld").getAsInt();
        g.history.clear();
        return g;
    }

    /** A saved game as text, and back. */
    public static String toText(Game g) {
        return GSON.toJson(save(g));
    }

    public static Game fromText(SimMap map, Rules rules, String text, int maxRounds, boolean quiet) {
        return load(map, rules, GSON.fromJson(text, JsonObject.class), maxRounds, quiet);
    }

    private static boolean isNull(JsonElement e) {
        return e == null || e.isJsonNull();
    }
}
