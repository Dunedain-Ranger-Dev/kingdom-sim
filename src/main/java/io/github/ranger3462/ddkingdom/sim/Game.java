package io.github.ranger3462.ddkingdom.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One game of the Kingdom Stats ruleset (docs/rules/kingdom-stats.txt): the simulator's engine
 * (tools/simulator/web/engine.js) in Java, with no Minecraft in it. A seed plays the same game as the simulator does.
 * <p>
 * Each round every kingdom takes a turn, in an order that rotates by one each round. A turn is: morale recovers,
 * nodes produce into the stockpile, the AI may buy the growth buff, upkeep is paid (a shortage costs the largest
 * garrison 10 troops per kind short), upgrades and imports tick, then the kingdom takes its actions (2, plus 1 per
 * Tier 3 castle). AI kingdoms choose with {@link GameAi}; a kingdom whose {@code ai} is "manual" stops after
 * production for {@link #buyBuff}, {@link #finishProduction} and {@link #act} to be called from outside.
 * <p>
 * Stockpiles and devastation are rounded to hundredths, as in the simulator; troops, tiers, defense and morale are
 * whole numbers.
 */
public final class Game {
    /** A move the rules don't allow, with why. */
    public static final class RuleException extends RuntimeException {
        public RuleException(String message) {
            super(message);
        }
    }

    public enum Phase { PRODUCTION, ACTIONS }

    /** The turn under way. */
    public static final class Turn {
        public final String kid;
        public Phase phase = Phase.PRODUCTION;
        public int actionsLeft, actionsTotal, generalLeft, extraLeft;
        /** Gold produced this turn, which the growth buff's price is based on. */
        public final double grossGold;

        Turn(String kid, double grossGold) {
            this.kid = kid;
            this.grossGold = grossGold;
        }
    }

    public record LogEntry(int round, String kid, String kind, String text) {}

    public record Output(double food, double gold, double prod) {}

    public record Upkeep(int batches, double food, double gold) {}

    public record Cost(double food, double gold, double res, int newTier, int turns) {}

    /** Raid loot: {@code amount} of {@code key} ("food", "gold" or "res"; null for nothing to steal). */
    public record Steal(double amount, double cap, String key, double stock) {}

    /** One kingdom at the end of a round. */
    public record Row(double food, double gold, double res, int army, int nodes, double foodNet, double goldNet, double resNet) {}

    public record Snapshot(int round, Map<String, Row> kingdoms) {}

    /** A garrison with no cap. */
    public static final int UNCAPPED = Integer.MAX_VALUE;
    private static final int LOG_LIMIT = 4000;

    public final Rules rules;
    public final int seed;
    final Rng rand;
    public final int maxRounds;
    public final boolean quiet;
    public final List<Kingdom> kingdoms = new ArrayList<>();
    final Map<String, Kingdom> K = new LinkedHashMap<>();
    public final List<Node> nodes = new ArrayList<>();
    final Map<String, Node> N = new LinkedHashMap<>();
    final Map<String, List<String>> adj = new HashMap<>();
    private final Map<String, Personality> personalities = new HashMap<>();
    public int round = 1;
    List<String> order;
    int turnPos;
    public Turn cur;
    public final List<LogEntry> log = new ArrayList<>();
    public final List<Snapshot> history = new ArrayList<>();
    final GameAi ai;
    /** The council's influence by group (Rules.Politics), and the simulator's stand-in quests' own random numbers. */
    public final Map<String, Double> influence = new LinkedHashMap<>();
    private final Rng questRng;

    /**
     * A new game on {@code map}. {@code maxRounds} is how long the AI expects the game to last (0: open-ended);
     * {@code quiet} keeps no log.
     */
    public Game(SimMap map, Rules rules, int seed, int maxRounds, boolean quiet) {
        this.rules = rules;
        this.seed = seed;
        this.rand = new Rng(seed);
        this.maxRounds = maxRounds;
        this.quiet = quiet;
        for (SimMap.KingdomSetup setup : map.kingdoms) {
            Kingdom k = new Kingdom(setup);
            kingdoms.add(k);
            K.put(k.id, k);
        }
        for (SimMap.NodeSetup setup : map.nodes) {
            Node n = new Node(setup);
            if (n.type == NodeType.CAPITAL) {
                n.tier = rules.capitalTier;
                n.morale = 10;
            }
            nodes.add(n);
            N.put(n.id, n);
            adj.put(n.id, new ArrayList<>());
        }
        for (String[] edge : map.edges) {
            if (!N.containsKey(edge[0]) || !N.containsKey(edge[1])) {
                continue;
            }
            adj.get(edge[0]).add(edge[1]);
            adj.get(edge[1]).add(edge[0]);
        }
        order = new ArrayList<>(K.keySet());
        questRng = new Rng(seed ^ 0x2545F491);
        if (politicsOn()) {
            rules.politics.groups.forEach((id, group) -> influence.put(id, group.baseline));
        }
        ai = new GameAi(this);
        snapshot(0);
    }

    // ---- Lookups ----

    public Kingdom kingdom(String kid) {
        return K.get(kid);
    }

    public Node node(String id) {
        return N.get(id);
    }

    public List<String> neighbours(String id) {
        return adj.get(id);
    }

    /** How the kingdom's AI plays: its own personality, or the brood's while the brood is awake. */
    public Personality personality(Kingdom k) {
        String ai = k.awake ? rules.brood.personality : k.ai;
        return personalities.computeIfAbsent(k.id + "/" + ai, id -> Personality.of(rules, ai));
    }

    String kname(String kid) {
        Kingdom k = kid == null ? null : K.get(kid);
        return k == null ? "Neutral" : k.name;
    }

    // ---- Logging and history ----

    void emit(String kid, String kind, String text) {
        if (quiet) {
            return;
        }
        log.add(new LogEntry(round, kid, kind, text));
        if (log.size() > LOG_LIMIT) {
            log.subList(0, log.size() - LOG_LIMIT).clear();
        }
    }

    void snapshot(int round) {
        Map<String, Row> row = new LinkedHashMap<>();
        for (Kingdom k : kingdoms) {
            Output inc = income(k.id, false);
            Upkeep up = upkeep(k.id);
            row.put(k.id, new Row(k.food, k.gold, k.res, army(k.id), ownedNodes(k.id).size(), r2(inc.food() - up.food()),
                    r2(inc.gold() - up.gold()), r2(inc.prod())));
        }
        history.add(new Snapshot(round, row));
    }

    /** Rounds to hundredths, as the simulator does. */
    static double r2(double x) {
        return Math.round(x * 100) / 100.0;
    }

    /** A number as the simulator prints it: whole numbers without a decimal point. */
    static String num(double x) {
        if (x == Math.rint(x) && Math.abs(x) < 1e15) {
            return Long.toString((long) x);
        }
        return Double.toString(x);
    }

    // ---- Node stats ----

    private Rules.CastleTier castleTier(int tier) {
        return tier >= 0 && tier < rules.castle.size() ? rules.castle.get(tier) : rules.castle.get(0);
    }

    private static double at(double[] values, int tier) {
        return tier >= 0 && tier < values.length ? values[tier] : 0;
    }

    public Output baseOutput(Node n) {
        return baseOutput(n, n.tier);
    }

    /** What a node produces each turn at {@code tier}, before devastation. */
    public Output baseOutput(Node n, int tier) {
        Rules c = rules;
        return switch (n.type) {
            case CAPITAL, TOWN -> {
                double m = Combat.tierMult(n.type == NodeType.CAPITAL ? c.capitalTier : tier);
                yield new Output(c.town.food * m, c.town.gold * m * c.town.goldMult, c.town.prod * m);
            }
            case CASTLE -> new Output(castleTier(tier).food, 0, 0);
            case FOOD -> new Output(at(c.foodNode, tier), 0, 0);
            case PRODUCTION -> new Output(0, 0, at(c.prodNode, tier));
            case OUTPOST -> new Output(tier >= 1 ? c.outpost.food : 0, 0, 0);
        };
    }

    /** What a node produces now. Devastation scales production only; fixed food costs (castles, outposts) aren't reduced. */
    public Output output(Node n) {
        Output b = baseOutput(n);
        double f = 1 - n.devastation;
        return new Output(b.food() > 0 ? b.food() * f : b.food(), b.gold() > 0 ? b.gold() * f : b.gold(),
                b.prod() > 0 ? b.prod() * f : b.prod());
    }

    public int defCap(Node n) {
        return defCap(n, n.tier);
    }

    public int defCap(Node n, int tier) {
        Rules c = rules;
        return switch (n.type) {
            case CAPITAL -> c.capitalTier * c.town.defPerTier;
            case TOWN -> tier * c.town.defPerTier;
            case CASTLE -> castleTier(tier).defCap;
            case FOOD, PRODUCTION -> c.resource.defCap;
            case OUTPOST -> tier >= 1 ? c.outpost.defCap : 0;
        };
    }

    public int garrisonCap(Node n) {
        if (n.type.resource()) {
            return rules.resource.garrisonCap;
        }
        return n.type == NodeType.OUTPOST ? rules.outpost.garrisonCap : UNCAPPED;
    }

    public double defMult(Node n) {
        return defMult(n, n.tier);
    }

    public double defMult(Node n, int tier) {
        return n.type == NodeType.CASTLE ? castleTier(tier).mult : 1;
    }

    /** Final Defense = (base defense, capped, × castle multiplier, castles at most 50) × morale / 10. */
    public double finalDefense(Node n) {
        return finalDefense(n, n.defense);
    }

    public double finalDefense(Node n, int baseDefense) {
        double v = Math.min(baseDefense, defCap(n)) * defMult(n);
        if (n.type == NodeType.CASTLE) {
            v = Math.min(v, rules.castleMaxDef);
        }
        return v * (n.morale / 10.0);
    }

    public double defPower(Node n) {
        return Combat.defenderPower(rules, n.garrison, finalDefense(n), defStrength(n));
    }

    /** Defender power with the base defense at {@code baseDefense}. */
    public double defPower(Node n, int baseDefense) {
        return Combat.defenderPower(rules, n.garrison, finalDefense(n, baseDefense), defStrength(n));
    }

    /** Troop strength: the kingdom's own, plus what it has trained. */
    public double strength(String kid) {
        Kingdom k = kid == null ? null : K.get(kid);
        if (k == null) {
            return 1;
        }
        double s = k.strength + (k.trained > 0 ? k.trained * rules.train.step : 0);
        return k.awake ? s * rules.brood.strength : s;
    }

    public boolean politicsOn() {
        return rules.politics != null && rules.politics.enabled;
    }

    /** A quest done for a group (a player's, or the simulator's stand-in) raises its influence on the council. */
    public void questDone(String group) {
        if (politicsOn() && influence.containsKey(group)) {
            influence.put(group, influence.get(group) + rules.politics.questInfluence);
        }
    }

    /** At the start of the player kingdom's turn: influence fades back toward the baselines, then the stand-in quests. */
    private void updatePolitics() {
        Rules.Politics politics = rules.politics;
        politics.groups.forEach((id, group) -> influence.put(id, influence.get(id) + (group.baseline - influence.get(id)) * politics.decay));
        if (politics.simQuests <= 0) {
            return;
        }
        double whole = Math.floor(politics.simQuests);
        int quests = (int) whole + (questRng.next() < politics.simQuests - whole ? 1 : 0);
        List<String> ids = new ArrayList<>(politics.groups.keySet());
        for (int i = 0; i < quests; i++) {
            boolean favoured = politics.simFavour != null && !politics.simFavour.isEmpty();
            questDone(favoured ? politics.simFavour : ids.get((int) Math.floor(questRng.next() * ids.size())));
        }
    }

    /** The council's lean on an action: each group's share above or below its usual share, times its affinity. 0 at the baselines. */
    public double lean(Action.Type type) {
        if (!politicsOn()) {
            return 0;
        }
        double total = 0, baseTotal = 0;
        for (Map.Entry<String, Rules.Group> e : rules.politics.groups.entrySet()) {
            total += influence.get(e.getKey());
            baseTotal += e.getValue().baseline;
        }
        if (total <= 0 || baseTotal <= 0) {
            return 0;
        }
        double lean = 0;
        for (Map.Entry<String, Rules.Group> e : rules.politics.groups.entrySet()) {
            Double affinity = e.getValue().affinity.get(type.id);
            if (affinity != null) {
                lean += (influence.get(e.getKey()) / total - e.getValue().baseline / baseTotal) * affinity;
            }
        }
        return lean;
    }

    /** Whether the kingdom keeps a dragon's hoard (Rules.Brood). */
    public boolean hoards(Kingdom k) {
        Rules.Brood b = rules.brood;
        return b != null && b.enabled && k.brood;
    }

    /** Wakes the brood once the hoard is full, and puts it back to sleep once it isn't. */
    void updateBrood(Kingdom k) {
        if (!hoards(k)) {
            return;
        }
        boolean awake = k.hoard >= rules.brood.gold;
        if (awake == k.awake) {
            return;
        }
        k.awake = awake;
        if (awake) {
            k.stats.broodWoke++;
            if (k.stats.firstWoke == 0) {
                k.stats.firstWoke = round;
            }
        }
        if (!quiet) {
            emit(k.id, "react", awake ? "The dragon's hoard holds " + num(k.hoard) + " gold and her brood is loose: troop strength now ×" + num(r2(strength(k.id))) + "."
                    : "The dragon's hoard has fallen to " + num(k.hoard) + " gold and her brood goes back to sleep.");
        }
    }

    /** What the kingdom's next Train troops costs, or -1 if it can't train (off, or trained the most times). */
    public double trainCost(Kingdom k) {
        Rules.Train t = rules.train;
        return t == null || !t.enabled || k.trained >= t.max ? -1 : t.gold * (k.trained + 1);
    }

    /** Strength when defending: defStrength over strength, times homeDefStrength on the owner's own home nodes. */
    public double defStrength(Node n) {
        Kingdom k = n.owner == null ? null : K.get(n.owner);
        if (k == null) {
            return 1;
        }
        double base = k.defStrength != null ? k.defStrength : strength(n.owner);
        return base * (n.owner.equals(n.home) && k.homeDefStrength != 0 ? k.homeDefStrength : 1);
    }

    // ---- Kingdom stats ----

    public List<Node> ownedNodes(String kid) {
        List<Node> out = new ArrayList<>();
        for (Node n : nodes) {
            if (n.ownedBy(kid)) {
                out.add(n);
            }
        }
        return out;
    }

    public Node capital(String kid) {
        for (Node n : nodes) {
            if (n.ownedBy(kid) && n.type == NodeType.CAPITAL) {
                return n;
            }
        }
        return null;
    }

    public boolean onlyCapital(String kid) {
        List<Node> owned = ownedNodes(kid);
        return owned.size() == 1 && owned.get(0).type == NodeType.CAPITAL;
    }

    public int army(String kid) {
        int total = 0;
        for (Node n : nodes) {
            if (n.ownedBy(kid)) {
                total += n.garrison;
            }
        }
        return total;
    }

    /** Node output, plus food arriving from import contracts unless {@code nodesOnly}. */
    public Output income(String kid, boolean nodesOnly) {
        double food = 0, gold = 0, prod = 0;
        for (Node n : nodes) {
            if (n.ownedBy(kid)) {
                Output o = output(n);
                food += o.food();
                gold += o.gold();
                prod += o.prod();
            }
        }
        if (!nodesOnly) {
            food += K.get(kid).importedFood();
        }
        return new Output(food, gold, prod);
    }

    /** Every 10 soldiers (rounded up) cost 1 food and 100 gold, times the kingdom's upkeep multiplier. */
    public Upkeep upkeepFor(int army, String kid) {
        int batches = (int) Math.ceil(army / (double) rules.hire.batchSize);
        Kingdom k = kid == null ? null : K.get(kid);
        double m = k == null || k.upkeepMult == 0 ? 1 : k.upkeepMult;
        return new Upkeep(batches, Math.ceil(batches * rules.upkeep.food * m), Math.ceil(batches * rules.upkeep.gold * m));
    }

    public Upkeep upkeep(String kid) {
        return upkeepFor(army(kid), kid);
    }

    /** Growth rate × 2 with only the capital × the buff's 2 while it lasts. */
    public double growthMult(String kid) {
        Kingdom k = K.get(kid);
        Double rate = rules.growth.get(k.growth);
        double m = rate != null ? rate : 1;
        if (onlyCapital(kid)) {
            m *= rules.capitalOnlyGrowthMult;
        }
        if (k.buffTurns > 0) {
            m *= rules.buff.mult;
        }
        return m;
    }

    public int bonusActions(String kid) {
        int count = 0;
        for (Node n : nodes) {
            if (n.ownedBy(kid) && n.type == NodeType.CASTLE && n.tier >= rules.bonusActionCastleTier) {
                count++;
            }
        }
        return count;
    }

    public boolean isHostile(Node n, String kid) {
        if (n.ownedBy(kid)) {
            return false;
        }
        if (n.owner == null) {
            return rules.reinforceBorder.neutralIsHostile;
        }
        return true;
    }

    public List<Node> borderNodes(String kid) {
        List<Node> out = new ArrayList<>();
        for (Node n : ownedNodes(kid)) {
            if (hostileNeighbour(n, kid)) {
                out.add(n);
            }
        }
        return out;
    }

    boolean hostileNeighbour(Node n, String kid) {
        for (String id : adj.get(n.id)) {
            if (isHostile(N.get(id), kid)) {
                return true;
            }
        }
        return false;
    }

    /** Whether a path of the kingdom's own nodes joins {@code nodeId} to its capital. */
    public boolean connectedToCapital(String kid, String nodeId) {
        Node cap = capital(kid);
        if (cap == null) {
            return false;
        }
        Set<String> seen = new HashSet<>(Set.of(nodeId));
        ArrayDeque<String> queue = new ArrayDeque<>(List.of(nodeId));
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (id.equals(cap.id)) {
                return true;
            }
            for (String nb : adj.get(id)) {
                if (!seen.contains(nb) && N.get(nb).ownedBy(kid)) {
                    seen.add(nb);
                    queue.add(nb);
                }
            }
        }
        return false;
    }

    public List<Node> hireLocations(String kid) {
        List<Node> out = new ArrayList<>();
        for (Node n : ownedNodes(kid)) {
            if (n.type.musters() && connectedToCapital(kid, n.id)) {
                out.add(n);
            }
        }
        return out;
    }

    /** The next tier's cost: new tier × 5 food, × 300 gold, × 100 resources (150 for castles); rebuilding ruins is free. */
    public Cost upgradeCost(Node n) {
        int next = n.tier + 1;
        Rules.Upgrade u = rules.upgrade;
        if (n.tier == 0) {
            return new Cost(0, 0, 0, 1, u.timeBase + u.timePerTier);
        }
        return new Cost(next * u.food, next * u.gold, next * (n.type == NodeType.CASTLE ? u.castleRes : u.res), next, u.timeBase + next * u.timePerTier);
    }

    /** The growth buff's price this turn: 2 turns of gross gold income. */
    public double buffCost() {
        return cur == null ? 0 : rules.buff.costTurns * cur.grossGold;
    }

    // ---- Turn flow ----

    public String currentKid() {
        return order.get(turnPos);
    }

    /** Morale recovers and the nodes produce. The turn then waits in its production phase. */
    public Turn beginTurn() {
        String kid = currentKid();
        Kingdom k = K.get(kid);
        for (Node n : ownedNodes(kid)) {
            n.morale = n.type == NodeType.CAPITAL ? 10 : Math.min(10, n.morale + rules.morale.recover);
        }
        Output inc = income(kid, false);
        k.food = r2(k.food + inc.food());
        k.gold = r2(k.gold + inc.gold());
        k.res = r2(k.res + inc.prod());
        if (hoards(k)) {
            // The tithe only fills the hoard up to the target, so any raid that takes from a full hoard puts the brood back to sleep.
            double tithe = Math.min(r2(inc.gold() * rules.brood.tithe), Math.max(0, r2(rules.brood.gold - k.hoard)));
            k.gold = r2(k.gold - tithe);
            k.hoard = r2(k.hoard + tithe);
            updateBrood(k);
            if (k.awake) {
                k.stats.turnsAwake++;
            }
        }
        if (k.player && politicsOn()) {
            updatePolitics();
        }
        k.stats.turns++;
        cur = new Turn(kid, inc.gold());
        if (!quiet) {
            emit(kid, "phase", "Production: +" + num(r2(inc.food())) + " food, +" + num(r2(inc.gold())) + " gold, +" + num(r2(inc.prod()))
                    + " resources → stockpile " + num(k.food) + " / " + num(k.gold) + " / " + num(k.res));
        }
        return cur;
    }

    /** Buys the growth buff; only in the production phase. */
    public void buyBuff() {
        if (cur == null || cur.phase != Phase.PRODUCTION) {
            throw new RuleException("The growth buff can only be bought during the production phase.");
        }
        Kingdom k = K.get(cur.kid);
        double cost = buffCost();
        if (k.gold < cost) {
            throw new RuleException("Growth buff costs " + num(cost) + " gold (2 turns of gross gold income); only " + num(k.gold) + " available.");
        }
        k.gold = r2(k.gold - cost);
        k.buffTurns = rules.buff.duration;
        k.stats.buffs++;
        if (!quiet) {
            emit(cur.kid, "buff", "Bought growth buff for " + num(cost) + " gold: growth ×" + num(rules.buff.mult) + " for " + rules.buff.duration
                    + " turns (now ×" + num(growthMult(cur.kid)) + ").");
        }
    }

    /** Pays upkeep (with any shortage), ticks upgrades, the buff and imports, and opens the action phase. */
    public Turn finishProduction() {
        String kid = cur.kid;
        Kingdom k = K.get(kid);
        int army = army(kid);
        Upkeep up = upkeepFor(army, kid);
        k.food = r2(k.food - up.food());
        k.gold = r2(k.gold - up.gold());
        // The optional stockpile tax: a share of gold above the tax-free amount is lost each upkeep (0 = off).
        if (rules.goldTax.rate > 0 && k.gold > rules.goldTax.free) {
            k.gold = r2(k.gold - (k.gold - rules.goldTax.free) * rules.goldTax.rate);
        }
        List<String> kinds = new ArrayList<>();
        if (k.food < 0) {
            kinds.add("food");
            k.stats.foodShortTurns++;
            k.food = 0;
        }
        if (k.gold < 0) {
            kinds.add("gold");
            k.stats.goldShortTurns++;
            k.gold = 0;
        }
        if (!quiet) {
            emit(kid, "phase", "Upkeep for " + army + " soldiers (" + up.batches() + " batches): −" + num(up.food()) + " food, −" + num(up.gold()) + " gold");
        }
        if (!kinds.isEmpty()) {
            Node largest = capital(kid);
            for (Node n : ownedNodes(kid)) {
                if (largest == null || n.garrison > largest.garrison) {
                    largest = n;
                }
            }
            int loss = Math.min(largest.garrison, rules.shortagePenalty * kinds.size());
            largest.garrison -= loss;
            k.stats.shortageLosses += loss;
            if (!quiet) {
                emit(kid, "shortage", String.join(" and ", kinds) + " shortage: " + largest.name + " loses " + loss + " garrison.");
            }
        }
        for (Node n : ownedNodes(kid)) {
            if (n.upgrade == null) {
                continue;
            }
            n.upgrade.turnsLeft--;
            if (n.upgrade.turnsLeft <= 0) {
                n.tier = n.upgrade.target;
                n.upgrade = null;
                n.devastation = 0;
                if (!quiet) {
                    emit(kid, "upgrade", n.name + " finished upgrading to " + n.tierName() + " (T" + n.tier + ").");
                }
            }
        }
        if (k.buffTurns > 0) {
            k.buffTurns--;
        }
        for (Kingdom.Contract c : k.imports) {
            c.turnsLeft--;
        }
        k.imports.removeIf(c -> c.turnsLeft <= 0);
        cur.phase = Phase.ACTIONS;
        cur.generalLeft = rules.baseActions + bonusActions(kid);
        cur.extraLeft = rules.nonCombatActions.count;
        cur.actionsTotal = cur.actionsLeft = cur.generalLeft + cur.extraLeft;
        return cur;
    }

    void endTurn() {
        decayGold();
        cur = null;
        turnPos++;
        if (turnPos >= order.size()) {
            snapshot(round);
            round++;
            turnPos = 0;
            List<String> ids = new ArrayList<>(K.keySet());
            if ("rotate".equals(rules.turnOrder)) {
                int s = (round - 1) % ids.size();
                List<String> rotated = new ArrayList<>(ids.subList(s, ids.size()));
                rotated.addAll(ids.subList(0, s));
                order = rotated;
            } else if ("random".equals(rules.turnOrder)) {
                for (int i = ids.size() - 1; i > 0; i--) {
                    int j = (int) Math.floor(rand.next() * (i + 1));
                    String t = ids.get(i);
                    ids.set(i, ids.get(j));
                    ids.set(j, t);
                }
                order = ids;
            } else {
                order = ids;
            }
        }
    }

    /**
     * The end of a kingdom's turn: it loses a share of the gold its nodes produced this turn (Rules.GoldDecay), so
     * gold that isn't spent wears away. Its stockpile never drops below 0.
     */
    private void decayGold() {
        Rules.GoldDecay decay = rules.goldDecay;
        if (cur == null || decay == null || decay.share <= 0) {
            return;
        }
        Kingdom k = K.get(cur.kid);
        double before = k.gold;
        k.gold = r2(Math.max(0, k.gold - cur.grossGold * decay.share));
        k.stats.goldDecayed += before - k.gold;
        if (!quiet && before > k.gold) {
            emit(cur.kid, "phase", "End of turn: " + num(r2(before - k.gold)) + " gold lost (" + num(decay.share * 100) + "% of the " + num(r2(cur.grossGold))
                    + " produced this turn) → " + num(k.gold) + " gold");
        }
    }

    /** What {@link #step} stopped at. */
    public enum StepResult { DONE, MANUAL, WAITING }

    /** Runs the next kingdom's turn. An AI kingdom's runs to the end; a manual one stops after production. */
    public StepResult step() {
        if (cur != null) {
            return StepResult.WAITING;
        }
        String kid = currentKid();
        Kingdom k = K.get(kid);
        beginTurn();
        if ("manual".equals(k.ai)) {
            return StepResult.MANUAL;
        }
        ai.buff(kid);
        finishProduction();
        int guard = 10;
        while (cur != null && cur.actionsLeft > 0 && guard-- > 0) {
            Action a = ai.choose(kid);
            try {
                act(a);
            } catch (RuleException e) {
                act(fallback(kid));
            }
        }
        if (cur != null) {
            endTurn();
        }
        return StepResult.DONE;
    }

    /** Runs turns until the round is over, or a manual kingdom's turn comes up. */
    public StepResult runRound() {
        int r = round;
        while (round == r) {
            StepResult s = step();
            if (s != StepResult.DONE) {
                return s;
            }
        }
        return StepResult.DONE;
    }

    // ---- Actions ----

    /** Takes an action for the kingdom whose turn it is. Throws {@link RuleException} if the rules don't allow it. */
    public void act(Action a) {
        if (cur == null || cur.phase != Phase.ACTIONS) {
            throw new RuleException("Not in an action phase.");
        }
        String kid = cur.kid;
        Kingdom k = K.get(kid);
        // Restricted actions use the extra slot first, so general slots stay free for attacks.
        boolean fitsExtra = fitsExtraSlot(a.type());
        if (!fitsExtra && !(cur.generalLeft > 0)) {
            throw new RuleException("Only " + (rules.nonCombatActions.economyOnly ? "economy" : "non-combat") + " actions are left this turn.");
        }
        switch (a.type()) {
            case REINFORCE_BORDER -> reinforceBorder(kid);
            case SEND -> send(kid, a);
            case CAPTURE -> capture(kid, a, false);
            case HEROIC -> capture(kid, a, true);
            case RAID -> raid(kid, a);
            case HIRE -> hire(kid, a);
            case PRODUCE -> produce(kid);
            case IMPORT_FOOD -> importFood(kid, a);
            case FORTIFY -> fortify(kid, a);
            case UPGRADE -> upgrade(kid, a);
            case REPAIR -> repair(kid, a);
            case TRAIN -> train(kid);
        }
        k.stats.actions.merge(a.type().id, 1, Integer::sum);
        if (fitsExtra && cur.extraLeft > 0) {
            cur.extraLeft--;
        } else {
            cur.generalLeft--;
        }
        cur.actionsLeft--;
        if (cur.actionsLeft <= 0) {
            endTurn();
        }
    }

    /**
     * The action a kingdom takes when nothing better is allowed: Produce Resources, or if that's on its cooldown,
     * Reinforce Border (which is always allowed, even if it does nothing). Every action is used.
     */
    Action fallback(String kid) {
        if (canProduce(kid)) {
            return Action.of(Action.Type.PRODUCE);
        }
        return Action.of(Action.Type.REINFORCE_BORDER);
    }

    public boolean fitsExtraSlot(Action.Type type) {
        if (type.attack()) {
            return false;
        }
        return !(rules.nonCombatActions.economyOnly && (type == Action.Type.HIRE || type == Action.Type.SEND));
    }

    private Node own(String kid, String id, String what) {
        Node n = id == null ? null : N.get(id);
        if (n == null) {
            throw new RuleException("Pick " + what + ".");
        }
        if (!n.ownedBy(kid)) {
            throw new RuleException(n.name + " is not yours.");
        }
        return n;
    }

    private static int atLeastOne(int v, String what) {
        if (v < 1) {
            throw new RuleException(what + " must be at least 1.");
        }
        return v;
    }

    private void reinforceBorder(String kid) {
        Rules.ReinforceBorder c = rules.reinforceBorder;
        List<String> parts = new ArrayList<>();
        for (Node n : borderNodes(kid)) {
            if (n.upgrade != null) {
                continue;
            }
            int before = n.defense;
            n.defense = Math.min(defCap(n), n.defense + c.def);
            double dv = n.devastation;
            n.devastation = r2(Math.max(0, n.devastation - c.dev));
            if (n.defense != before || n.devastation != dv) {
                parts.add(n.name + " def " + before + "→" + n.defense);
            }
        }
        if (!quiet) {
            emit(kid, "action", "Reinforce border: " + (parts.isEmpty() ? "no node could gain anything" : String.join(", ", parts)) + ".");
        }
    }

    private void send(String kid, Action a) {
        Node from = own(kid, a.from(), "a source node"), to = own(kid, a.to(), "a destination");
        if (from == to) {
            throw new RuleException("Source and destination are the same node.");
        }
        int amount = atLeastOne(a.amount(), "Troops");
        if (amount > from.garrison) {
            throw new RuleException(from.name + " only has " + from.garrison + " troops.");
        }
        int space = garrisonCap(to) - to.garrison;
        if (space <= 0) {
            throw new RuleException(to.name + " is at its garrison cap.");
        }
        amount = Math.min(amount, space);
        from.garrison -= amount;
        to.garrison += amount;
        if (!quiet) {
            emit(kid, "action", "Send reinforcements: " + amount + " troops " + from.name + " → " + to.name + ".");
        }
    }

    private record Attack(Node from, Node target, int troops) {}

    private Attack attackChecks(String kid, Action a, Action.Type kind) {
        Node from = own(kid, a.from(), "an attacking garrison");
        Node target = a.target() == null ? null : N.get(a.target());
        if (target == null) {
            throw new RuleException("Pick a target.");
        }
        if (target.ownedBy(kid)) {
            throw new RuleException(target.name + " is already yours.");
        }
        if (!adj.get(from.id).contains(target.id)) {
            throw new RuleException(target.name + " is not adjacent to " + from.name + ".");
        }
        if (kind != Action.Type.RAID && target.type == NodeType.CAPITAL) {
            throw new RuleException("Capitals cannot be captured (they can be raided).");
        }
        if (kind == Action.Type.RAID && target.owner == null) {
            throw new RuleException("Neutral nodes have no stockpile to raid.");
        }
        if (kind == Action.Type.HEROIC && !onlyCapital(kid)) {
            throw new RuleException("Heroic conquest requires the capital to be your only node.");
        }
        int troops = atLeastOne(a.troops(), "Troops");
        if (troops > from.garrison) {
            throw new RuleException(from.name + " only has " + from.garrison + " troops.");
        }
        return new Attack(from, target, troops);
    }

    /** An attacked watchtower lets its owner rush up to 10 troops from its biggest garrison to the weakest node beside it. */
    private void watchtowerWarning(Node target) {
        if (target.type != NodeType.OUTPOST || target.tier < 1 || target.owner == null) {
            return;
        }
        String dk = target.owner;
        Node dest = null;
        for (String id : adj.get(target.id)) {
            Node n = N.get(id);
            if (n.ownedBy(dk) && (dest == null || defPower(n) < defPower(dest))) {
                dest = n;
            }
        }
        if (dest == null) {
            return;
        }
        Node donor = null;
        for (Node n : ownedNodes(dk)) {
            if (n != target && n != dest && n.garrison > 0 && (donor == null || n.garrison > donor.garrison)) {
                donor = n;
            }
        }
        if (donor == null) {
            return;
        }
        int amount = Math.min(Math.min(rules.outpost.warnMove, donor.garrison), garrisonCap(dest) - dest.garrison);
        if (amount <= 0) {
            return;
        }
        donor.garrison -= amount;
        dest.garrison += amount;
        if (!quiet) {
            emit(dk, "react", "Watchtower " + target.name + " spotted the attack: " + amount + " troops rushed " + donor.name + " → " + dest.name + ".");
        }
    }

    private void hitMorale(Node n, int amount) {
        if (n.type == NodeType.CAPITAL) {
            n.morale = 10;
            return;
        }
        n.morale = Math.max(1, n.morale - amount);
    }

    private void capture(String kid, Action a, boolean heroic) {
        Rules c = rules;
        Kingdom k = K.get(kid);
        Attack at = attackChecks(kid, a, heroic ? Action.Type.HEROIC : Action.Type.CAPTURE);
        Node from = at.from(), target = at.target();
        int troops = at.troops();
        watchtowerWarning(target);
        String defender = target.owner;
        double fd = finalDefense(target), dp = defPower(target);
        int g = target.garrison;
        Combat.Dice dice = Combat.Dice.roll(c.combat.dice, rand);
        Combat.Battle r = Combat.capture(c, troops, g, dp, heroic, strength(kid), dice);
        k.stats.capturesTried++;
        k.stats.attacksOn.merge(defender == null ? "none" : defender, 1, Integer::sum);
        if (dice.rolled() && r.win() != Combat.capture(c, troops, g, dp, heroic, strength(kid)).win()) {
            k.stats.upsets++;
        }
        from.garrison -= troops;
        hitMorale(target, c.morale.attacked);
        target.attackedRound = round;
        String head = quiet ? "" : (heroic ? "Heroic conquest" : "Capture") + ": " + troops + (heroic ? " (×" + num(c.combat.heroicMult) + ")" : "")
                + " from " + from.name + " vs " + target.name + " [" + kname(defender) + ", " + g + " garrison, defense " + num(r2(fd)) + ", power " + num(r2(dp)) + "]"
                + rolls(dice, r);
        k.stats.battleLosses += r.attackerLosses();
        k.stats.kills += r.defenderLosses();
        if (defender != null) {
            K.get(defender).stats.battleLosses += r.defenderLosses();
        }
        noteAttack(kid, defender, r.win());
        if (r.win()) {
            k.stats.capturesWon++;
            k.stats.nodesGained++;
            if (defender != null) {
                K.get(defender).stats.nodesLost++;
                K.get(defender).lost.put(target.id, round);
            }
            k.lost.remove(target.id);
            target.owner = kid;
            target.capturedRound = round;
            if (c.combat.captureTierLoss || k.razes) {
                target.tier = Math.max(0, target.tier - 1);
            }
            target.defense = 0;
            target.morale = c.morale.start;
            target.devastation = r2(Math.min(1, target.devastation + c.combat.captureDevastation));
            target.upgrade = null;
            if (target.tier == 0 && c.freeRebuildOnCapture && !(k.noFreeRebuild && !kid.equals(target.home))) {
                target.upgrade = new Node.Upgrade(1, 1, true);
            }
            int keep = Math.min(r.survivors(), garrisonCap(target));
            target.garrison = keep;
            from.garrison += r.survivors() - keep;
            raiseDead(kid, defender, from, target, r);
            if (!quiet) {
                emit(kid, "capture", head + " → CAPTURED. " + r.survivors() + " survive (√(" + num(r2(r.ap())) + "² − " + num(r2(r.dp())) + "²)"
                        + (heroic ? " ÷ " + num(c.combat.heroicMult) : "") + "); " + target.name + " drops to " + target.tierName() + " (T" + target.tier
                        + "), devastation " + num(target.devastation) + (target.upgrade != null ? "; free rebuild to T1 next turn" : "") + "."
                        + (keep < r.survivors() ? " " + (r.survivors() - keep) + " over the garrison cap return home." : ""));
            }
        } else {
            target.garrison = r.defenderRemaining();
            from.garrison += r.survivors();
            hitMorale(from, c.morale.lost);
            if (!quiet) {
                emit(kid, "fail", head + " → REPELLED. " + r.survivors() + " retreat (" + num(c.combat.retreat * 100) + "%); defenders keep "
                        + r.defenderRemaining() + " of " + g + ".");
            }
            raiseDead(kid, defender, from, target, r);
        }
    }

    /** The dice in a battle's log line, with the power they gave each side. */
    private static String rolls(Combat.Dice dice, Combat.Battle r) {
        if (!dice.rolled()) {
            return "";
        }
        return ", rolls " + dice.attackRoll() + " vs " + dice.defenseRoll() + " (power " + num(r2(r.ap())) + " vs " + num(r2(r.dp())) + ")";
    }

    /** Counter-attacking kingdoms remember who last attacked them, and give a counter-attack up after losing a fight. */
    private void noteAttack(String attKid, String defKid, boolean won) {
        Kingdom dk = defKid == null ? null : K.get(defKid), ak = K.get(attKid);
        if (dk != null && personality(dk).counter != 0 && !(dk.counter != null && dk.counter.kid().equals(attKid))) {
            dk.counter = new Kingdom.Counter(attKid, round);
            dk.stats.counters++;
            if (!quiet) {
                emit(defKid, "react", dk.name + " were attacked by " + ak.name + " and will strike back towards their capital.");
            }
        }
        if (!won && ak.counter != null) {
            endCounter(attKid, "a fight was lost");
        }
    }

    void endCounter(String kid, String why) {
        Kingdom k = K.get(kid);
        if (k.counter == null) {
            return;
        }
        k.counter = null;
        k.falling = true;
        if (!quiet) {
            emit(kid, "react", k.name + " end the counter-attack (" + why + ") and fall back to their home nodes.");
        }
    }

    /**
     * A kingdom with raiseDead (the Undead) gains that share of a battle's total casualties, on either side of the
     * fight. Raised troops join the attacker's surviving force, or the defending garrison; if the defender lost the
     * node they rise at its strongest adjacent node, else the capital. Garrison caps still apply, with any overflow
     * going to the capital.
     */
    private void raiseDead(String attKid, String defKid, Node from, Node target, Combat.Battle r) {
        int total = r.attackerLosses() + r.defenderLosses();
        for (String kid : new String[] { attKid, defKid }) {
            Kingdom k = kid == null ? null : K.get(kid);
            if (k == null || !(k.raiseDead > 0)) {
                continue;
            }
            int raised = (int) Math.floor(total * k.raiseDead);
            if (raised <= 0) {
                continue;
            }
            Node at;
            if (kid.equals(attKid)) {
                at = target.ownedBy(kid) ? target : from;
            } else if (target.ownedBy(kid)) {
                at = target;
            } else {
                at = null;
                for (String id : adj.get(target.id)) {
                    Node n = N.get(id);
                    if (n.ownedBy(kid) && (at == null || n.garrison > at.garrison)) {
                        at = n;
                    }
                }
                if (at == null) {
                    at = capital(kid);
                }
            }
            Node cap = capital(kid);
            int fit = Math.max(0, Math.min(raised, garrisonCap(at) - at.garrison));
            at.garrison += fit;
            if (raised > fit && cap != null) {
                cap.garrison += raised - fit;
            }
            k.stats.raised += raised;
            if (!quiet) {
                emit(kid, "react", "Raise dead: " + raised + " of the " + total + " fallen rise at " + (fit > 0 || cap == null ? at.name : cap.name)
                        + (raised > fit && fit > 0 && cap != null ? " (" + (raised - fit) + " at " + cap.name + ")" : "") + ".");
            }
        }
    }

    /** What a raid by {@code survivors} takes: ½ food, 10 gold or 1 resource each, up to the defender's stockpile. */
    public Steal raidSteal(Node target, String resource, int survivors) {
        Rules.Raid c = rules.raid;
        if (target.type == NodeType.OUTPOST || target.owner == null) {
            return new Steal(0, 0, null, 0);
        }
        Output base = baseOutput(target);
        double per, out;
        switch (resource) {
            case "food" -> {
                per = c.foodPer;
                out = base.food();
            }
            case "gold" -> {
                per = c.goldPer;
                out = base.gold();
            }
            case "res" -> {
                per = c.prodPer;
                out = base.prod();
            }
            case "hoard" -> {
                // Only from the capital of a kingdom keeping a dragon's hoard, at the rate gold is taken.
                if (target.type != NodeType.CAPITAL || !hoards(K.get(target.owner))) {
                    return new Steal(0, 0, null, 0);
                }
                per = c.goldPer;
                out = base.gold();
            }
            default -> throw new RuleException("Pick food, gold or resources to raid for.");
        }
        double cap = c.maxTurns > 0 ? Math.max(0, out * c.maxTurns) : Double.POSITIVE_INFINITY;
        Kingdom owner = K.get(target.owner);
        double stock = Math.max(0, stockpile(owner, resource));
        double amount = Math.max(0, Math.min(Math.min(Math.floor(survivors * per), cap), stock));
        return new Steal(amount, cap, resource, stock);
    }

    static double stockpile(Kingdom k, String key) {
        return switch (key) {
            case "food" -> k.food;
            case "gold" -> k.gold;
            case "hoard" -> k.hoard;
            default -> k.res;
        };
    }

    private static void addStockpile(Kingdom k, String key, double amount) {
        switch (key) {
            case "food" -> k.food = r2(k.food + amount);
            case "gold" -> k.gold = r2(k.gold + amount);
            case "hoard" -> k.hoard = r2(k.hoard + amount);
            default -> k.res = r2(k.res + amount);
        }
    }

    private void raid(String kid, Action a) {
        Rules c = rules;
        Kingdom k = K.get(kid);
        Attack at = attackChecks(kid, a, Action.Type.RAID);
        Node from = at.from(), target = at.target();
        int troops = at.troops();
        watchtowerWarning(target);
        String defender = target.owner;
        Kingdom dk = K.get(defender);
        double fd = finalDefense(target), dp = defPower(target);
        int g = target.garrison;
        Combat.Dice dice = Combat.Dice.roll(c.combat.dice, rand);
        Combat.Battle r = Combat.raid(c, troops, g, dp, strength(kid), dice);
        k.stats.raidsTried++;
        k.stats.attacksOn.merge(defender, 1, Integer::sum);
        if (dice.rolled() && r.win() != Combat.raid(c, troops, g, dp, strength(kid)).win()) {
            k.stats.upsets++;
        }
        from.garrison -= troops;
        hitMorale(target, c.morale.attacked);
        target.attackedRound = round;
        k.stats.battleLosses += r.attackerLosses();
        k.stats.kills += r.defenderLosses();
        dk.stats.battleLosses += r.defenderLosses();
        noteAttack(kid, defender, r.win());
        String head = quiet ? "" : "Raid (" + a.resource() + "): " + troops + " from " + from.name + " vs " + target.name + " [" + dk.name + ", " + g
                + " garrison, defense " + num(r2(fd)) + ", power " + num(r2(dp)) + "]" + rolls(dice, r);
        if (r.win()) {
            k.stats.raidsWon++;
            target.garrison = r.defenderRemaining();
            from.garrison += r.survivors();
            hitMorale(target, c.morale.lost);
            Steal s = raidSteal(target, a.resource(), r.survivors());
            if (s.amount() > 0) {
                addStockpile(dk, s.key(), -s.amount());
                addStockpile(k, "hoard".equals(s.key()) ? "gold" : s.key(), s.amount()); // a stolen hoard is just gold to the thief
                switch (s.key()) {
                    case "food" -> k.stats.stolenFood += s.amount();
                    case "gold" -> k.stats.stolenGold += s.amount();
                    case "hoard" -> {
                        k.stats.stolenHoard += s.amount();
                        dk.stats.hoardLost += s.amount();
                        updateBrood(dk);
                    }
                    default -> k.stats.stolenRes += s.amount();
                }
            }
            double dev = Math.min(c.raid.devMax, c.raid.devPer10 * Math.floor(r.survivors() / 10.0));
            target.devastation = r2(Math.min(1, target.devastation + dev));
            raiseDead(kid, defender, from, target, r);
            if (!quiet) {
                emit(kid, "raid", head + " → SUCCESS. " + r.survivors() + " return, defenders lose " + r.defenderLosses() + ". Stole " + num(s.amount()) + " "
                        + a.resource() + (s.key() != null ? (s.cap() == Double.POSITIVE_INFINITY ? "" : " (cap " + num(s.cap()) + ")") : " (outposts yield nothing)")
                        + "; devastation +" + num(dev) + ".");
            }
        } else {
            target.garrison = r.defenderRemaining();
            from.garrison += r.survivors();
            hitMorale(from, c.morale.lost);
            if (!quiet) {
                emit(kid, "fail", head + " → REPELLED. " + r.survivors() + " retreat; defenders keep " + r.defenderRemaining() + " of " + g + ".");
            }
            raiseDead(kid, defender, from, target, r);
        }
    }

    private void hire(String kid, Action a) {
        Rules.Hire c = rules.hire;
        Kingdom k = K.get(kid);
        int b = atLeastOne(a.batches(), "Batches");
        if (b > c.maxBatches) {
            throw new RuleException("At most " + c.maxBatches + " batches per hire.");
        }
        Node node = own(kid, a.node(), "where to place the troops");
        if (!node.type.musters()) {
            throw new RuleException("Troops can only be placed in the capital, a town or a castle.");
        }
        if (!connectedToCapital(kid, node.id)) {
            throw new RuleException(node.name + " has no friendly path to the capital; troops are cut off.");
        }
        double w = b * c.gold, rs = b * c.res;
        double paid = goldToPay(k, 0, w, rs);
        if (paid < 0) {
            throw new RuleException("Hiring " + b + " batch(es) costs " + num(w) + " gold and " + num(rs) + " resources.");
        }
        double gm = growthMult(kid);
        int soldiers = (int) Math.floor(b * c.batchSize * gm);
        pay(k, 0, w, rs);
        node.garrison += soldiers;
        k.stats.hired += soldiers;
        if (!quiet) {
            emit(kid, "action", "Hire: " + b + " batch(es) for " + num(w) + " gold + " + num(rs) + " resources" + wildcardNote(paid - w) + " × growth " + num(gm)
                    + " = " + soldiers + " soldiers at " + node.name + ".");
        }
    }

    /**
     * The gold it takes {@code k} to pay {@code food}, {@code gold} and {@code res}, with gold standing in for any food or
     * resources it lacks at the wildcard's rate (Rules.GoldWildcard); -1 if it can't pay.
     */
    public double goldToPay(Kingdom k, double food, double gold, double res) {
        Rules.GoldWildcard w = rules.goldWildcard;
        double shortFood = Math.max(0, food - k.food), shortRes = Math.max(0, res - k.res);
        if (shortFood > 0 && (w == null || w.food <= 0) || shortRes > 0 && (w == null || w.res <= 0)) {
            return -1;
        }
        double total = gold + (shortFood > 0 ? shortFood * w.food : 0) + (shortRes > 0 ? shortRes * w.res : 0);
        return total <= k.gold ? total : -1;
    }

    /** Pays a cost {@link #goldToPay} says the kingdom can: what it has of the food and resources, and gold for the rest. */
    private void pay(Kingdom k, double food, double gold, double res) {
        double total = goldToPay(k, food, gold, res);
        double f = Math.min(food, k.food), r = Math.min(res, k.res);
        k.food = r2(k.food - f);
        k.gold = r2(k.gold - total);
        k.res = r2(k.res - r);
    }

    private static String wildcardNote(double extraGold) {
        return extraGold > 0 ? " (" + num(r2(extraGold)) + " more gold in place of what was missing)" : "";
    }

    private void train(String kid) {
        Kingdom k = K.get(kid);
        double cost = trainCost(k);
        if (cost < 0) {
            throw new RuleException("Troops can't be trained any further.");
        }
        if (k.gold < cost) {
            throw new RuleException("Training costs " + num(cost) + " gold.");
        }
        k.gold = r2(k.gold - cost);
        k.trained++;
        if (!quiet) {
            emit(kid, "action", "Train troops for " + num(cost) + " gold: troop strength now ×" + num(r2(strength(kid))) + ".");
        }
    }

    /** Whether Produce Resources is off its cooldown for {@code kid}. */
    public boolean canProduce(String kid) {
        Integer last = K.get(kid).lastProduced;
        return rules.produce.cooldown <= 0 || last == null || round - last >= rules.produce.cooldown;
    }

    /** Pays a share of the nodes' per-turn output; imported food doesn't count. */
    private void produce(String kid) {
        Kingdom k = K.get(kid);
        if (!canProduce(kid)) {
            throw new RuleException("Produce Resources can be used once every " + rules.produce.cooldown + " turns; next on turn "
                    + (k.lastProduced + rules.produce.cooldown) + ".");
        }
        k.lastProduced = round;
        Output inc = income(kid, true);
        double m = rules.produce.payout;
        double f = Math.floor(Math.max(0, inc.food()) * m), w = Math.floor(inc.gold() * m), p = Math.floor(inc.prod() * m);
        k.food = r2(k.food + f);
        k.gold = r2(k.gold + w);
        k.res = r2(k.res + p);
        if (!quiet) {
            emit(kid, "action", "Produce resources (" + num(m * 100) + "% of node output): +" + num(f) + " food, +" + num(w) + " gold, +" + num(p) + " resources.");
        }
    }

    /** A supply contract: pay up front, receive food each production phase for the contract's duration. */
    private void importFood(String kid, Action a) {
        Rules.ImportFood c = rules.importFood;
        Kingdom k = K.get(kid);
        if (!c.enabled) {
            throw new RuleException("Food imports are turned off in the rules.");
        }
        int food = atLeastOne(a.food(), "Food per turn");
        if (food > c.maxPerAction) {
            throw new RuleException("At most " + c.maxPerAction + " food per turn per contract.");
        }
        double cost = food * c.duration * c.goldPerFood;
        if (k.gold < cost) {
            throw new RuleException("Importing " + food + " food per turn for " + c.duration + " turns costs " + num(cost) + " gold.");
        }
        k.gold = r2(k.gold - cost);
        k.imports.add(new Kingdom.Contract(food, c.duration));
        k.stats.imported += food * c.duration;
        if (!quiet) {
            emit(kid, "action", "Import food: " + num(cost) + " gold for +" + food + " food per turn over the next " + c.duration + " turns.");
        }
    }

    private void fortify(String kid, Action a) {
        Rules.Fortify c = rules.fortify;
        Kingdom k = K.get(kid);
        Node n = own(kid, a.node(), "a node to fortify");
        if (n.upgrade != null) {
            throw new RuleException(n.name + " is upgrading and cannot gain stats.");
        }
        int steps = atLeastOne(a.steps() == 0 ? 1 : a.steps(), "Steps");
        if (steps > c.maxSteps) {
            throw new RuleException("At most " + c.maxSteps + " steps (" + num(c.maxSteps * c.cost) + " resources).");
        }
        double cost = steps * c.cost;
        if (k.res < cost) {
            throw new RuleException("Fortifying " + steps + " step(s) costs " + num(cost) + " resources.");
        }
        k.res = r2(k.res - cost);
        int before = n.defense;
        n.defense = Math.min(defCap(n), n.defense + steps);
        n.devastation = r2(Math.max(0, n.devastation - steps * c.devPerStep));
        if (!quiet) {
            emit(kid, "action", "Fortify " + n.name + ": " + num(cost) + " resources, defense " + before + "→" + n.defense + " (cap " + defCap(n)
                    + "), devastation now " + num(n.devastation) + ".");
        }
    }

    private void upgrade(String kid, Action a) {
        Kingdom k = K.get(kid);
        Node n = own(kid, a.node(), "a node to upgrade");
        if (n.type == NodeType.CAPITAL) {
            throw new RuleException("Capitals are always Tier 5.");
        }
        if (n.upgrade != null) {
            throw new RuleException(n.name + " is already upgrading.");
        }
        if (n.tier >= n.type.maxTier()) {
            throw new RuleException(n.name + " is already at max tier.");
        }
        Cost cost = upgradeCost(n);
        double paid = goldToPay(k, cost.food(), cost.gold(), cost.res());
        if (paid < 0) {
            throw new RuleException("Upgrading to T" + cost.newTier() + " costs " + num(cost.food()) + " food, " + num(cost.gold()) + " gold, "
                    + num(cost.res()) + " resources.");
        }
        pay(k, cost.food(), cost.gold(), cost.res());
        n.upgrade = new Node.Upgrade(cost.newTier(), cost.turns(), false);
        if (!quiet) {
            emit(kid, "action", (n.tier == 0 ? "Rebuild" : "Upgrade") + " " + n.name + " to T" + cost.newTier() + " (" + n.type.tierName(cost.newTier()) + "): "
                    + num(cost.food()) + " food, " + num(cost.gold()) + " gold, " + num(cost.res()) + " resources" + wildcardNote(paid - cost.gold()) + ", "
                    + cost.turns() + " turns.");
        }
    }

    private void repair(String kid, Action a) {
        Kingdom k = K.get(kid);
        Node n = own(kid, a.node(), "a node to repair");
        if (n.upgrade != null) {
            throw new RuleException(n.name + " is upgrading and cannot gain stats.");
        }
        if (n.devastation <= 0) {
            throw new RuleException(n.name + " has no devastation.");
        }
        double cost = Math.ceil(n.devastation * rules.repairCost);
        if (k.res < cost) {
            throw new RuleException("Repairing costs " + num(cost) + " resources.");
        }
        k.res = r2(k.res - cost);
        if (!quiet) {
            emit(kid, "action", "Repair " + n.name + ": devastation " + num(n.devastation) + "→0 for " + num(cost) + " resources.");
        }
        n.devastation = 0;
    }
}
