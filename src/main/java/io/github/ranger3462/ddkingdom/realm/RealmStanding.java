package io.github.ranger3462.ddkingdom.realm;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Each player's own standing with the realm's groups, from the quests they do for them (realm-politics.md): theirs
 * alone, never shared or affected by anyone else's. Everyone starts neutral, at 0. The dwarves are the first group
 * with quests ({@link DwarvenQuests}); the council's groups will follow.
 * <p>
 * Also keeps how far each player is through the quest a group has them on, such as how much Mithril they've brought
 * the dwarves towards the next one.
 */
public class RealmStanding extends SavedData {
    private static final String NAME = KingdomSim.MODID + "_standing";
    public static final String DWARVES = "dwarves";

    private final Map<UUID, Map<String, Integer>> standing = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> progress = new HashMap<>();

    public static RealmStanding get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(RealmStanding::new, RealmStanding::load, null), NAME);
    }

    /** A player's standing with a group: 0 (neutral) until they've done something for it. */
    public int standing(UUID player, String group) {
        return standing.getOrDefault(player, Map.of()).getOrDefault(group, 0);
    }

    public void raise(UUID player, String group, int amount) {
        standing.computeIfAbsent(player, p -> new HashMap<>()).merge(group, amount, Integer::sum);
        setDirty();
    }

    /** How far a player is through a group's current quest. */
    public int progress(UUID player, String group) {
        return progress.getOrDefault(player, Map.of()).getOrDefault(group, 0);
    }

    public void setProgress(UUID player, String group, int amount) {
        progress.computeIfAbsent(player, p -> new HashMap<>()).put(group, amount);
        setDirty();
    }

    /** Forgets everyone's standing (for tests). */
    public void clear() {
        standing.clear();
        progress.clear();
        setDirty();
    }

    private static RealmStanding load(CompoundTag tag, HolderLookup.Provider registries) {
        RealmStanding data = new RealmStanding();
        read(tag.getCompound("standing"), data.standing);
        read(tag.getCompound("progress"), data.progress);
        return data;
    }

    private static void read(CompoundTag tag, Map<UUID, Map<String, Integer>> into) {
        for (String key : tag.getAllKeys()) {
            UUID player;
            try {
                player = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                continue;
            }
            CompoundTag groups = tag.getCompound(key);
            Map<String, Integer> map = new HashMap<>();
            for (String group : groups.getAllKeys()) {
                map.put(group, groups.getInt(group));
            }
            into.put(player, map);
        }
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put("standing", write(standing));
        tag.put("progress", write(progress));
        return tag;
    }

    private static CompoundTag write(Map<UUID, Map<String, Integer>> from) {
        CompoundTag tag = new CompoundTag();
        from.forEach((player, groups) -> {
            CompoundTag entry = new CompoundTag();
            groups.forEach(entry::putInt);
            tag.put(player.toString(), entry);
        });
        return tag;
    }
}
