package io.github.ranger3462.ddkingdom.registry;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.integration.irons.IronsTownMages;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.DeferredSpawnEggItem;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Everything Kingdom Sim adds to the game's registries: the town guards (and, with Iron's Spells, battlemages). */
public final class KingdomRegistries {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, KingdomSim.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(KingdomSim.MODID);

    /** The town guard (DD-98): a human soldier who turns out when a realm town is raided. */
    public static final DeferredHolder<EntityType<?>, EntityType<TownGuard>> TOWN_GUARD = ENTITIES.register("town_guard",
            () -> EntityType.Builder.of(TownGuard::new, MobCategory.MISC).sized(0.6f, 1.8f).eyeHeight(1.62f).clientTrackingRange(10)
                    .build(KingdomSim.id("town_guard").toString()));
    public static final DeferredItem<DeferredSpawnEggItem> TOWN_GUARD_SPAWN_EGG = ITEMS.registerItem("town_guard_spawn_egg",
            properties -> new DeferredSpawnEggItem(TOWN_GUARD, 0x3A5A8C, 0xC8C8C8, properties));

    private KingdomRegistries() {}

    public static void register(IEventBus modEventBus) {
        if (ModList.get().isLoaded("irons_spellbooks")) {
            IronsTownMages.register(modEventBus);
        }
        ENTITIES.register(modEventBus);
        ITEMS.register(modEventBus);
        modEventBus.addListener((EntityAttributeCreationEvent event) -> event.put(TOWN_GUARD.get(), TownGuard.createAttributes().build()));
        modEventBus.addListener((BuildCreativeModeTabContentsEvent event) -> {
            if (event.getTabKey() == CreativeModeTabs.SPAWN_EGGS) {
                event.accept(TOWN_GUARD_SPAWN_EGG);
            }
        });
    }
}
