# How the realm's factions decide

Every turn (20 real minutes, `realmTurnMinutes`), the kingdom acts once, then each monster nation acts once. Each side
looks at the map, gives each of its possible actions a **weight**, and picks one at random in proportion to the
weights. A weight of 6 against 2 and 2 means a 60% chance, not a certainty, so sides don't act like clockwork. The
code is `RealmTurns` (`kingdomWeights`, `nationWeights`); the log (`/realm log`) records every choice.

Before anyone acts, two things happen on their own:

- **Repairs:** any town with raid damage still to rebuild pays 1 wealth and rebuilds 64 blocks (`RealmDamage.fundRepairs`).
- **Growth:** towns start as **ruins** (tier 0) and grow, whoever holds them (`RealmTiers.grow`). A town with 10 × its
  tier in wealth, plus 5 to spare, and nothing left to rebuild, pays and starts **upgrading** (ruins start for free,
  even with damage left). An upgrade takes 2 turns (days) per tier it's growing to: 2 to a hamlet, 4 to a village, up
  to 10 to a city. Then it goes up a tier and gains 3 defense.
- **Upgrading towns get no support:** no reinforcing, no wealth, no repairs, no tribute, until the upgrade's done.
  Ruins have no guards, so a fought raid on them is the raiders against whoever's there.

Raids declared on a turn resolve at its "dusk", half a turn later.

## The kingdom

The kingdom looks at its **border towns** (towns linked to a monster stronghold) and the **threat**: the total
military of every monster stronghold touching the border.

| Action | Weight | What it does |
| --- | --- | --- |
| Reinforce | 2, plus 4 if the threat is greater than the border's defense (its towns' defense plus the army). 0 if there's no border | Every border town gains 2 defense |
| Muster | 2, plus 2 if the army is less than half the threat | The army grows by 3 (a quarter of it helps defend any raided town) |
| Gather wealth | 2, plus 2 if the treasury is under 10 | Every kingdom town gains 1 wealth; the treasury gains 1 per town |
| March | 1, plus 1 for every 3 its attack (half its army, +10 with a capital) is over the target's defense. 0 when it isn't stronger, with no target, or with a march already under way | Its army raids a town linked to one of its towns or its capital: the nearest in ruins that isn't its own (to take it), or else the weakest monster-held one (to sack it) |

So a kingdom under pressure mostly reinforces and musters. A safe one spreads its effort evenly, its towns slowly get
richer and grow, and a strong army goes to win back lost towns.

## Each monster nation

