package io.github.ranger3462.ddkingdom.sim;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.github.ranger3462.ddkingdom.sim.Game.Output;

/**
 * How an AI kingdom picks its actions (docs/rules/kingdom-behaviour.txt), as the simulator's AI does. Every action it
 * could take is given a score: what it gains (land, output, loot, defense) less what it costs (troops lost, stockpile
 * spent), weighted by the kingdom's {@link Personality}, with a little noise. The best score wins. Counter-attacking
 * kingdoms (the Elementals) and the old burst cycle run their own logic first.
 */
final class GameAi {
    private final Game g;

    GameAi(Game game) {
        this.g = game;
    }

    private record Candidate(double score, Action action) {}

    /** A target no single garrison can take yet, and the best node to mass troops next to it. */
    private record Blocked(double gain, double score, String target, String from, double need) {}

    private record Pair(Node s, Node t, double dp) {}

    /** The biggest garrison beside {@code n} held by someone else. */
    int maxAdjThreat(Node n, String kid) {
        int m = 0;
        for (String id : g.adj.get(n.id)) {
            Node o = g.N.get(id);
            if (o.owner != null && !o.owner.equals(kid)) {
                m = Math.max(m, o.garrison);
            }
        }
        return m;
    }

    private Rules.Guard guardRules() {
        Rules.Guard guard = g.rules.ai.guard;
        return guard != null && guard.enabled ? guard : null;
    }

