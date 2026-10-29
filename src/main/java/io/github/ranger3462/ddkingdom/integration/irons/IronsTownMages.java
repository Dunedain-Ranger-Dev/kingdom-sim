package io.github.ranger3462.ddkingdom.integration.irons;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.RealmLiveRaids;
import io.github.ranger3462.ddkingdom.registry.KingdomRegistries;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;

/** The town battlemage, a guard that needs Iron's Spells. Only loaded when Iron's Spells is installed. */
public final class IronsTownMages {
    public static DeferredHolder<EntityType<?>, EntityType<TownMage>> TOWN_MAGE;
    public static DeferredItem<DeferredSpawnEggItem> TOWN_MAGE_SPAWN_EGG;

    private IronsTownMages() {}

    /** Called from the mod constructor when Iron's Spells is loaded. */
    public static void register(IEventBus modEventBus) {
        TOWN_MAGE = KingdomRegistries.ENTITIES.register("town_mage", () -> EntityType.Builder.of(TownMage::new, MobCategory.MISC).sized(0.6f, 1.8f)
                .eyeHeight(1.62f).clientTrackingRange(10).build(KingdomSim.id("town_mage").toString()));
        TOWN_MAGE_SPAWN_EGG = KingdomRegistries.ITEMS.registerItem("town_mage_spawn_egg",
                properties -> new DeferredSpawnEggItem(TOWN_MAGE, 0x3A5A8C, 0x8A4AC8, properties));
        modEventBus.addListener((EntityAttributeCreationEvent event) -> event.put(TOWN_MAGE.get(), TownMage.createAttributes().build()));
        modEventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
                event.accept(TOWN_MAGE_SPAWN_EGG);
            }
        });
        RealmLiveRaids.mageSpawner = (level, pos) -> TOWN_MAGE.get().spawn(level, pos, MobSpawnType.EVENT);
    }
}
