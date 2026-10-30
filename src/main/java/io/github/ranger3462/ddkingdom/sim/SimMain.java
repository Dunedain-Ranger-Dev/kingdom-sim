package io.github.ranger3462.ddkingdom.sim;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The simulator from the command line ({@code ./gradlew runSim --args="..."}): plays a batch of AI-only games and
 * reports how they went, as the simulator's Batch tab does.
 * <pre>
 *   --map large_ruins       a map from data/ddkingdom/realm/sim/maps
 *   --games 200             games to play (seeds 1 up, or from --seed)
 *   --rounds 40             rounds per game
 *   --seed 1                first seed
 *   --set combat.retreat=0.3    change a rule (any number of times; the value is JSON)
 *   --fingerprint           print each game's end state instead, to check against the browser simulator
 *                           (tools/simulator/parity.js), with the rules added since the port turned off:
 *                           --set combat.dice.mode="none"
 * </pre>
 */
public final class SimMain {
    private SimMain() {}

    public static void main(String[] args) {
        String mapId = "large_ruins";
        int games = 200, rounds = 40, seed = 1;
        boolean fingerprint = false;
        JsonObject overrides = new JsonObject();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> mapId = args[++i];
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--rounds" -> rounds = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Integer.parseInt(args[++i]);
                case "--fingerprint" -> fingerprint = true;
                case "--set" -> {
                    String[] kv = args[++i].split("=", 2);
                    Rules.override(overrides, kv[0], JsonParser.parseString(kv[1]));
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        SimMap map = SimMap.named(mapId);
        Rules rules = Rules.load(overrides);
        if (fingerprint) {
            for (int s = seed; s < seed + games; s++) {
                System.out.print(s + " " + fingerprint(play(map, rules, s, rounds)) + "\n"); // \n on every system, to compare with parity.js
            }
            return;
        }
        report(map, rules, seed, games, rounds);
    }

    public static Game play(SimMap map, Rules rules, int seed, int rounds) {
        Game game = new Game(map, rules, seed, rounds, true);
        for (int i = 0; i < rounds; i++) {
            game.runRound();
        }
        return game;
    }

    /** A game's state in a form both simulators print the same: stockpiles in hundredths, every node's state. */
    public static String fingerprint(Game game) {
        StringBuilder out = new StringBuilder();
        for (Kingdom k : game.kingdoms) {
            out.append(k.id).append(':').append(Math.round(k.food * 100)).append(',').append(Math.round(k.wealth * 100)).append(',')
                    .append(Math.round(k.res * 100)).append(',').append(game.army(k.id)).append(' ');
        }
        for (Node n : game.nodes) {
            out.append(n.owner == null ? "-" : n.owner).append('|').append(n.tier).append('|').append(n.garrison).append('|').append(n.defense).append('|')
                    .append(n.morale).append('|').append(Math.round(n.devastation * 100)).append(' ');
        }
        return out.toString().trim();
    }

    private static void report(SimMap map, Rules rules, int seed, int games, int rounds) {
        long start = System.currentTimeMillis();
        Map<String, double[]> per = new LinkedHashMap<>(); // nodes, army, wealth, leads
        Map<String, Integer> actions = new LinkedHashMap<>();
        double ruins = 0, unowned = 0, captures = 0, raids = 0, attacks = 0, attacksWon = 0, upsets = 0;
        int totalActions = 0;
        for (int s = seed; s < seed + games; s++) {
            Game game = play(map, rules, s, rounds);
            String leader = null;
            int most = -1;
            for (Kingdom k : game.kingdoms) {
                int nodes = game.ownedNodes(k.id).size();
                double[] row = per.computeIfAbsent(k.id, id -> new double[4]);
                row[0] += nodes;
                row[1] += game.army(k.id);
                row[2] += k.wealth;
                if (nodes > most) {
                    most = nodes;
                    leader = k.id;
                }
                captures += k.stats.capturesWon;
                raids += k.stats.raidsWon;
                attacks += k.stats.capturesTried + k.stats.raidsTried;
                attacksWon += k.stats.capturesWon + k.stats.raidsWon;
                upsets += k.stats.upsets;
                for (Map.Entry<String, Integer> e : k.stats.actions.entrySet()) {
                    actions.merge(e.getKey(), e.getValue(), Integer::sum);
                    totalActions += e.getValue();
                }
            }
            per.get(leader)[3]++;
            for (Node n : game.nodes) {
                if (n.type != NodeType.CAPITAL) {
                    if (n.tier == 0 && !(n.upgrade != null && n.upgrade.free)) {
                        ruins++;
                    }
                    if (n.owner == null) {
                        unowned++;
                    }
                }
            }
        }
        System.out.printf(Locale.ROOT, "%s: %d games of %d rounds (%d ms)%n", map.name, games, rounds, System.currentTimeMillis() - start);
        System.out.printf(Locale.ROOT, "  ruins %.1f | unowned %.1f | captures %.1f | raids %.1f (per game)%n", ruins / games, unowned / games,
                captures / games, raids / games);
        System.out.printf(Locale.ROOT, "  attacks won %.0f%% | turned by the dice %.1f%% of attacks%n", 100 * attacksWon / Math.max(1, attacks),
                100 * upsets / Math.max(1, attacks));
        System.out.printf(Locale.ROOT, "  %-6s %7s %7s %8s %7s%n", "", "nodes", "army", "wealth", "leads");
        for (Map.Entry<String, double[]> e : per.entrySet()) {
            double[] r = e.getValue();
            System.out.printf(Locale.ROOT, "  %-6s %7.1f %7.0f %8.0f %6.0f%%%n", e.getKey(), r[0] / games, r[1] / games, r[2] / games, 100 * r[3] / games);
        }
        StringBuilder mix = new StringBuilder("  actions:");
        int total = totalActions;
        actions.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> mix.append(String.format(Locale.ROOT, " %s %.0f%%", e.getKey(), 100.0 * e.getValue() / total)));
        System.out.println(mix);
    }
}
