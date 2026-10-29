package io.github.ranger3462.ddkingdom.realm;

import java.util.Optional;

import net.minecraft.world.item.DyeColor;

/**
 * A guard a town turns out for a live raid: the swordsmen and crossbowmen ({@code TownGuard}), and battlemages when
 * Iron's Spells is installed ({@code TownMage}). The town sets its look and kit.
 */
public interface RealmGuard {
    void setSkin(String skin);

    /** Kits it out for its town: by wealth, defense and tier, in the town's color if it has one. */
    void equip(String townName, int wealth, int defense, int tier, Optional<DyeColor> color);
}
