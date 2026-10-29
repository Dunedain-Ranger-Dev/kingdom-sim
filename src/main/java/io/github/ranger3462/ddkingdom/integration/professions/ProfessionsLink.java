package io.github.ranger3462.ddkingdom.integration.professions;

import io.github.ranger3462.ddprofessions.character.MobLevels;
import net.minecraft.world.entity.Mob;
import net.neoforged.fml.ModList;

/**
 * Kingdom Sim with Professions and Skills: a guard kitted out, or a monster made tougher by its area, has its level (on
 * its nameplate, and for skill experience) worked out again from its new stats. Only touches Professions and Skills'
 * classes when it's installed.
 */
public final class ProfessionsLink {
    private static final boolean LOADED = ModList.get().isLoaded("ddprofessions");

    private ProfessionsLink() {}

    /** Works a mob's level out again from its stats now, if Professions and Skills is there to show it. */
    public static void refreshLevel(Mob mob) {
        if (LOADED) {
            Levels.refresh(mob);
        }
    }

    /** Kept apart so its class is only loaded with Professions and Skills installed. */
    private static final class Levels {
        static void refresh(Mob mob) {
            MobLevels.refresh(mob);
        }
    }
}
