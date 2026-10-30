var S = require('./web/engine.js'), Small = require('./web/continent.js'), Large = require('./web/continent-large.js');
const G = +process.argv[3] || 200, ids = ['hb', 'go', 'or', 'ko', 'un', 'el', 'dw'];
function run(label, map, cfg, R) {
  const t0 = Date.now(), tot = map.nodes.length;
  let ruins = 0, unowned = 0, caps = 0, wealth = 0; const lead = {}, nodes = {}, army = {}, acts = {};
  for (let s = 1; s <= G; s++) {
    const g = new S.Game(map, S.deepMerge({ ai: { noise: 0.3 } }, cfg), { seed: s, maxRounds: R, quiet: true });
    for (let i = 0; i < R; i++) g.runRound();
    const nc = g.nodes.filter(n => n.type !== 'capital');
    ruins += nc.filter(n => n.tier === 0 && !(n.upgrade && n.upgrade.free)).length / G;
    unowned += nc.filter(n => !n.owner).length / G;
    for (const id of ids) {
      const k = g.K[id];
      nodes[id] = (nodes[id] || 0) + g.ownedNodes(id).length / G;
      army[id] = (army[id] || 0) + g.army(id) / G;
      caps += k.stats.capturesWon / G; wealth += k.wealth / G / ids.length;
      for (const [t, n] of Object.entries(k.stats.actions)) acts[t] = (acts[t] || 0) + n / G / ids.length;
    }
    const cnt = ids.map(i => g.ownedNodes(i).length), best = Math.max(...cnt);
    ids.forEach((id, i) => { if (cnt[i] === best) lead[id] = (lead[id] || 0) + 1 / cnt.filter(c => c === best).length / G; });
  }
  const at = Object.values(acts).reduce((a, b) => a + b, 0);
  console.log(`\n${label} (${tot} nodes, R${R}, ${((Date.now() - t0) / 1000).toFixed(0)}s)  ruins ${ruins.toFixed(1)} | unowned ${unowned.toFixed(1)} | captures ${caps.toFixed(0)} | avg wealth ${Math.round(wealth)}`);
  console.log('  nodes  ' + ids.map(i => `${i} ${nodes[i].toFixed(1)}`).join('  '));
  console.log('  army   ' + ids.map(i => `${i} ${Math.round(army[i])}`).join('  '));
  console.log('  leads  ' + ids.map(i => `${i} ${Math.round((lead[i] || 0) * 100)}%`).join('  '));
  console.log('  actions/kingdom ' + at.toFixed(0) + ': ' + Object.entries(acts).sort((a, b) => b[1] - a[1]).slice(0, 6).map(([t, n]) => `${t} ${Math.round(n / at * 100)}%`).join(', '));
}
const R = +process.argv[2] || 40;
//run('A Small map, 1 action', Small.dwarves, { baseActions: 1 }, R);
run('B Large map, 1 action', Large.ruins, { baseActions: 1 }, R);
run('C Large map, 2 actions', Large.ruins, { baseActions: 2 }, R);
