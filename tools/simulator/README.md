# Kingdom Stats simulator

A browser simulator for the Kingdom Stats ruleset (`kingdom-stats.txt`) and faction behaviours (`kingdom-behaviour.txt`),
built to tune the rules before they go into the mod. It plays AI-only or hand-played games on the realm map, runs
batches of hundreds of games, and has a combat calculator and an editor for every rule number. The published copy is
https://claude.ai/artifact/KMdU1nqC1zLqNmi3KNjGN4 (version 11); this is its source.

## Layout

| Path | What it is |
|---|---|
| `web/engine.js` | The rules engine and AI: plain JavaScript, no DOM, runs in the browser and in Node. `DEFAULT_CONFIG` holds every rule number |
| `web/continent-large.js` | The default map: the 102-node continent, ruins start, with the Dwarves |
| `web/continent.js` | The 48-node continent presets (settled start, ruins start, with Dwarves) |
| `web/scenario.js` | The first test map: four kingdoms in the corners |
| `web/index.html` | The page: play, batch test, calculator, rules and map editor |
| `build.py` | Inlines the scripts into one page, `kingdom-simulator.html` (not committed) |
| `test.js` | Spot checks of the combat and tier formulas against hand-worked examples |
| `bigtest.js` | Batch comparison on the 48- and 102-node maps |
| `maps/` | The large map's generator (`gen_large.py`, from `small_map.json`), its data (`large.json`), and a drawing of it |

## Running it

```
python build.py               # then open kingdom-simulator.html in a browser
node test.js                  # formula checks
node bigtest.js 80 200        # rounds, games
python maps/gen_large.py      # regenerates web/continent-large.js
python maps/render_map.py     # draws maps/large_map.png (needs Pillow)
```

The page saves edited settings in the browser, under a name that changes when the default map or rules change.

## The Java version

The mod has the same engine in Java, in `src/main/java/io/github/ranger3462/ddkingdom/sim`, with the rules and maps as
data in `src/main/resources/data/ddkingdom/realm/sim`. A seed plays the same game in both, which `parity.js` checks:

```
node parity.js ../../src/main/resources/data/ddkingdom/realm/sim/maps/large_ruins.json 20 40 > js.txt
../../gradlew -p ../.. runSim -q --args="--map large_ruins --games 20 --rounds 40 --fingerprint --set combat.dice.mode=\"none\"" > java.txt
```

The two files should match exactly. Rules added in Java since the port aren't in this browser version, so the check
turns them off: so far that's the dice in battles (`combat.dice`), which are on in the Java rules.
