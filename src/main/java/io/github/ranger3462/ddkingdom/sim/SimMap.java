package io.github.ranger3462.ddkingdom.sim;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonObject;

/**
 * A map to play on, as the simulator's scenarios: the kingdoms with their starting stockpiles, the nodes with their
 * starting state, and the roads (edges) between nodes. The maps from the simulator are in
 * {@code data/ddkingdom/realm/sim/maps}: {@code large_ruins} (the default), {@code large_settled},
 * {@code continent_dwarves}, {@code continent_ruins}, {@code continent_settled} and {@code four_corners}.
 */
public final class SimMap {
    public String name = "";
    public List<KingdomSetup> kingdoms = new ArrayList<>();
    public List<NodeSetup> nodes = new ArrayList<>();
    public List<String[]> edges = new ArrayList<>();

    /** A kingdom as a map sets it up. Field names match the simulator's map JSON. */
    public static final class KingdomSetup {
        public String id, name, color = "";
        public double food, gold, res;
        /** "fast", "normal" or "slow" (a key of {@link Rules#growth}). */
        public String growth = "normal";
        /** Its personality, a key of {@link Rules.Ai#personalities}. */
        public String ai = "balanced";
        /** The player faction (the Hearthbound): what the old Elemental burst cycle and "playerOnly" targets aim at. */
        public boolean player;
        /** May hire into negative food when the hire lets it take a node. */
        public boolean goblinRule;
        /** Troop strength, attacking and defending. */
        public double strength = 1;
        /** Troop strength when defending, if different (null: {@link #strength}). */
        public Double defStrength;
        /** Multiplier on defending strength at its own home nodes. */
        public double homeDefStrength;
        public double upkeepMult;
        /** Share of every battle's casualties it raises as its own. */
        public double raiseDead;
        /** Kingdoms a "raze" kingdom attacks. */
        public List<String> foes = new ArrayList<>();
        /** Gets no free rebuild on land it captures outside its home nodes. */
        public boolean noFreeRebuild;
        /** Its captures always knock a node down a tier, whatever the rules say (the Dwarves). */
        public boolean razes;
        /** It can Unleash the brood (Rules.Brood): the Kobolds. */
        public boolean brood;
        /** A minor faction (Rules.Minor), set when the game adds it. */
        public transient boolean minor;
        /** Rounds a counter-attacking kingdom builds up at home before giving the grudge up (0: none). */
        public int counterPatience;
    }

    /** A node as a map sets it up. */
    public static final class NodeSetup {
        public String id, name;
        public NodeType type;
        public int tier = 1;
        /** Null for unowned land. */
        public String owner;
        /** The faction whose homeland it is, or null for neutral land. */
        public String home;
        public int garrison, defense, morale = 10;
        public double devastation;
        /** Where it's drawn on the simulator's map. */
        public double x, y;
    }

    /** One of the maps that ships with the mod, by id ("large_ruins"). */
    public static SimMap named(String id) {
        String path = "/data/ddkingdom/realm/sim/maps/" + id + ".json";
        try (InputStream in = SimMap.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalArgumentException("No map " + id);
            }
            try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
                return Rules.GSON.fromJson(reader, SimMap.class);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Can't read " + path, e);
        }
    }

    public static SimMap parse(JsonObject json) {
        return Rules.GSON.fromJson(json, SimMap.class);
    }
}