    /**
     * The threat a node keeps a reserve against: the biggest enemy garrison next to it and, with the guard on, a share
     * of the biggest within {@link Rules.Guard#reach} links, which can take the empty land between and strike next.
     */
    double threat(Node n, String kid) {
        double t = maxAdjThreat(n, kid);
        Rules.Guard guard = guardRules();
        if (guard == null || guard.reach < 2) {
            return t;
        }
        Set<String> seen = new java.util.HashSet<>(Set.of(n.id));
        List<String> frontier = List.of(n.id);
        for (int d = 1; d <= guard.reach; d++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (String nb : g.adj.get(id)) {
                    if (seen.add(nb)) {
                        next.add(nb);
                        Node o = g.N.get(nb);
                        if (d >= 2 && o.owner != null && !o.owner.equals(kid)) {
                            t = Math.max(t, o.garrison * guard.reachShare);
                        }
                    }
                }
            }
            frontier = next;
        }
        return t;
    }

    /** With the guard on, the troops a node next to land it doesn't hold always keeps; 0 otherwise, and for capitals. */
    double guard(Node n, String kid, Personality p) {
        Rules.Guard guard = guardRules();
        if (guard == null || n.type == NodeType.CAPITAL) {
            return 0;
        }
        for (String id : g.adj.get(n.id)) {
            if (!g.N.get(id).ownedBy(kid)) {
                return Math.ceil(guard.min * p.guardMult);
            }
        }
        return 0;
    }

    /** Buys the growth buff when it wants a bigger army and can afford to hire with it. */
    void buff(String kid) {
        Kingdom k = g.K.get(kid);
        Personality p = g.personality(k);
        if (k.buffTurns > 0) {
            return;
        }
        double cost = g.buffCost();
        if (cost <= 0 || k.gold < cost) {
            return;
        }
        int army = g.army(kid);
        double desired = desiredArmy(kid, p);
        double after = k.gold - cost, hireGold = g.rules.hire.gold * g.rules.hire.maxBatches;
        Output inc = g.income(kid, false);
        Game.Upkeep up = g.upkeepFor(army + 20, kid);
        if (desired - army >= 20 / p.mil && after >= hireGold * 2 && inc.food() - up.food() >= 0 && k.res >= g.rules.hire.res * 4) {
            g.buyBuff();
        }
    }

    double desiredArmy(String kid, Personality p) {
        int enemy = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (Node n : g.ownedNodes(kid)) {
            for (String id : g.adj.get(n.id)) {
                Node o = g.N.get(id);
                if (o.owner != null && !o.owner.equals(kid) && seen.add(id)) {
                    enemy += o.garrison;
                }
            }
        }
        return Math.max(p.minArmy, p.armyRatio * enemy);
    }

    /** Nodes within {@code radius} links of the capital, walking over any node. */
    Set<String> homeArea(String kid, double radius) {
        Node cap = g.capital(kid);
        Set<String> out = new LinkedHashSet<>();
        if (cap == null || radius == 0) {
            return out;
        }
        List<String> frontier = List.of(cap.id);
        out.add(cap.id);
        for (int d = 0; d < radius; d++) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (String nb : g.adj.get(id)) {
                    if (out.add(nb)) {
                        next.add(nb);
                    }
                }
            }
            frontier = next;
        }
        return out;
    }

    /** Links from every node to {@code goal}, walking over any node; unreachable nodes are left out. */
    Map<String, Integer> distTo(String goal) {
        Map<String, Integer> d = new HashMap<>();
        d.put(goal, 0);
        ArrayDeque<String> frontier = new ArrayDeque<>(List.of(goal));
        while (!frontier.isEmpty()) {
            List<String> next = new ArrayList<>();
            for (String id : frontier) {
                for (String nb : g.adj.get(id)) {
                    if (!d.containsKey(nb)) {
                        d.put(nb, d.get(id) + 1);
                        next.add(nb);
                    }
                }
            }
            frontier = new ArrayDeque<>(next);
        }
        return d;
    }

    /** {@code a < b} where either may be missing (unreachable), which is never less. */
    private static boolean closer(Integer a, Integer b) {
        return a != null && b != null && a < b;
    }

    private static int sign(double x) {
        return x < 0 ? -1 : x > 0 ? 1 : 0;
    }

    /** The first node with the most {@code value}, as the simulator's reduce picks it. */
    private static Node most(List<Node> list, java.util.function.ToDoubleFunction<Node> value) {
        Node best = null;
        for (Node n : list) {
            if (best == null || value.applyAsDouble(n) > value.applyAsDouble(best)) {
                best = n;
            }
        }
        return best;
    }

    /** The old Elemental burst cycle: mass in the capital, strike player land, fall back when the army runs thin. */
    private Action burst(String kid, Personality p) {
        Kingdom k = g.K.get(kid);
        Node cap = g.capital(kid);
        double str = g.strength(kid);
        List<Node> owned = g.ownedNodes(kid);
        List<Pair> pairs = new ArrayList<>();
        for (Node s : owned) {
            for (String id : g.adj.get(s.id)) {
                Node t = g.N.get(id);
                if (t.owner != null && g.K.get(t.owner).player && t.type != NodeType.CAPITAL) {
                    pairs.add(new Pair(s, t, g.defPower(t)));
                }
            }
        }
        if (pairs.isEmpty() || cap == null) {
            return null;
        }
        java.util.function.ToDoubleFunction<Node> keep = n -> n == cap ? Math.min(n.garrison, p.capitalReserve) : 0;
        if (!"attack".equals(k.mode)) {
            if (k.cooldownUntil != 0 && g.round < k.cooldownUntil) {
                return null;
            }
            Pair weakest = pairs.get(0);
            for (Pair q : pairs) {
                if (q.dp() < weakest.dp()) {
                    weakest = q;
                }
            }
            double ready = cap.garrison - keep.applyAsDouble(cap);
            if (ready <= 0 || ready * str < p.burstRatio * weakest.dp()) {
                return null;
            }
            k.mode = "attack";
            if (!g.quiet) {
                g.emit(kid, "react", Game.num(ready) + " troops massed at " + cap.name + ", " + Game.num(p.burstRatio) + "× the strength of "
                        + weakest.t().name + ": the offensive begins.");
            }
        }
        Node army = most(owned, n -> n.garrison - keep.applyAsDouble(n));
        int troops = (int) (army.garrison - keep.applyAsDouble(army));
        List<Pair> here = new ArrayList<>();
        List<Pair> reach = new ArrayList<>();
        for (Pair q : pairs) {
            if (q.s() == army && troops * str > q.dp() * p.margin) {
                here.add(q);
            }
            if (q.s() != army && g.garrisonCap(q.s()) - q.s().garrison >= troops && (troops + q.s().garrison) * str > q.dp() * p.margin) {
                reach.add(q);
            }
        }
        here.sort((a, b) -> sign(a.dp() - b.dp()));
        reach.sort((a, b) -> sign(a.dp() - b.dp()));
        if (troops > 0 && !here.isEmpty()) {
            return Action.capture(army.id, here.get(0).t().id, troops);
        }
        if (troops > 0 && !reach.isEmpty()) {
            return Action.send(army.id, reach.get(0).s().id, troops);
        }
        k.mode = "gather";
        k.cooldownUntil = g.round + 3;
        if (!g.quiet) {
            g.emit(kid, "react", "The offensive is spent: " + Math.max(0, troops) + " troops can't beat the next player node. Falling back to " + cap.name + ".");
        }
        if (army != cap && troops > 0) {
            return Action.send(army.id, cap.id, troops);
        }
        return null;
    }

    /**
     * Counter-attack: push the main army towards the aggressor's capital, capturing what's in the way, then raid the
     * capital. Ends after a lost fight ({@link Game#noteAttack}) or when the next fight would likely be lost.
     */
    private Action counter(String kid, Personality p) {
        Kingdom k = g.K.get(kid);
        Node cap = g.capital(kid);
        double str = g.strength(kid);
        if (k.falling) {
            // Bring the field army back to the capital.
            k.falling = false;
            List<Node> out = new ArrayList<>();
            for (Node n : g.ownedNodes(kid)) {
                if (n != cap && !kid.equals(n.home) && n.garrison > 2) {
                    out.add(n);
                }
            }
            if (!out.isEmpty() && cap != null) {
                Node d = most(out, n -> n.garrison);
                return Action.send(d.id, cap.id, d.garrison - 2);
            }
            return null;
        }
        Node ec = k.counter == null ? null : g.capital(k.counter.kid());
        if (ec == null) {
            k.counter = null;
            return null;
        }
        Map<String, Integer> dist = distTo(ec.id);
        java.util.function.ToDoubleFunction<Node> keep = n -> n == cap ? Math.min(n.garrison, p.capitalReserve) : 0;
        List<Node> owned = g.ownedNodes(kid);
        Node army = most(owned, n -> n.garrison - keep.applyAsDouble(n));
        int troops = (int) (army.garrison - keep.applyAsDouble(army));
        if (troops < 5) {
            g.endCounter(kid, "no army left");
            return null;
        }
        // Raid the capital once next to it.
        if (g.adj.get(army.id).contains(ec.id)) {
            if (troops * str > g.defPower(ec) * p.raidMargin) {
                return Action.raid(army.id, ec.id, troops, "gold");
            }
            g.endCounter(kid, ec.name + " is too strong to raid");
            return null;
        }
        // Capture the weakest beatable node one step closer to the capital, the aggressor's first.
        String foe = k.counter.kid();
        List<Node> steps = new ArrayList<>();
        for (String id : g.adj.get(army.id)) {
            Node t = g.N.get(id);
            if (!t.ownedBy(kid) && t.type != NodeType.CAPITAL && closer(dist.get(t.id), dist.get(army.id)) && troops * str > g.defPower(t) * p.margin) {
                steps.add(t);
            }
        }
        steps.sort((a, b) -> {
            int byFoe = (b.ownedBy(foe) ? 1 : 0) - (a.ownedBy(foe) ? 1 : 0);
            return byFoe != 0 ? byFoe : sign(g.defPower(a) - g.defPower(b));
        });
        if (!steps.isEmpty()) {
            return Action.capture(army.id, steps.get(0).id, troops);
        }
        // Or move the army to an owned node that's closer and can hold it.
        List<Node> hops = new ArrayList<>();
        for (Node n : owned) {
            if (n != army && closer(dist.get(n.id), dist.get(army.id)) && g.garrisonCap(n) - n.garrison >= troops) {
                hops.add(n);
            }
        }
        hops.sort(Comparator.comparingInt(n -> dist.get(n.id)));
        if (!hops.isEmpty()) {
            return Action.send(army.id, hops.get(0).id, troops);
        }
        // Patient factions keep the grudge and build up at home for a few rounds instead of giving up.
        if (k.counterPatience != 0 && g.round - k.counter.since() < k.counterPatience && (army == cap || kid.equals(army.home))) {
            return null;
        }
        g.endCounter(kid, "the next fight looks lost");
        return null;
    }

    /** The action the kingdom whose turn it is takes next. */
    Action choose(String kid) {
        Rules c = g.rules;
        Rules.Ai ai = c.ai;
        Kingdom k = g.K.get(kid);
        Personality p = g.personality(k);
        if (p.burst != 0) {
            Action b = burst(kid, p);
            if (b != null) {
                return b;
            }
        }
        if (p.counter != 0 && (k.counter != null || k.falling)) {
            Action b = counter(kid, p);
            if (b != null) {
                return b;
            }
        }
        double horizon = g.maxRounds != 0 ? Math.max(1, Math.min(ai.horizon, g.maxRounds - g.round + 1)) : ai.horizon;
        List<Node> owned = g.ownedNodes(kid);
        Node cap = g.capital(kid);
        double str = g.strength(kid);
        Output inc = g.income(kid, false);
        int army = g.army(kid);
        Game.Upkeep up = g.upkeepFor(army, kid);
        // full() values long-term output; value() values stockpile changes, worth less the bigger the hoard already is.
        double wf = ai.foodValue * sat(k.food, inc.food()), ww = sat(k.gold, inc.gold()), wr = ai.resValue * sat(k.res, inc.prod());
        Values v = new Values(ai, wf, ww, wr);
        double netFood = inc.food() - up.food(), netGold = inc.gold() - up.gold();
        boolean onlyCap = g.onlyCapital(kid);
        double sv = ai.soldierValue;
        List<Candidate> cands = new ArrayList<>();
        java.util.function.BiConsumer<Double, Action> add = (score, action) -> {
            if (Double.isFinite(score)) {
                cands.add(new Candidate(score * (1 + (g.rand.next() - 0.5) * ai.noise), action));
            }
        };
        Map<Node, Double> reserves = new HashMap<>(); // garrisons don't change while it chooses
        java.util.function.ToDoubleFunction<Node> reserve = n -> reserves.computeIfAbsent(n, x -> {
            double t = threat(x, kid);
            double r = t != 0 ? Math.ceil(Math.min(x.garrison, t * 0.6 * p.def * p.reserveMult)) : 0;
            if (x.type == NodeType.CAPITAL) {
                r = Math.max(r, Math.min(x.garrison, p.capitalReserve));
            }
            if (g.garrisonCap(x) == Game.UNCAPPED) {
                r = Math.max(r, Math.min(x.garrison, p.leaveBehind));
            }
            return Math.max(r, Math.min(x.garrison, guard(x, kid, p)));
        });
        java.util.function.ToDoubleFunction<Node> importance = n -> n.type == NodeType.CAPITAL ? 1.5 : 1 + v.full(g.baseOutput(n)) / 600;
        boolean heroic = onlyCap;
        double hm = (heroic ? c.combat.heroicMult : 1) * str;
        Set<String> home = homeArea(kid, p.homeRadius);
        int round = g.round;
        // Behaviour: which targets this kingdom is willing to attack at all.
        TargetFilter allowTarget = (t, power, dp) -> {
            Kingdom owner = t.owner == null ? null : g.K.get(t.owner);
            return switch (p.targets) {
                case "playerOnly" -> owner != null && owner.player;
                case "home" -> kid.equals(t.home);
                case "raze" -> kid.equals(t.home) || owner != null && k.foes.contains(t.owner);
                case "avoidMonsters" -> owner == null || owner.player || power >= dp * 2 || t.morale <= 5 || t.devastation >= 0.3;
                default -> true;
            };
        };
        java.util.function.ToDoubleFunction<Node> captureGain = t -> {
            if (p.raze != 0 && !kid.equals(t.home)) {
                return 200 + 400 * t.tier;
            }
            int afterTier = Math.max(c.freeRebuildOnCapture ? 1 : 0, t.tier - 1);
            // Territory is worth something on its own; castles also for defense and (at T3) an extra action.
            double base = (300 + (t.owner != null ? 150 : 0)) * p.landValue + (t.type == NodeType.CASTLE ? 250 + 200 * afterTier : 0);
            double gain = afterTier == 0
                    ? base + Math.max(0, v.full(g.baseOutput(t, 1))) * Math.max(0, horizon - 3) * 0.3
                    : base + Math.max(0, v.full(g.baseOutput(t, afterTier)) * 0.7) * horizon * 0.6;
            if (k.lost.get(t.id) != null) {
                gain *= p.retake;
            }
            if (p.homeRadius != 0) {
                gain *= home.contains(t.id) ? p.homeBias : 0.7;
            }
            return gain;
        };
        // Behaviour: new conquests that still need securing before expanding again.
        List<Node> unsecured = new ArrayList<>();
        if (p.secureNew != 0) {
            for (Node n : owned) {
                if (recent(n.capturedRound, 8, round) && n.type != NodeType.CAPITAL
                        && (n.defense < g.defCap(n) || g.defPower(n) < maxAdjThreat(n, kid) * 1.2)) {
                    unsecured.add(n);
                }
            }
        }
        double expandMult = unsecured.isEmpty() ? 1 : 0.35;
        boolean attacks = p.burst == 0;
        boolean dice = Combat.Dice.on(c.combat.dice);
        // War-only kingdoms (the Orcs) take fights whatever the odds, unless they have a risk appetite to hold them to.
        boolean anyFight = p.warOnly != 0 && !(dice && p.minWin > 0);

        // Captures and raids
        if (attacks) {
            for (Node from : owned) {
                double avail = from.garrison - reserve.applyAsDouble(from);
                if (avail < 1) {
                    continue;
                }
                int troops = (int) avail;
                for (String tid : g.adj.get(from.id)) {
                    Node t = g.N.get(tid);
                    if (t.ownedBy(kid)) {
                        continue;
                    }
                    double dp = g.defPower(t);
                    if (!allowTarget.allows(t, avail * hm, dp)) {
                        continue;
                    }
                    if (t.type != NodeType.CAPITAL && (anyFight || avail * hm > dp * p.margin)) {
                        Action capture = heroic ? Action.heroic(from.id, tid, troops) : Action.capture(from.id, tid, troops);
                        if (dice) {
                            // With dice, the land is only won some of the time; the losses are what each side expects to lose.
                            Combat.Odds o = Combat.odds(c, false, troops, t.garrison, dp, heroic, str);
                            double raised = (o.attackerLosses() + o.defenderLosses()) * k.raiseDead;
                            add.accept(expandMult * p.aggr * (o.win() * captureGain.applyAsDouble(t) - (o.attackerLosses() - raised) * sv
                                    + o.defenderLosses() * sv * 0.3), capture);
                        } else {
                            Combat.Battle r = Combat.capture(c, troops, t.garrison, dp, heroic, str);
                            int raised = (int) Math.floor((r.attackerLosses() + r.defenderLosses()) * k.raiseDead);
                            add.accept(expandMult * p.aggr * (captureGain.applyAsDouble(t) - (r.attackerLosses() - raised) * sv + r.defenderLosses() * sv * 0.3),
                                    capture);
                        }
                    }
                    double rm = p.raidMargin != 0 ? p.raidMargin : p.margin;
                    if (p.raid > 0 && t.owner != null && (anyFight || avail * str > dp * rm)) {
                        // Loot and devastation come from the troops left after a win, which the dice make a chance.
                        int lootTroops;
                        double chance, attackerLosses, defenderLosses, raised;
                        if (dice) {
                            Combat.Odds o = Combat.odds(c, true, troops, t.garrison, dp, false, str);
                            lootTroops = (int) Math.round(o.winSurvivors());
                            chance = o.win();
                            attackerLosses = o.attackerLosses();
                            defenderLosses = o.defenderLosses();
                            raised = (attackerLosses + defenderLosses) * k.raiseDead;
                        } else {
                            Combat.Battle r = Combat.raid(c, troops, t.garrison, dp, str);
                            lootTroops = r.survivors();
                            chance = 1;
                            attackerLosses = r.attackerLosses();
                            defenderLosses = r.defenderLosses();
                            raised = (int) Math.floor((r.attackerLosses() + r.defenderLosses()) * k.raiseDead);
                        }
                        String bestRes = null;
                        double bestVal = 0;
                        for (String res : new String[] { "food", "gold", "res" }) {
                            Game.Steal s = g.raidSteal(t, res, lootTroops);
                            double val = v.full(new Output("food".equals(s.key()) ? s.amount() : 0, "gold".equals(s.key()) ? s.amount() : 0,
                                    "res".equals(s.key()) ? s.amount() : 0));
                            if (res.equals("gold")) {
                                val *= p.goldRaidBias;
                            }
                            if (bestRes == null || val > bestVal) {
                                bestRes = res;
                                bestVal = val;
                            }
                        }
                        double dev = Math.min(c.raid.devMax, c.raid.devPer10 * Math.floor(lootTroops / 10.0));
                        double harm = v.full(g.baseOutput(t)) * dev * 4 * 0.3;
                        double loot = chance * bestVal;
                        double score = p.raid * (loot + defenderLosses * sv * 0.7 + chance * harm - (attackerLosses - raised) * sv);
                        if (loot > 0 || defenderLosses > 0) {
                            add.accept(score, Action.raid(from.id, tid, troops, bestRes));
                        }
                    }
                }
            }
        }

        // Targets no single garrison can take yet: pick the best one to mass troops next to.
        // Resource nodes and outposts cap their garrison, so only stages that can hold enough troops count.
        Blocked blocked = null;
        if (attacks) {
            for (Node t : g.nodes) {
                if (t.ownedBy(kid) || t.type == NodeType.CAPITAL) {
                    continue;
                }
                List<Node> stages = new ArrayList<>();
                for (String id : g.adj.get(t.id)) {
                    Node n = g.N.get(id);
                    if (n.ownedBy(kid)) {
                        stages.add(n);
                    }
                }
                if (stages.isEmpty()) {
                    continue;
                }
                double dp = g.defPower(t);
                double need0 = Math.floor(dp * p.margin / hm) + 1;
                if (!allowTarget.allows(t, need0 * hm, dp)) {
                    continue;
                }
                double gain = captureGain.applyAsDouble(t) * expandMult;
                for (Node s : stages) {
                    if (g.garrisonCap(s) < need0) {
                        continue;
                    }
                    double need = need0 - (s.garrison - reserve.applyAsDouble(s));
                    if (need <= 0) {
                        continue;
                    }
                    double score = gain - need * sv;
                    if (blocked == null || score > blocked.score()) {
                        blocked = new Blocked(gain, score, t.id, s.id, need);
                    }
                }
            }
        }

        // Hire
        double desired = desiredArmy(kid, p) + (blocked != null ? blocked.need() : 0);
        // Burst factions want enough troops in the capital to reach the attack threshold.
        if (p.burst != 0 && cap != null && !"attack".equals(k.mode)) {
            double minDp = Double.POSITIVE_INFINITY;
            boolean any = false;
            for (Node t : g.nodes) {
                if (t.owner != null && g.K.get(t.owner).player && t.type != NodeType.CAPITAL && neighbourOwnedBy(t, kid)) {
                    minDp = Math.min(minDp, g.defPower(t));
                    any = true;
                }
            }
            if (any) {
                double need = Math.ceil(p.burstRatio * minDp / str) + p.capitalReserve - cap.garrison;
                desired = Math.max(desired, army + need);
            }
        }
        double gm = g.growthMult(kid);
        List<Node> locs = g.hireLocations(kid);
        if (!locs.isEmpty()) {
            for (int b = c.hire.maxBatches; b >= 1; b--) {
                double w = b * c.hire.gold, rs = b * c.hire.res;
                if (k.gold < w || k.res < rs) {
                    continue;
                }
                int soldiers = (int) Math.floor(b * c.hire.batchSize * gm);
                if (soldiers < 1) {
                    continue;
                }
                Game.Upkeep nu = g.upkeepFor(army + soldiers, kid);
                double nf = inc.food() - nu.food(), nw = inc.gold() - nu.gold();
                Node loc = null;
                if (blocked != null) {
                    for (Node n : locs) {
                        if (n.id.equals(blocked.from())) {
                            loc = n;
                            break;
                        }
                    }
                }
                double progress = loc != null ? Math.min(1, soldiers / blocked.need()) : 0;
                // Goblin exception: may dip into negative food if the hire lets them take a node.
                if (nf < 0 && !(k.goblinRule && progress >= 1)) {
                    continue;
                }
                if (nw < 0 && k.gold - w < -nw * 4) {
                    continue;
                }
                if (loc == null && p.burst != 0 && cap != null && locs.contains(cap)) {
                    loc = cap;
                }
                if (loc == null) {
                    loc = locs.get(0);
                    for (int i = 1; i < locs.size(); i++) {
                        Node n = locs.get(i);
                        if (maxAdjThreat(n, kid) - g.defPower(n) > maxAdjThreat(loc, kid) - g.defPower(loc)) {
                            loc = n;
                        }
                    }
                }
                double deficit = Math.max(0, desired - army);
                double score = p.mil * (Math.min(soldiers, deficit) * sv * 2.5 + soldiers * 5) + p.aggr * (blocked != null ? blocked.gain() * 0.5 * progress : 0)
                        - w * ww * 0.5;
                add.accept(score, Action.hire(loc.id, b));
            }
        }

        // Defensive moves
        double rbScore = 0;
        for (Node n : owned) {
            int threat = maxAdjThreat(n, kid);
            double dp = g.defPower(n);
            // Behaviour multipliers: nodes recently attacked, freshly taken, or inside the home area.
            double focus = 1;
            if (recent(n.attackedRound, 2, round)) {
                focus *= p.fortifyAttacked;
            }
            if (unsecured.contains(n)) {
                focus *= 2;
            }
            if (home.contains(n.id)) {
                focus *= p.homeBias;
            }
            double imp = importance.applyAsDouble(n) * focus;
            boolean isBorder = g.hostileNeighbour(n, kid);
            double f = focus;
            java.util.function.IntToDoubleFunction defValue = newBase -> {
                double gain = g.defPower(n, newBase) - dp;
                if (gain <= 0) {
                    return 0;
                }
                double danger = threat > dp * 0.8 ? Math.min(gain, Math.max(0, threat * 1.3 - dp)) : 0;
                return danger * sv * imp + gain * 4 * f;
            };
            if (isBorder && n.upgrade == null) {
                rbScore += defValue.applyAsDouble(n.defense + c.reinforceBorder.def);
                rbScore += v.value(g.baseOutput(n)) * Math.min(n.devastation, c.reinforceBorder.dev) * Math.min(horizon, 8);
            }
            if (n.upgrade == null && n.defense < g.defCap(n)) {
                int steps = (int) Math.min(Math.min(c.fortify.maxSteps, g.defCap(n) - n.defense), Math.floor(k.res / c.fortify.cost));
                if (steps >= 1) {
                    double val = defValue.applyAsDouble(n.defense + steps)
                            + v.value(g.baseOutput(n)) * Math.min(n.devastation, steps * c.fortify.devPerStep) * Math.min(horizon, 8);
                    if (focus > 1) {
                        val += steps * 12 * focus; // fortify these even before an enemy shows up
                    }
                    add.accept(p.def * val - steps * c.fortify.cost * ai.resValue, Action.fortify(n.id, steps));
                }
            }
            double deficit = threat * 1.25 - dp;
            if (threat > 0 && deficit > 0) {
                int space = g.garrisonCap(n) - n.garrison;
                List<Node> donors = new ArrayList<>();
                for (Node d : owned) {
                    if (d != n && maxAdjThreat(d, kid) == 0 && d.garrison > 0) {
                        donors.add(d);
                    }
                }
                if (!donors.isEmpty() && space > 0) {
                    Node d = most(donors, x -> x.garrison);
                    double amount = Math.min(Math.min(d.garrison - reserve.applyAsDouble(d), Math.ceil(deficit)), space);
                    if (amount > 0) {
                        add.accept(p.def * amount * sv * 0.9 * imp, Action.send(d.id, n.id, (int) amount));
                    }
                }
            }
            // Behaviour: spread troops so each node holds what its threats call for, taking from any node with a surplus.
            if (p.rebalance != 0) {
                double want = needGarrison(n, kid, p, str) - n.garrison;
                int space = g.garrisonCap(n) - n.garrison;
                if (want > 0 && space > 0) {
                    java.util.function.ToDoubleFunction<Node> surplus = d -> d.garrison - needGarrison(d, kid, p, str) - 3;
                    List<Node> donors = new ArrayList<>();
                    for (Node d : owned) {
                        if (d != n && surplus.applyAsDouble(d) > 0) {
                            donors.add(d);
                        }
                    }
                    if (!donors.isEmpty()) {
                        Node d = most(donors, surplus);
                        double amount = Math.min(Math.min(surplus.applyAsDouble(d), want), space);
                        add.accept(p.def * amount * sv * 1.1 * imp, Action.send(d.id, n.id, (int) amount));
                    }
                }
            }
            if (n.devastation >= 0.15 && n.upgrade == null) {
                double cost = Math.ceil(n.devastation * c.repairCost);
                if (k.res >= cost) {
                    add.accept(p.econ * (v.value(g.baseOutput(n)) * n.devastation * Math.min(horizon, 8) - cost * ai.resValue), Action.repair(n.id));
                }
            }
        }
        if (rbScore > 0) {
            add.accept(p.def * rbScore, Action.of(Action.Type.REINFORCE_BORDER));
        }

        // Offensive staging: gather troops next to a target no single garrison can take.
        if (blocked != null && !onlyCap) {
            Node stage = g.N.get(blocked.from());
            java.util.function.ToDoubleFunction<Node> spare = d -> d.garrison - reserve.applyAsDouble(d)
                    - (d.type == NodeType.CAPITAL ? Math.max(10, p.capitalReserve) : 0);
            List<Node> donors = new ArrayList<>();
            for (Node d : owned) {
                if (d != stage && spare.applyAsDouble(d) > 0) {
                    donors.add(d);
                }
            }
            int space = g.garrisonCap(stage) - stage.garrison;
            if (!donors.isEmpty() && space > 0) {
                Node d = most(donors, spare);
                double amount = Math.min(spare.applyAsDouble(d), space);
                add.accept(p.aggr * blocked.gain() * 0.6 * Math.min(1, amount / blocked.need()), Action.send(d.id, stage.id, (int) amount));
            }
        }

        // Behaviour: razing factions bring troops left on captured foe land back to the capital.
        if (p.raze != 0 && cap != null) {
            for (Node d : owned) {
                if (d != cap && !kid.equals(d.home) && d.garrison > 0) {
                    add.accept(p.mil * d.garrison * sv, Action.send(d.id, cap.id, d.garrison));
                }
            }
        }

        // Behaviour: burst factions pull spare troops back to the capital between offensives.
        if (p.burst != 0 && cap != null) {
            java.util.function.ToDoubleFunction<Node> spare = d -> d.garrison - reserve.applyAsDouble(d) - 2;
            List<Node> donors = new ArrayList<>();
            for (Node d : owned) {
                if (d != cap && spare.applyAsDouble(d) > 0) {
                    donors.add(d);
                }
            }
            if (!donors.isEmpty()) {
                Node d = most(donors, spare);
                add.accept(p.mil * spare.applyAsDouble(d) * sv * 0.6, Action.send(d.id, cap.id, (int) spare.applyAsDouble(d)));
            }
        }

        // Upgrades
        for (Node n : owned) {
            if (n.upgrade != null || n.type == NodeType.CAPITAL || n.tier >= n.type.maxTier()) {
                continue;
            }
            if (p.raze != 0 && !kid.equals(n.home)) {
                continue;
            }
            // Behaviour: land outside the home area is rebuilt from ruins at most, never raised further.
            if (p.upgradeAway == 0 && p.homeRadius != 0 && !home.contains(n.id) && n.tier >= 1) {
                continue;
            }
            Game.Cost cost = g.upgradeCost(n);
            double foodBuf = Math.max(0, -netFood) * 3, goldBuf = Math.max(0, -netGold) * 3;
            if (k.food < cost.food() + foodBuf || k.gold < cost.gold() + goldBuf || k.res < cost.res()) {
                continue;
            }
            double eff = Math.max(0, horizon - cost.turns());
            double gain = (v.full(g.baseOutput(n, cost.newTier())) - v.full(g.baseOutput(n))) * eff;
            if (n.type == NodeType.CASTLE) {
                int threat = maxAdjThreat(n, kid);
                gain += (g.defCap(n, cost.newTier()) * g.defMult(n, cost.newTier()) - g.defCap(n) * g.defMult(n)) * (threat > 0 ? 25 : 8);
                if (cost.newTier() == c.bonusActionCastleTier) {
                    gain += v.value(inc) * eff * 0.5;
                }
            }
            if (n.type == NodeType.OUTPOST) {
                gain += maxAdjThreat(n, kid) > 0 ? 150 : 20;
            }
            add.accept(p.econ * (gain - v.value(new Output(cost.food(), cost.gold(), cost.res()))), Action.upgrade(n.id));
        }

        // Import food: worth it when food, not gold, is what stops the army from growing.
        Rules.ImportFood ic = c.importFood;
        if (ic.enabled) {
            for (int food = ic.maxPerAction; food >= 1; food--) {
                double cost = food * ic.duration * ic.goldPerFood;
                if (k.gold < cost + Math.max(0, -netGold) * 3) {
                    continue;
                }
                double room = Math.max(0, desired - army) - Math.max(0, netFood) * c.hire.batchSize;
                double enabled = Math.min(food * c.hire.batchSize, Math.max(0, room));
                double relief = netFood < 0 ? Math.min(food, -netFood) * ai.foodValue * ic.duration : 0;
                add.accept(p.mil * enabled * sv * 1.5 + relief - cost * ww, Action.importFood(food));
                break;
            }
        }

        // Produce Resources is always there, unless it's on its cooldown.
        if (g.canProduce(kid)) {
            Output nodeInc = g.income(kid, true);
            double pm = c.produce.payout;
            add.accept(p.econ * v.value(new Output(Math.max(0, nodeInc.food()) * pm, nodeInc.gold() * pm, nodeInc.prod() * pm)), Action.of(Action.Type.PRODUCE));
        }

        if (p.warOnly != 0) {
            List<Candidate> war = new ArrayList<>();
            for (Candidate cand : cands) {
                Action.Type type = cand.action().type();
                if (type.attack() || p.warHire != 0 && type == Action.Type.HIRE) {
                    war.add(cand);
                }
            }
            if (!war.isEmpty()) {
                cands.clear();
                cands.addAll(war);
            }
            // With no fight to take, it uses the action on the best of everything else: every action is used.
        }
        cands.sort((a, b) -> sign(b.score() - a.score()));
        // With only restricted slots left, pick the best action that fits them.
        if (g.cur != null && !(g.cur.generalLeft > 0)) {
            for (Candidate cand : cands) {
                if (g.fitsExtraSlot(cand.action().type())) {
                    return cand.action();
                }
            }
            return g.fallback(kid);
        }
        return cands.isEmpty() ? g.fallback(kid) : cands.get(0).action();
    }

    @FunctionalInterface
    private interface TargetFilter {
        boolean allows(Node target, double power, double dp);
    }

    private boolean neighbourOwnedBy(Node t, String kid) {
        for (String id : g.adj.get(t.id)) {
            if (g.N.get(id).ownedBy(kid)) {
                return true;
            }
        }
        return false;
    }

    /** Troops a node needs so its defender power is 1.3× the biggest enemy garrison beside it. */
    private double needGarrison(Node n, String kid, Personality p, double str) {
        int threat = maxAdjThreat(n, kid);
        if (threat == 0) {
            return Math.max(n.type == NodeType.CAPITAL ? p.capitalReserve : 0, guard(n, kid, p));
        }
        double fd = g.finalDefense(n);
        return Math.max(0, Math.ceil((threat * 1.3 - fd) / (str * (1 + fd / g.rules.combat.defDivisor))));
    }

    private static boolean recent(Integer when, int span, int round) {
        return when != null && when >= round - span;
    }

    /** A stockpile's worth less the bigger it already is next to what's coming in. */
    private static double sat(double stock, double gross) {
        return 1 / (1 + Math.max(0, stock) / (6 * Math.max(gross, 1)));
    }

    /** What output is worth: {@link #full} at face value, {@link #value} as a change to this kingdom's stockpiles. */
    private record Values(Rules.Ai ai, double wf, double ww, double wr) {
        double full(Output o) {
            return o.food() * ai.foodValue + o.gold() + o.prod() * ai.resValue;
        }

        double value(Output o) {
            return o.food() * wf + o.gold() * ww + o.prod() * wr;
        }
    }
}
