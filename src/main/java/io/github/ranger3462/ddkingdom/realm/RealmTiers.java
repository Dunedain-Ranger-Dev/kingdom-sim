package io.github.ranger3462.ddkingdom.realm;

import net.minecraft.util.Mth;

/**
 * Town tiers (DD-99, docs/design/realm.md). A town starts as ruins (tier 0) and grows to a city (tier 5), whoever holds
 * it. Each turn, a held town with wealth to spare (the tier-up's {@link #cost}, plus {@link #RESERVE} kept back for
 * repairs; ruins need nothing), whose holder has the {@link #food} for it, and no raid damage still being rebuilt spends it and starts upgrading. An upgrade takes
 * {@link #DAYS_PER_TIER} turns (days) for each tier it's growing to, and while it lasts the town gets no other support:
 * no reinforcing, no wealth, no repairs. Done, it goes up a tier and gains {@link #DEFENSE_PER_TIER} defense. Its tier
 * makes its guards stronger (see {@code TownGuard.equip}), and so higher level, and lets more of them turn out; ruins
 * have none, and only ruins can be captured (see {@link RealmRaids#capturable}).
 */
public final class RealmTiers {
    public static final int MAX_TIER = 5;
    /** Wealth a town keeps back after growing, so it can still pay for repairs. */
    public static final int RESERVE = 5;
    public static final int DEFENSE_PER_TIER = 3;
    public static final int FOOD_PER_TIER = 5;
    /** Turns (days) an upgrade takes, per tier it's growing to: 2 to a hamlet, 10 to a city. */
    public static final int DAYS_PER_TIER = 2;
    private static final String[] NAMES = { "ruins", "hamlet", "village", "town", "market town", "city" };

    private RealmTiers() {}

    /** A town's tier: 0 (ruins) to {@link #MAX_TIER}. */
    public static int tier(RealmNode town) {
        return Mth.clamp(town.stat("tier"), 0, MAX_TIER);
    }

    public static String name(int tier) {
        return NAMES[Mth.clamp(tier, 0, MAX_TIER)];
    }

    /** The wealth it takes to grow from {@code tier} to the next: 10 per tier (nothing from ruins, then 10, 20, 30, 40). */
    public static int cost(int tier) {
        return 10 * tier;
    }

    /** The food its holder spends to grow a town from {@code tier} to the next (DD-103): 5 per tier it grows to (ruins resettle free). */
    public static int food(int tier) {
        return FOOD_PER_TIER * (tier + 1);
    }

    /** The turns (days) it takes to upgrade to {@code tier}. */
    public static int days(int tier) {
        return DAYS_PER_TIER * tier;
    }

    /** Whether a town is in the middle of upgrading, and so gets no other support. */
    public static boolean upgrading(RealmNode town) {
        return town.stat("upgrading") > 0;
    }

    /**
     * Moves every held town's upgrade on a turn, finishing the ones that are done, and starts one in every town that can
     * afford it. Returns how many finished.
     */
    public static int grow(Realm realm) {
        int grown = 0;
        for (RealmNode town : realm.nodes()) {
            if (town.type != RealmNode.Type.TOWN || (!town.kingdoms() && !town.monsters())) {
                continue;
            }
            int tier = tier(town);
            if (upgrading(town)) {
                realm.addStat(town, "upgrading", -1);
                if (!upgrading(town)) {
                    realm.setStat(town, "tier", tier + 1);
                    realm.addStat(town, "defense", DEFENSE_PER_TIER);
                    realm.news(Realm.LogKind.TOWN, town.owner(), town.name() + " has grown into a " + name(tier + 1) + " (tier " + (tier + 1) + ")"
                            + (tier == 0 ? ": people are living there again." : ": its guards grow stronger."));
                    grown++;
                }
                continue;
            }
            boolean rebuilding = town.stat("wrecked") > town.stat("repair") || town.stat("damage") > 0;
            if (tier >= MAX_TIER || (tier > 0 && (rebuilding || town.stat("wealth") < cost(tier) + RESERVE))
                    || (tier > 0 && RealmEconomy.food(realm, town.owner()) < food(tier))) {
                continue;
            }
            realm.addStat(town, "wealth", -cost(tier));
            if (tier > 0) {
                RealmEconomy.spendFood(realm, town.owner(), food(tier));
            }
            realm.setStat(town, "upgrading", days(tier + 1));
            realm.news(Realm.LogKind.TOWN, town.owner(), town.name() + " has begun " + (tier == 0 ? "resettling" : "growing") + " into a " + name(tier + 1) + ": it'll take "
                    + days(tier + 1) + " days, and it can't be helped until then.");
        }
        return grown;
    }
}
