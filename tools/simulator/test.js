const path = require('path');
const dir = path.join(__dirname, 'web');
const S = require(path.join(dir, 'engine.js'));
const SC = S.MAPS.four_corners;

// Rule spot checks
const cfg = S.DEFAULT_CONFIG;
const assert = (c, m) => { if (!c) { console.error('FAIL', m); process.exitCode = 1; } else console.log('ok', m); };
assert(S.tierMult(1) === 1 && S.tierMult(2) === 1.5 && S.tierMult(5) === 3 && S.tierMult(0) === 0, 'tier multipliers');
let r = S.resolveCapture(cfg, 50, 20, 30, false);
assert(r.win && r.survivors === 40, 'capture 50 vs 30 -> 40 survivors');
r = S.resolveCapture(cfg, 30, 20, 30, false);
assert(!r.win && r.survivors === 7 && r.defenderRemaining === 0, 'tie goes to defender');
r = S.resolveCapture(cfg, 18, 20, 30, false);
assert(!r.win && r.survivors === 4 && r.defenderRemaining === 16, 'failed capture: 25% retreat, defender 20*24/30=16');
r = S.resolveCapture(cfg, 10, 0, 30, true);
assert(r.win && r.survivors === 6, 'heroic 10x4=40 vs 30 -> floor(26.46/4)=6');
r = S.resolveRaid(cfg, 50, 20, 30);
assert(r.win && r.survivors === 45 && r.defenderRemaining === 15, 'raid 50 vs 30: losses 5, defender -25%');

const g = new S.Game(SC, {}, { seed: 1, maxRounds: 40 });
const cap = g.N.dw_cap, castle = g.N.dw_castle;
assert(g.finalDefense(cap) === 10, 'capital def cap 10');
assert(g.finalDefense(castle) === 9, 'T1 castle def 6*1.5=9');
const inc = g.income('dw');
console.log('dw income', inc, 'upkeep', g.upkeep('dw'));

for (let i = 0; i < 40; i++) g.runRound();
for (const k of g.kingdoms) {
  console.log(k.name, 'nodes', g.ownedNodes(k.id).length, 'army', g.army(k.id), 'F/W/R', k.food, k.gold, k.res, JSON.stringify(k.stats.actions), 'cap', k.stats.capturesWon + '/' + k.stats.capturesTried, 'raids', k.stats.raidsWon + '/' + k.stats.raidsTried, 'short', k.stats.foodShortTurns, k.stats.goldShortTurns, 'buffs', k.stats.buffs);
}
console.log(g.log.slice(0, 40).map((l) => `R${l.round} ${l.kid} ${l.text}`).join('\n'));
console.log('...');
console.log(g.log.filter((l) => ['capture', 'raid', 'fail', 'shortage'].includes(l.kind)).slice(0, 30).map((l) => `R${l.round} ${l.kid} ${l.text}`).join('\n'));
