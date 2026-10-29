package io.github.ranger3462.ddkingdom.realm;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The realm's economy (DD-103, docs/design/realm-factions.md section 5): every faction keeps a purse of <b>food</b> and
 * <b>trade</b>, filled each turn by what it holds.
 * <ul>
 * <li>Income: each town gives food and trade by its tier, each stronghold 1 of each, a capital 3 of each, and each
 * resource node what its kind gives ({@link Kind}), twice over for a faction with an {@code affinity} for that kind.</li>
 * <li>Food feeds troops ({@link #upkeep}: 1 a turn per {@link #TROOPS_PER_FOOD}) and pays for towns growing
 * ({@link RealmTiers}). A faction that can't feed its troops loses some to desertion.</li>
 * <li>Trade pays for building up, fortifying and mustering ({@link #ACTION_COST} an action; without it the action gives
 * half), and makes them stronger: points scale by {@link #factor}, 1 + trade / 50, up to 2.</li>
 * </ul>
 * Resource nodes also work where they stand: a mine adds defense to its holder's linked places, a lumber camp adds to
 * its holder's fortifying, and an arcane site strengthens its holder's champion.
 */
public final class RealmEconomy {
    public static final String FOOD = "food";
    public static final String TRADE = "trade";
    /** What a faction's purse starts with, of each. */
    public static final int START = 10;
    /** Trade an action that builds (military, fortifications, the army) costs. */
    public static final int ACTION_COST = 2;
    /** Troops one food a turn feeds. */
    public static final int TROOPS_PER_FOOD = 20;
    /** Food a stronghold and a capital grow for themselves a turn: enough that a nation's home can feed a real garrison. */
    public static final int STRONGHOLD_FOOD = 3;
    public static final int CAPITAL_FOOD = 8;
    /** Trade for each step of the wealth factor's 1: at 50 trade, points are doubled. */
    public static final int FACTOR_TRADE = 50;
    public static final double MAX_FACTOR = 2.0;
    /** Defense a mine adds to each of its holder's places linked to it. */
    public static final int MINE_DEFENSE = 3;
    /** Fortifying points each lumber camp adds, up to {@link #MAX_LUMBER} camps. */
    public static final int MAX_LUMBER = 3;
    /** Champion strength each arcane site adds. */
    public static final int ARCANE_CHAMPION = 2;

    /** What a resource node is, and what it gives its holder each turn. */
    public enum Kind {
        FARMLAND(3, 0, "+3 food a turn"),
        MINE(0, 1, "+" + MINE_DEFENSE + " defense to its holder's linked places, +1 trade a turn"),
        LUMBER_CAMP(0, 1, "+1 to its holder's fortifying, +1 trade a turn"),
        MARKET(0, 3, "+3 trade a turn"),
        ARCANE_SITE(0, 1, "+" + ARCANE_CHAMPION + " to its holder's champion, +1 trade a turn");

        public final int food;
        public final int trade;
        public final String gives;

        Kind(int food, int trade, String gives) {
            this.food = food;
            this.trade = trade;
            this.gives = gives;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** A kind by its id or a common other name: "lumber", "port", "ley_stone" and so on. */
        public static Optional<Kind> byId(String id) {
            return Optional.ofNullable(switch (id.toLowerCase(Locale.ROOT)) {
                case "farmland", "farm", "fields" -> FARMLAND;
                case "mine", "iron_mine" -> MINE;
                case "lumber_camp", "lumber", "forest" -> LUMBER_CAMP;
                case "market", "port", "crossing" -> MARKET;
                case "arcane_site", "arcane", "ley_stone" -> ARCANE_SITE;
                default -> null;
            });
        }
    }

    /** A turn's takings: food and trade in, food eaten. */
    public record Income(int food, int trade, int upkeep) {}

    private RealmEconomy() {}

    public static int food(Realm realm, String faction) {
        return realm.purse(faction).getOrDefault(FOOD, START);
    }

    public static int trade(Realm realm, String faction) {
        return realm.purse(faction).getOrDefault(TRADE, START);
    }

    /** The kind of a resource node, if it's one with a kind this knows. */
    public static Optional<Kind> kind(RealmNode node) {
        return node.type == RealmNode.Type.RESOURCE ? Kind.byId(node.kind()) : Optional.empty();
    }

    /** How many resource nodes of a kind a faction holds. */
    public static int holding(Realm realm, String faction, Kind kind) {
        return (int) realm.nodes().stream().filter(node -> faction.equals(node.owner()) && kind(node).orElse(null) == kind).count();
    }

    /** What a faction's holdings bring in a turn, and what its troops eat. */
    public static Income income(Realm realm, String faction) {
        String affinity = RealmFactions.get(faction).affinity();
        int food = 0, trade = 0;
        for (RealmNode node : realm.nodes()) {
            if (!faction.equals(node.owner())) {
                continue;
            }
            switch (node.type) {
                case TOWN -> {
                    if (!RealmTiers.upgrading(node)) {
                        food += RealmTiers.tier(node);
                        trade += RealmTiers.tier(node);
                    }
                }
                case STRONGHOLD -> {
                    food += STRONGHOLD_FOOD;
                    trade++;
                }
                case CAPITAL -> {
                    food += CAPITAL_FOOD;
                    trade += 3;
                }
                case RESOURCE -> {
                    Optional<Kind> kind = kind(node);
                    if (kind.isPresent()) {
                        int times = kind.get().id().equals(affinity) ? 2 : 1;
                        food += kind.get().food * times;
                        trade += kind.get().trade * times;
                    }
                }
                default -> {}
            }
        }
        return new Income(food, trade, upkeep(realm, faction));
    }

    /** The troops a faction feeds: the kingdom's army, or a nation's military in its strongholds and capital. */
    public static int troops(Realm realm, String faction) {
        if (RealmNode.KINGDOM.equals(faction)) {
            return realm.kingdomStat("army");
        }
        return realm.nodes().stream().filter(node -> faction.equals(node.owner())
                && (node.type == RealmNode.Type.STRONGHOLD || node.type == RealmNode.Type.CAPITAL)).mapToInt(node -> node.stat("military")).sum();
    }

    /** The food a faction's troops eat a turn: 1 per {@link #TROOPS_PER_FOOD}. */
    public static int upkeep(Realm realm, String faction) {
        return troops(realm, faction) / TROOPS_PER_FOOD;
    }

    /** The factions with a purse to fill: the kingdom, and every nation holding anything. */
    public static Set<String> factions(Realm realm) {
        Set<String> factions = new LinkedHashSet<>();
        factions.add(RealmNode.KINGDOM);
        realm.nodes().stream().filter(RealmNode::monsters).forEach(node -> factions.add(node.owner()));
        return factions;
    }

    /**
     * Fills every faction's purse for a turn and feeds its troops. A faction that runs out of food loses the troops it
     * couldn't feed: from the army, or from its biggest garrison.
     */
    public static void collect(Realm realm) {
        for (String faction : factions(realm)) {
            Income income = income(realm, faction);
            add(realm, faction, TRADE, income.trade());
            int food = food(realm, faction) + income.food() - income.upkeep();
            if (food >= 0) {
                set(realm, faction, FOOD, food);
                continue;
            }
            set(realm, faction, FOOD, 0);
            int hungry = -food * TROOPS_PER_FOOD / 4; // A quarter of those who went unfed slip away.
            int deserted = desert(realm, faction, Math.max(1, hungry));
            if (deserted > 0) {
                String name = RealmNode.KINGDOM.equals(faction) ? "the kingdom" : "the " + RealmFactions.get(faction).displayName();
                realm.news(Realm.LogKind.SUPPLY, faction, "Food has run out for " + name + ": " + deserted + (deserted == 1 ? " troop deserts." : " troops desert."));
            }
        }
    }

    private static int desert(Realm realm, String faction, int count) {
        if (RealmNode.KINGDOM.equals(faction)) {
            int lost = Math.min(realm.kingdomStat("army"), count);
            realm.addKingdomStat("army", -lost);
            return lost;
        }
        Optional<RealmNode> biggest = realm.nodes().stream().filter(node -> faction.equals(node.owner())
                && (node.type == RealmNode.Type.STRONGHOLD || node.type == RealmNode.Type.CAPITAL)).max(java.util.Comparator.comparingInt(node -> node.stat("military")));
        if (biggest.isEmpty()) {
            return 0;
        }
        int lost = Math.min(biggest.get().stat("military"), count);
        realm.addStat(biggest.get(), "military", -lost);
        return lost;
    }

    /** How much a faction's trade multiplies its actions: 1 + trade / 50, up to 2. */
    public static double factor(Realm realm, String faction) {
        return Math.min(MAX_FACTOR, 1.0 + Math.max(0, trade(realm, faction)) / (double) FACTOR_TRADE);
    }

    /**
     * Pays for a building action, and returns the points it gives: {@code base} by the faction's growth and wealth
     * factor, halved if it couldn't pay (at least 1).
     */
    public static int spend(Realm realm, String faction, int base) {
        double points = base * RealmFactions.get(faction).growth() * factor(realm, faction);
        if (trade(realm, faction) >= ACTION_COST) {
            add(realm, faction, TRADE, -ACTION_COST);
        } else {
            points /= 2;
        }
        return Math.max(1, (int) Math.round(points));
    }

    /** The extra fortifying points a faction's lumber camps give. */
    public static int lumber(Realm realm, String faction) {
        return Math.min(MAX_LUMBER, holding(realm, faction, Kind.LUMBER_CAMP));
    }

    /** The defense a place gets from its holder's mines linked to it. */
    public static int mineDefense(Realm realm, RealmNode node) {
        if (!node.kingdoms() && !node.monsters()) {
            return 0;
        }
        return MINE_DEFENSE * (int) realm.neighbors(node.id).stream()
                .filter(other -> node.owner().equals(other.owner()) && kind(other).orElse(null) == Kind.MINE).count();
    }

    /** The strength a faction's arcane sites add to its champion. */
    public static int arcane(Realm realm, String faction) {
        return ARCANE_CHAMPION * holding(realm, faction, Kind.ARCANE_SITE);
    }

    /** Spends food if the faction has it; returns whether it did. */
    public static boolean spendFood(Realm realm, String faction, int amount) {
        if (food(realm, faction) < amount) {
            return false;
        }
        add(realm, faction, FOOD, -amount);
        return true;
    }

    /** Takes up to {@code amount} trade from one faction and gives it to another; returns how much. */
    public static int plunder(Realm realm, String from, String to, int amount) {
        int taken = Math.min(Math.max(0, trade(realm, from)), amount);
        add(realm, from, TRADE, -taken);
        add(realm, to, TRADE, taken);
        return taken;
    }

    public static void add(Realm realm, String faction, String what, int amount) {
        set(realm, faction, what, (FOOD.equals(what) ? food(realm, faction) : trade(realm, faction)) + amount);
    }

    public static void set(Realm realm, String faction, String what, int amount) {
        if (RealmNode.NONE.equals(faction) || faction.isEmpty()) {
            return;
        }
        realm.setPurse(faction, what, amount);
    }

    /** The kinds' ids, for commands. */
    public static List<String> kindIds() {
        return java.util.Arrays.stream(Kind.values()).map(Kind::id).toList();
    }
}
