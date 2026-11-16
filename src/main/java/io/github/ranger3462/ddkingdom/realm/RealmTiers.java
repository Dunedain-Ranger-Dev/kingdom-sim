package io.github.ranger3462.ddkingdom.realm;

import net.minecraft.util.Mth;

/**
 * Town tiers (DD-99): ruins (tier 0) to a city (tier 5), mirrored from the simulator ({@link RealmSimTurns}), which
 * grows them; {@code upgrading} is the rounds an upgrade has left. A town's tier makes its guards stronger (see
 * {@code TownGuard.equip}), and so higher level; ruins have none.
 */
public final class RealmTiers {
    public static final int MAX_TIER = 5;
    private static final String[] NAMES = { "ruins", "hamlet", "village", "town", "market town", "city" };

    private RealmTiers() {}

    /** A town's tier: 0 (ruins) to {@link #MAX_TIER}. */
    public static int tier(RealmNode town) {
        return Mth.clamp(town.stat("tier"), 0, MAX_TIER);
    }

    public static String name(int tier) {
        return NAMES[Mth.clamp(tier, 0, MAX_TIER)];
    }

    /** Whether a town is in the middle of upgrading, and so gets no other support. */
    public static boolean upgrading(RealmNode town) {
        return town.stat("upgrading") > 0;
    }
}
