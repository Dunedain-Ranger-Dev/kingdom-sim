package io.github.ranger3462.ddkingdom;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import io.github.ranger3462.ddkingdom.registry.KingdomRegistries;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;

/**
 * Kingdom Sim: the realm (docs/design/realm.md), one kingdom holding back a tide of monster nations, with daily turns,
 * raids, capitals and town guards. Dungeon Delving's factions are the monster nations.
 */
@Mod(KingdomSim.MODID)
public class KingdomSim {
    public static final String MODID = "ddkingdom";
    public static final Logger LOGGER = LogUtils.getLogger();

    public KingdomSim(IEventBus modEventBus, ModContainer modContainer) {
        KingdomRegistries.register(modEventBus);
        modContainer.registerConfig(ModConfig.Type.COMMON, KingdomConfig.SPEC);
    }

    /** Builds a resource location in this mod's namespace, e.g. {@code ddkingdom:town_guard}. */
    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MODID, path);
    }
}
