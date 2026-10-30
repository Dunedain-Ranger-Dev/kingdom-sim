# Kingdom Stats simulator

A browser simulator for the Kingdom Stats ruleset ([kingdom-stats.txt](../../docs/rules/kingdom-stats.txt)) and
faction behaviours ([kingdom-behaviour.txt](../../docs/rules/kingdom-behaviour.txt)), for tuning the rules before they go
into the mod. It plays AI-only or hand-played games on the realm map, runs batches of hundreds of games, and has a combat
calculator and an editor for every rule number. The published copy is https://claude.ai/artifact/KMdU1nqC1zLqNmi3KNjGN4;
this is its source.

The rules and maps are the mod's own data files, in `src/main/resources/data/ddkingdom/realm/sim`: `rules.json` holds
every rule number and AI personality, and `maps/` the maps (`large_ruins` is the default). The mod's Java simulator
(`src/main/java/io/github/ranger3462/ddkingdom/sim`, `./gradlew runSim`) reads the same files, and a seed plays the same
game in both. To change a rule for everyone, change `rules.json` and rebuild the page.

## Layout

| Path | What it is |
|---|---|
| `web/engine.js` | The rules engine and AI: plain JavaScript, no DOM, runs in the browser and in Node |
| `web/index.html` | The page: play, batch test, calculator, rules and map editor |
| `build.py` | Builds one page, `kingdom-simulator.html` (not committed), with the engine and the rules and maps inlined |
| `test.js` | Spot checks of the combat and tier formulas against hand-worked examples |
| `parity.js` | Prints games the way the Java simulator's `--fingerprint` does, to check the two match |
| `maps/` | The large map's generator (`gen_large.py`, from `small_map.json`) and a drawing of it |

## Running it

```
python build.py               # then open kingdom-simulator.html in a browser
node test.js                  # formula checks
python maps/gen_large.py      # regenerates large_ruins.json and large_settled.json in the mod's map data
python maps/render_map.py     # draws maps/large_map.png (needs Pillow)
```

The page saves edited settings in the browser, under a name that changes when the default map or rules change.

## Checking it against the Java version

```
node parity.js large_ruins 20 40 > js.txt
../../gradlew -p ../.. runSim -q --args="--map large_ruins --games 20 --rounds 40 --fingerprint" > java.txt
```

The two files should match exactly. Any change to the rules engine goes into both `web/engine.js` and the Java `sim`
package, and this check shows whether they still agree.
