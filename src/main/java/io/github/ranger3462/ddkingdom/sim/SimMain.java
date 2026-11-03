package io.github.ranger3462.ddkingdom.sim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *   --kingdom un.raiseDead=0.4  change a kingdom's setting on the map (any number of times; the value is JSON)
 *   --fingerprint           print each game's end state instead, to check against the browser simulator
 *                           (tools/simulator/parity.js)
 * </pre>
 */
public final class SimMain {
    private SimMain() {}

    public static void main(String[] args) {
        String mapId = "large_ruins";
        int games = 200, rounds = 40, seed = 1;
        boolean fingerprint = false;
        JsonObject overrides = new JsonObject();
        Map<String, com.google.gson.JsonElement> kingdomOverrides = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--map" -> mapId = args[++i];
                case "--games" -> games = Integer.parseInt(args[++i]);
                case "--rounds" -> rounds = Integer.parseInt(args[++i]);
                case "--seed" -> seed = Integer.parseInt(args[++i]);
                case "--fingerprint" -> fingerprint = true;
                case "--kingdom" -> {
                    String[] kv = args[++i].split("=", 2);
                    kingdomOverrides.put(kv[0], JsonParser.parseString(kv[1]));
                }
                case "--set" -> {
                    String[] kv = args[++i].split("=", 2);
                    Rules.override(overrides, kv[0], JsonParser.parseString(kv[1]));
                }
                default -> throw new IllegalArgumentException("Unknown option " + args[i]);
            }
        }
        SimMap map = withKingdomSettings(SimMap.named(mapId), kingdomOverrides);
        Rules rules = Rules.load(overrides);
        if (fingerprint) {
            for (int s = seed; s < seed + games; s++) {
                System.out.print(s + " " + fingerprint(play(map, rules, s, rounds)) + "\n"); // \n on every system, to compare with parity.js
            }
            return;
        }
        report(map, rules, seed, games, rounds);
    }

    /** The map with kingdom settings changed: "un.raiseDead" sets raiseDead on the kingdom with id un. */
    static SimMap withKingdomSettings(SimMap map, Map<String, com.google.gson.JsonElement> settings) {
        if (settings.isEmpty()) {
            return map;
        }
        JsonObject json = Rules.GSON.toJsonTree(map).getAsJsonObject();
        for (Map.Entry<String, com.google.gson.JsonElement> e : settings.entrySet()) {
            String[] parts = e.getKey().split("\\.", 2);
            boolean found = false;
            for (com.google.gson.JsonElement k : json.getAsJsonArray("kingdoms")) {
                if (k.getAsJsonObject().get("id").getAsString().equals(parts[0])) {
                    k.getAsJsonObject().add(parts[1], e.getValue());
                    found = true;
                }
            }
            if (!found) {
                throw new IllegalArgumentException("No kingdom " + parts[0] + " on the map");
            }
        }
        return SimMap.parse(json);
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
            out.append(k.id).append(':').append(Math.round(k.food * 100)).append(',').append(Math.round(k.gold * 100)).append(',')
                    .append(Math.round(k.res * 100)).append(',').append(game.army(k.id)).append(' ');
        }
        for (Node n : game.nodes) {
            out.append(n.owner == null ? "-" : n.owner).append('|').append(n.tier).append('|').append(n.garrison).append('|').append(n.defense).append('|')
                    .append(n.morale).append('|').append(Math.round(n.devastation * 100)).append(' ');
        }
        return out.toString().trim();
    }

    /** What one kingdom did, summed over a batch. */
    private static final class Totals {
        double endNodes, landValue, avgNodes, peakNodes, maxPeakNodes, tierSum, tierGames, landTierSum, landTierGames, avgArmy, peakArmy, maxPeakArmy, leads;
        double wokeGames, firstWokeSum, turnsAwake, timesWoke, hoardEnd, hoardLost, hoarders;
        double conquests, conquestsWon, raids, raidsWon, stolenFood, stolenGold, stolenRes, goldEnd;
        final Map<String, Double> attacksOn = new LinkedHashMap<>();
        final Map<String, Double> actionMix = new LinkedHashMap<>();
        boolean player, minor;
        double spawns, turnsPresent, lostNodes;
    }

    /**
     * The batch report, per kingdom: nodes held at the end, on average over the game and at its peak; the average tier of
     * its nodes at the end (capitals left out); its average and peak army; the conquests and raids it launched (and won);
     * what its raids stole; and whom its attacks were aimed at.
     */
    private static void report(SimMap map, Rules rules, int seed, int games, int rounds) {
        long start = System.currentTimeMillis();
        Map<String, Totals> per = new LinkedHashMap<>();
        Map<String, Double> council = new LinkedHashMap<>(); // each group's share of the council at the end, summed
        Map<String, Integer> actions = new LinkedHashMap<>();
        int totalActions = 0;
        double ruins = 0, unowned = 0, upsets = 0, attacks = 0;
        for (int s = seed; s < seed + games; s++) {
            Game game = play(map, rules, s, rounds);
            String leader = null;
            int most = -1;
            for (Kingdom k : game.kingdoms) {
                Totals t = per.computeIfAbsent(k.id, id -> new Totals());
                List<Node> owned = game.ownedNodes(k.id);
                t.endNodes += owned.size();
                // The leader holds the most land by value: the tiers of its nodes added up.
                int value = 0;
                for (Node n : owned) {
                    value += n.tier;
                }
                t.landValue += value;
                if (!k.minor && value > most) { // minor factions never count for winning
                    most = value;
                    leader = k.id;
                }
                double nodeSum = 0, armySum = 0, nodePeak = 0, armyPeak = 0;
                int rows = 0;
                for (Game.Snapshot snap : game.history) {
                    if (snap.round() == 0) {
                        continue;
                    }
                    Game.Row row = snap.kingdoms().get(k.id);
                    nodeSum += row.nodes();
                    armySum += row.army();
                    nodePeak = Math.max(nodePeak, row.nodes());
                    armyPeak = Math.max(armyPeak, row.army());
                    rows++;
                }
                t.avgNodes += rows == 0 ? 0 : nodeSum / rows;
                t.avgArmy += rows == 0 ? 0 : armySum / rows;
                t.peakNodes += nodePeak;
                t.peakArmy += armyPeak;
                t.maxPeakNodes = Math.max(t.maxPeakNodes, nodePeak);
                t.maxPeakArmy = Math.max(t.maxPeakArmy, armyPeak);
                double tiers = 0;
                int counted = 0;
                for (Node n : owned) {
                    if (n.type != NodeType.CAPITAL) {
                        tiers += n.tier;
                        counted++;
                    }
                }
                if (counted > 0) {
                    t.tierSum += tiers / counted;
                    t.tierGames++;
                }
                // The same without watchtowers, which can't go above Tier 1.
                double landTiers = 0;
                int land = 0;
                for (Node n : owned) {
                    if (n.type != NodeType.CAPITAL && n.type != NodeType.OUTPOST) {
                        landTiers += n.tier;
                        land++;
                    }
                }
                if (land > 0) {
                    t.landTierSum += landTiers / land;
                    t.landTierGames++;
                }
                if (game.hoards(k)) {
                    t.hoarders++;
                    t.hoardEnd += k.hoard;
                    t.hoardLost += k.stats.hoardLost;
                    t.turnsAwake += k.stats.turnsAwake;
                    t.timesWoke += k.stats.broodWoke;
                    if (k.stats.firstWoke > 0) {
                        t.wokeGames++;
                        t.firstWokeSum += k.stats.firstWoke;
                    }
                }
                t.conquests += k.stats.capturesTried;
                t.conquestsWon += k.stats.capturesWon;
                t.raids += k.stats.raidsTried;
                t.raidsWon += k.stats.raidsWon;
                t.stolenFood += k.stats.stolenFood;
                t.stolenGold += k.stats.stolenGold + k.stats.stolenHoard; // gold raided from a dragon's hoard counts too
                t.stolenRes += k.stats.stolenRes;
                t.goldEnd += k.gold;
                k.stats.attacksOn.forEach((target, n) -> t.attacksOn.merge(target, (double) n, Double::sum));
                k.stats.actions.forEach((type, n) -> t.actionMix.merge(type, (double) n, Double::sum));
                t.player = k.player;
                t.minor = k.minor;
                t.spawns += k.stats.spawns;
                t.lostNodes += k.stats.nodesLost;
                t.turnsPresent += k.stats.turnsPresent;
                upsets += k.stats.upsets;
                attacks += k.stats.capturesTried + k.stats.raidsTried;
                for (Map.Entry<String, Integer> e : k.stats.actions.entrySet()) {
                    actions.merge(e.getKey(), e.getValue(), Integer::sum);
                    totalActions += e.getValue();
                }
            }
            per.get(leader).leads++;
            double influence = game.influence.values().stream().mapToDouble(Double::doubleValue).sum();
            game.influence.forEach((group, v) -> council.merge(group, influence == 0 ? 0 : v / influence, Double::sum));
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
        double g = games;
        // The name column fits the longest id (minor factions have long ones).
        int w = Math.max(4, per.keySet().stream().mapToInt(String::length).max().orElse(4));
        String name = "  %-" + w + "s";
        System.out.printf(Locale.ROOT, "%s: %d games of %d rounds (%d ms). Averages per game; peaks are the average game's peak, then the highest in any game. Tier: of its nodes at the end, capitals left out (tier*: watchtowers left out too). Value: its nodes' tiers added up; leads: the most value at the end.%n",
                map.name, games, rounds, System.currentTimeMillis() - start);
        System.out.printf(Locale.ROOT, "  ruins at the end %.1f | unowned %.1f | battles turned by the dice %.1f%%%n", ruins / g, unowned / g,
                100 * upsets / Math.max(1, attacks));
        System.out.printf(Locale.ROOT, name + " %6s %6s %6s %11s %5s %5s %6s %11s %14s %13s %21s %6s %6s%n", "", "nodes", "value", "avg", "peak", "tier", "tier*", "army", "peak army",
                "conquests(won)", "raids(won)", "stolen food/gold/res", "gold", "leads");
        for (Map.Entry<String, Totals> e : per.entrySet()) {
            Totals t = e.getValue();
            System.out.printf(Locale.ROOT, name + " %6.1f %6.1f %6.1f %5.1f / %-3.0f %5.2f %5.2f %6.0f %5.0f / %-4.0f %7.1f (%4.1f) %6.1f (%4.1f) %6.0f/%6.0f/%6.0f %6.0f %5.0f%%%n",
                    e.getKey(), t.endNodes / g, t.landValue / g, t.avgNodes / g, t.peakNodes / g, t.maxPeakNodes, t.tierGames == 0 ? 0 : t.tierSum / t.tierGames,
                    t.landTierGames == 0 ? 0 : t.landTierSum / t.landTierGames,
                    t.avgArmy / g, t.peakArmy / g, t.maxPeakArmy, t.conquests / g, t.conquestsWon / g, t.raids / g, t.raidsWon / g,
                    t.stolenFood / g, t.stolenGold / g, t.stolenRes / g, t.goldEnd / g, 100 * t.leads / g);
        }
        if (!council.isEmpty()) {
            StringBuilder shares = new StringBuilder("  council at the end:");
            council.forEach((group, share) -> shares.append(String.format(Locale.ROOT, " %s %.0f%%", group, 100 * share / g)));
            System.out.println(shares);
        }
        per.forEach((id, t) -> {
            if (t.player) {
                double all = t.actionMix.values().stream().mapToDouble(Double::doubleValue).sum();
                StringBuilder mix = new StringBuilder("  " + id + " (the player kingdom) spent its actions on:");
                t.actionMix.entrySet().stream().sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                        .forEach(e -> mix.append(String.format(Locale.ROOT, " %s %.0f%%", e.getKey(), 100 * e.getValue() / all)));
                System.out.println(mix);
            }
            if (t.minor) {
                System.out.printf(Locale.ROOT, "  %s (minor faction): appeared %.1f times a game, on the map %.1f turns of %d, took %.1f nodes and lost %.1f%n",
                        id, t.spawns / g, t.turnsPresent / g, rounds, t.conquestsWon / g + t.spawns / g, t.lostNodes / g);
            }
            if (t.hoarders > 0) {
                System.out.printf(Locale.ROOT, "  %s: brood woke in %.0f%% of games (first in round %.1f on average), awake %.1f turns and woke %.1f times a game;"
                        + " hoard %.0f at the end, %.0f raided from it a game%n", id, 100 * t.wokeGames / g, t.wokeGames == 0 ? 0 : t.firstWokeSum / t.wokeGames,
                        t.turnsAwake / g, t.timesWoke / g, t.hoardEnd / g, t.hoardLost / g);
            }
        });
        List<String> targets = new ArrayList<>(per.keySet());
        targets.add("none");
        System.out.println("  attacks aimed at (% of each kingdom's conquests and raids):");
        StringBuilder head = new StringBuilder("  " + " ".repeat(w));
        for (String target : targets) {
            head.append(String.format(Locale.ROOT, "%9s", target.equals("none") ? "unowned" : target));
        }
        System.out.println(head);
        for (Map.Entry<String, Totals> e : per.entrySet()) {
            double total = e.getValue().attacksOn.values().stream().mapToDouble(Double::doubleValue).sum();
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, name, e.getKey()));
            for (String target : targets) {
                double n = e.getValue().attacksOn.getOrDefault(target, 0.0);
                row.append(target.equals(e.getKey()) ? String.format(Locale.ROOT, "%9s", "-") : String.format(Locale.ROOT, "%8.0f%%", total == 0 ? 0 : 100 * n / total));
            }
            System.out.println(row);
        }
        StringBuilder mix = new StringBuilder("  actions:");
        int total = totalActions;
        actions.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                .forEach(e -> mix.append(String.format(Locale.ROOT, " %s %.0f%%", e.getKey(), 100.0 * e.getValue() / total)));
        System.out.println(mix);
    }
}
