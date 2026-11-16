package io.github.ranger3462.ddkingdom.realm;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The realm's economy as the world sees it (docs/design/realm-cutover.md): each faction's <b>purse</b> is its food,
 * and its gold as {@code trade}, mirrored from the simulator ({@link RealmSimTurns}), which keeps the stockpiles and
 * spends them. Resource nodes are the simulator's food and production nodes.
 */
public final class RealmEconomy {
    public static final String FOOD = "food";
    public static final String TRADE = "trade";

    /** What a resource node is, and what it gives its holder. */
    public enum Kind {
        FARMLAND("food a turn"),
        MINE("resources a turn");

        public final String gives;

        Kind(String gives) {
            this.gives = gives;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A kind by its id or a common other name. */
        public static Optional<Kind> byId(String id) {
            return Optional.ofNullable(switch (id.toLowerCase(Locale.ROOT)) {
                case "farmland", "farm", "fields" -> FARMLAND;
                case "mine", "iron_mine", "quarry" -> MINE;
                default -> null;
            });
        }
    }

    /** A round's takings: food, gold and resources in, and the food and gold its troops cost. */
    public record Income(int food, int gold, int res, int upkeepFood, int upkeepGold) {}

    private RealmEconomy() {}

    public static int food(Realm realm, String faction) {
        return realm.purse(faction).getOrDefault(FOOD, 0);
    }

    /** A faction's gold. */
    public static int trade(Realm realm, String faction) {
        return realm.purse(faction).getOrDefault(TRADE, 0);
    }

    /** The kind of a resource node, if it's one with a kind this knows. */
    public static Optional<Kind> kind(RealmNode node) {
        return node.type == RealmNode.Type.RESOURCE ? Kind.byId(node.kind()) : Optional.empty();
    }

    /** A faction's takings a round, by the simulator's rules; nothing if the realm hasn't been started by them. */
    public static Income income(Realm realm, String faction) {
        return RealmSimTurns.income(realm, faction);
    }

    /** The kinds' ids, for commands. */
    public static List<String> kindIds() {
        return java.util.Arrays.stream(Kind.values()).map(Kind::id).toList();
    }
}
