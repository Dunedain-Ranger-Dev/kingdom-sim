package io.github.ranger3462.ddkingdom.entity;

import java.util.Optional;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * The armor town guards wear, by role and gear tier (0 to 3, from {@link TownGuard#gearTier}). With the pack's RPG class
 * mods installed, each role wears its class's sets: swordsmen the paladin line (Paladins & Priests), crossbowmen the
 * archer line (Archers); tiers 0-1 the first set, 2 the second, 3 its netherite version. Battlemages wear Iron's Spells'
 * own mage armor (wandering magician, archevoker, netherite mage), since their renderer can't draw the Wizards robes.
 * Without them, vanilla armor: leather, chainmail, iron, diamond (battlemages always leather robes).
 */
public final class GuardGear {
    /** A guard's role, and the RPG sets it wears, worst to best. */
    public enum Role {
        SWORD("paladins", Pieces.RPG, "paladin_armor", "crusader_armor", "netherite_crusader_armor"),
        CROSSBOW("archers", Pieces.RPG, "archer_armor", "ranger_armor", "netherite_ranger_armor"),
        MAGE("irons_spellbooks", Pieces.IRONS, "wandering_magician", "archevoker", "netherite_mage");

        final String namespace;
        final String[] pieces;
        final String[] sets;

        Role(String namespace, String[] pieces, String... sets) {
            this.namespace = namespace;
            this.pieces = pieces;
            this.sets = sets;
        }
    }

    /** How each mod names head, chest, legs and feet. */
    private static final class Pieces {
        static final String[] RPG = { "head", "chest", "legs", "feet" };
        static final String[] IRONS = { "helmet", "chestplate", "leggings", "boots" };
    }
    private static final Item[][] VANILLA = {
            { Items.LEATHER_HELMET, Items.LEATHER_CHESTPLATE, Items.LEATHER_LEGGINGS, Items.LEATHER_BOOTS },
            { Items.CHAINMAIL_HELMET, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_BOOTS },
            { Items.IRON_HELMET, Items.IRON_CHESTPLATE, Items.IRON_LEGGINGS, Items.IRON_BOOTS },
            { Items.DIAMOND_HELMET, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_LEGGINGS, Items.DIAMOND_BOOTS } };

    private GuardGear() {}

    /** Head, chest, legs and feet for a role at a gear tier. */
    public static ItemStack[] armor(Role role, int gear) {
        Optional<Item[]> rpg = rpgSet(role, gear);
        Item[] items = rpg.orElse(role == Role.MAGE ? VANILLA[0] : VANILLA[Math.max(0, Math.min(3, gear))]);
        ItemStack[] stacks = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            stacks[i] = new ItemStack(items[i]);
        }
        return stacks;
    }

    /** The role's RPG set for a gear tier, if its mod is installed. */
    public static Optional<Item[]> rpgSet(Role role, int gear) {
        String set = role.sets[gear <= 1 ? 0 : gear == 2 ? 1 : 2];
        Item[] items = new Item[role.pieces.length];
        for (int i = 0; i < role.pieces.length; i++) {
            Optional<Item> item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.fromNamespaceAndPath(role.namespace, set + "_" + role.pieces[i]));
            if (item.isEmpty()) {
                return Optional.empty();
            }
            items[i] = item.get();
        }
        return Optional.of(items);
    }
}
