package io.github.ranger3462.ddkingdom.sim;

/** A node in a game: its type and place on the map (from its {@link SimMap.NodeSetup}), and its state now. */
public final class Node {
    public final String id, name;
    public final NodeType type;
    /** The faction whose homeland it is, whoever holds it; null for neutral land. */
    public final String home;
    public final double x, y;

    public int tier;
    /** Null for unowned land. */
    public String owner;
    public int garrison;
    /** Base defense, before caps, castle multipliers and morale. */
    public int defense;
    public int morale;
    public double devastation;
    /** An upgrade under way, or null. */
    public Upgrade upgrade;
    /** The last round it was attacked, and captured (null: never). */
    public Integer attackedRound, capturedRound;

    /** An upgrade to {@code target}, done in {@code turnsLeft} more turns; {@code free} for the rebuild after a capture. */
    public static final class Upgrade {
        public final int target;
        public int turnsLeft;
        public final boolean free;

        Upgrade(int target, int turnsLeft, boolean free) {
            this.target = target;
            this.turnsLeft = turnsLeft;
            this.free = free;
        }
    }

    Node(SimMap.NodeSetup s) {
        id = s.id;
        name = s.name;
        type = s.type;
        home = s.home;
        x = s.x;
        y = s.y;
        tier = s.tier;
        owner = s.owner;
        garrison = s.garrison;
        defense = s.defense;
        morale = s.morale;
        devastation = s.devastation;
    }

    public boolean ownedBy(String kid) {
        return kid != null && kid.equals(owner);
    }

    public String tierName() {
        return type.tierName(tier);
    }
}
