package io.github.ranger3462.ddkingdom.realm;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * Where a simulator map's nodes stand in the server's world, from {@code data/ddkingdom/realm/sim/places/<map>.json}
 * (for {@code large_ruins}, the WorldPainter build kit's node list: block X, surface Y and Z). A realm started by the
 * simulator's rules is placed from it ({@link Realm#startSim}), and {@code /realm sim place all} places it again.
 */
public final class SimPlaces {
    private SimPlaces() {}

    /** Each node's place, by id; empty if the map has no places file. */
    public static Map<String, Realm.Place> named(String mapId) {
        Map<String, Realm.Place> places = new LinkedHashMap<>();
        try (InputStream in = SimPlaces.class.getResourceAsStream("/data/ddkingdom/realm/sim/places/" + mapId + ".json")) {
            if (in == null) {
                return places;
            }
            JsonObject o = new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(o.get("dimension").getAsString()));
            o.getAsJsonObject("places").entrySet().forEach(e -> {
                JsonArray at = e.getValue().getAsJsonArray();
                places.put(e.getKey(), new Realm.Place(dimension, new BlockPos(at.get(0).getAsInt(), at.get(1).getAsInt(), at.get(2).getAsInt())));
            });
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return places;
    }
}
