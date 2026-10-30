package io.github.ranger3462.ddkingdom.sim;

import com.google.gson.annotations.SerializedName;

/** The kinds of node in the ruleset, each with its tier names (Tier 0 is always ruins). */
public enum NodeType {
    @SerializedName("capital") CAPITAL("Capital", "Ruins", "Hamlet", "Village", "Town", "City", "Fortified City"),
    @SerializedName("town") TOWN("Town", "Ruins", "Hamlet", "Village", "Town", "City", "Fortified City"),
    @SerializedName("castle") CASTLE("Castle", "Ruins", "Hold", "Fort", "Citadel"),
    @SerializedName("production") PRODUCTION("Production", "Ruins", "Small Survey Site", "Rich Survey Site", "Abundant Survey Site"),
    @SerializedName("food") FOOD("Food", "Ruins", "Garden", "Farm", "Plantation"),
    @SerializedName("outpost") OUTPOST("Outpost", "Ruins", "Watchtower");

    public final String label;
    private final String[] tiers;

    NodeType(String label, String... tiers) {
        this.label = label;
        this.tiers = tiers;
    }

    public int maxTier() {
        return tiers.length - 1;
    }

    public String tierName(int tier) {
        return tier >= 0 && tier < tiers.length ? tiers[tier] : "T" + tier;
    }

    /** A food or production node: garrison and defense are capped low. */
    public boolean resource() {
        return this == FOOD || this == PRODUCTION;
    }

    /** Where troops can be hired into: the capital, towns and castles. */
    public boolean musters() {
        return this == CAPITAL || this == TOWN || this == CASTLE;
    }
}
