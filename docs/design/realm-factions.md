# Realm factions: who they are and how they play (proposal)

From the kingdom behaviour writeup (Zach, 2026-10-27). This is the plan for the next round of realm work. Today's
rules are in `realm-decisions.md`; this page says what changes. Phases 1 (DD-102) and 2 (DD-103) are built; the rest isn't yet. Questions still open are
at the end.

## The factions

| Faction | Capital | Leader | Champion | Mobs today |
| --- | --- | --- | --- | --- |
| Goblins | Gristlehold (Goblin Wilds) | Warlord Skarrik Ironjaw | Nixa the Tinker-Queen | Yes (goblins, hobgoblins) |
| Orcs | Khar Dumak (Orc Steppe) | Warchief Ghorza Bloodmane | Durhak the Unbroken | Yes |
| Kobolds and dragons | Vyrnhallow (Western Peaks) | Azhvorrath, the Ashen Wyrm | High Scalecaller Rixx | Not yet (DD-54) |
| Undead | Morvane (Blightmarsh) | The Pale Regent, Lord Caelith Morvane | The Hollow Knight, Ser Varric Thane | Yes |
| Elementals | The Shattered Spire (Frostreach) | The Conclave of Four | Pyrrhis the Unbound | Not yet |
| Hearthbound Kingdom (players) | Landfall | Queen Aldra Vayne | Ser Bram Hollowell | Town guards |

