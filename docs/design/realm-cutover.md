# Switching the realm to the simulator's rules

A plan, 2026-11-03; its open questions decided the same day. The rules now live in the pure-Java simulator (`sim` package, `docs/rules/`);
the realm the server runs (`realm` package, `realm-decisions.md`) still plays by the older rules. This is how to put
the one inside the other.

## Where things stand

| | The mod's realm today (`realm`) | The simulator (`sim`) |
| --- | --- | --- |
| Places | Town, site, stronghold, resource, capital (plus battle and event, unused) | Capital, town, castle, watchtower (outpost), production, food |
| A place's numbers | Defense, wealth, morale, tier; a stronghold's military and fortification; a site's heat | Tier 0–5, garrison (troops), defense, morale, devastation, an upgrade under way |
| A faction's numbers | The kingdom's army and treasury; each faction's food and trade | Food, gold and resources stockpiles; its army is the troops in its nodes |
| A turn | Every 20 minutes, each side picks **one** weighted action | Each faction produces, pays upkeep, then takes several actions (hire, send, capture, raid, upgrade, produce…) |
| Who decides | Weights in `RealmTurns`, traits in `realm/factions/*.json` | `GameAi` with a personality per faction, in `rules.json` |
| Fights | Declared at dawn, settled at dusk: rolled, or fought live if a player is near | Settled at once by `Combat`, with the scaled d20 dice |
| Extras | Capitals rally and send champions, sites gain heat, towns grow from their own wealth | Politics, the Kobolds' hoard, minor factions, Train troops, imports |
| Minecraft | Guards, live raid waves, block damage and blueprints, danger levels, the realm menu, map icons | None: no Minecraft code at all, and no saving |

## The approach

**The simulator becomes the realm's brain; the Minecraft layer stays and reads from it.** The realm's saved data
holds a running `Game`. Each realm turn runs the simulator's turn. Everything a player sees or touches (guards, live
raids, broken blocks, danger, the menu) reads the simulator's state and feeds results back into it. The old turn
logic (`RealmTurns`, `RealmRaids`, `RealmEconomy`, `RealmTiers`, `RealmFactions`) goes away once the new one works.

Why not port the rules into the old classes one at a time: there would be two copies of every rule to keep in
step (three, with the web simulator), and the realm couldn't be checked against the simulator's batch runs. With
the simulator inside the mod, a realm with no players on it plays **exactly** the game `SimMain` plays for the same
seed, and a test can check that.

## How the mod's ideas map onto the simulator's

| The mod's | Becomes |
| --- | --- |
| The kingdom | The Hearthbound (`hb`), the player kingdom |
| Monster nations (goblins, orcs, undead, kobolds, elementals) | The simulator's factions, plus the Dwarves, bandits and vampires |
| Town | Town (tier, defense, morale); its garrison is its guards |
| Town wealth | **Gone**: gold is the faction's stockpile, not each town's |
| Stronghold (military, fortification) | Castle (garrison, defense) |
| Site (watchtower, ford…) and its heat | Watchtower; heat is **dropped** (the AI's aggression and odds do that job) |
| Resource node | Food or production node |
| Capital | Capital: never taken (KING-1's "sacked, not captured") |
| Capital rally and champions (KING-16) | Kept as a Minecraft-side event on top of the simulator |
| Raid damage to blocks | The simulator's devastation: a raid's devastation breaks blueprint blocks; Repair rebuilds them |
| Danger around a node (KING-17) | From the node's owner and tier, as now |

## Phases

Each phase is its own change, tested and merged before the next. The old engine keeps running until phase 3, behind a
config switch (`realmEngine = legacy | sim`), so the server never has a broken realm.

1. **Make the simulator embeddable** (in `sim`, no Minecraft code)
   - Save and load a whole game: kingdoms, nodes, round, whose turn it is, and the random number generators' states.
     Test: save halfway through, load and finish, and get the same fingerprint as a game never saved.
   - An endless game: no last round, and no "winner" (scoring only matters in batch runs).
   - **Held battles**: let the host say "don't settle a fight on this node now". The attack is declared and waits;
     the host settles it later with a result (survivors and losses on each side), or asks the dice to settle it.
   - Whatever the simulator logs becomes realm news, by kind.
2. **The realm runs a `Game`** (`Realm`, `RealmTurns`, behind the switch)
   - The realm's saved data holds the game; each sim node is tied to a place in the world (dimension and position).
   - The clock (now hourly) and the catch-up cap stay; each tick of it runs one round.
   - Attacks on the Hearthbound's nodes with a player within 48 blocks are held for dusk; every other fight is
     settled at once by the dice.
   - `/realm node set` and the other admin commands change the simulator's numbers.
   - Test: with no players, the realm's game matches `SimMain --fingerprint` for the same seed and map.
3. **Rewire the Minecraft side, then remove the old engine**
   - The realm menu (`RealmMap`, `RealmScreen`) and the map icons show tiers, garrisons, stockpiles and owners.
   - Guards: a town's garrison is its guard count (capped, as now). Live raid waves are sized from the attacking
     troops; the fight's survivors go back to the simulator.
   - Devastation breaks and rebuilds blueprint blocks; danger follows owners and tiers.
   - Delete the old turn logic and its tests; set `realmEngine` to `sim` for good.
4. **Built on the new engine**
   - Politics for players: each group posts quests; finishing one is `questDone`; each player's standing is kept on
     its own (`realm-politics.md`).
   - KING-22: vampirism and minor factions in the world (bandit camps, vampire towns, danger).
   - KING-18–21: the Dwarves' capital, standing raised by Mithril quests, the Deep Mines entrance gated on it.
   - The players' raid on the Kobold hoard (no ticket yet).

## What happens to the open tickets

| Ticket | After the switch |
| --- | --- |
| KING-3, KING-4 (phases 1–2: traits, odds, factions, food, trade, resources) | Done by phase 2: close them |
| KING-1 (capitals are towns, sacked not taken) | Done by phase 2: close it |
| KING-2 (troop counts, field armies, upkeep) | Done by phase 2, except field armies (the simulator moves troops node to node): close or narrow |
| KING-8 (the realm, overall) | Becomes the parent ticket for this switch |
| KING-13 (live raids when a player is there) | Phase 3 |
| KING-16 (capitals send champions) | Phase 3: kept as a world event on top of the simulator |
| KING-22, KING-18–21 | Phase 4 |
| KING-6 (realm menu), KING-7 (new players), KING-5 (war effort) | Unaffected; KING-6 is easier after phase 3 |

## Decided (2026-11-03)

1. **The map:** `large_ruins`. It maps directly onto the world already built; each node is tied to its place
   there (`/realm node place <id>`, or matched to a node already placed with the same id).
2. **Pace:** a round **every hour** (`realmTurnMinutes` 60), and the game **never ends**: no last round, no winner.
   Dusk, when a held fight is settled, is half a turn after it's declared (30 minutes).
3. **Champions:** kept as a **Minecraft-side event**. When a faction is down to its capital, the mod rallies it
   and sends a champion, and players can `/challenge` it, as now (KING-16). It isn't a simulator rule, so batch
   runs don't include it; a champion's win or loss feeds back as troops gained or lost at the capital.
4. **The old save:** the realm **starts fresh** on `large_ruins`. World positions and guard posts already set are
   kept wherever a node's id matches.
5. **Live fights:** only attacks on the **Hearthbound's** nodes wait for players, if one is within 48 blocks.
   Every other fight is settled at once by the dice.
