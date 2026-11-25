# The dwarves

Decided 2026-11-25 (KING-18, from Deep Mines' MINE-9). The dwarves own the old mines under the realm (Deep Mines), and
the way into them is at their capital.

## In the realm (KING-19)

- The dwarves are one of the simulator's factions (`dw`, `dwarves`), **friendly** to the Hearthbound: their land is safe
  ground (`RealmFactions.friendly`, `RealmLevels`), and they don't send champions against the kingdom.
- Their capital is **Karak Varn** (`dw_cap`), placed by the map's places (`large_ruins`: 3563, 150, -1754), with a mine
  (`dw_mine`) and a farm (`dw_farm`). Like every capital it's never taken, so the dwarves always hold it.
- Players find it as they find every capital: on the realm menu, and on FTB Chunks' map with the other nodes.

## Standing (KING-20)

- Each player has their own **standing** with the dwarves (`RealmStanding`), theirs alone, as with the council's groups
  in `realm-politics.md`. Everyone starts **neutral**, at 0.
- **Mithril quests:** the dwarves want Mithril from their mines (raw Mithril or ingots, the `ddkingdom:mithril_tribute`
  tag). Bring it to the mine gate and use the gate holding it: every `mithrilPerQuest` (4) finishes a quest and raises
  the player's standing by `standingPerMithrilQuest` (5). What's left over counts towards the next quest.
- How the dwarves see a player: distrusted (below 0), neutral (0 to 9), friendly (10 to 29), honored (30 to 59),
  revered (60 and up). Nothing lowers standing yet.
- `RealmStanding` keeps any group's standing, so the council's quests can use it too.

## The mine gate (KING-21)

- **Dwarven Mine Gate** (`ddkingdom:mine_gate`): a block built into Karak Varn (it's in the creative menu's functional
  blocks, for whoever builds the capital). It can't be broken in survival.
- It only opens within 96 blocks of the dwarves' capital, so a gate anywhere else stays shut.
- Using it (not holding Mithril) takes the player into Deep Mines, to their own Mines' entrance hall
  (`MinesEntrance.enter`), if their standing is at least `dwarvenStandingForMines`. The ladder up in the hall brings
  them back out at the gate. Below that standing they're turned away, told their standing and what they need.
- **Decided:** the standing needed starts at **neutral (0)**: anyone can go in. Mithril only comes from the Mines, so
  requiring more would lock new players out of the only way to raise it. Servers can raise it.
- Deep Mines is optional (`DeepMinesLink`). Without it the gate says the mine is sealed. With Kingdom Sim installed,
  Deep Mines closes its own deepslate portal (its `portal` setting, AUTO), so the gate is the only way in.

## Later

- What else standing gets a player (prices, rewards, deeper quests).
- Quests other than Mithril, and things that lower standing.
- A dwarf at the gate to talk to, instead of the gate itself.
