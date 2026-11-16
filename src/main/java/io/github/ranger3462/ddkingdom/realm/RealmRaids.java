package io.github.ranger3462.ddkingdom.realm;

import java.util.Optional;

/**
 * Raids in the world (DD-96, docs/design/realm-cutover.md): the realm's raids are the simulator's attacks held for
 * players, and champions' rallies ({@link RealmSimTurns}). At dusk each is fought live where a player is at its target
 * ({@link RealmLiveRaids}), or rolled here: the attackers' military plus a d20, against the target's defense plus a
 * d20. Either way, {@link #decide} hands the result to the simulator's realm, which settles what it means.
 */
public final class RealmRaids {
    /** A capital's defenders fight twice as hard (DD-104). */
    public static final int CAPITAL_DEFENSE = 2;

    private RealmRaids() {}

    /** A node's own defense: its defense, and a monster capital's garrison, half its military. */
    public static int ownDefense(RealmNode node) {
        return node.stat("defense") + (node.type == RealmNode.Type.CAPITAL && node.monsters() ? node.stat("military") / 2 : 0);
    }

    /** The defense a raid's target puts up, before its roll: its own, plus its troops; a capital's doubled. */
    public static int defense(Realm realm, RealmNode target) {
        int defense = ownDefense(target) + target.stat("military");
        return target.type == RealmNode.Type.CAPITAL ? defense * CAPITAL_DEFENSE : defense;
    }

    /** A raid's attack, before its roll: the attackers' military, and a champion's bonus from a capital. */
    public static int attack(Realm realm, Realm.Raid raid, RealmNode attacker) {
        return attacker.stat("military") + (attacker.type == RealmNode.Type.CAPITAL && !RealmNode.KINGDOM.equals(raid.nation()) ? RealmCapitals.CHAMPION_BONUS : 0);
    }

    /** Rolls one raid with these rolls (a d20 each), and decides it. Returns whether the attackers won. */
    public static boolean resolve(Realm realm, Realm.Raid raid, int attackRoll, int defenseRoll) {
        Optional<RealmNode> town = realm.node(raid.town());
        Optional<RealmNode> stronghold = realm.node(raid.stronghold());
        int attack = stronghold.map(node -> attack(realm, raid, node)).orElse(0) + attackRoll;
        int defense = town.map(node -> defense(realm, node)).orElse(0) + defenseRoll;
        return decide(realm, raid, attack, defense, false);
    }

    /**
     * Settles a raid on its attack and defense (a roll's totals, or a fight's scores), and takes it off the list: the
     * simulator's realm decides what it means ({@link RealmSimTurns#decided}). Returns whether the attackers won.
     */
    public static boolean decide(Realm realm, Realm.Raid raid, int attack, int defense, boolean fought) {
        if (!RealmSimTurns.decided(realm, raid, attack, defense, fought)) {
            realm.raids.remove(raid); // a realm not started by the simulator's rules has nothing to settle
            realm.setDirty();
        }
        return attack > defense;
    }
}
