package io.github.ranger3462.ddkingdom.integration.deepmines;

import io.github.ranger3462.deepmines.MinesEntrance;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;

/**
 * Kingdom Sim with Deep Mines: the dwarves' mine gate takes players into the Mines. Only touches Deep Mines' classes
 * when it's installed. With Kingdom Sim installed, Deep Mines closes its own portal (its {@code portal} setting, AUTO).
 */
public final class DeepMinesLink {
    private static final boolean LOADED = ModList.get().isLoaded("deepmines");

    private DeepMinesLink() {}

    public static boolean installed() {
        return LOADED;
    }

    /** Takes a player into the Mines, to their start hall. Whether they went. */
    public static boolean enter(ServerPlayer player) {
        return LOADED && Mines.enter(player);
    }

    /** Kept apart so its class is only loaded with Deep Mines installed. */
    private static final class Mines {
        static boolean enter(ServerPlayer player) {
            return MinesEntrance.enter(player);
        }
    }
}
