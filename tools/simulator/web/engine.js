// Kingdom Stats rules engine. Pure JS, no DOM: runs in the browser (window.KingdomSim) and in Node (module.exports).
// The rules and maps are the mod's data files: Node reads them from src/main/resources, and the page has them inlined
// as window.KINGDOM_DATA by build.py.
(function (root, factory) {
  if (typeof module === 'object' && module.exports) {
    const fs = require('fs');
    const path = require('path');
    const dir = path.join(__dirname, '..', '..', '..', 'src', 'main', 'resources', 'data', 'ddkingdom', 'realm', 'sim');
    const data = { rules: JSON.parse(fs.readFileSync(path.join(dir, 'rules.json'), 'utf8')), maps: {} };
    for (const f of fs.readdirSync(path.join(dir, 'maps')).sort()) {
      if (f.endsWith('.json')) data.maps[f.slice(0, -5)] = JSON.parse(fs.readFileSync(path.join(dir, 'maps', f), 'utf8'));
    }
    module.exports = factory(data);
  } else root.KingdomSim = factory(root.KINGDOM_DATA);
})(typeof self !== 'undefined' ? self : this, function (DATA) {
  'use strict';

  // Every rule number, from the mod's data/ddkingdom/realm/sim/rules.json (the Java simulator reads the same file).
  const DEFAULT_CONFIG = clone(DATA.rules);
  // The maps, from data/ddkingdom/realm/sim/maps, by file name: large_ruins is the default.
  const MAPS = DATA.maps;

  const PERSONALITY_DEFAULTS = {
    aggr: 1, raid: 0.8, def: 1, econ: 1, mil: 1, margin: 1.25, raidMargin: 0, armyRatio: 1, minArmy: 30,
    targets: 'any', reserveMult: 1, capitalReserve: 0, retake: 1, secureNew: 0, fortifyAttacked: 1,
    homeRadius: 0, homeBias: 1, upgradeAway: 1, goldRaidBias: 1, burst: 0, burstRatio: 2, rebalance: 0, leaveBehind: 0, landValue: 1,
    counter: 0, warOnly: 0, warHire: 0, raze: 0, minWin: 0, raidMinWin: 0, guardMult: 1, developTier: 0, developExpand: 1, developBonus: 0, hoardRaidBias: 2,
  };

  const TIER_NAMES = {
    capital: ['Ruins', 'Hamlet', 'Village', 'Town', 'City', 'Fortified City'],
    town: ['Ruins', 'Hamlet', 'Village', 'Town', 'City', 'Fortified City'],
    castle: ['Ruins', 'Hold', 'Fort', 'Citadel'],
    production: ['Ruins', 'Small Survey Site', 'Rich Survey Site', 'Abundant Survey Site'],
    food: ['Ruins', 'Garden', 'Farm', 'Plantation'],
    outpost: ['Ruins', 'Watchtower'],
  };
  const MAX_TIER = { capital: 5, town: 5, castle: 3, production: 3, food: 3, outpost: 1 };
  const TYPE_LABELS = { capital: 'Capital', town: 'Town', castle: 'Castle', production: 'Production', food: 'Food', outpost: 'Outpost' };

  const ACTION_LABELS = {
    reinforce_border: 'Reinforce border',
    send: 'Send reinforcements',
    capture: 'Capture node',
    raid: 'Raid',
    hire: 'Hire soldiers',
    produce: 'Produce resources',
    fortify: 'Fortify a node',
    upgrade: 'Upgrade a node',
    heroic: 'Heroic conquest',
    repair: 'Repair node',
    import_food: 'Import food',
    train: 'Train troops',
  };


  function clone(o) { return JSON.parse(JSON.stringify(o)); }
  function deepMerge(base, over) {
    if (over === undefined || over === null) return clone(base);
    if (Array.isArray(base) || typeof base !== 'object' || base === null) return clone(over);
    const out = {};
    for (const key of Object.keys(base)) out[key] = deepMerge(base[key], over[key]);
    for (const key of Object.keys(over)) if (!(key in base)) out[key] = clone(over[key]);
    return out;
  }
  function mulberry32(a) {
    return function () {
      a |= 0; a = (a + 0x6D2B79F5) | 0;
      let t = Math.imul(a ^ (a >>> 15), 1 | a);
      t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
      return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
    };
  }
  const r2 = (x) => Math.round(x * 100) / 100;

  // multiplier = min(tier, 1) * (1 + 0.5 * (tier - 1))
  function tierMult(t) { return t <= 0 ? 0 : Math.min(t, 1) * (1 + 0.5 * (t - 1)); }

  // ---- Pure combat math (shared by the engine, the preview and the calculator) ----

  // strength: the defending kingdom's troop strength multiplier (1 for everyone unless a faction has a bonus).
  function defenderPower(cfg, garrison, finalDef, strength) {
    return garrison * (strength || 1) * (1 + finalDef / cfg.combat.defDivisor) + finalDef;
  }

  // ---- Dice (rules: combat.dice) ----
  // Each side rolls a die; its power is multiplied ("scaled": 1 + (roll - middle) x swing) or added to ("flat": roll x flat).
  const NO_DICE = { attackRoll: 0, defenseRoll: 0, attackMult: 1, attackAdd: 0, defenseMult: 1, defenseAdd: 0 };
  function diceOn(d) { return !!d && (d.mode === 'scaled' || d.mode === 'flat'); }
  function diceOf(d, a, b) {
    const middle = (d.sides + 1) / 2;
    if (d.mode === 'scaled') return { attackRoll: a, defenseRoll: b, attackMult: 1 + (a - middle) * d.swing, attackAdd: 0, defenseMult: 1 + (b - middle) * d.swing, defenseAdd: 0 };
    return { attackRoll: a, defenseRoll: b, attackMult: 1, attackAdd: a * d.flat, defenseMult: 1, defenseAdd: b * d.flat };
  }
  function rollDice(d, rand) {
    if (!diceOn(d)) return NO_DICE;
    const a = 1 + Math.floor(rand() * d.sides), b = 1 + Math.floor(rand() * d.sides);
    return diceOf(d, a, b);
  }

  // Power is troops x multiplier (heroic x4, faction strength) x the attacker's roll; survivors are turned back into troops
  // by dividing by it. Ties go to the defender.
  function resolveCapture(cfg, troops, garrison, dp, heroic, strength, dice) {
    dice = dice || NO_DICE;
    const mult = (heroic ? cfg.combat.heroicMult : 1) * (strength || 1);
    const ap = troops * mult * dice.attackMult + dice.attackAdd;
    dp = dp * dice.defenseMult + dice.defenseAdd;
    if (ap > dp) {
      const raw = Math.sqrt(ap * ap - dp * dp);
      const survivors = Math.min(troops, Math.floor(raw / (mult * dice.attackMult)));
      return { win: true, ap, dp, survivors, attackerLosses: troops - survivors, defenderRemaining: 0, defenderLosses: garrison };
    }
    const retreat = Math.floor(troops * cfg.combat.retreat);
    const survPow = Math.sqrt(dp * dp - ap * ap);
    const defRem = dp > 0 ? Math.floor(garrison * survPow / dp) : garrison;
    return { win: false, ap, dp, survivors: retreat, attackerLosses: troops - retreat, defenderRemaining: defRem, defenderLosses: garrison - defRem };
  }

  function resolveRaid(cfg, troops, garrison, dp, strength, dice) {
    dice = dice || NO_DICE;
    const s = strength || 1, ap = troops * s * dice.attackMult + dice.attackAdd;
    const rolledDp = dp * dice.defenseMult + dice.defenseAdd;
    if (ap > rolledDp) {
      const losses = 0.5 * (ap - Math.sqrt(ap * ap - rolledDp * rolledDp)) / (s * dice.attackMult);
      const survivors = Math.max(0, Math.floor(troops - losses));
      const defLoss = Math.floor(garrison * cfg.raid.garrisonDmg);
      return { win: true, ap, dp: rolledDp, survivors, attackerLosses: troops - survivors, defenderRemaining: garrison - defLoss, defenderLosses: defLoss };
    }
    return resolveCapture(cfg, troops, garrison, dp, false, s, dice);
  }

  // What a battle can be expected to do over every roll of the dice: the chance the attacker wins, the troops each side
  // expects to lose, and the attacker's troops left when it wins. Without dice it's the one battle the rules give.
  function odds(cfg, raid, troops, garrison, dp, heroic, strength) {
    const d = cfg.combat.dice;
    if (!diceOn(d)) {
      const b = raid ? resolveRaid(cfg, troops, garrison, dp, strength) : resolveCapture(cfg, troops, garrison, dp, heroic, strength);
      return { win: b.win ? 1 : 0, attackerLosses: b.attackerLosses, defenderLosses: b.defenderLosses, winSurvivors: b.win ? b.survivors : 0 };
    }
    let wins = 0, aL = 0, dL = 0, ws = 0;
    for (let a = 1; a <= d.sides; a++) {
      for (let b = 1; b <= d.sides; b++) {
        const roll = diceOf(d, a, b);
        const r = raid ? resolveRaid(cfg, troops, garrison, dp, strength, roll) : resolveCapture(cfg, troops, garrison, dp, heroic, strength, roll);
        aL += r.attackerLosses; dL += r.defenderLosses;
        if (r.win) { wins++; ws += r.survivors; }
      }
    }
    const rolls = d.sides * d.sides;
    return { win: wins / rolls, attackerLosses: aL / rolls, defenderLosses: dL / rolls, winSurvivors: wins === 0 ? 0 : ws / wins };
  }

  // How many times the defender's power the attacker's must be to win at least `chance` of the time, with scaled dice.
  // 0 for other dice, which don't scale (or none).
  function marginFor(d, chance) {
    if (!d || d.mode !== 'scaled') return 0;
    const ratios = [];
    for (let a = 1; a <= d.sides; a++) for (let b = 1; b <= d.sides; b++) { const r = diceOf(d, a, b); ratios.push(r.defenseMult / r.attackMult); }
    ratios.sort((x, y) => x - y);
    const k = Math.max(1, Math.min(ratios.length, Math.ceil(chance * ratios.length - 1e-9)));
    return ratios[k - 1];
  }

  function wildcardNote(extra) { return extra > 0 ? ` (${r2(extra)} more gold in place of what was missing)` : ''; }

  // The dice in a battle's log line, with the power they gave each side.
  function rollText(dice, r) {
    return dice.attackRoll ? `, rolls ${dice.attackRoll} vs ${dice.defenseRoll} (power ${r2(r.ap)} vs ${r2(r.dp)})` : '';
  }

  // ---- Game ----

  class Game {
    constructor(scenario, config, opts) {
      opts = opts || {};
      this.cfg = deepMerge(DEFAULT_CONFIG, config || {});
      this.seed = opts.seed == null ? 1 : opts.seed;
      this.rand = mulberry32(this.seed);
      this.maxRounds = opts.maxRounds || 0;
      this.quiet = !!opts.quiet;
      const sc = clone(scenario);
      this.kingdoms = sc.kingdoms.map((k) => Object.assign({
        food: 0, gold: 0, res: 0, growth: 'normal', ai: 'balanced', goblinRule: false, buffTurns: 0,
        strength: 1, player: false, mode: 'gather', raiseDead: 0, lastProduced: null, trained: 0, hoard: 0, awake: false,
      }, k));
      for (const k of this.kingdoms) {
        k.imports = (k.imports || []).map((c) => Object.assign({}, c));
        k.lost = Object.assign({}, k.lost || {}); // node id -> round this kingdom lost it
      }
      this.K = {};
      for (const k of this.kingdoms) {
        k.stats = {
          actions: {}, capturesTried: 0, capturesWon: 0, raidsTried: 0, raidsWon: 0, hired: 0,
          battleLosses: 0, kills: 0, shortageLosses: 0, foodShortTurns: 0, goldShortTurns: 0,
          buffs: 0, nodesGained: 0, nodesLost: 0, stolen: { food: 0, gold: 0, res: 0, hoard: 0 }, turns: 0, upsets: 0, broodWoke: 0, firstWoke: 0, turnsAwake: 0, hoardLost: 0,
        };
        this.K[k.id] = k;
      }
      this.nodes = sc.nodes.map((n) => Object.assign({
        tier: 1, owner: null, garrison: 0, defense: 0, morale: 10, devastation: 0, upgrade: null,
      }, n));
      this.N = {};
      for (const n of this.nodes) {
        if (n.type === 'capital') { n.tier = this.cfg.capitalTier; n.morale = 10; }
        this.N[n.id] = n;
      }
      this.adj = {};
      for (const n of this.nodes) this.adj[n.id] = [];
      for (const [a, b] of sc.edges) {
        if (!this.N[a] || !this.N[b]) continue;
        this.adj[a].push(b); this.adj[b].push(a);
      }
      this.round = 1;
      this.order = this.kingdoms.map((k) => k.id);
      this.turnPos = 0;
      this.cur = null;
      this.log = [];
      this.history = [];
      this.pcache = {};
      this.snapshot(0);
    }

    // ---- logging / history ----
    emit(kid, kind, text) {
      if (this.quiet) return;
      this.log.push({ round: this.round, kid, kind, text });
      if (this.log.length > 4000) this.log.splice(0, this.log.length - 4000);
    }
    snapshot(round) {
      const row = { round, k: {} };
      for (const k of this.kingdoms) {
        const inc = this.income(k.id), up = this.upkeep(k.id);
        row.k[k.id] = {
          food: k.food, gold: k.gold, res: k.res, army: this.army(k.id),
          nodes: this.ownedNodes(k.id).length,
          foodNet: r2(inc.food - up.food), goldNet: r2(inc.gold - up.gold), resNet: r2(inc.prod),
        };
      }
      this.history.push(row);
    }

    // ---- node stats ----
    name(id) { return this.N[id] ? this.N[id].name : id; }
    kname(kid) { return kid && this.K[kid] ? this.K[kid].name : 'Neutral'; }
    tierName(n) { return (TIER_NAMES[n.type] || [])[n.tier] || ('T' + n.tier); }

    baseOutput(n, tierOverride) {
      const c = this.cfg, t = tierOverride == null ? n.tier : tierOverride;
      switch (n.type) {
        case 'capital':
        case 'town': {
          const m = tierMult(n.type === 'capital' ? c.capitalTier : t);
          return { food: c.town.food * m, gold: c.town.gold * m * c.town.goldMult, prod: c.town.prod * m };
        }
        case 'castle': return { food: (c.castle[t] || c.castle[0]).food, gold: 0, prod: 0 };
        case 'food': return { food: c.foodNode[t] || 0, gold: 0, prod: 0 };
        case 'production': return { food: 0, gold: 0, prod: c.prodNode[t] || 0 };
        case 'outpost': return { food: t >= 1 ? c.outpost.food : 0, gold: 0, prod: 0 };
      }
      return { food: 0, gold: 0, prod: 0 };
    }
    // Devastation scales production only; fixed food costs (castles, outposts) are not reduced.
    output(n, devOverride) {
      const b = this.baseOutput(n), f = 1 - (devOverride == null ? n.devastation : devOverride);
      const s = (v) => (v > 0 ? v * f : v);
      return { food: s(b.food), gold: s(b.gold), prod: s(b.prod) };
    }
    defCap(n, tierOverride) {
      const c = this.cfg, t = tierOverride == null ? n.tier : tierOverride;
      switch (n.type) {
        case 'capital': return c.capitalTier * c.town.defPerTier;
        case 'town': return t * c.town.defPerTier;
        case 'castle': return (c.castle[t] || c.castle[0]).defCap;
        case 'food': case 'production': return c.resource.defCap;
        case 'outpost': return t >= 1 ? c.outpost.defCap : 0;
      }
      return 0;
    }
    garrisonCap(n) {
      if (n.type === 'food' || n.type === 'production') return this.cfg.resource.garrisonCap;
      if (n.type === 'outpost') return this.cfg.outpost.garrisonCap;
      return Infinity;
    }
    defMult(n, tierOverride) {
      const t = tierOverride == null ? n.tier : tierOverride;
      return n.type === 'castle' ? (this.cfg.castle[t] || this.cfg.castle[0]).mult : 1;
    }
    finalDefense(n, baseOverride) {
      const base = Math.min(baseOverride == null ? n.defense : baseOverride, this.defCap(n));
      let v = base * this.defMult(n);
      if (n.type === 'castle') v = Math.min(v, this.cfg.castleMaxDef);
      return v * (n.morale / 10);
    }
    defPower(n, garrisonOverride, baseOverride) {
      return defenderPower(this.cfg, garrisonOverride == null ? n.garrison : garrisonOverride, this.finalDefense(n, baseOverride), this.defStrength(n));
    }
    // Troop strength: the kingdom's own, plus what it has trained.
    strength(kid) {
      const k = kid && this.K[kid];
      if (!k) return 1;
      const s = (k.strength || 1) + (k.trained > 0 ? k.trained * this.cfg.train.step : 0);
      return k.awake ? s * this.cfg.brood.strength : s;
    }
    // Whether the kingdom keeps a dragon's hoard (brood): the Kobolds.
    hoards(k) { const b = this.cfg.brood; return !!(b && b.enabled && k.brood); }
    // Wakes the brood once the hoard is full, and puts it back to sleep once it isn't.
    updateBrood(k) {
      if (!this.hoards(k)) return;
      const awake = k.hoard >= this.cfg.brood.gold;
      if (awake === k.awake) return;
      k.awake = awake;
      if (awake) { k.stats.broodWoke++; if (!k.stats.firstWoke) k.stats.firstWoke = this.round; }
      this.emit(k.id, 'react', awake ? `The dragon's hoard holds ${k.hoard} gold and her brood is loose: troop strength now ×${r2(this.strength(k.id))}.`
        : `The dragon's hoard has fallen to ${k.hoard} gold and her brood goes back to sleep.`);
    }
    // What the kingdom's next Train troops costs, or -1 if it can't train (off, or trained the most times).
    trainCost(k) {
      const t = this.cfg.train;
      return !t || !t.enabled || k.trained >= t.max ? -1 : t.gold * (k.trained + 1);
    }
    // Strength when defending: defStrength overrides strength, and homeDefStrength multiplies it on the owner's home nodes.
    defStrength(n) {
      const k = n.owner && this.K[n.owner];
      if (!k) return 1;
      const base = k.defStrength != null ? k.defStrength : this.strength(n.owner);
      return base * (n.home === n.owner && k.homeDefStrength ? k.homeDefStrength : 1);
    }

    // ---- kingdom stats ----
    ownedNodes(kid) { return this.nodes.filter((n) => n.owner === kid); }
    capital(kid) { return this.nodes.find((n) => n.owner === kid && n.type === 'capital'); }
    onlyCapital(kid) { const o = this.ownedNodes(kid); return o.length === 1 && o[0].type === 'capital'; }
    army(kid) { let s = 0; for (const n of this.nodes) if (n.owner === kid) s += n.garrison; return s; }
    // Node output plus food arriving from import contracts (nodesOnly leaves the imports out).
    income(kid, nodesOnly) {
      const t = { food: 0, gold: 0, prod: 0 };
      for (const n of this.nodes) if (n.owner === kid) {
        const o = this.output(n); t.food += o.food; t.gold += o.gold; t.prod += o.prod;
      }
      if (!nodesOnly) t.food += this.importedFood(kid);
      return t;
    }
    importedFood(kid) { return this.K[kid].imports.reduce((a, c) => a + c.food, 0); }
    upkeepFor(army, kid) {
      const b = Math.ceil(army / this.cfg.hire.batchSize), m = (kid && this.K[kid].upkeepMult) || 1;
      return { batches: b, food: Math.ceil(b * this.cfg.upkeep.food * m), gold: Math.ceil(b * this.cfg.upkeep.gold * m) };
    }
    upkeep(kid) { return this.upkeepFor(this.army(kid), kid); }
    growthMult(kid) {
      const k = this.K[kid];
      let m = this.cfg.growth[k.growth] != null ? this.cfg.growth[k.growth] : 1;
      if (this.onlyCapital(kid)) m *= this.cfg.capitalOnlyGrowthMult;
      if (k.buffTurns > 0) m *= this.cfg.buff.mult;
      return m;
    }
    bonusActions(kid) {
      return this.nodes.filter((n) => n.owner === kid && n.type === 'castle' && n.tier >= this.cfg.bonusActionCastleTier).length;
    }
    isHostile(n, kid) {
      if (n.owner === kid) return false;
      if (n.owner == null) return !!this.cfg.reinforceBorder.neutralIsHostile;
      return true;
    }
    borderNodes(kid) {
      return this.ownedNodes(kid).filter((n) => this.adj[n.id].some((id) => this.isHostile(this.N[id], kid)));
    }
    connectedToCapital(kid, nodeId) {
      const cap = this.capital(kid);
      if (!cap) return false;
      const seen = new Set([nodeId]), q = [nodeId];
      while (q.length) {
        const id = q.shift();
        if (id === cap.id) return true;
        for (const nb of this.adj[id]) if (!seen.has(nb) && this.N[nb].owner === kid) { seen.add(nb); q.push(nb); }
      }
      return false;
    }
    hireLocations(kid) {
      return this.ownedNodes(kid).filter((n) => (n.type === 'capital' || n.type === 'town' || n.type === 'castle') && this.connectedToCapital(kid, n.id));
    }
    upgradeCost(n) {
      const nt = n.tier + 1, u = this.cfg.upgrade;
      if (n.tier === 0) return { food: 0, gold: 0, res: 0, newTier: 1, turns: u.timeBase + u.timePerTier };
      return {
        food: nt * u.food, gold: nt * u.gold, res: nt * (n.type === 'castle' ? u.castleRes : u.res),
        newTier: nt, turns: u.timeBase + nt * u.timePerTier,
      };
    }
    buffCost() { return this.cur ? this.cfg.buff.costTurns * this.cur.grossGold : 0; }

    // ---- turn flow ----
    currentKid() { return this.order[this.turnPos]; }

    beginTurn() {
      const kid = this.currentKid(), k = this.K[kid], c = this.cfg;
      for (const n of this.ownedNodes(kid)) {
        n.morale = n.type === 'capital' ? 10 : Math.min(10, n.morale + c.morale.recover);
      }
      const inc = this.income(kid);
      k.food += inc.food; k.gold += inc.gold; k.res += inc.prod;
      k.food = r2(k.food); k.gold = r2(k.gold); k.res = r2(k.res);
      if (this.hoards(k)) {
        // The tithe only fills the dragon's hoard up to the target.
        const tithe = Math.min(r2(inc.gold * this.cfg.brood.tithe), Math.max(0, r2(this.cfg.brood.gold - k.hoard)));
        k.gold = r2(k.gold - tithe);
        k.hoard = r2(k.hoard + tithe);
        this.updateBrood(k);
        if (k.awake) k.stats.turnsAwake++;
      }
      k.stats.turns++;
      this.cur = { kid, phase: 'production', actionsLeft: 0, actionsTotal: 0, grossGold: inc.gold };
      this.emit(kid, 'phase', `Production: +${r2(inc.food)} food, +${r2(inc.gold)} gold, +${r2(inc.prod)} resources → stockpile ${k.food} / ${k.gold} / ${k.res}`);
      return this.cur;
    }

    buyBuff() {
      const cur = this.cur;
      if (!cur || cur.phase !== 'production') throw new Error('The growth buff can only be bought during the production phase.');
      const k = this.K[cur.kid], cost = this.buffCost();
      if (k.gold < cost) throw new Error(`Growth buff costs ${cost} gold (2 turns of gross gold income); only ${k.gold} available.`);
      k.gold = r2(k.gold - cost);
      k.buffTurns = this.cfg.buff.duration;
      k.stats.buffs++;
      this.emit(cur.kid, 'buff', `Bought growth buff for ${cost} gold: growth ×${this.cfg.buff.mult} for ${this.cfg.buff.duration} turns (now ×${this.growthMult(cur.kid)}).`);
    }

    finishProduction() {
      const cur = this.cur, kid = cur.kid, k = this.K[kid], c = this.cfg;
      // Upkeep
      const army = this.army(kid), up = this.upkeepFor(army, kid);
      k.food = r2(k.food - up.food); k.gold = r2(k.gold - up.gold);
      // Optional stockpile tax: a share of gold above the tax-free amount is lost each upkeep (0 = off).
      if (c.goldTax.rate > 0 && k.gold > c.goldTax.free) k.gold = r2(k.gold - (k.gold - c.goldTax.free) * c.goldTax.rate);
      const kinds = [];
      if (k.food < 0) { kinds.push('food'); k.stats.foodShortTurns++; k.food = 0; }
      if (k.gold < 0) { kinds.push('gold'); k.stats.goldShortTurns++; k.gold = 0; }
      const short = kinds.length;
      this.emit(kid, 'phase', `Upkeep for ${army} soldiers (${up.batches} batches): −${up.food} food, −${up.gold} gold`);
      if (short) {
        const largest = this.ownedNodes(kid).reduce((a, b) => (b.garrison > a.garrison ? b : a), this.capital(kid));
        const loss = Math.min(largest.garrison, c.shortagePenalty * short);
        largest.garrison -= loss;
        k.stats.shortageLosses += loss;
        this.emit(kid, 'shortage', `${kinds.join(' and ')} shortage:${largest.name} loses ${loss} garrison.`);
      }
      // Upgrades
      for (const n of this.ownedNodes(kid)) {
        if (!n.upgrade) continue;
        n.upgrade.turnsLeft--;
        if (n.upgrade.turnsLeft <= 0) {
          n.tier = n.upgrade.target; n.upgrade = null; n.devastation = 0;
          this.emit(kid, 'upgrade', `${n.name} finished upgrading to ${this.tierName(n)} (T${n.tier}).`);
        }
      }
      if (k.buffTurns > 0) k.buffTurns--;
      for (const c of k.imports) c.turnsLeft--;
      k.imports = k.imports.filter((c) => c.turnsLeft > 0);
      cur.phase = 'actions';
      cur.generalLeft = this.cfg.baseActions + this.bonusActions(kid);
      cur.extraLeft = this.cfg.nonCombatActions.count;
      cur.actionsTotal = cur.actionsLeft = cur.generalLeft + cur.extraLeft;
      return cur;
    }

    // The end of a kingdom's turn: it loses a share of the gold its nodes produced this turn (goldDecay), so gold that
    // isn't spent wears away. Its stockpile never drops below 0.
    decayGold() {
      const d = this.cfg.goldDecay, cur = this.cur;
      if (!cur || !d || !(d.share > 0)) return;
      const k = this.K[cur.kid], before = k.gold;
      k.gold = r2(Math.max(0, k.gold - cur.grossGold * d.share));
      k.stats.goldDecayed = (k.stats.goldDecayed || 0) + before - k.gold;
      if (before > k.gold) this.emit(cur.kid, 'phase', `End of turn: ${r2(before - k.gold)} gold lost (${d.share * 100}% of the ${r2(cur.grossGold)} produced this turn) → ${k.gold} gold`);
    }

    endTurn() {
      this.decayGold();
      this.cur = null;
      this.turnPos++;
      if (this.turnPos >= this.order.length) {
        this.snapshot(this.round);
        this.round++;
        this.turnPos = 0;
        const ids = this.kingdoms.map((k) => k.id);
        if (this.cfg.turnOrder === 'rotate') {
          const s = (this.round - 1) % ids.length;
          this.order = ids.slice(s).concat(ids.slice(0, s));
        } else if (this.cfg.turnOrder === 'random') {
          for (let i = ids.length - 1; i > 0; i--) { const j = Math.floor(this.rand() * (i + 1)); [ids[i], ids[j]] = [ids[j], ids[i]]; }
          this.order = ids;
        } else this.order = ids;
      }
    }

    // Runs one kingdom's turn. AI kingdoms run to completion; manual kingdoms stop and return 'manual'.
    step() {
      if (this.cur) return 'waiting';
      const kid = this.currentKid(), k = this.K[kid];
      this.beginTurn();
      if (k.ai === 'manual') return 'manual';
      this.aiBuff(kid);
      this.finishProduction();
      let guard = 10;
      while (this.cur && this.cur.actionsLeft > 0 && guard-- > 0) {
        const a = this.aiChoose(kid);
        try { this.act(a); } catch (e) { this.act(this.fallback(kid)); }
      }
      if (this.cur) this.endTurn();
      return 'done';
    }
    runRound() {
      const r = this.round;
      while (this.round === r) { const s = this.step(); if (s !== 'done') return s; }
      return 'done';
    }

    // ---- actions ----
    act(a) {
      const cur = this.cur;
      if (!cur || cur.phase !== 'actions') throw new Error('Not in an action phase.');
      const kid = cur.kid, k = this.K[kid];
      const fn = this['do_' + a.type];
      if (!fn) throw new Error('Unknown action ' + a.type);
      // Restricted actions use the extra slot first, so general slots stay free for attacks.
      const fitsExtra = this.fitsExtraSlot(a.type);
      if (!fitsExtra && !(cur.generalLeft > 0)) {
        throw new Error(`Only ${this.cfg.nonCombatActions.economyOnly ? 'economy' : 'non-combat'} actions are left this turn.`);
      }
      fn.call(this, kid, a);
      k.stats.actions[a.type] = (k.stats.actions[a.type] || 0) + 1;
      if (fitsExtra && cur.extraLeft > 0) cur.extraLeft--; else cur.generalLeft--;
      cur.actionsLeft--;
      if (cur.actionsLeft <= 0) this.endTurn();
    }

    own(kid, id, what) {
      const n = this.N[id];
      if (!n) throw new Error(`Pick ${what || 'a node'}.`);
      if (n.owner !== kid) throw new Error(`${n.name} is not yours.`);
      return n;
    }
    int(v, what) {
      const x = Math.floor(Number(v));
      if (!(x >= 1)) throw new Error(`${what} must be at least 1.`);
      return x;
    }

    // The action a kingdom takes when nothing better is allowed: Produce Resources, or if that's on its cooldown,
    // Reinforce border (always allowed, even if it does nothing). Every action is used; there's no passing.
    fallback(kid) { return { type: this.canProduce(kid) ? 'produce' : 'reinforce_border' }; }

    do_reinforce_border(kid) {
      const c = this.cfg.reinforceBorder, parts = [];
      for (const n of this.borderNodes(kid)) {
        if (n.upgrade) continue;
        const before = n.defense;
        n.defense = Math.min(this.defCap(n), n.defense + c.def);
        const dv = n.devastation;
        n.devastation = r2(Math.max(0, n.devastation - c.dev));
        if (n.defense !== before || n.devastation !== dv) parts.push(`${n.name} def ${before}→${n.defense}`);
      }
      this.emit(kid, 'action', `Reinforce border: ${parts.length ? parts.join(', ') : 'no node could gain anything'}.`);
    }

    do_send(kid, a) {
      const from = this.own(kid, a.from, 'a source node'), to = this.own(kid, a.to, 'a destination');
      if (from === to) throw new Error('Source and destination are the same node.');
      let amt = this.int(a.amount, 'Troops');
      if (amt > from.garrison) throw new Error(`${from.name} only has ${from.garrison} troops.`);
      const space = this.garrisonCap(to) - to.garrison;
      if (space <= 0) throw new Error(`${to.name} is at its garrison cap.`);
      amt = Math.min(amt, space);
      from.garrison -= amt; to.garrison += amt;
      this.emit(kid, 'action', `Send reinforcements: ${amt} troops ${from.name} → ${to.name}.`);
    }

    attackChecks(kid, a, kind) {
      const from = this.own(kid, a.from, 'an attacking garrison');
      const target = this.N[a.target];
      if (!target) throw new Error('Pick a target.');
      if (target.owner === kid) throw new Error(`${target.name} is already yours.`);
      if (!this.adj[from.id].includes(target.id)) throw new Error(`${target.name} is not adjacent to ${from.name}.`);
      if (kind !== 'raid' && target.type === 'capital') throw new Error('Capitals cannot be captured (they can be raided).');
      if (kind === 'raid' && target.owner == null) throw new Error('Neutral nodes have no stockpile to raid.');
      if (kind === 'heroic' && !this.onlyCapital(kid)) throw new Error('Heroic conquest requires the capital to be your only node.');
      const troops = this.int(a.troops, 'Troops');
      if (troops > from.garrison) throw new Error(`${from.name} only has ${from.garrison} troops.`);
      return { from, target, troops };
    }

    watchtowerWarning(target) {
      if (target.type !== 'outpost' || target.tier < 1 || !target.owner) return;
      const dk = target.owner;
      const dests = this.adj[target.id].map((id) => this.N[id]).filter((n) => n.owner === dk);
      if (!dests.length) return;
      const dest = dests.reduce((a, b) => (this.defPower(b) < this.defPower(a) ? b : a));
      const donors = this.ownedNodes(dk).filter((n) => n !== target && n !== dest && n.garrison > 0);
      if (!donors.length) return;
      const donor = donors.reduce((a, b) => (b.garrison > a.garrison ? b : a));
      const amt = Math.min(this.cfg.outpost.warnMove, donor.garrison, this.garrisonCap(dest) - dest.garrison);
      if (amt <= 0) return;
      donor.garrison -= amt; dest.garrison += amt;
      this.emit(dk, 'react', `Watchtower ${target.name} spotted the attack: ${amt} troops rushed ${donor.name} → ${dest.name}.`);
    }

    hitMorale(n, amount) {
      if (n.type === 'capital') { n.morale = 10; return; }
      n.morale = Math.max(1, n.morale - amount);
    }

    do_capture(kid, a) { this.doCapture(kid, a, false); }
    do_heroic(kid, a) { this.doCapture(kid, a, true); }
    doCapture(kid, a, heroic) {
      const c = this.cfg, k = this.K[kid];
      const { from, target, troops } = this.attackChecks(kid, a, heroic ? 'heroic' : 'capture');
      this.watchtowerWarning(target);
      const defender = target.owner, fd = this.finalDefense(target), dp = this.defPower(target), g = target.garrison;
      const dice = rollDice(c.combat.dice, this.rand);
      const r = resolveCapture(c, troops, g, dp, heroic, this.strength(kid), dice);
      k.stats.capturesTried++;
      if (dice.attackRoll && r.win !== resolveCapture(c, troops, g, dp, heroic, this.strength(kid)).win) k.stats.upsets++;
      from.garrison -= troops;
      this.hitMorale(target, c.morale.attacked);
      target.attackedRound = this.round;
      const label = heroic ? 'Heroic conquest' : 'Capture';
      const head = `${label}: ${troops}${heroic ? ` (×${c.combat.heroicMult})` : ''} from ${from.name} vs ${target.name} [${this.kname(defender)}, ${g} garrison, defense ${r2(fd)}, power ${r2(dp)}]${rollText(dice, r)}`;
      k.stats.battleLosses += r.attackerLosses; k.stats.kills += r.defenderLosses;
      if (defender) this.K[defender].stats.battleLosses += r.defenderLosses;
      this.noteAttack(kid, defender, r.win);
      if (r.win) {
        k.stats.capturesWon++; k.stats.nodesGained++;
        if (defender) { this.K[defender].stats.nodesLost++; this.K[defender].lost[target.id] = this.round; }
        delete k.lost[target.id];
        target.owner = kid;
        target.capturedRound = this.round;
        if (c.combat.captureTierLoss !== false || k.razes) target.tier = Math.max(0, target.tier - 1);
        target.defense = 0;
        target.morale = c.morale.start;
        target.devastation = r2(Math.min(1, target.devastation + c.combat.captureDevastation));
        target.upgrade = null;
        if (target.tier === 0 && c.freeRebuildOnCapture && !(k.noFreeRebuild && target.home !== kid)) target.upgrade = { target: 1, turnsLeft: 1, free: true };
        const keep = Math.min(r.survivors, this.garrisonCap(target));
        target.garrison = keep;
        from.garrison += r.survivors - keep;
        this.raiseDead(kid, defender, from, target, r);
        this.emit(kid, 'capture', `${head} → CAPTURED. ${r.survivors} survive (√(${r2(r.ap)}² − ${r2(r.dp)}²)${heroic ? ' ÷ ' + c.combat.heroicMult : ''}); ${target.name} drops to ${this.tierName(target)} (T${target.tier}), devastation ${target.devastation}${target.upgrade ? '; free rebuild to T1 next turn' : ''}.${keep < r.survivors ? ` ${r.survivors - keep} over the garrison cap return home.` : ''}`);
      } else {
        target.garrison = r.defenderRemaining;
        from.garrison += r.survivors;
        this.hitMorale(from, c.morale.lost);
        this.emit(kid, 'fail', `${head} → REPELLED. ${r.survivors} retreat (${c.combat.retreat * 100}%); defenders keep ${r.defenderRemaining} of ${g}.`);
        this.raiseDead(kid, defender, from, target, r);
      }
    }

    // Counter-attacking kingdoms remember who last attacked them, and give up a counter-attack after losing a fight.
    noteAttack(attKid, defKid, won) {
      const dk = defKid && this.K[defKid], ak = this.K[attKid];
      if (dk && this.personality(dk).counter && !(dk.counter && dk.counter.kid === attKid)) {
        dk.counter = { kid: attKid, since: this.round };
        dk.stats.counters = (dk.stats.counters || 0) + 1;
        this.emit(defKid, 'react', `${dk.name} were attacked by ${ak.name} and will strike back towards their capital.`);
      }
      if (!won && ak.counter) this.endCounter(attKid, 'a fight was lost');
    }
    endCounter(kid, why) {
      const k = this.K[kid];
      if (!k.counter) return;
      k.counter = null;
      k.falling = true;
      this.emit(kid, 'react', `${k.name} end the counter-attack (${why}) and fall back to their home nodes.`);
    }

    // A kingdom with raiseDead (the Undead) gains that share of the battle's total casualties, on either side of the fight.
    // Raised troops join the attacker's surviving force, or the defending garrison; if the defender lost the node they
    // rise at its strongest adjacent node, else the capital. Garrison caps still apply, with any overflow going to the capital.
    raiseDead(attKid, defKid, from, target, r) {
      const total = r.attackerLosses + r.defenderLosses;
      for (const kid of [attKid, defKid]) {
        const k = kid && this.K[kid];
        if (!k || !(k.raiseDead > 0)) continue;
        const raised = Math.floor(total * k.raiseDead);
        if (raised <= 0) continue;
        let at;
        if (kid === attKid) at = target.owner === kid ? target : from;
        else if (target.owner === kid) at = target;
        else {
          const near = this.adj[target.id].map((id) => this.N[id]).filter((n) => n.owner === kid);
          at = near.length ? near.reduce((a, b) => (b.garrison > a.garrison ? b : a)) : this.capital(kid);
        }
        const cap = this.capital(kid);
        const fit = Math.max(0, Math.min(raised, this.garrisonCap(at) - at.garrison));
        at.garrison += fit;
        if (raised > fit && cap) cap.garrison += raised - fit;
        k.stats.raised = (k.stats.raised || 0) + raised;
        this.emit(kid, 'react', `Raise dead: ${raised} of the ${total} fallen rise at ${fit ? at.name : cap.name}${raised > fit && fit ? ` (${raised - fit} at ${cap.name})` : ''}.`);
      }
    }

    raidSteal(target, resource, survivors) {
      const c = this.cfg.raid;
      if (target.type === 'outpost' || !target.owner) return { amount: 0, cap: 0, key: null };
      // A dragon's hoard can only be raided from the capital that keeps it, at the rate gold is taken.
      if (resource === 'hoard' && !(target.type === 'capital' && this.hoards(this.K[target.owner]))) return { amount: 0, cap: 0, key: null };
      const base = this.baseOutput(target);
      const map = {
        food: { per: c.foodPer, out: base.food, key: 'food' },
        gold: { per: c.goldPer, out: base.gold, key: 'gold' },
        res: { per: c.prodPer, out: base.prod, key: 'res' },
        hoard: { per: c.goldPer, out: base.gold, key: 'hoard' },
      }[resource];
      if (!map) throw new Error('Pick food, gold or resources to raid for.');
      const cap = c.maxTurns > 0 ? Math.max(0, map.out * c.maxTurns) : Infinity;
      const stock = Math.max(0, this.K[target.owner][map.key]);
      const amount = Math.max(0, Math.min(Math.floor(survivors * map.per), cap, stock));
      return { amount, cap, key: map.key, stock };
    }

    do_raid(kid, a) {
      const c = this.cfg, k = this.K[kid];
      const { from, target, troops } = this.attackChecks(kid, a, 'raid');
      this.watchtowerWarning(target);
      const defender = target.owner, dk = this.K[defender], fd = this.finalDefense(target), dp = this.defPower(target), g = target.garrison;
      const dice = rollDice(c.combat.dice, this.rand);
      const r = resolveRaid(c, troops, g, dp, this.strength(kid), dice);
      k.stats.raidsTried++;
      if (dice.attackRoll && r.win !== resolveRaid(c, troops, g, dp, this.strength(kid)).win) k.stats.upsets++;
      from.garrison -= troops;
      this.hitMorale(target, c.morale.attacked);
      target.attackedRound = this.round;
      k.stats.battleLosses += r.attackerLosses; k.stats.kills += r.defenderLosses;
      dk.stats.battleLosses += r.defenderLosses;
      this.noteAttack(kid, defender, r.win);
      const head = `Raid (${a.resource}): ${troops} from ${from.name} vs ${target.name} [${dk.name}, ${g} garrison, defense ${r2(fd)}, power ${r2(dp)}]${rollText(dice, r)}`;
      if (r.win) {
        k.stats.raidsWon++;
        target.garrison = r.defenderRemaining;
        from.garrison += r.survivors;
        this.hitMorale(target, c.morale.lost);
        const s = this.raidSteal(target, a.resource, r.survivors);
        if (s.amount > 0) {
          // A stolen hoard is just gold to the thief.
          const into = s.key === 'hoard' ? 'gold' : s.key;
          dk[s.key] = r2(dk[s.key] - s.amount); k[into] = r2(k[into] + s.amount);
          k.stats.stolen[s.key] += s.amount;
          if (s.key === 'hoard') { dk.stats.hoardLost += s.amount; this.updateBrood(dk); }
        }
        const dev = Math.min(c.raid.devMax, c.raid.devPer10 * Math.floor(r.survivors / 10));
        target.devastation = r2(Math.min(1, target.devastation + dev));
        this.raiseDead(kid, defender, from, target, r);
        this.emit(kid, 'raid', `${head} → SUCCESS. ${r.survivors} return, defenders lose ${r.defenderLosses}. Stole ${s.amount} ${a.resource}${s.key ? (s.cap === Infinity ? '' : ` (cap ${s.cap})`) : ' (outposts yield nothing)'}; devastation +${dev}.`);
      } else {
        target.garrison = r.defenderRemaining;
        from.garrison += r.survivors;
        this.hitMorale(from, c.morale.lost);
        this.emit(kid, 'fail', `${head} → REPELLED. ${r.survivors} retreat; defenders keep ${r.defenderRemaining} of ${g}.`);
        this.raiseDead(kid, defender, from, target, r);
      }
    }

    do_hire(kid, a) {
      const c = this.cfg.hire, k = this.K[kid];
      const b = this.int(a.batches, 'Batches');
      if (b > c.maxBatches) throw new Error(`At most ${c.maxBatches} batches per hire.`);
      const node = this.own(kid, a.node, 'where to place the troops');
      if (!['capital', 'town', 'castle'].includes(node.type)) throw new Error('Troops can only be placed in the capital, a town or a castle.');
      if (!this.connectedToCapital(kid, node.id)) throw new Error(`${node.name} has no friendly path to the capital; troops are cut off.`);
      const w = b * c.gold, rs = b * c.res, paid = this.goldToPay(k, 0, w, rs);
      if (paid < 0) throw new Error(`Hiring ${b} batch(es) costs ${w} gold and ${rs} resources.`);
      const gm = this.growthMult(kid), soldiers = Math.floor(b * c.batchSize * gm);
      this.pay(k, 0, w, rs);
      node.garrison += soldiers;
      k.stats.hired += soldiers;
      this.emit(kid, 'action', `Hire: ${b} batch(es) for ${w} gold + ${rs} resources${wildcardNote(paid - w)} × growth ${gm} = ${soldiers} soldiers at ${node.name}.`);
    }

    // The gold it takes k to pay food, gold and res, with gold standing in for any food or resources it lacks at the
    // wildcard's rate (goldWildcard); -1 if it can't pay.
    goldToPay(k, food, gold, res) {
      const w = this.cfg.goldWildcard;
      const shortFood = Math.max(0, food - k.food), shortRes = Math.max(0, res - k.res);
      if ((shortFood > 0 && !(w && w.food > 0)) || (shortRes > 0 && !(w && w.res > 0))) return -1;
      const total = gold + (shortFood > 0 ? shortFood * w.food : 0) + (shortRes > 0 ? shortRes * w.res : 0);
      return total <= k.gold ? total : -1;
    }
    // Pays a cost goldToPay says the kingdom can: what it has of the food and resources, and gold for the rest.
    pay(k, food, gold, res) {
      const total = this.goldToPay(k, food, gold, res), f = Math.min(food, k.food), r = Math.min(res, k.res);
      k.food = r2(k.food - f); k.gold = r2(k.gold - total); k.res = r2(k.res - r);
    }

    // Pays a share of the nodes' per-turn output; imported food does not count.
    canProduce(kid) {
      const cd = this.cfg.produce.cooldown || 0, last = this.K[kid].lastProduced;
      return cd <= 0 || last == null || this.round - last >= cd;
    }
    do_produce(kid) {
      const k = this.K[kid];
      if (!this.canProduce(kid)) throw new Error(`Produce resources can be used once every ${this.cfg.produce.cooldown} turns; next on turn ${k.lastProduced + this.cfg.produce.cooldown}.`);
      k.lastProduced = this.round;
      const inc = this.income(kid, true), m = this.cfg.produce.payout;
      const f = Math.floor(Math.max(0, inc.food) * m), w = Math.floor(inc.gold * m), p = Math.floor(inc.prod * m);
      k.food = r2(k.food + f); k.gold = r2(k.gold + w); k.res = r2(k.res + p);
      this.emit(kid, 'action', `Produce resources (${m * 100}% of node output): +${f} food, +${w} gold, +${p} resources.`);
    }

    // Supply contract: pay up front, receive food each production phase for the contract's duration.
    do_import_food(kid, a) {
      const c = this.cfg.importFood, k = this.K[kid];
      if (!c.enabled) throw new Error('Food imports are turned off in the rules.');
      const food = this.int(a.food, 'Food per turn');
      if (food > c.maxPerAction) throw new Error(`At most ${c.maxPerAction} food per turn per contract.`);
      const cost = food * c.duration * c.goldPerFood;
      if (k.gold < cost) throw new Error(`Importing ${food} food per turn for ${c.duration} turns costs ${cost} gold.`);
      k.gold = r2(k.gold - cost);
      k.imports.push({ food, turnsLeft: c.duration });
      k.stats.imported = (k.stats.imported || 0) + food * c.duration;
      this.emit(kid, 'action', `Import food: ${cost} gold for +${food} food per turn over the next ${c.duration} turns.`);
    }

    do_fortify(kid, a) {
      const c = this.cfg.fortify, k = this.K[kid];
      const n = this.own(kid, a.node, 'a node to fortify');
      if (n.upgrade) throw new Error(`${n.name} is upgrading and cannot gain stats.`);
      const steps = this.int(a.steps || 1, 'Steps');
      if (steps > c.maxSteps) throw new Error(`At most ${c.maxSteps} steps (${c.maxSteps * c.cost} resources).`);
      const cost = steps * c.cost;
      if (k.res < cost) throw new Error(`Fortifying ${steps} step(s) costs ${cost} resources.`);
      k.res = r2(k.res - cost);
      const before = n.defense;
      n.defense = Math.min(this.defCap(n), n.defense + steps);
      n.devastation = r2(Math.max(0, n.devastation - steps * c.devPerStep));
      this.emit(kid, 'action', `Fortify ${n.name}: ${cost} resources, defense ${before}→${n.defense} (cap ${this.defCap(n)}), devastation now ${n.devastation}.`);
    }

    do_upgrade(kid, a) {
      const k = this.K[kid];
      const n = this.own(kid, a.node, 'a node to upgrade');
      if (n.type === 'capital') throw new Error('Capitals are always Tier 5.');
      if (n.upgrade) throw new Error(`${n.name} is already upgrading.`);
      if (n.tier >= MAX_TIER[n.type]) throw new Error(`${n.name} is already at max tier.`);
      const cost = this.upgradeCost(n);
      const paid = this.goldToPay(k, cost.food, cost.gold, cost.res);
      if (paid < 0) {
        throw new Error(`Upgrading to T${cost.newTier} costs ${cost.food} food, ${cost.gold} gold, ${cost.res} resources.`);
      }
      this.pay(k, cost.food, cost.gold, cost.res);
      n.upgrade = { target: cost.newTier, turnsLeft: cost.turns };
      this.emit(kid, 'action', `${n.tier === 0 ? 'Rebuild' : 'Upgrade'} ${n.name} to T${cost.newTier} (${(TIER_NAMES[n.type] || [])[cost.newTier]}): ${cost.food} food, ${cost.gold} gold, ${cost.res} resources${wildcardNote(paid - cost.gold)}, ${cost.turns} turns.`);
    }

    do_train(kid) {
      const k = this.K[kid], cost = this.trainCost(k);
      if (cost < 0) throw new Error("Troops can't be trained any further.");
      if (k.gold < cost) throw new Error(`Training costs ${cost} gold.`);
      k.gold = r2(k.gold - cost);
      k.trained++;
      this.emit(kid, 'action', `Train troops for ${cost} gold: troop strength now ×${r2(this.strength(kid))}.`);
    }

    do_repair(kid, a) {
      const k = this.K[kid];
      const n = this.own(kid, a.node, 'a node to repair');
      if (n.upgrade) throw new Error(`${n.name} is upgrading and cannot gain stats.`);
      if (n.devastation <= 0) throw new Error(`${n.name} has no devastation.`);
      const cost = Math.ceil(n.devastation * this.cfg.repairCost);
      if (k.res < cost) throw new Error(`Repairing costs ${cost} resources.`);
      k.res = r2(k.res - cost);
      this.emit(kid, 'action', `Repair ${n.name}: devastation ${n.devastation}→0 for ${cost} resources.`);
      n.devastation = 0;
    }

    // ---- AI ----
    // Fields left out take PERSONALITY_DEFAULTS. With scaled dice, minWin (and raidMinWin) replace the margins with the
    // ones that win that often: the faction's risk appetite.
    personality(k) {
      // While the brood is awake, the kingdom plays as the brood's personality.
      const name = k.awake ? this.cfg.brood.personality : k.ai, key = k.id + '/' + name;
      if (this.pcache[key]) return this.pcache[key];
      const ps = this.cfg.ai.personalities;
      const P = Object.assign({}, PERSONALITY_DEFAULTS, ps[name] || ps.balanced);
      const d = this.cfg.combat.dice;
      const m = P.minWin > 0 ? marginFor(d, P.minWin) : 0;
      if (m > 0) P.margin = m;
      const rc = P.raidMinWin > 0 ? P.raidMinWin : P.minWin;
      const rm = rc > 0 ? marginFor(d, rc) : 0;
      if (rm > 0) P.raidMargin = rm;
      return (this.pcache[key] = P);
    }
    guardRules() { const g = this.cfg.ai.guard; return g && g.enabled ? g : null; }
    // The threat a node keeps a reserve against: the biggest enemy garrison next to it and, with the guard on, a share
    // of the biggest within guard.reach links, which can take the empty land between and strike next.
    threat(n, kid) {
      let t = this.maxAdjThreat(n, kid);
      const g = this.guardRules();
      if (!g || g.reach < 2) return t;
      const seen = new Set([n.id]);
      let frontier = [n.id];
      for (let d = 1; d <= g.reach; d++) {
        const next = [];
        for (const id of frontier) for (const nb of this.adj[id]) {
          if (seen.has(nb)) continue;
          seen.add(nb); next.push(nb);
          const o = this.N[nb];
          if (d >= 2 && o.owner && o.owner !== kid) t = Math.max(t, o.garrison * g.reachShare);
        }
        frontier = next;
      }
      return t;
    }
    // With the guard on, the troops a node next to land it doesn't hold always keeps; 0 otherwise, and for capitals.
    guard(n, kid, P) {
      const g = this.guardRules();
      if (!g || n.type === 'capital') return 0;
      return this.adj[n.id].some((id) => this.N[id].owner !== kid) ? Math.ceil(g.min * P.guardMult) : 0;
    }
    maxAdjThreat(n, kid) {
      let m = 0;
      for (const id of this.adj[n.id]) { const o = this.N[id]; if (o.owner && o.owner !== kid) m = Math.max(m, o.garrison); }
      return m;
    }

    aiBuff(kid) {
      const k = this.K[kid], P = this.personality(k);
      if (k.buffTurns > 0) return;
      const cost = this.buffCost();
      if (cost <= 0 || k.gold < cost) return;
      const army = this.army(kid), desired = this.desiredArmy(kid, P);
      const after = k.gold - cost, hireW = this.cfg.hire.gold * this.cfg.hire.maxBatches;
      const inc = this.income(kid), up = this.upkeepFor(army + 20, kid);
      if (desired - army >= 20 / P.mil && after >= hireW * 2 && inc.food - up.food >= 0 && k.res >= this.cfg.hire.res * 4) this.buyBuff();
    }

    // The army the kingdom wants: armyRatio times the enemy troops next to its land, and at least minArmy. With the
    // army target off (ai.armyTarget), there's no limit: food and gold decide.
    desiredArmy(kid, P) {
      const target = this.cfg.ai.armyTarget;
      if (target && !target.enabled) return Infinity;
      let enemy = 0;
      const seen = new Set();
      for (const n of this.ownedNodes(kid)) for (const id of this.adj[n.id]) {
        const o = this.N[id];
        if (o.owner && o.owner !== kid && !seen.has(id)) { seen.add(id); enemy += o.garrison; }
      }
      return Math.max(P.minArmy, P.armyRatio * enemy);
    }

    // Nodes within `radius` steps of the capital, walking over any node.
    homeArea(kid, radius) {
      const cap = this.capital(kid), out = new Set();
      if (!cap || !radius) return out;
      let frontier = [cap.id]; out.add(cap.id);
      for (let d = 0; d < radius; d++) {
        const next = [];
        for (const id of frontier) for (const nb of this.adj[id]) if (!out.has(nb)) { out.add(nb); next.push(nb); }
        frontier = next;
      }
      return out;
    }

    // Elemental burst cycle: mass in the capital, strike player land, fall back when the army runs thin.
    aiBurst(kid, P) {
      const k = this.K[kid], cap = this.capital(kid), str = this.strength(kid);
      const pairs = [];
      for (const s of this.ownedNodes(kid)) for (const id of this.adj[s.id]) {
        const t = this.N[id];
        if (t.owner && this.K[t.owner].player && t.type !== 'capital') pairs.push({ s, t, dp: this.defPower(t) });
      }
      if (!pairs.length || !cap) return null;
      const keep = (n) => (n === cap ? Math.min(n.garrison, P.capitalReserve) : 0);
      if (k.mode !== 'attack') {
        if (k.cooldownUntil && this.round < k.cooldownUntil) return null;
        const weakest = pairs.reduce((a, b) => (b.dp < a.dp ? b : a));
        const ready = cap.garrison - keep(cap);
        if (ready <= 0 || ready * str < P.burstRatio * weakest.dp) return null;
        k.mode = 'attack';
        this.emit(kid, 'react', `${ready} troops massed at ${cap.name}, ${P.burstRatio}× the strength of ${weakest.t.name}: the offensive begins.`);
      }
      const army = this.ownedNodes(kid).reduce((a, b) => (b.garrison - keep(b) > a.garrison - keep(a) ? b : a));
      const troops = army.garrison - keep(army);
      const here = pairs.filter((p) => p.s === army && troops * str > p.dp * P.margin).sort((a, b) => a.dp - b.dp);
      if (troops > 0 && here.length) return { type: 'capture', from: army.id, target: here[0].t.id, troops };
      const reach = pairs.filter((p) => p.s !== army && this.garrisonCap(p.s) - p.s.garrison >= troops && (troops + p.s.garrison) * str > p.dp * P.margin)
        .sort((a, b) => a.dp - b.dp);
      if (troops > 0 && reach.length) return { type: 'send', from: army.id, to: reach[0].s.id, amount: troops };
      k.mode = 'gather';
      k.cooldownUntil = this.round + 3;
      this.emit(kid, 'react', `The offensive is spent: ${Math.max(0, troops)} troops can't beat the next player node. Falling back to ${cap.name}.`);
      if (army !== cap && troops > 0) return { type: 'send', from: army.id, to: cap.id, amount: troops };
      return null;
    }

    // Steps from every node to `goal`, walking over any node.
    distTo(goal) {
      const d = { [goal]: 0 };
      let frontier = [goal];
      while (frontier.length) {
        const next = [];
        for (const id of frontier) for (const nb of this.adj[id]) if (d[nb] == null) { d[nb] = d[id] + 1; next.push(nb); }
        frontier = next;
      }
      return d;
    }

    // Counter-attack: push the main army towards the aggressor's capital, capturing what's in the way, then raid the
    // capital. Ends after a lost fight (noteAttack) or when the next fight would likely be lost.
    aiCounter(kid, P) {
      const k = this.K[kid], cap = this.capital(kid), str = this.strength(kid);
      if (k.falling) {
        // Bring the field army back to the capital.
        k.falling = false;
        const out = this.ownedNodes(kid).filter((n) => n !== cap && n.home !== kid && n.garrison > 2);
        if (out.length && cap) {
          const d = out.reduce((a, b) => (b.garrison > a.garrison ? b : a));
          return { type: 'send', from: d.id, to: cap.id, amount: d.garrison - 2 };
        }
        return null;
      }
      const ec = k.counter && this.capital(k.counter.kid);
      if (!ec) { k.counter = null; return null; }
      const dist = this.distTo(ec.id);
      const keep = (n) => (n === cap ? Math.min(n.garrison, P.capitalReserve) : 0);
      const owned = this.ownedNodes(kid);
      const army = owned.reduce((a, b) => (b.garrison - keep(b) > a.garrison - keep(a) ? b : a));
      const troops = army.garrison - keep(army);
      if (troops < 5) { this.endCounter(kid, 'no army left'); return null; }
      // Raid the capital once adjacent.
      if (this.adj[army.id].includes(ec.id)) {
        if (troops * str > this.defPower(ec) * P.raidMargin) return { type: 'raid', from: army.id, target: ec.id, troops, resource: 'gold' };
        this.endCounter(kid, `${ec.name} is too strong to raid`);
        return null;
      }
      // Capture the weakest beatable node one step closer to the capital.
      const steps = this.adj[army.id].map((id) => this.N[id])
        .filter((t) => t.owner !== kid && t.type !== 'capital' && dist[t.id] < dist[army.id] && troops * str > this.defPower(t) * P.margin)
        .sort((a, b) => (b.owner === k.counter.kid) - (a.owner === k.counter.kid) || this.defPower(a) - this.defPower(b));
      if (steps.length) return { type: 'capture', from: army.id, target: steps[0].id, troops };
      // Or move the army to an owned node that's closer and can hold it.
      const hops = owned.filter((n) => n !== army && dist[n.id] < dist[army.id] && this.garrisonCap(n) - n.garrison >= troops)
        .sort((a, b) => dist[a.id] - dist[b.id]);
      if (hops.length) return { type: 'send', from: army.id, to: hops[0].id, amount: troops };
      // Patient factions keep the grudge and build up at home for a few rounds instead of giving up.
      if (k.counterPatience && this.round - k.counter.since < k.counterPatience && (army === cap || army.home === kid)) return null;
      this.endCounter(kid, 'the next fight looks lost');
      return null;
    }

    aiChoose(kid) {
      const c = this.cfg, ai = c.ai, k = this.K[kid], P = this.personality(k);
      if (P.burst) { const b = this.aiBurst(kid, P); if (b) return b; }
      if (P.counter && (k.counter || k.falling)) { const b = this.aiCounter(kid, P); if (b) return b; }
      const H = this.maxRounds ? Math.max(1, Math.min(ai.horizon, this.maxRounds - this.round + 1)) : ai.horizon;
      const owned = this.ownedNodes(kid);
      const cap = this.capital(kid), str = this.strength(kid);
      const inc = this.income(kid), army = this.army(kid), up = this.upkeepFor(army, kid);
      // Vfull values long-term output; V values stockpile changes, worth less the bigger the hoard already is.
      const sat = (stock, gross) => 1 / (1 + Math.max(0, stock) / (6 * Math.max(gross, 1)));
      const wf = ai.foodValue * sat(k.food, inc.food), ww = sat(k.gold, inc.gold), wr = ai.resValue * sat(k.res, inc.prod);
      const pr = (o) => (o.prod != null ? o.prod : o.res || 0);
      const Vfull = (o) => o.food * ai.foodValue + (o.gold || 0) + pr(o) * ai.resValue;
      const V = (o) => o.food * wf + (o.gold || 0) * ww + pr(o) * wr;
      const netFood = inc.food - up.food, netGold = inc.gold - up.gold;
      const onlyCap = this.onlyCapital(kid);
      const SV = ai.soldierValue;
      const cands = [];
      const add = (score, action) => { if (isFinite(score)) cands.push({ score: score * (1 + (this.rand() - 0.5) * ai.noise), action }); };
      const reserve = (n) => {
        const t = this.threat(n, kid);
        let r = t ? Math.ceil(Math.min(n.garrison, t * 0.6 * P.def * P.reserveMult)) : 0;
        if (n.type === 'capital') r = Math.max(r, Math.min(n.garrison, P.capitalReserve));
        if (this.garrisonCap(n) === Infinity) r = Math.max(r, Math.min(n.garrison, P.leaveBehind));
        return Math.max(r, Math.min(n.garrison, this.guard(n, kid, P)));
      };
      const importance = (n) => (n.type === 'capital' ? 1.5 : 1 + Vfull(this.baseOutput(n)) / 600);
      const heroic = onlyCap, hm = (heroic ? c.combat.heroicMult : 1) * str;
      const home = this.homeArea(kid, P.homeRadius);
      const recent = (round, span) => round != null && round >= this.round - span;
      // Behaviour: which targets this kingdom is willing to attack at all.
      const allowTarget = (t, power, dp) => {
        const owner = t.owner ? this.K[t.owner] : null;
        if (P.targets === 'playerOnly') return !!(owner && owner.player);
        if (P.targets === 'home') return t.home === kid;
        if (P.targets === 'raze') return t.home === kid || !!(owner && (k.foes || []).includes(t.owner));
        if (P.targets === 'avoidMonsters' && owner && !owner.player) {
          return power >= dp * 2 || t.morale <= 5 || t.devastation >= 0.3;
        }
        return true;
      };
      const captureGain = (t) => {
        if (P.raze && t.home !== kid) return 200 + 400 * t.tier;
        const afterTier = Math.max(c.freeRebuildOnCapture ? 1 : 0, t.tier - (c.combat.captureTierLoss !== false || k.razes ? 1 : 0));
        // Territory is worth something on its own; castles also for defense and (at T3) an extra action.
        const base = (300 + (t.owner ? 150 : 0)) * P.landValue + (t.type === 'castle' ? 250 + 200 * afterTier : 0);
        let g = afterTier === 0
          ? base + Math.max(0, Vfull(this.baseOutput(t, 1))) * Math.max(0, H - 3) * 0.3
          : base + Math.max(0, Vfull(this.baseOutput(t, afterTier)) * 0.7) * H * 0.6;
        if (k.lost[t.id] != null) g *= P.retake;
        if (P.homeRadius) g *= home.has(t.id) ? P.homeBias : 0.7;
        return g;
      };
      // Behaviour: new conquests that still need securing before expanding again.
      const unsecured = P.secureNew ? owned.filter((n) => recent(n.capturedRound, 8) && n.type !== 'capital' &&
        (n.defense < this.defCap(n) || this.defPower(n) < this.maxAdjThreat(n, kid) * 1.2)) : [];
      let expandMult = unsecured.length ? 0.35 : 1;
      // Behaviour: methodical factions build their land up to developTier before expanding further.
      const undeveloped = new Set();
      if (P.developTier > 0) {
        for (const n of owned) {
          if (n.type !== 'capital' && !n.upgrade && n.tier < Math.min(P.developTier, MAX_TIER[n.type]) && !(P.raze && n.home !== kid)) {
            const cost = this.upgradeCost(n);
            if (this.goldToPay(k, cost.food, cost.gold, cost.res) >= 0) undeveloped.add(n);
          }
        }
        if (undeveloped.size) expandMult *= P.developExpand;
      }
      const attacks = !P.burst;
      const dice = diceOn(c.combat.dice);
      // War-only kingdoms (the Orcs) take fights whatever the odds, unless they have a risk appetite to hold them to.
      const anyFight = !!P.warOnly && !(dice && P.minWin > 0);

      // Captures and raids
      if (attacks) for (const from of owned) {
        const avail = from.garrison - reserve(from);
        if (avail < 1) continue;
        for (const tid of this.adj[from.id]) {
          const t = this.N[tid];
          if (t.owner === kid) continue;
          const dp = this.defPower(t);
          if (!allowTarget(t, avail * hm, dp)) continue;
          if (t.type !== 'capital' && (anyFight || avail * hm > dp * P.margin)) {
            const capture = { type: heroic ? 'heroic' : 'capture', from: from.id, target: tid, troops: avail };
            if (dice) {
              // With dice, the land is only won some of the time; the losses are what each side expects to lose.
              const o = odds(c, false, avail, t.garrison, dp, heroic, str);
              const raised = (o.attackerLosses + o.defenderLosses) * (k.raiseDead || 0);
              add(expandMult * P.aggr * (o.win * captureGain(t) - (o.attackerLosses - raised) * SV + o.defenderLosses * SV * 0.3), capture);
            } else {
              const r = resolveCapture(c, avail, t.garrison, dp, heroic, str);
              const raised = Math.floor((r.attackerLosses + r.defenderLosses) * (k.raiseDead || 0));
              add(expandMult * P.aggr * (captureGain(t) - (r.attackerLosses - raised) * SV + r.defenderLosses * SV * 0.3), capture);
            }
          }
          const rm = P.raidMargin || P.margin;
          if (P.raid > 0 && t.owner && (anyFight || avail * str > dp * rm)) {
            // Loot and devastation come from the troops left after a win, which the dice make a chance.
            let lootTroops, chance, aL, dL, raised;
            if (dice) {
              const o = odds(c, true, avail, t.garrison, dp, false, str);
              lootTroops = Math.round(o.winSurvivors); chance = o.win; aL = o.attackerLosses; dL = o.defenderLosses;
              raised = (aL + dL) * (k.raiseDead || 0);
            } else {
              const r = resolveRaid(c, avail, t.garrison, dp, str);
              lootTroops = r.survivors; chance = 1; aL = r.attackerLosses; dL = r.defenderLosses;
              raised = Math.floor((aL + dL) * (k.raiseDead || 0));
            }
            let best = null;
            // A dragon's hoard can be raided from the capital that keeps it.
            const hoard = t.type === 'capital' && this.hoards(this.K[t.owner]) && this.K[t.owner].hoard > 0;
            for (const res of hoard ? ['food', 'gold', 'res', 'hoard'] : ['food', 'gold', 'res']) {
              const s = this.raidSteal(t, res, lootTroops);
              const gold = s.key === 'gold' || s.key === 'hoard';
              let val = Vfull({ food: s.key === 'food' ? s.amount : 0, gold: gold ? s.amount : 0, prod: s.key === 'res' ? s.amount : 0 });
              if (res === 'gold') val *= P.goldRaidBias;
              if (res === 'hoard') val *= P.hoardRaidBias; // taking it also keeps the brood asleep
              if (!best || val > best.val) best = { res, val };
            }
            const dev = Math.min(c.raid.devMax, c.raid.devPer10 * Math.floor(lootTroops / 10));
            const harm = Vfull(this.baseOutput(t)) * dev * 4 * 0.3;
            const loot = chance * best.val;
            const score = P.raid * (loot + dL * SV * 0.7 + chance * harm - (aL - raised) * SV);
            if (loot > 0 || dL > 0) add(score, { type: 'raid', from: from.id, target: tid, troops: avail, resource: best.res });
          }
        }
      }

      // Targets no single garrison can take yet: pick the best one to mass troops next to.
      // Resource nodes and outposts cap their garrison, so only stages that can hold enough troops count.
      let blocked = null;
      if (attacks) for (const t of this.nodes) {
        if (t.owner === kid || t.type === 'capital') continue;
        const stages = this.adj[t.id].map((id) => this.N[id]).filter((n) => n.owner === kid);
        if (!stages.length) continue;
        const dp = this.defPower(t);
        const R = Math.floor(dp * P.margin / hm) + 1;
        if (!allowTarget(t, R * hm, dp)) continue;
        const gain = captureGain(t) * expandMult;
        for (const s of stages) {
          if (this.garrisonCap(s) < R) continue;
          const need = R - (s.garrison - reserve(s));
          if (need <= 0) continue;
          const score = gain - need * SV;
          if (!blocked || score > blocked.score) blocked = { gain, score, target: t.id, from: s.id, need };
        }
      }

      // Hire
      let desired = this.desiredArmy(kid, P) + (blocked ? blocked.need : 0);
      // Burst factions want enough troops in the capital to reach the attack threshold.
      if (P.burst && cap && k.mode !== 'attack') {
        const playerDp = this.nodes.filter((t) => t.owner && this.K[t.owner].player && t.type !== 'capital' &&
          this.adj[t.id].some((id) => this.N[id].owner === kid)).map((t) => this.defPower(t));
        if (playerDp.length) {
          const need = Math.ceil(P.burstRatio * Math.min(...playerDp) / str) + P.capitalReserve - cap.garrison;
          desired = Math.max(desired, army + need);
        }
      }
      const gm = this.growthMult(kid);
      const locs = this.hireLocations(kid);
      if (locs.length) {
        for (let b = c.hire.maxBatches; b >= 1; b--) {
          // The gold it costs, with gold standing in for missing resources if the rules allow.
          const W = this.goldToPay(k, 0, b * c.hire.gold, b * c.hire.res);
          if (W < 0) continue;
          const soldiers = Math.floor(b * c.hire.batchSize * gm);
          if (soldiers < 1) continue;
          const nu = this.upkeepFor(army + soldiers, kid);
          const nf = inc.food - nu.food, nw = inc.gold - nu.gold;
          let loc = blocked ? locs.find((n) => n.id === blocked.from) : null;
          const progress = loc ? Math.min(1, soldiers / blocked.need) : 0;
          // Goblin exception: may dip into negative food if the hire lets them take a node.
          if (nf < 0 && !(k.goblinRule && progress >= 1)) continue;
          if (nw < 0 && k.gold - W < -nw * 4) continue;
          if (!loc && P.burst && cap && locs.includes(cap)) loc = cap;
          if (!loc) {
            loc = locs.reduce((a2, n) => {
              const d = this.maxAdjThreat(n, kid) - this.defPower(n), da = this.maxAdjThreat(a2, kid) - this.defPower(a2);
              return d > da ? n : a2;
            });
          }
          const deficit = Math.max(0, desired - army);
          const score = P.mil * (Math.min(soldiers, deficit) * SV * 2.5 + soldiers * 5) + P.aggr * (blocked ? blocked.gain * 0.5 * progress : 0) - W * ww * 0.5;
          add(score, { type: 'hire', batches: b, node: loc.id });
        }
      }

      // Defensive moves
      let rbScore = 0;
      // Troops a node needs so its defender power is 1.3× the biggest adjacent enemy garrison.
      const needG = (n) => {
        const T = this.maxAdjThreat(n, kid);
        if (!T) return Math.max(n.type === 'capital' ? P.capitalReserve : 0, this.guard(n, kid, P));
        const fd = this.finalDefense(n);
        return Math.max(0, Math.ceil((T * 1.3 - fd) / (str * (1 + fd / c.combat.defDivisor))));
      };
      for (const n of owned) {
        const T = this.maxAdjThreat(n, kid);
        const dp = this.defPower(n);
        // Behaviour multipliers: nodes recently attacked, freshly taken, or inside the home area.
        let focus = 1;
        if (recent(n.attackedRound, 2)) focus *= P.fortifyAttacked;
        if (unsecured.includes(n)) focus *= 2;
        if (home.has(n.id)) focus *= P.homeBias;
        const imp = importance(n) * focus;
        const isBorder = this.adj[n.id].some((id) => this.isHostile(this.N[id], kid));
        const defValue = (newBase) => {
          const gain = this.defPower(n, null, newBase) - dp;
          if (gain <= 0) return 0;
          const danger = T > dp * 0.8 ? Math.min(gain, Math.max(0, T * 1.3 - dp)) : 0;
          return danger * SV * imp + gain * 4 * focus;
        };
        if (isBorder && !n.upgrade) {
          rbScore += defValue(n.defense + c.reinforceBorder.def);
          rbScore += V(this.baseOutput(n)) * Math.min(n.devastation, c.reinforceBorder.dev) * Math.min(H, 8);
        }
        if (!n.upgrade && n.defense < this.defCap(n)) {
          const steps = Math.min(c.fortify.maxSteps, this.defCap(n) - n.defense, Math.floor(k.res / c.fortify.cost));
          if (steps >= 1) {
            let val = defValue(n.defense + steps) + V(this.baseOutput(n)) * Math.min(n.devastation, steps * c.fortify.devPerStep) * Math.min(H, 8);
            if (focus > 1) val += steps * 12 * focus; // fortify these even before an enemy shows up
            add(P.def * val - steps * c.fortify.cost * ai.resValue, { type: 'fortify', node: n.id, steps });
          }
        }
        const deficit = T * 1.25 - dp;
        if (T > 0 && deficit > 0) {
          const space = this.garrisonCap(n) - n.garrison;
          const donors = owned.filter((d) => d !== n && this.maxAdjThreat(d, kid) === 0 && d.garrison > 0);
          if (donors.length && space > 0) {
            const d = donors.reduce((a2, b2) => (b2.garrison > a2.garrison ? b2 : a2));
            const amt = Math.min(d.garrison - reserve(d), Math.ceil(deficit), space);
            if (amt > 0) add(P.def * amt * SV * 0.9 * imp, { type: 'send', from: d.id, to: n.id, amount: amt });
          }
        }
        // Behaviour: spread troops so each node holds what its threats call for, taking from any node with a surplus.
        if (P.rebalance) {
          const want = needG(n) - n.garrison, space = this.garrisonCap(n) - n.garrison;
          if (want > 0 && space > 0) {
            const surplus = (d) => d.garrison - needG(d) - 3;
            const donors = owned.filter((d) => d !== n && surplus(d) > 0);
            if (donors.length) {
              const d = donors.reduce((a2, b2) => (surplus(b2) > surplus(a2) ? b2 : a2));
              const amt = Math.min(surplus(d), want, space);
              add(P.def * amt * SV * 1.1 * imp, { type: 'send', from: d.id, to: n.id, amount: amt });
            }
          }
        }
        if (n.devastation >= 0.15 && !n.upgrade) {
          const cost = Math.ceil(n.devastation * c.repairCost);
          if (k.res >= cost) add(P.econ * (V(this.baseOutput(n)) * n.devastation * Math.min(H, 8) - cost * ai.resValue), { type: 'repair', node: n.id });
        }
      }
      if (rbScore > 0) add(P.def * rbScore, { type: 'reinforce_border' });

      // Offensive staging: gather troops next to a target no single garrison can take.
      if (blocked && !onlyCap) {
        const stage = this.N[blocked.from];
        const spare = (d) => d.garrison - reserve(d) - (d.type === 'capital' ? Math.max(10, P.capitalReserve) : 0);
        const donors = owned.filter((d) => d !== stage && spare(d) > 0);
        const space = this.garrisonCap(stage) - stage.garrison;
        if (donors.length && space > 0) {
          const d = donors.reduce((a2, b2) => (spare(b2) > spare(a2) ? b2 : a2));
          const amt = Math.min(spare(d), space);
          add(P.aggr * blocked.gain * 0.6 * Math.min(1, amt / blocked.need), { type: 'send', from: d.id, to: stage.id, amount: amt });
        }
      }

      // Behaviour: razing factions bring troops left on captured foe land back to the capital.
      if (P.raze && cap) {
        for (const d of owned) {
          if (d !== cap && d.home !== kid && d.garrison > 0) add(P.mil * d.garrison * SV, { type: 'send', from: d.id, to: cap.id, amount: d.garrison });
        }
      }

      // Behaviour: burst factions pull spare troops back to the capital between offensives.
      if (P.burst && cap) {
        const spare = (d) => d.garrison - reserve(d) - 2;
        const donors = owned.filter((d) => d !== cap && spare(d) > 0);
        if (donors.length) {
          const d = donors.reduce((a2, b2) => (spare(b2) > spare(a2) ? b2 : a2));
          add(P.mil * spare(d) * SV * 0.6, { type: 'send', from: d.id, to: cap.id, amount: spare(d) });
        }
      }

      // Upgrades
      for (const n of owned) {
        if (n.upgrade || n.type === 'capital' || n.tier >= MAX_TIER[n.type]) continue;
        if (P.raze && n.home !== kid) continue;
        // Behaviour: land outside the home area is rebuilt from ruins at most, never raised further.
        if (!P.upgradeAway && P.homeRadius && !home.has(n.id) && n.tier >= 1) continue;
        const cost = this.upgradeCost(n);
        const foodBuf = Math.max(0, -netFood) * 3, wBuf = Math.max(0, -netGold) * 3;
        if (this.goldToPay(k, cost.food + foodBuf, cost.gold + wBuf, cost.res) < 0) continue;
        const eff = Math.max(0, H - cost.turns);
        let gain = (Vfull(this.baseOutput(n, cost.newTier)) - Vfull(this.baseOutput(n))) * eff;
        if (n.type === 'castle') {
          const T = this.maxAdjThreat(n, kid);
          gain += (this.defCap(n, cost.newTier) * this.defMult(n, cost.newTier) - this.defCap(n) * this.defMult(n)) * (T > 0 ? 25 : 8);
          if (cost.newTier === c.bonusActionCastleTier) gain += V(inc) * eff * 0.5;
        }
        if (n.type === 'outpost') gain += this.maxAdjThreat(n, kid) > 0 ? 150 : 20;
        if (undeveloped.has(n)) gain += P.developBonus;
        // What it spends: the food and resources it has, and gold for the rest.
        const spent = { food: Math.min(cost.food, k.food), gold: this.goldToPay(k, cost.food, cost.gold, cost.res), prod: Math.min(cost.res, k.res) };
        add(P.econ * (gain - V(spent)), { type: 'upgrade', node: n.id });
      }

      // Import food: worth it when food, not gold, is what stops the army from growing.
      const ic = c.importFood;
      if (ic.enabled) {
        for (let f = ic.maxPerAction; f >= 1; f--) {
          const cost = f * ic.duration * ic.goldPerFood;
          if (k.gold < cost + Math.max(0, -netGold) * 3) continue;
          // Troops it wants beyond what its food income feeds: with no army target, the troops its gold could hire.
          const wanted = desired === Infinity ? Math.floor(k.gold / c.hire.gold) * c.hire.batchSize * gm : Math.max(0, desired - army);
          const room = wanted - Math.max(0, netFood) * c.hire.batchSize;
          const enabled = Math.min(f * c.hire.batchSize, Math.max(0, room));
          const relief = netFood < 0 ? Math.min(f, -netFood) * ai.foodValue * ic.duration : 0;
          add(P.mil * enabled * SV * 1.5 + relief - cost * ww, { type: 'import_food', food: f });
          break;
        }
      }

      // Train troops: a permanent strength bonus, worth that many more soldiers for each round still to play (a fifth each).
      const trainCost = this.trainCost(k);
      if (trainCost >= 0 && k.gold >= trainCost + Math.max(0, -netGold) * 3) add(P.mil * army * c.train.step * SV * H / 5 - trainCost * ww, { type: 'train' });

      // Produce resources is always available.
      if (this.canProduce(kid)) {
        const nodeInc = this.income(kid, true), pm = c.produce.payout;
        add(P.econ * V({ food: Math.max(0, nodeInc.food) * pm, gold: nodeInc.gold * pm, prod: nodeInc.prod * pm }), { type: 'produce' });
      }

      if (P.warOnly) {
        const war = cands.filter((c2) => ['capture', 'heroic', 'raid'].includes(c2.action.type) || (P.warHire && c2.action.type === 'hire'));
        // With no fight to take, it uses the action on the best of everything else: every action is used.
        if (war.length) { cands.length = 0; cands.push(...war); }
      }
      cands.sort((a2, b2) => b2.score - a2.score);
      // With only restricted slots left, pick the best action that fits them.
      if (this.cur && !(this.cur.generalLeft > 0)) {
        const ok = cands.find((c2) => this.fitsExtraSlot(c2.action.type));
        return ok ? ok.action : this.fallback(kid);
      }
      return cands.length ? cands[0].action : this.fallback(kid);
    }

    fitsExtraSlot(type) {
      if (type === 'capture' || type === 'raid' || type === 'heroic') return false;
      if (this.cfg.nonCombatActions.economyOnly && (type === 'hire' || type === 'send')) return false;
      return true;
    }
  }

  return {
    DEFAULT_CONFIG, MAPS, TIER_NAMES, MAX_TIER, TYPE_LABELS, ACTION_LABELS,
    Game, PERSONALITY_DEFAULTS, tierMult, defenderPower, resolveCapture, resolveRaid, deepMerge, clone,
    diceOn, diceOf, rollDice, odds, marginFor,
  };
});
