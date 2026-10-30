// Prints each game's end state the way the Java simulator's --fingerprint does (SimMain.fingerprint), so the two can be
// compared game for game: node parity.js <map> <games> <rounds> [first seed] [rule overrides json]
// Both read the mod's rules.json and maps; <map> is a map's name (large_ruins) or a path to a map file.
const fs = require('fs');
const path = require('path');
const S = require(path.join(__dirname, 'web', 'engine.js'));

const [mapArg, games = '20', rounds = '40', seed0 = '1', over = '{}'] = process.argv.slice(2);
const map = S.MAPS[mapArg] || JSON.parse(fs.readFileSync(mapArg, 'utf8'));
const cfg = JSON.parse(over);
for (let s = +seed0; s < +seed0 + +games; s++) {
  const g = new S.Game(map, cfg, { seed: s, maxRounds: +rounds, quiet: true });
  for (let i = 0; i < +rounds; i++) g.runRound();
  const ks = g.kingdoms.map((k) => `${k.id}:${Math.round(k.food * 100)},${Math.round(k.gold * 100)},${Math.round(k.res * 100)},${g.army(k.id)}`);
  const ns = g.nodes.map((n) => `${n.owner == null ? '-' : n.owner}|${n.tier}|${n.garrison}|${n.defense}|${n.morale}|${Math.round(n.devastation * 100)}`);
  console.log(s + ' ' + ks.concat(ns).join(' '));
}
