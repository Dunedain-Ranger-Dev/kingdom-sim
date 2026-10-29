# The Realm

One kingdom against the monster tide (DD-93). The user's direction, 2026-10-26; numbers are first guesses for tuning.

## The idea

There aren't factions to join. There's one kingdom, a Venice of a city-state standing its ground, holding back a tide
of monster nations. The player represents the kingdom's many groups, has standing with all of them, and lives with
their politics. Pushing the tide back is the player's job: capturing sites, raiding strongholds, running dungeons.
Nothing stays won. The longer the kingdom holds something, the harder it's attacked, so sites are lost and retaken.

## Who's who

**The kingdom's groups.** A player has standing with each; their rivalries are the internal politics.
- **Adventurer guilds:** several, rivals for the best contracts.
- **Official guilds:** one for each class (fighter, archer, rogue, mage), profession (smiths, tanners, weavers,
  woodworkers, goldsmiths) and skill. The profession guilds grow out of the Guild Ledger.
- **The mercenary guild:** fights for whoever pays; the kingdom's hired muscle.
- **Noble and merchant houses:** money and the city's seats of power.

**The monster nations:** the dungeon factions (factions.md) as nations with bases: undead, goblins, orcs, and later
kobolds and dark elves. Each has its own military, mood and goals.

## The map: nodes

The realm is a web of **nodes**, hand-placed on a purpose-built map (not Alcareon), joined by **links**. Links make
borders: a kingdom node linked to a monster node is on the border.

| Node | What it is | Stats |
|---|---|---|
| **Town** | A kingdom settlement: guild halls and Guild Ledgers, merchants, guards | Defense, wealth, morale; a blueprint (below) |
| **Site** | A place worth holding: watchtower, bridge, mine, ford, old fort. Held, it helps its neighbors | Holder, bonus, heat |
| **Stronghold** | A monster nation's base. Its dungeons are its depth: clearing runs there bleeds its military | Military, fortification |
| **Battle** | Temporary, made when a raid or attack is declared; gone once resolved | Attacker, defender, deadline |
| **Event** | Temporary: a caravan, a plague, a festival, a refugee column, an envoy | By event |

Site bonuses, for example: a watchtower warns its towns earlier (raids declared further ahead), a bridge adds defense
to both ends, a mine adds wealth, a fort adds military reach.

## Turns

- **A turn every Minecraft day** (20 minutes), on a real-time clock: turns keep coming while no one is online, at the
  same pace.
- **Catch-up cap:** a server that was off catches up on the turns it missed, up to a cap (first guess: 24 turns, 8
  hours). Turns past the cap are lost, not saved up, so a week away doesn't flatten the realm.
- Each turn, at dawn, **every side picks one action**, weighted by its situation:

| Side | Actions |
|---|---|
| Kingdom | Reinforce a border (its border towns gain defense), muster troops, gather wealth, fortify a site, launch an expedition against a stronghold |
| Monster nation | Build military, raid a settlement, expand into a site, fortify its stronghold |

- **Politics steer the kingdom's choice:** each group pushes for what suits it (merchants for wealth, the military for
  musters, adventurers for expeditions), and players' standing can tip the vote.
- A **realm log** records each turn: what each side did, and how it turned out. Players can read it, and hear news when
  something happens near them.

## Raids

1. **Declared at dawn:** a monster nation raids a border town. Players hear of it, with time to get there.
2. **Resolved at dusk:**
   - **No player there:** a roll. The attacker's military plus a roll against the town's defense plus its site bonuses
     plus a roll. If the attackers win, the town loses defense, wealth and morale and takes block damage, more for a
     bigger margin. If they lose, they lose military.
   - **A player there:** it plays out as a village-style raid: waves of that nation's mobs against the town's guards.
     The damage each side does decides the outcome, in place of the roll. A player can fight on either side; siding
     with the monsters costs a great deal of standing.

## Real damage, and blueprints

Raids do real damage, so towns need a way back:
- **Blueprint:** each town saves a snapshot of its buildings when it's set up, and again on purpose when its builds
  change.
- **Damage:** a lost raid breaks and burns real blocks, walls first, then roofs and interiors. It only touches blocks in
  the blueprint. Damage to a town that isn't loaded is queued and applied when it next loads.
- **Rebuilding:** the town restores blocks from its blueprint over time, paid from its wealth. Players can speed it up
  (repair contracts, or by hand). A hand-built town can never be lost for good.

## The tide

Every turn a site stays held, its **heat** rises, and attackers against it get stronger. Sooner or later it falls,
and has to be retaken. The tide can't be beaten, only held back: there's always somewhere to fight for.

## How it ties in

- **Dungeons** are a stronghold's depth: a run's tier is how far into monster land it goes, and clearing runs weakens
  that nation.
- **The Guild Ledger** becomes the heart of each guild hall; the profession guilds' standing grows from crafting there.
- **Race and charisma** were set aside for faction standing (player-systems.md): a race can start friendly with some
  groups, and charisma eases standing gains.
- **The new-player flow** (player-systems.md) starts in a town: greeted, given an adventuring book, pointed to a guild.

## First slice (DD-94 to DD-98)

1. **Nodes** (DD-94): the registry, placing and linking by hand, and a command to inspect them.
2. **Turns** (DD-95): the real-time clock with catch-up, and each side's daily action; the realm log.
3. **Raids by roll** (DD-96): declare at dawn, resolve at dusk.
4. **Blueprints** (DD-97): snapshot, real damage, rebuilding.
5. **Live raids** (DD-98): waves when a player is there, damage decides it.

Built and tested with one town and one goblin stronghold on a dev world. Then: politics and standing, contracts,
sites and heat, more nations, events.

## Open questions

- **Expeditions:** does the kingdom attacking a stronghold play out as a battle node a player can join, or only through
  dungeons?
- **Guards:** who defends towns in a live raid: our own guard mobs, or recruited mercenaries?
- **Multiplayer:** does the realm vote count every online player's standing, or just the most involved?
- **The cap:** 24 turns of catch-up is a guess; tune once it's playable.
