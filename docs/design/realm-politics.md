# Realm politics: council pressure

Decided 2026-11-01 (the user, from the 2026-10-30 draft). Kept deliberately simple: a balancing game for players, who
tip it by doing the quests each group posts.

## The idea

Queen Aldra rules the Hearthbound, and that never changes. Around her, five groups press the council to do what suits
them, and the more **influence** a group has, the more the kingdom leans toward its goals. Players raise a group's
influence by completing the quests it posts. They never vote, and they can't change who rules.

With no players doing quests, every group sits at the same influence and politics changes nothing: the kingdom plays as
it always has. Only the Hearthbound are affected; the monster nations keep their own personalities.

## The groups

| Group | Who | Presses for |
| --- | --- | --- |
| **The Crown** | The queen's court and the noble houses | Holding land and defending it |
| **The Merchant Houses** | The merchant houses and traders | Building towns up and producing |
| **The Watch** | The guard and the mercenary guild | Troops and fortifications |
| **The Adventurers' Guilds** | The adventurer guilds | Taking new nodes and raiding the monsters |
| **The Craft Guilds** | The class, profession and skill guilds | Upgrades and repairs |

## How it leans the kingdom

Each group's pull on each action (its **affinity**) is in `rules.json` (`politics.groups`):

| Action | Crown | Merchants | Watch | Adventurers | Craft guilds |
| --- | --- | --- | --- | --- | --- |
| Capture a node, Heroic conquest | +1 | -1 | +1 | +2 | -1 |
| Raid | -1 | +1 | +1 | +2 | 0 |
| Hire soldiers | +1 | -1 | +2 | 0 | 0 |
| Fortify, reinforce the border | +2 | 0 | +1 | -1 | 0 |
| Send reinforcements | +1 | 0 | +1 | 0 | 0 |
| Upgrade a node | +1 | +2 | 0 | 0 | +2 |
| Produce resources | 0 | +2 | -1 | -1 | +1 |
| Import food | 0 | +1 | 0 | 0 | 0 |
| Repair a node | 0 | 0 | 0 | 0 | +2 |

- Each group's **share** of the council is its influence over everyone's.
- The **lean** on an action adds up each group's share above or below its usual share, times its affinity. With every
  group at its usual share, the lean is 0.
- The Hearthbound AI's score for the action moves by `politics.weight` × the lean (0.5 to start), so a group with more
  influence makes its kind of action look better and its dislikes look worse. The AI still weighs everything else as
  before.

## How influence moves

- **Quests:** each quest a player completes for a group adds `politics.questInfluence` to it (5 to start).
- **Fading:** each of the Hearthbound's turns, every group's influence moves `politics.decay` of the way back to its
  baseline (5%), so pressure that isn't kept up fades.
- **Baselines:** 20 each.

## Players

- Each player has their own **standing** with each group, from the quests they do for it. It's theirs alone: it doesn't
  depend on, or affect, anyone else's standing. (What standing gets a player is for later: quest access, rewards,
  prices.)
- The influence a quest adds is the same for every player and goes into the realm's shared council.

## In the simulator

There are no players in the simulator, so `politics.simQuests` stands in for them: that many quests a turn on average,
for `politics.simFavour` (a group's id) or, if that's blank, a random group each time. It's 0 by default, so a plain
batch plays exactly as before. The quests draw on their own random numbers, so turning them on doesn't change any other
roll in the game. The batch report shows each group's share at the end and how the Hearthbound spent their actions.

## Building it in the mod

After the realm switches to the new rules engine:

1. The council's influence saved with the realm, faded each turn, and leaning the Hearthbound's turns.
2. Quests from each group, each adding influence and the player's own standing when done.
3. The council on the realm screen: each group's share, and the player's standing with each.
