// Kingdom Stats rules engine. Pure JS, no DOM: runs in the browser (window.KingdomSim) and in Node (module.exports).
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.KingdomSim = api;
})(typeof self !== 'undefined' ? self : this, function () {
  'use strict';

  const DEFAULT_CONFIG = {
    town: { food: 2, wealth: 100, prod: 4, wealthMult: 1, defPerTier: 2 },
    capitalTier: 5,
    castle: [
      { food: 0, defCap: 0, mult: 1 },
      { food: -1, defCap: 10, mult: 1.5 },
      { food: -2, defCap: 15, mult: 2 },
      { food: -3, defCap: 20, mult: 2.5 },
    ],
    castleMaxDef: 50,
    foodNode: [0, 4, 6, 8],
    prodNode: [0, 20, 30, 40],
    resource: { defCap: 1, garrisonCap: 10 },
    outpost: { food: -1, defCap: 5, garrisonCap: 10, warnMove: 10 },
    upkeep: { food: 1, wealth: 100 },
    shortagePenalty: 10,
    growth: { fast: 2, normal: 1, slow: 0.5 },
    capitalOnlyGrowthMult: 2,
    buff: { costTurns: 2, duration: 5, mult: 2 },
    hire: { batchSize: 10, maxBatches: 2, wealth: 300, res: 50 },
    upgrade: { food: 5, wealth: 300, res: 100, castleRes: 150, timeBase: 1, timePerTier: 2 },
    morale: { start: 3, recover: 1, attacked: 2, lost: 2 },
    combat: { defDivisor: 20, retreat: 0.25, captureDevastation: 0.5, heroicMult: 4 },
    raid: { foodPer: 0.5, wealthPer: 10, prodPer: 1, maxTurns: 0, garrisonDmg: 0.25, devPer10: 0.05, devMax: 0.25 },
    fortify: { cost: 10, maxSteps: 3, devPerStep: 0.1 },
    reinforceBorder: { def: 1, dev: 0.1, neutralIsHostile: false },
    repairCost: 100,
    wealthTax: { rate: 0, free: 3000 },
    // A node that is a ruin (T0) once captured starts a free rebuild to T1 that costs no action and completes next turn.
    freeRebuildOnCapture: true,
    produce: { payout: 0.5 },
    importFood: { enabled: true, wealthPerFood: 50, maxPerAction: 5, duration: 5 },
    // Actions every kingdom gets each turn, before the extra one from each Tier 3 castle.
    baseActions: 2,
    bonusActionCastleTier: 3,
    // Extra actions per turn that can't be used for Capture, Raid or Heroic Conquest (0 = off).
    // economyOnly also rules out Hire and Send reinforcements for these extra actions.
    nonCombatActions: { count: 0, economyOnly: false },
    turnOrder: 'rotate',
    ai: {
      noise: 0.2, horizon: 25, foodValue: 50, resValue: 3, soldierValue: 40,
      // Missing fields fall back to PERSONALITY_DEFAULTS. Faction behaviours come from kingdom-behaviour.txt.
      personalities: {
        balanced:   { aggr: 1.0, raid: 0.8, def: 1.0, econ: 1.0, mil: 1.0, margin: 1.25, armyRatio: 1.0, minArmy: 30 },
        aggressive: { aggr: 1.6, raid: 1.1, def: 0.7, econ: 0.8, mil: 1.4, margin: 1.1, armyRatio: 1.4, minArmy: 40 },
        defensive:  { aggr: 0.5, raid: 0.5, def: 1.6, econ: 1.1, mil: 1.0, margin: 1.5, armyRatio: 1.3, minArmy: 35 },
        economic:   { aggr: 0.7, raid: 0.6, def: 0.9, econ: 1.5, mil: 0.7, margin: 1.3, armyRatio: 0.8, minArmy: 25 },
        // Expands aggressively; attacks on a toss-up but not blindly; leaves other monsters alone unless a node is weakened;
        // secures new territory before expanding again; fortifies nodes under attack and tries to retake lost ones.
        goblin:     { aggr: 1.6, raid: 0.9, def: 1.1, econ: 0.8, mil: 1.4, margin: 1.05, raidMargin: 1.1, armyRatio: 1.3, minArmy: 40,
                      targets: 'avoidMonsters', secureNew: 1, fortifyAttacked: 2, retake: 1.6 },
        // Only ever captures, raids or hires (warOnly, warHire), and will take losing fights; nothing is held back in reserve.
        orc:        { aggr: 1.8, raid: 1.0, def: 0.4, econ: 0.7, mil: 1.7, margin: 1.15, raidMargin: 1.2, armyRatio: 1.5, minArmy: 45,
                      reserveMult: 0, capitalReserve: 0, retake: 0.7, warOnly: 1, warHire: 1 },
        // Expands to hold the area around its capital and fortifies it; raids nearby nodes only when nearly certain to win,
        // mostly for wealth; captures further out but leaves that land low tier.
        kobold:     { aggr: 0.9, raid: 3, def: 1.4, econ: 1.1, mil: 1.0, margin: 1.4, raidMargin: 1.5, armyRatio: 1.2, minArmy: 30,
                      homeRadius: 2, homeBias: 1.6, wealthRaidBias: 1.5, upgradeAway: 0 },
        // Takes its home nodes and retakes them when attacked. counter: when attacked, the army pushes towards the
        // aggressor's capital and raids it, falling back home after a lost fight or when the next fight looks lost.
        elemental:  { aggr: 1.2, raid: 0, def: 1.0, econ: 1.0, mil: 1.3, margin: 1.1, raidMargin: 1.1, armyRatio: 1.0, minArmy: 30,
                      targets: 'home', retake: 2, fortifyAttacked: 2, capitalReserve: 10, counter: 1 },
        // Player faction: tries to hold as much land as possible (values territory highly, secures and retakes it),
        // only raids it is fairly confident in, and spreads its troops to where they're needed.
        hearthbound: { aggr: 1.3, raid: 0.8, def: 1.3, econ: 1.0, mil: 1.2, margin: 1.3, raidMargin: 1.3, armyRatio: 1.2, minArmy: 35,
                      rebalance: 1, leaveBehind: 8, landValue: 2, retake: 1.8, secureNew: 1, fortifyAttacked: 1.5 },
        // Sits in its capital, takes the resource nodes behind it, and attacks its foes (the kingdom's foes list) on any shared
        // border to knock their nodes down a tier, razing what it takes and bringing the army home afterwards.
        dwarf:      { aggr: 1.3, raid: 0, def: 1.2, econ: 1.0, mil: 1.2, margin: 1.2, armyRatio: 1.2, minArmy: 30,
                      targets: 'raze', raze: 1, capitalReserve: 10 },
        // Attacks whenever a battle can be won, because every battle raises some of the dead (the kingdom's raiseDead share).
        undead:     { aggr: 1.5, raid: 1.4, def: 0.9, econ: 0.8, mil: 1.2, margin: 1.0, raidMargin: 1.0, armyRatio: 1.2, minArmy: 35 },
      },
    },
  };

  const PERSONALITY_DEFAULTS = {
    aggr: 1, raid: 0.8, def: 1, econ: 1, mil: 1, margin: 1.25, raidMargin: 0, armyRatio: 1, minArmy: 30,
    targets: 'any', reserveMult: 1, capitalReserve: 0, retake: 1, secureNew: 0, fortifyAttacked: 1,
    homeRadius: 0, homeBias: 1, upgradeAway: 1, wealthRaidBias: 1, burst: 0, burstRatio: 2, rebalance: 0, leaveBehind: 0, landValue: 1,
    counter: 0, warOnly: 0, warHire: 0, raze: 0,
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
    pass: 'Pass',
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

  // Power is troops × multiplier (heroic ×4, faction strength); survivors are converted back to troops by dividing by it.
  function resolveCapture(cfg, troops, garrison, dp, heroic, strength) {
    const mult = (heroic ? cfg.combat.heroicMult : 1) * (strength || 1);
    const ap = troops * mult;
    if (ap > dp) {
      const raw = Math.sqrt(ap * ap - dp * dp);
      const survivors = Math.min(troops, Math.floor(raw / mult));
      return { win: true, ap, dp, survivors, attackerLosses: troops - survivors, defenderRemaining: 0, defenderLosses: garrison };
    }
    // Ties go to the defender.
    const retreat = Math.floor(troops * cfg.combat.retreat);
    const survPow = Math.sqrt(dp * dp - ap * ap);
    const defRem = dp > 0 ? Math.floor(garrison * survPow / dp) : garrison;
    return { win: false, ap, dp, survivors: retreat, attackerLosses: troops - retreat, defenderRemaining: defRem, defenderLosses: garrison - defRem };
  }

  function resolveRaid(cfg, troops, garrison, dp, strength) {
    const s = strength || 1, ap = troops * s;
    if (ap > dp) {
      const losses = 0.5 * (ap - Math.sqrt(ap * ap - dp * dp)) / s;
      const survivors = Math.max(0, Math.floor(troops - losses));
      const defLoss = Math.floor(garrison * cfg.raid.garrisonDmg);
      return { win: true, ap, dp, survivors, attackerLosses: troops - survivors, defenderRemaining: garrison - defLoss, defenderLosses: defLoss };
    }
    return resolveCapture(cfg, troops, garrison, dp, false, s);
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
        food: 0, wealth: 0, res: 0, growth: 'normal', ai: 'balanced', goblinRule: false, buffTurns: 0,
        strength: 1, player: false, mode: 'gather', raiseDead: 0,
      }, k));
      for (const k of this.kingdoms) {
        k.imports = (k.imports || []).map((c) => Object.assign({}, c));
        k.lost = Object.assign({}, k.lost || {}); // node id -> round this kingdom lost it
      }
      this.K = {};
      for (const k of this.kingdoms) {
        k.stats = {
          actions: {}, capturesTried: 0, capturesWon: 0, raidsTried: 0, raidsWon: 0, hired: 0,
          battleLosses: 0, kills: 0, shortageLosses: 0, foodShortTurns: 0, wealthShortTurns: 0,
          buffs: 0, nodesGained: 0, nodesLost: 0, stolen: { food: 0, wealth: 0, res: 0 }, turns: 0,
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
          food: k.food, wealth: k.wealth, res: k.res, army: this.army(k.id),
          nodes: this.ownedNodes(k.id).length,
          foodNet: r2(inc.food - up.food), wealthNet: r2(inc.wealth - up.wealth), resNet: r2(inc.prod),
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
          return { food: c.town.food * m, wealth: c.town.wealth * m * c.town.wealthMult, prod: c.town.prod * m };
        }
        case 'castle': return { food: (c.castle[t] || c.castle[0]).food, wealth: 0, prod: 0 };
        case 'food': return { food: c.foodNode[t] || 0, wealth: 0, prod: 0 };
        case 'production': return { food: 0, wealth: 0, prod: c.prodNode[t] || 0 };
        case 'outpost': return { food: t >= 1 ? c.outpost.food : 0, wealth: 0, prod: 0 };
      }
      return { food: 0, wealth: 0, prod: 0 };
    }
    // Devastation scales production only; fixed food costs (castles, outposts) are not reduced.
    output(n, devOverride) {
      const b = this.baseOutput(n), f = 1 - (devOverride == null ? n.devastation : devOverride);
      const s = (v) => (v > 0 ? v * f : v);
      return { food: s(b.food), wealth: s(b.wealth), prod: s(b.prod) };
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
    strength(kid) { return kid && this.K[kid] ? this.K[kid].strength || 1 : 1; }
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
      const t = { food: 0, wealth: 0, prod: 0 };
      for (const n of this.nodes) if (n.owner === kid) {
        const o = this.output(n); t.food += o.food; t.wealth += o.wealth; t.prod += o.prod;
      }
      if (!nodesOnly) t.food += this.importedFood(kid);
      return t;
    }
    importedFood(kid) { return this.K[kid].imports.reduce((a, c) => a + c.food, 0); }
    upkeepFor(army, kid) {
      const b = Math.ceil(army / this.cfg.hire.batchSize), m = (kid && this.K[kid].upkeepMult) || 1;
      return { batches: b, food: Math.ceil(b * this.cfg.upkeep.food * m), wealth: Math.ceil(b * this.cfg.upkeep.wealth * m) };
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
      if (n.tier === 0) return { food: 0, wealth: 0, res: 0, newTier: 1, turns: u.timeBase + u.timePerTier };
      return {
        food: nt * u.food, wealth: nt * u.wealth, res: nt * (n.type === 'castle' ? u.castleRes : u.res),
        newTier: nt, turns: u.timeBase + nt * u.timePerTier,
      };
    }
    buffCost() { return this.cur ? this.cfg.buff.costTurns * this.cur.grossWealth : 0; }

    // ---- turn flow ----
    currentKid() { return this.order[this.turnPos]; }

    beginTurn() {
      const kid = this.currentKid(), k = this.K[kid], c = this.cfg;
      for (const n of this.ownedNodes(kid)) {
        n.morale = n.type === 'capital' ? 10 : Math.min(10, n.morale + c.morale.recover);
      }
      const inc = this.income(kid);
      k.food += inc.food; k.wealth += inc.wealth; k.res += inc.prod;
      k.food = r2(k.food); k.wealth = r2(k.wealth); k.res = r2(k.res);
      k.stats.turns++;
      this.cur = { kid, phase: 'production', actionsLeft: 0, actionsTotal: 0, grossWealth: inc.wealth };
      this.emit(kid, 'phase', `Production: +${r2(inc.food)} food, +${r2(inc.wealth)} wealth, +${r2(inc.prod)} resources → stockpile ${k.food} / ${k.wealth} / ${k.res}`);
      return this.cur;
    }

    buyBuff() {
      const cur = this.cur;
      if (!cur || cur.phase !== 'production') throw new Error('The growth buff can only be bought during the production phase.');
      const k = this.K[cur.kid], cost = this.buffCost();
      if (k.wealth < cost) throw new Error(`Growth buff costs ${cost} wealth (2 turns of gross wealth income); only ${k.wealth} available.`);
      k.wealth = r2(k.wealth - cost);
      k.buffTurns = this.cfg.buff.duration;
      k.stats.buffs++;
      this.emit(cur.kid, 'buff', `Bought growth buff for ${cost} wealth: growth ×${this.cfg.buff.mult} for ${this.cfg.buff.duration} turns (now ×${this.growthMult(cur.kid)}).`);
    }

    finishProduction() {
      const cur = this.cur, kid = cur.kid, k = this.K[kid], c = this.cfg;
      // Upkeep
      const army = this.army(kid), up = this.upkeepFor(army, kid);
      k.food = r2(k.food - up.food); k.wealth = r2(k.wealth - up.wealth);
      // Optional stockpile tax: a share of wealth above the tax-free amount is lost each upkeep (0 = off).
      if (c.wealthTax.rate > 0 && k.wealth > c.wealthTax.free) k.wealth = r2(k.wealth - (k.wealth - c.wealthTax.free) * c.wealthTax.rate);
      const kinds = [];
      if (k.food < 0) { kinds.push('food'); k.stats.foodShortTurns++; k.food = 0; }
      if (k.wealth < 0) { kinds.push('wealth'); k.stats.wealthShortTurns++; k.wealth = 0; }
      const short = kinds.length;
      this.emit(kid, 'phase', `Upkeep for ${army} soldiers (${up.batches} batches): −${up.food} food, −${up.wealth} wealth`);
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

    endTurn() {
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
        try { this.act(a); } catch (e) { this.act({ type: 'produce' }); }
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
    skipActions() { if (this.cur && this.cur.phase === 'actions') this.endTurn(); }

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

    do_pass(kid) { this.emit(kid, 'action', 'No attack available: the action is spent idle.'); }

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
      const r = resolveCapture(c, troops, g, dp, heroic, this.strength(kid));
      k.stats.capturesTried++;
      from.garrison -= troops;
      this.hitMorale(target, c.morale.attacked);
      target.attackedRound = this.round;
      const label = heroic ? 'Heroic conquest' : 'Capture';
      const head = `${label}: ${troops}${heroic ? ` (×${c.combat.heroicMult} = ${r.ap})` : ''} from ${from.name} vs ${target.name} [${this.kname(defender)}, ${g} garrison, defense ${r2(fd)}, power ${r2(dp)}]`;
      k.stats.battleLosses += r.attackerLosses; k.stats.kills += r.defenderLosses;
      if (defender) this.K[defender].stats.battleLosses += r.defenderLosses;
      this.noteAttack(kid, defender, r.win);
      if (r.win) {
        k.stats.capturesWon++; k.stats.nodesGained++;
        if (defender) { this.K[defender].stats.nodesLost++; this.K[defender].lost[target.id] = this.round; }
        delete k.lost[target.id];
        target.owner = kid;
        target.capturedRound = this.round;
        target.tier = Math.max(0, target.tier - 1);
        target.defense = 0;
        target.morale = c.morale.start;
        target.devastation = r2(Math.min(1, target.devastation + c.combat.captureDevastation));
        target.upgrade = null;
        if (target.tier === 0 && c.freeRebuildOnCapture && !(k.noFreeRebuild && target.home !== kid)) target.upgrade = { target: 1, turnsLeft: 1, free: true };
        const keep = Math.min(r.survivors, this.garrisonCap(target));
        target.garrison = keep;
        from.garrison += r.survivors - keep;
        this.raiseDead(kid, defender, from, target, r);
        this.emit(kid, 'capture', `${head} → CAPTURED. ${r.survivors} survive (√(${r2(r.ap)}² − ${r2(dp)}²)${heroic ? ' ÷ ' + c.combat.heroicMult : ''}); ${target.name} drops to ${this.tierName(target)} (T${target.tier}), devastation ${target.devastation}${target.upgrade ? '; free rebuild to T1 next turn' : ''}.${keep < r.survivors ? ` ${r.survivors - keep} over the garrison cap return home.` : ''}`);
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
      const base = this.baseOutput(target);
      const map = {
        food: { per: c.foodPer, out: base.food, key: 'food' },
        wealth: { per: c.wealthPer, out: base.wealth, key: 'wealth' },
        res: { per: c.prodPer, out: base.prod, key: 'res' },
      }[resource];
      if (!map) throw new Error('Pick food, wealth or resources to raid for.');
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
      const r = resolveRaid(c, troops, g, dp, this.strength(kid));
      k.stats.raidsTried++;
      from.garrison -= troops;
      this.hitMorale(target, c.morale.attacked);
      target.attackedRound = this.round;
      k.stats.battleLosses += r.attackerLosses; k.stats.kills += r.defenderLosses;
      dk.stats.battleLosses += r.defenderLosses;
      this.noteAttack(kid, defender, r.win);
      const head = `Raid (${a.resource}): ${troops} from ${from.name} vs ${target.name} [${dk.name}, ${g} garrison, defense ${r2(fd)}, power ${r2(dp)}]`;
      if (r.win) {
        k.stats.raidsWon++;
        target.garrison = r.defenderRemaining;
        from.garrison += r.survivors;
        this.hitMorale(target, c.morale.lost);
        const s = this.raidSteal(target, a.resource, r.survivors);
        if (s.amount > 0) {
          dk[s.key] = r2(dk[s.key] - s.amount); k[s.key] = r2(k[s.key] + s.amount);
          k.stats.stolen[s.key] += s.amount;
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
      const w = b * c.wealth, rs = b * c.res;
      if (k.wealth < w || k.res < rs) throw new Error(`Hiring ${b} batch(es) costs ${w} wealth and ${rs} resources.`);
      const gm = this.growthMult(kid), soldiers = Math.floor(b * c.batchSize * gm);
      k.wealth = r2(k.wealth - w); k.res = r2(k.res - rs);
      node.garrison += soldiers;
      k.stats.hired += soldiers;
      this.emit(kid, 'action', `Hire: ${b} batch(es) for ${w} wealth + ${rs} resources × growth ${gm} = ${soldiers} soldiers at ${node.name}.`);
    }

    // Pays a share of the nodes' per-turn output; imported food does not count.
    do_produce(kid) {
      const k = this.K[kid], inc = this.income(kid, true), m = this.cfg.produce.payout;
      const f = Math.floor(Math.max(0, inc.food) * m), w = Math.floor(inc.wealth * m), p = Math.floor(inc.prod * m);
      k.food = r2(k.food + f); k.wealth = r2(k.wealth + w); k.res = r2(k.res + p);
      this.emit(kid, 'action', `Produce resources (${m * 100}% of node output): +${f} food, +${w} wealth, +${p} resources.`);
    }

    // Supply contract: pay up front, receive food each production phase for the contract's duration.
    do_import_food(kid, a) {
      const c = this.cfg.importFood, k = this.K[kid];
      if (!c.enabled) throw new Error('Food imports are turned off in the rules.');
      const food = this.int(a.food, 'Food per turn');
      if (food > c.maxPerAction) throw new Error(`At most ${c.maxPerAction} food per turn per contract.`);
      const cost = food * c.duration * c.wealthPerFood;
      if (k.wealth < cost) throw new Error(`Importing ${food} food per turn for ${c.duration} turns costs ${cost} wealth.`);
      k.wealth = r2(k.wealth - cost);
      k.imports.push({ food, turnsLeft: c.duration });
      k.stats.imported = (k.stats.imported || 0) + food * c.duration;
      this.emit(kid, 'action', `Import food: ${cost} wealth for +${food} food per turn over the next ${c.duration} turns.`);
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
      if (k.food < cost.food || k.wealth < cost.wealth || k.res < cost.res) {
        throw new Error(`Upgrading to T${cost.newTier} costs ${cost.food} food, ${cost.wealth} wealth, ${cost.res} resources.`);
      }
      k.food = r2(k.food - cost.food); k.wealth = r2(k.wealth - cost.wealth); k.res = r2(k.res - cost.res);
      n.upgrade = { target: cost.newTier, turnsLeft: cost.turns };
      this.emit(kid, 'action', `${n.tier === 0 ? 'Rebuild' : 'Upgrade'} ${n.name} to T${cost.newTier} (${(TIER_NAMES[n.type] || [])[cost.newTier]}): ${cost.food} food, ${cost.wealth} wealth, ${cost.res} resources, ${cost.turns} turns.`);
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
    personality(k) {
      const ps = this.cfg.ai.personalities;
      return Object.assign({}, PERSONALITY_DEFAULTS, ps[k.ai] || ps.balanced);
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
      if (cost <= 0 || k.wealth < cost) return;
      const army = this.army(kid), desired = this.desiredArmy(kid, P);
      const after = k.wealth - cost, hireW = this.cfg.hire.wealth * this.cfg.hire.maxBatches;
      const inc = this.income(kid), up = this.upkeepFor(army + 20, kid);
      if (desired - army >= 20 / P.mil && after >= hireW * 2 && inc.food - up.food >= 0 && k.res >= this.cfg.hire.res * 4) this.buyBuff();
    }

    desiredArmy(kid, P) {
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
        if (troops * str > this.defPower(ec) * P.raidMargin) return { type: 'raid', from: army.id, target: ec.id, troops, resource: 'wealth' };
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
      const wf = ai.foodValue * sat(k.food, inc.food), ww = sat(k.wealth, inc.wealth), wr = ai.resValue * sat(k.res, inc.prod);
      const pr = (o) => (o.prod != null ? o.prod : o.res || 0);
      const Vfull = (o) => o.food * ai.foodValue + (o.wealth || 0) + pr(o) * ai.resValue;
      const V = (o) => o.food * wf + (o.wealth || 0) * ww + pr(o) * wr;
      const netFood = inc.food - up.food, netWealth = inc.wealth - up.wealth;
      const onlyCap = this.onlyCapital(kid);
      const SV = ai.soldierValue;
      const cands = [];
      const add = (score, action) => { if (isFinite(score)) cands.push({ score: score * (1 + (this.rand() - 0.5) * ai.noise), action }); };
      const reserve = (n) => {
        const t = this.maxAdjThreat(n, kid);
        let r = t ? Math.ceil(Math.min(n.garrison, t * 0.6 * P.def * P.reserveMult)) : 0;
        if (n.type === 'capital') r = Math.max(r, Math.min(n.garrison, P.capitalReserve));
        if (this.garrisonCap(n) === Infinity) r = Math.max(r, Math.min(n.garrison, P.leaveBehind));
        return r;
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
        const afterTier = Math.max(c.freeRebuildOnCapture ? 1 : 0, t.tier - 1);
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
      const expandMult = unsecured.length ? 0.35 : 1;
      const attacks = !P.burst;

      // Captures and raids
      if (attacks) for (const from of owned) {
        const avail = from.garrison - reserve(from);
        if (avail < 1) continue;
        for (const tid of this.adj[from.id]) {
          const t = this.N[tid];
          if (t.owner === kid) continue;
          const dp = this.defPower(t);
          if (!allowTarget(t, avail * hm, dp)) continue;
          if (t.type !== 'capital' && (P.warOnly || avail * hm > dp * P.margin)) {
            const r = resolveCapture(c, avail, t.garrison, dp, heroic, str);
            const raised = Math.floor((r.attackerLosses + r.defenderLosses) * (k.raiseDead || 0));
            add(expandMult * P.aggr * (captureGain(t) - (r.attackerLosses - raised) * SV + r.defenderLosses * SV * 0.3), { type: heroic ? 'heroic' : 'capture', from: from.id, target: tid, troops: avail });
          }
          const rm = P.raidMargin || P.margin;
          if (P.raid > 0 && t.owner && (P.warOnly || avail * str > dp * rm)) {
            const r = resolveRaid(c, avail, t.garrison, dp, str);
            let best = null;
            for (const res of ['food', 'wealth', 'res']) {
              const s = this.raidSteal(t, res, r.survivors);
              let val = Vfull({ food: s.key === 'food' ? s.amount : 0, wealth: s.key === 'wealth' ? s.amount : 0, prod: s.key === 'res' ? s.amount : 0 });
              if (res === 'wealth') val *= P.wealthRaidBias;
              if (!best || val > best.val) best = { res, val };
            }
            const dev = Math.min(c.raid.devMax, c.raid.devPer10 * Math.floor(r.survivors / 10));
            const harm = Vfull(this.baseOutput(t)) * dev * 4 * 0.3;
            const raised = Math.floor((r.attackerLosses + r.defenderLosses) * (k.raiseDead || 0));
            const score = P.raid * (best.val + r.defenderLosses * SV * 0.7 + harm - (r.attackerLosses - raised) * SV);
            if (best.val > 0 || r.defenderLosses > 0) add(score, { type: 'raid', from: from.id, target: tid, troops: avail, resource: best.res });
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
          const W = b * c.hire.wealth, R = b * c.hire.res;
          if (k.wealth < W || k.res < R) continue;
          const soldiers = Math.floor(b * c.hire.batchSize * gm);
          if (soldiers < 1) continue;
          const nu = this.upkeepFor(army + soldiers, kid);
          const nf = inc.food - nu.food, nw = inc.wealth - nu.wealth;
          let loc = blocked ? locs.find((n) => n.id === blocked.from) : null;
          const progress = loc ? Math.min(1, soldiers / blocked.need) : 0;
          // Goblin exception: may dip into negative food if the hire lets them take a node.
          if (nf < 0 && !(k.goblinRule && progress >= 1)) continue;
          if (nw < 0 && k.wealth - W < -nw * 4) continue;
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
        if (!T) return n.type === 'capital' ? P.capitalReserve : 0;
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
        const foodBuf = Math.max(0, -netFood) * 3, wBuf = Math.max(0, -netWealth) * 3;
        if (k.food < cost.food + foodBuf || k.wealth < cost.wealth + wBuf || k.res < cost.res) continue;
        const eff = Math.max(0, H - cost.turns);
        let gain = (Vfull(this.baseOutput(n, cost.newTier)) - Vfull(this.baseOutput(n))) * eff;
        if (n.type === 'castle') {
          const T = this.maxAdjThreat(n, kid);
          gain += (this.defCap(n, cost.newTier) * this.defMult(n, cost.newTier) - this.defCap(n) * this.defMult(n)) * (T > 0 ? 25 : 8);
          if (cost.newTier === c.bonusActionCastleTier) gain += V(inc) * eff * 0.5;
        }
        if (n.type === 'outpost') gain += this.maxAdjThreat(n, kid) > 0 ? 150 : 20;
        add(P.econ * (gain - V({ food: cost.food, wealth: cost.wealth, prod: cost.res })), { type: 'upgrade', node: n.id });
      }

      // Import food: worth it when food, not wealth, is what stops the army from growing.
      const ic = c.importFood;
      if (ic.enabled) {
        for (let f = ic.maxPerAction; f >= 1; f--) {
          const cost = f * ic.duration * ic.wealthPerFood;
          if (k.wealth < cost + Math.max(0, -netWealth) * 3) continue;
          const room = Math.max(0, desired - army) - Math.max(0, netFood) * c.hire.batchSize;
          const enabled = Math.min(f * c.hire.batchSize, Math.max(0, room));
          const relief = netFood < 0 ? Math.min(f, -netFood) * ai.foodValue * ic.duration : 0;
          add(P.mil * enabled * SV * 1.5 + relief - cost * ww, { type: 'import_food', food: f });
          break;
        }
      }

      // Produce resources is always available.
      const nodeInc = this.income(kid, true), pm = c.produce.payout;
      add(P.econ * V({ food: Math.max(0, nodeInc.food) * pm, wealth: nodeInc.wealth * pm, prod: nodeInc.prod * pm }), { type: 'produce' });

      if (P.warOnly) {
        const war = cands.filter((c2) => ['capture', 'heroic', 'raid'].includes(c2.action.type) || (P.warHire && c2.action.type === 'hire'));
        if (!war.length) return { type: 'pass' };
        cands.length = 0; cands.push(...war);
      }
      cands.sort((a2, b2) => b2.score - a2.score);
      // With only restricted slots left, pick the best action that fits them.
      if (this.cur && !(this.cur.generalLeft > 0)) {
        const ok = cands.find((c2) => this.fitsExtraSlot(c2.action.type));
        return ok ? ok.action : { type: 'produce' };
      }
      return cands[0].action;
    }

    fitsExtraSlot(type) {
      if (type === 'capture' || type === 'raid' || type === 'heroic') return false;
      if (this.cfg.nonCombatActions.economyOnly && (type === 'hire' || type === 'send')) return false;
      return true;
    }
  }

  return {
    DEFAULT_CONFIG, TIER_NAMES, MAX_TIER, TYPE_LABELS, ACTION_LABELS,
    Game, PERSONALITY_DEFAULTS, tierMult, defenderPower, resolveCapture, resolveRaid, deepMerge, clone,
  };
});