The champion names go on the champion bosses and in the news ("Durhak the Unbroken leads the orcs of ... against
...").

## 1. Traits: each faction plays its own way

Today every monster nation uses the same table of weights. Instead each faction gets a **traits** file
(`data/dungeondelving/realm/factions/<faction>.json`), so its behaviour can be tuned without code changes. The turn
logic reads the traits; the weights stay random, so factions are likely to act in character but aren't scripted.

**When to attack: the odds.** A rolled battle is attack + d20 against defense + d20, so every possible attack has an
exact chance of winning. Each faction attacks only when its chance is above its own bar:

| Faction | Attacks when | Bar (chance to win) |
| --- | --- | --- |
| Goblins | It's a toss-up, but not a long shot | 45% |
| Orcs | Anything weak enough, not held by orcs | 50%, and the keenest to raid (aggression 2) |
| Kobolds | Success is almost guaranteed | 90% |
| Elementals | Their capital is at least 2x the nearest kingdom node, then keep going until the army is weaker than the next node | Burst rule, see below |
| Kingdom | It's fairly confident | 75% |
| Undead | It's an even fight or better | 50% |

**The traits:**

| Trait | What it does | Goblins | Orcs | Kobolds | Elementals | Kingdom |
| --- | --- | --- | --- | --- | --- | --- |
| `growth` | Multiplies the points every action gives | 1.4 | 1.0 | 0.7 | 0.7 | 1.0 |
| `attack_bar` | Lowest chance to win it attacks at (above) | 0.45 | 0.5 | 0.9 | 0.5 in a burst | 0.75 |
| `aggression` | How keen it is to raid once ready | 1.5 | 2.0 | 0.7 | 1.0 | 1.0 |
| `targets` | Who it attacks | Kingdom; other monsters only if weakened | Anyone not orc | Anyone near home | Kingdom only | Monsters |
| `fortify_after_capture` | After taking a place, fortify it before moving on | Yes | No | Yes | Yes | Yes |
| `retake` | How keen it is to win back what it lost | High | Low | High near home | Low | High |
| `reserve` | Share of its army kept home at the capital | 10% | 30% | 50% | 100% between bursts | 20% |
| `roams` | The main army moves around instead of garrisoning | No | Yes | No | No | No |
| `home_radius` | How far out it holds land in earnest (links from the capital) | - | - | 2 | - | - |
| `tithe` | Share of distant holdings' wealth sent to the capital | 0 | 0 | 75% | 0 | 0 |
| `military_bonus` | Extra strength per troop | 0 | +20% | Dragons at high tiers | 0 | 0 |

## 2. Wealth drives growth

Each faction keeps a **treasury**, filled each turn by what it holds (see resource nodes below). Every action's points
scale with how rich the faction is:

> points = base x growth x wealth factor, where the wealth factor is 1 + treasury / 50, capped at 2.

So a very wealthy faction builds, fortifies and musters up to twice as fast. Actions also **cost** wealth: troops
need upkeep (below), and fortifying costs coin. A faction can't grow forever on nothing.

## 3. Capitals are towns

A faction's capital becomes a real settlement: a city-tier town with defense, wealth, morale and a garrison of troops.
For the monster nations it's built in their style. What's special about it:

- **It can't be captured** outside a `/challenge` (the champion fight).
- **It can be sacked.** A raid that wins steals wealth from it, but the defenders get a **huge bonus**: x2 defense,
  so only a much stronger army tries.
- It's where new troops muster and where a faction pulls back to.

The kingdom's Landfall works the same way; monster nations can sack it on very good odds.

## 4. Armies: real troop counts

Today "army" (kingdom) and "military" (strongholds) are single numbers that go up and down. Instead, **troops** are
counted where they are:

- **Garrisons:** every settlement, stronghold and capital holds some troops. A place's defense is its fortification
  plus its garrison (with any trait bonus).
- **Field armies:** troops on the move between linked nodes, one link a turn, visible on the realm map with a count.
  A raid is an army arriving; it fights with the troops it brought.
- **Losses** come off the troop count of whichever side lost them. A fought raid's dead are exact: every raider or
  guard killed is one troop.
- **Upkeep:** each troop costs a little wealth or food a turn. A broke faction's troops desert.
- **Mustering** adds troops at the capital (and at big towns), using the points from section 2.

**The kingdom spreads its troops.** Each turn it moves troops along links so each frontier town's garrison matches its
need: how much enemy strength is next to it. A town under attack, or just raided, gets more. Inner towns keep only a
token guard, and the capital keeps its reserve.

**The town guards are the garrison.** The standing guards (`RealmGarrison`) become the town's troops made visible, up
to the most a town can show (12). A town with 30 troops shows 12 guards; the rest fight as reinforcement waves in a
live raid.

## 5. Resource nodes

A new node type, **resource**, each with a kind and what it gives whoever holds it each turn:

| Kind | Gives | Example |
| --- | --- | --- |
| Farmland | Food | Fields round a town |
| Mine | Ore: defense for linked settlements, and troops' gear | Iron mine |
| Lumber camp | Fortification points | Forest camp |
| Market or port | Trade (wealth) | River crossing |
| Arcane site | Spell power for champions, elementals' growth | Ley stone |

Towns give some wealth too, by tier. Holding resources gives every faction something to fight over besides towns, and
the map's resource layout shapes each campaign.

**Food and trade (option).** Wealth could be split in two: **food** (pays troop upkeep and town growth) and **trade**
(pays fortification, upgrades and mustering). Two stats make farmland and markets matter in different ways, at the
cost of more numbers on the map. The other option is one **wealth** stat, with farmland and markets both giving it.

## 6. Building it, in phases

1. **Traits and odds** (small): the faction files, the attack bar from real odds, and growth rates. Names for leaders
   and champions in the news. This fixes "too passive" right away.
2. **Wealth and resource nodes**: the treasury, the wealth factor, the resource node type and its yields.
3. **Capitals as towns**: settlements that can be sacked but not taken, with the defender bonus.
4. **Troop counts**: garrisons, field armies, upkeep, the kingdom spreading its troops, and guards as garrison.
   This is the biggest change, so it goes last, once the economy it depends on works.

The kobolds and elementals can join the realm in phase 1 with stand-in mobs, or wait for their own mobs (DD-54).

## Decided (2026-10-27)

- **Undead: relentless risers.** Slow growth, but after every battle they raise the dead of both sides as new troops
  (a share of all the troops lost in it). They never pull back from a fight they started, and the land they hold
  grows more dangerous each turn they hold it. Attack bar 50%; `retake` high; `reserve` 10%.
- **Wealth splits into food and trade.** Food pays troop upkeep and town growth; trade pays fortifying, upgrades and
  mustering. The wealth factor in section 2 uses trade.
- **Kobolds and elementals join now**, with stand-in mobs until their own exist (DD-54).
- **Order:** the phases as written.

## Built in phase 2 (DD-103)

- **Purses:** every faction keeps food and trade (starting at 10 each). The kingdom's old treasury became its trade.
- **Income each turn:** a town gives food and trade equal to its tier; a stronghold 3 food and 1 trade; a capital 8 food and 3 trade (a capital alone feeds 160 troops);
  resources by kind (below), twice over for a faction's `affinity` (elementals: arcane sites; kobolds: mines).
- **Food:** troops eat 1 a turn per 20 (the kingdom's army; a nation's strongholds and capital). A faction that runs
  out loses a quarter of the unfed troops to desertion, with news. Growing a town costs its holder 5 food per tier it
  grows to (ruins resettle free).
- **Trade:** building up, fortifying, reinforcing and mustering cost 2 trade; without it they give half. Their points
  scale by 1 + trade / 50, up to x2. A sack carries off the defender's trade as well as the town's wealth.
- **Resource nodes** (`/realm node add <id> resource [owner]`, then `/realm node kind <id> <kind>`):

| Kind | Gives its holder |
| --- | --- |
| farmland | +3 food a turn |
| market (or port) | +3 trade a turn |
| mine | +3 defense to each of its holder's places linked to it, +1 trade |
| lumber_camp | +1 to its holder's fortifying and reinforcing (up to 3 camps), +1 trade |
| arcane_site | +2 to its holder's champion, +1 trade |

  Anyone can take a resource node it doesn't hold; the kingdom marches on them before sacking towns.
- `/realm purse <faction> [food|trade <amount>]` shows or sets a purse.

Still to come: `tithe` (needs wealth moving between places) and real troop upkeep and gear (phase 4).


## Built in phase 3 (DD-104)

- **Capitals are cities:** every capital has a town's defense (20), wealth (20) and morale (10) at city tier, plus its
  garrison (its military, 30 to start). Capitals made before this become cities when the world loads.
- **Sacked, never taken:** a raid that beats a capital sacks it like a town (defense, wealth, morale and trade lost,
  and for a monster capital some of its garrison), but it never changes hands or drops a tier.
- **Hard to beat:** a capital's defense is doubled, and a monster capital's garrison adds half its military to it
  first. Nobody attacks a capital below 85% odds. The kingdom marches on a linked monster capital only then; a nation
  sacks Landfall from a stronghold linked to it.
- **Home:** a nation's new troops muster at its capital until its garrison is back to 30, and half the troops of a
  stronghold it loses fall back there. Capitals gain wealth each turn and are fortified with the rest of its holdings;
  the kingdom's capital is reinforced with its border towns and turns out a garrison of guards like a town.
- Still to come: building capitals in each nation's style (blueprints).
