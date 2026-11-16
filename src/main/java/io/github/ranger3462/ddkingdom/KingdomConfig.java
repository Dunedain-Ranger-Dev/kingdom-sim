package io.github.ranger3462.ddkingdom;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings in {@code config/ddkingdom-common.toml}. */
public final class KingdomConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue REALM_TURN_MINUTES = BUILDER
            .comment("Real minutes between the realm's rounds (docs/design/realm-cutover.md): a round every hour. Rounds keep coming",
                    "while the server is off, and are caught up when it starts (up to realmCatchUpTurns). (Called realmTurnMinutes, 20",
                    "by default, under the old rules.)")
            .defineInRange("realmRoundMinutes", 60, 1, 24 * 60);

    public static final ModConfigSpec.IntValue REALM_CATCH_UP_TURNS = BUILDER
            .comment("The most turns the realm catches up on when the server starts after being off. Any more are skipped, so a long",
                    "time away doesn't flatten the realm.")
            .defineInRange("realmCatchUpTurns", 24, 0, 1000);

    static final ModConfigSpec SPEC = BUILDER.build();

    private KingdomConfig() {}
}