A nation (goblins, undead, orcs) looks at its **strongholds**, their total **military**, and its **raid target**.
Taking ground comes first: the target is the nearest place linked to one of its strongholds that it could take (a
stronghold or site it doesn't hold, or a town in ruins no nation holds). Only if there's none is it the
weakest-defended town linked to them, to sack it. Each town a nation holds gains 1 wealth a turn, so it can grow. (A target's defense is a
town's defense, or a stronghold's or site's fortification.)

| Action | Weight | What it does |
| --- | --- | --- |
| Build military | 3, plus 4 if it isn't ready to raid | Every stronghold gains 2 military, by the nation's growth (goblins 3, kobolds 1) |
| Raid | 0 unless it's ready. Then 1, plus 1 for every 10% its chance beats its bar, times its aggression. 0 with a raid already under way, or while securing a new holding | Declares a raid on the target, from the stronghold linked to it |
| Fortify | 1, plus 1 if its weakest stronghold's fortification is under 5, plus 6 while securing a new holding | Every stronghold gains 1 fortification (by growth), and every town it holds as much defense |

Each nation plays by its **traits** (`data/dungeondelving/realm/factions/<nation>.json`, DD-102; see
`realm-factions.md`). It's **ready** to raid when its chance of winning the roll (`RealmFactions.winChance`: 47.5% at
even odds, 75% at +7, 90% at +12) is at least its **attack bar**: goblins 45%, orcs and undead 50%, kobolds 90%, the
kingdom's marches 75%. Its **targets** say whom: goblins and undead go for the kingdom and unheld places, and other
monsters only where they're weak (a 75% chance); orcs and kobolds anyone; elementals only the kingdom. Kobolds stay
within 2 links of their capital. Elementals raid from their capital in **bursts**: once it has twice the target's
defense in military, until the odds turn, then they fall back. Goblins, kobolds, elementals and the kingdom **secure**
a place they take for 2 turns before raiding again. Orcs hit 20% harder. The undead **raise** a third of a battle's
fallen (both sides') as their own, and the land they hold gains a level of **blight** danger a turn, up to 5, until
it's taken from them.

Fortification also defends a stronghold or site when a nation comes to take it.

## Capitals

Each nation can have a **capital** (a `capital` node). It can never be taken or cleared, and the land around it stays
dangerous: at least danger 6 at its heart, fading out over 360 blocks (`RealmCapitals`).

- While the nation holds any stronghold, its capital just waits.
- Once it has lost them all, it's **pushed back**. Building military now goes to the capital, and each turn the
  capital **rallies**. Every 3 turns of rallying, its **champion** marches to retake the nearest stronghold the
  nation doesn't hold. With none left to retake, it marches on the nearest kingdom town instead. The
  champion is one of the nation's dungeon bosses, tier 1 plus 1 per 20 of the capital's military, up to 4. A rolled
  champion raid gets +10 attack; a fought one brings the champion with the last wave. Retaking a stronghold ends
  the rally: the nation is back in the field and takes normal turns again.
- Players can call the champion out at the capital with `/challenge` (within 48 blocks of it, once the nation is
  pushed back, one champion at a time).
- **Killing the champion** costs the capital a third of its military and resets its rally. A champion that wins its
  raid, or kills its challenger, adds 5 military.

**The kingdom's capital.** The kingdom can have one capital too (made with the kingdom as owner; it can't be taken
or cleared after that). It's never raided and never rallies. Its army can march out from it like from any kingdom
town, its champion leads every march (+10 attack, rolled like all the kingdom's marches; it has no name yet), and it
pays 1 into the treasury when the kingdom gathers wealth.

## Raids

A raid's target is a town (to sack), or a place to take: a stronghold or site the nation doesn't hold, or a town
knocked down to ruins. A town above ruins can only be sacked; a bad sacking knocks it down a tier, so enough of them
leave it in ruins for the next win to take. The kingdom's marches work the same way on monster-held towns. At dusk a
nation's raid is fought if a player is within 48 blocks of the target; the kingdom's are always rolled:

- attack = the stronghold's military (+10 for a champion), or half the kingdom's army (+10 for its champion, with a capital), + d20
- defense = the target's defense (a town's defense, or a stronghold's or site's fortification), plus a quarter of
  the kingdom's army if it's the kingdom's, + d20

A fought raid on a kingdom-held stronghold or site is defended by a small garrison of guards. An unheld one has none.

A fought raid uses the damage each side does instead.

| Result | What happens |
| --- | --- |
| Attackers win by a margin | The town loses defense (a quarter of the margin, at least 1), wealth (2 + a quarter of the margin) and 2 morale, and takes block damage equal to the margin. A margin of 10 or more also knocks it down a tier (to ruins from a hamlet) and stops any upgrade. The attackers lose 1 military, and a monster nation's gain plunder: 1 + half the wealth taken |
| Attackers win, against a stronghold, site or ruins | They take it, and move up to 5 military from the attacker (or the army) into it as a garrison: a stronghold's military, a town's defense. News either way |
| Defenders win | The stronghold loses 2 military, plus a quarter of the margin, and a town gains 1 morale |

## Town guards

A kingdom town above ruins keeps a standing garrison (`RealmGarrison`): as many guards as it would turn out for a raid
(tier + 1 + morale / 5, up to 12), standing whenever a player is within 48 blocks of it.

- **Posts:** each guard has one, the town's own in turn (`/realm guards <town> post add` where you stand, `post clear`),
  or a ring 8 blocks round its middle.
- **Leaving a post:** for a raid on the town, a monster within 12 blocks, a mob going for one of its villagers, or
  whatever attacks the guard. Monsters go for guards they see, as they do villagers.
  Outside a raid it gives up a chase 32 blocks from its post, and walks back.
- **Raids** are fought by the garrison as it stands; the survivors go back to their posts after.
- **Losses:** a guard that dies stays dead until the next turn (the town's `fallen` stat), so a raid's losses carry over.
  Guards that just unloaded come back when a player does.
- A town that's lost, falls to ruins or loses morale sends home the guards it can no longer keep.

## What players change

Players affect these choices only through the numbers the factions read:

- Fighting raids changes their outcome: more defense, morale and wealth for the town, less military for the nation.
- Taking or clearing a stronghold (`/realm node owner`, for now) removes it from its nation. Its land calms, and the
  nation loses that much military and those border links. The nation will try to take it back: from a neighbouring
  stronghold if it has one, or with its champion once it's pushed back.
- Admins can set any stat (`/realm node set`) to steer a world.

## Not decided yet

- Nations don't yet found new places or work together (they do fight each other, by their targets).
- The kingdom doesn't yet attack strongholds itself; for now that's the players' job. It only marches on towns.
- The mod doesn't spawn villagers yet; a town's tier is only its numbers, guards and growth for now.
