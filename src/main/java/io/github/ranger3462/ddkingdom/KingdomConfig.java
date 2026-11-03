package io.github.ranger3462.ddkingdom;

import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings in {@code config/ddkingdom-common.toml}. */
public final class KingdomConfig {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue REALM_TURN_MINUTES = BUILDER
            .comment("Real minutes between the realm's turns (docs/design/realm.md). 20 is a Minecraft day. Turns keep coming while",
                    "the server is off, and are caught up when it starts (up to realmCatchUpTurns).")
            .defineInRange("realmTurnMinutes", 20, 1, 24 * 60);

    public static final ModConfigSpec.IntValue REALM_CATCH_UP_TURNS = BUILDER
            .comment("The most turns the realm catches up on when the server starts after being off. Any more are skipped, so a long",
                    "time away doesn't flatten the realm.")
            .defineInRange("realmCatchUpTurns", 24, 0, 1000);

    public static final ModConfigSpec.ConfigValue<String> REALM_ENGINE = BUILDER
            .comment("Which rules the realm plays by: legacy (as it always has) or sim (the simulator's, docs/design/realm-cutover.md).",
                    "A sim realm is started with /realm sim start; until then, and while this is legacy, it waits.")
            .define("realmEngine", "legacy", value -> "legacy".equals(value) || "sim".equals(value));

    static final ModConfigSpec SPEC = BUILDER.build();

    /** Whether the realm plays by the simulator's rules. */
    public static boolean simEngine() {
        return "sim".equals(REALM_ENGINE.get());
    }

    private KingdomConfig() {}
}
