# Testing the realm's core on the server

What to try on the server now that the realm plays by the simulator's rules (docs/design/realm-cutover.md, phases
1 to 3). Each section says what to do and what should happen; anything else is a bug worth noting (what you did, what
happened, `/realm log`, and any errors in `logs/latest.log`).

## Before you start

- **The build:** `./gradlew build` in `kingdom-sim` (and the other mods), then `python tools/export_curseforge.py` in
  `dungeon-delving-pack` for the pack zip. Install that on the server and your client.
- **Faster rounds for testing:** in `config/ddkingdom-common.toml`, set `realmRoundMinutes` to 2 or 3 so rounds come
  while you watch. Put it back to 60 when you're done. `realmCatchUpTurns` (24) caps how many rounds a restart catches up.
- **A fresh start:** starting the realm removes every old realm node that isn't on the simulator's map (the fresh start
  decided in the plan). Blueprints and guard posts are kept by node id, so save blueprints **after** starting, under
  the simulator's ids (`/realm sim unplaced`, `/realm sim info <node>` and the menu show them).

## 1. Starting the realm

1. `/realm sim start` (or `/realm sim start <seed>` to replay a game).
2. `/realm sim unplaced` should say every node is placed.
3. Spot-check places: `/realm sim info hb_cap` should stand at -210, 76, 2544 (Landfall); `/realm sim info ko_cap` at
   -3990, 150, -2222 (Vyrnhallow). Go there: you should be standing on the node's pad.
4. Open the realm menu and the FTB Chunks map: every node should show, in its owner's colour.

Expected: 102 nodes; most start as ruins no one holds; each faction holds its capital.

## 2. Rounds

1. `/realm sim` shows the round and the time to the next. Wait for one (or `/realm sim turn 5` to play five now).
2. `/realm log` should show captures and raids; places changing hands are news in chat.
3. The menu and map should catch up within a few seconds: new owners, tiers, troops, each faction's food and gold.

Watch for: nothing ever happening, everyone standing still, or a faction growing absurdly fast.

## 3. Catch-up after the server's been off

1. Note the round (`/realm sim`). Stop the server for a few rounds' worth of time (with short rounds), then start it.
2. The missed rounds should be played at once, up to `realmCatchUpTurns`; past that, the log says the realm "was quiet"
   for the rest.

## 4. A live raid

You need a Hearthbound town with troops next to another faction's node with troops. Early on the Hearthbound hold
little, so set one up, e.g. `/realm sim owner hb_town hb`, `/realm sim set hb_town tier 2`, `/realm sim set hb_town
garrison 6`, `/realm sim owner hb_farm go`, `/realm sim set hb_farm garrison 12`.

1. Stand in the town and `/realm sim raid hb_town`: the goblins attack in 10 seconds.
2. A bell rings, villagers hide, the town's guards turn out (its troops, up to 12), and 3 waves come.
3. Fight. Kill every wave and the town should hold; lose the guards (or fall behind when time runs out) and it falls.
4. `/realm log` says it was "fought in the world"; `/realm sim info hb_town` shows the result (its owner, troops).
5. Try `/realm side raiders` once: you can't hurt raiders, and can hurt guards.
6. Walk away mid-fight: with no one there, it waits, then is rolled.

Also let one come on its own: hold a town as above, stay near it, and wait for a round. If an enemy attacks it while
you're within 48 blocks, the news says they march, and the fight comes at dusk (half a round later).

## 5. Guards

1. With a Hearthbound town above ruins holding troops, walk up to it: its guards appear at their posts (a ring round
   the middle unless set: `/realm guards <town> post add` where you stand).
2. Lure a monster near: a guard goes for it, then walks back. Kill a guard: it isn't replaced until the next round.
3. `/realm guards <town> skin mixed` and `color blue`: their look changes.

## 6. Block damage and rebuilding

1. `/realm blueprint save <town> <radius>` for a town you've built (from its middle).
2. Let a raid on it be won (or `/realm blueprint damage <town> 20`): blocks break once the area's loaded, walls first.
3. Over later rounds, as the simulator repairs the town, blocks come back from the blueprint.

## 7. Danger

1. `/realm danger` near Landfall: low. Near a monster capital: high (6 or more at its heart). Near Karak Varn: low,
   since the dwarves are friendly.
2. Mobs that spawn in dangerous areas are tougher and show a higher level.

## 8. Champions

1. Push a faction back to its capital: `/realm sim owner <node> none` on each of its other nodes (`/realm sim info`
   on its neighbours helps find them), or wait for it to happen.
2. Every 3 rounds its champion marches on the nearest Hearthbound town (news), at dusk. Be there: the champion leads
   the last wave.
3. Go to its capital and `/challenge`: the champion answers. Killing it costs the capital a third of its troops
   (`/realm sim info <capital>`).

## 9. Saving

1. Restart the server: `/realm sim` should show the same round, and `/realm sim info` the same nodes.
2. An attack held for dusk survives the restart (it's rolled at dusk if no one's there).

## 10. The server's health

Keep an eye on TPS during rounds (especially catch-up) and live raids, and on the log for errors.

## Known limits (not bugs)

- Politics: the council has no effect until quests exist (phase 4).
- Bandits, vampires and dwarves raid with vanilla stand-ins (pillagers, witches, vindicators...).
- The realm menu shows the simulator's figures in the old layout; its redesign is KING-6.
- Champions only raid; they don't retake places (the simulator decides who holds what).
