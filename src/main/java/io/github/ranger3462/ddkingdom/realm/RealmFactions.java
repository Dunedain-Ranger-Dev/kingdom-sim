package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.dungeondelving.dungeon.DungeonFactions;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.AddReloadListenerEvent;

/**
 * Who each realm faction is and how it plays (DD-102, docs/design/realm-factions.md): its names, and the
 * <b>traits</b> its turns read, from {@code data/<namespace>/realm/factions/<faction>.json} so they can be tuned in a
 * datapack. A faction with no file plays by {@link Traits#GENERIC}.
 * <p>
 * The traits that matter in a turn: how fast it grows ({@code growth}), the lowest chance of winning it'll attack at
 * ({@code attack_bar}, against {@link #winChance}), how keen it is to raid ({@code aggression}), whom it attacks
 * ({@code targets}), whether it secures a new holding before moving on ({@code fortify_after_capture}), how keen it is
 * to win back what it lost ({@code retake}), how far from its capital it fights ({@code home_radius}), how much
 * stronger its troops are ({@code military_bonus}), whether it raises the fallen ({@code raise_dead}), blights its land
 * ({@code blight}), or attacks in bursts from its capital ({@code burst_ratio}), and the resource kind it gets twice
 * as much from ({@code affinity}, DD-103). {@code reserve}, {@code roams} and
 * {@code tithe} are read once troops and wealth are tracked (phases 2 and 4).
 * <p>
 * A faction without its own dungeon mobs yet (the kobolds and elementals) raids with its file's {@code mobs} (spawner
 * entries, as in the config's mob lists), and its champion is one of {@code boss_faction}'s bosses.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmFactions {
    /** Whom a faction attacks. */
    public enum Targets {
        /** Anyone but itself. */
        ANYONE,
        /** The kingdom, and places no one holds; never other monsters. */
        KINGDOM,
        /** The kingdom and unheld places; other monsters only where they're weak ({@code weak_bar}). */
        KINGDOM_FIRST,
        /** Monster nations only (the kingdom's own marches). */
        MONSTERS
    }

    public record Traits(String id, String name, String capital, String leader, String champion, double growth, double aggression,
            double attackBar, Targets targets, double weakBar, boolean fortifyAfterCapture, double retake, double reserve, boolean roams,
            int homeRadius, double tithe, double militaryBonus, double raiseDead, int blight, double burstRatio, List<String> mobs,
            String bossFaction, String affinity) {
        /** For a faction with no file: average in every way. */
        public static final Traits GENERIC = new Traits("", "", "", "", "", 1.0, 1.0, 0.5, Targets.KINGDOM_FIRST, 0.75, false, 0.5, 0.2, false,
                0, 0.0, 0.0, 0.0, 0, 0.0, List.of(), "", "");

        /** The faction's name in the news: its own, or its id ("the goblins"). */
        public String displayName() {
            return name.isEmpty() ? id : name;
        }

        static Traits parse(String id, JsonObject json) {
            Traits d = GENERIC;
            List<String> mobs = new ArrayList<>();
            if (json.has("mobs")) {
                for (JsonElement mob : GsonHelper.getAsJsonArray(json, "mobs")) {
                    mobs.add(mob.getAsString());
                }
            }
            return new Traits(id, GsonHelper.getAsString(json, "name", id), GsonHelper.getAsString(json, "capital", ""),
                    GsonHelper.getAsString(json, "leader", ""), GsonHelper.getAsString(json, "champion", ""),
                    GsonHelper.getAsDouble(json, "growth", d.growth), GsonHelper.getAsDouble(json, "aggression", d.aggression),
                    GsonHelper.getAsDouble(json, "attack_bar", d.attackBar),
                    Targets.valueOf(GsonHelper.getAsString(json, "targets", d.targets.name()).toUpperCase(java.util.Locale.ROOT)),
                    GsonHelper.getAsDouble(json, "weak_bar", d.weakBar), GsonHelper.getAsBoolean(json, "fortify_after_capture", d.fortifyAfterCapture),
                    GsonHelper.getAsDouble(json, "retake", d.retake), GsonHelper.getAsDouble(json, "reserve", d.reserve),
                    GsonHelper.getAsBoolean(json, "roams", d.roams), GsonHelper.getAsInt(json, "home_radius", d.homeRadius),
                    GsonHelper.getAsDouble(json, "tithe", d.tithe), GsonHelper.getAsDouble(json, "military_bonus", d.militaryBonus),
                    GsonHelper.getAsDouble(json, "raise_dead", d.raiseDead), GsonHelper.getAsInt(json, "blight", d.blight),
                    GsonHelper.getAsDouble(json, "burst_ratio", d.burstRatio), List.copyOf(mobs), GsonHelper.getAsString(json, "boss_faction", ""),
                    GsonHelper.getAsString(json, "affinity", ""));
        }
    }

    private static final Map<String, Traits> LOADED = new ConcurrentHashMap<>();

    private RealmFactions() {}

    /** A faction's traits (the kingdom's too, as {@link RealmNode#KINGDOM}): its file's, or the generic ones. */
    public static Traits get(String faction) {
        Traits traits = LOADED.get(faction);
        return traits != null ? traits : Traits.parse(faction, new JsonObject());
    }

    /** The monster nations a node can belong to: every dungeon faction, and every faction with a file but the kingdom. */
    public static Set<String> nations() {
        Set<String> nations = new LinkedHashSet<>(DungeonFactions.ids());
        LOADED.keySet().stream().filter(id -> !RealmNode.KINGDOM.equals(id)).sorted().forEach(nations::add);
        return nations;
    }

    /**
     * The mobs a nation raids with: its file's, if it lists any (the kobolds hold only tier 1 dungeons, so their file
     * fills the higher tiers with stand-ins), else its dungeon faction's.
     */
    public static List<? extends String> mobs(String nation) {
        List<String> file = get(nation).mobs();
        return file.isEmpty() ? DungeonFactions.mobs(nation) : file;
    }

    /** The faction whose bosses a nation's champion is drawn from: its own, or its file's stand-in. */
    public static String bossFaction(String nation) {
        String standIn = get(nation).bossFaction();
        return standIn.isEmpty() ? nation : standIn;
    }

    /**
     * The chance a rolled battle is won by the attackers, ahead by {@code margin} before the rolls: attack + d20 must
     * beat defense + d20 (a tie holds). 47.5% at an even 0; 75% at +7; 90% at +12.
     */
    public static double winChance(int margin) {
        int wins = 0;
        for (int attack = 1; attack <= 20; attack++) {
            for (int defense = 1; defense <= 20; defense++) {
                if (margin + attack > defense) {
                    wins++;
                }
            }
        }
        return wins / 400.0;
    }

    /** Sets a faction's traits (for tests); {@code null} puts back what its file says. */
    public static void override(String faction, Traits traits) {
        if (traits == null) {
            LOADED.remove(faction);
        } else {
            LOADED.put(faction, traits);
        }
    }

    @SubscribeEvent
    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    private static final class Loader extends SimpleJsonResourceReloadListener {
        Loader() {
            super(new Gson(), "realm/factions");
        }

        @Override
        protected void apply(Map<ResourceLocation, JsonElement> files, ResourceManager manager, ProfilerFiller profiler) {
            LOADED.clear();
            files.forEach((id, json) -> {
                try {
                    LOADED.put(id.getPath(), Traits.parse(id.getPath(), json.getAsJsonObject()));
                } catch (RuntimeException e) {
                    KingdomSim.LOGGER.error("Bad realm faction file {}: {}", id, e.getMessage());
                }
            });
            KingdomSim.LOGGER.info("Loaded {} realm factions", LOADED.size());
        }
    }
}
