package io.github.ranger3462.ddkingdom.integration.ftbchunks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftbchunks.api.client.minimap.MinimapContext;
import dev.ftb.mods.ftbchunks.api.client.minimap.MinimapInfoComponent;
import dev.ftb.mods.ftbchunks.api.client.waypoint.Waypoint;
import dev.ftb.mods.ftbchunks.api.client.waypoint.WaypointManager;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.client.RealmMapClient;
import io.github.ranger3462.ddkingdom.client.RealmScreen;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;

/**
 * The realm on FTB Chunks' minimap and large map (client only; only loaded with FTB Chunks installed): an icon for
 * every town, stronghold, capital and site in its owner's colour, with a steady red mark on any in trouble (pinned to
 * the minimap's edge when it's off it) and its details on hover; and under the minimap, a line for each raid or march at
 * dusk with its countdown. The "[Realm]" waypoints an earlier version made are cleared away.
 */
public final class RealmMapIcons {
    /** What the old waypoints were named with. */
    private static final String OLD_PREFIX = "[Realm] ";
    private static final Set<String> PLACES = Set.of("town", "stronghold", "capital", "site", "resource");
    private static final int MAX_RAID_LINES = 3;
    private static final Set<String> CLEARED = new HashSet<>();
    private static boolean registered;

    private RealmMapIcons() {}

    /** Brings the map up to date with new realm data. False if some dimension's map isn't ready yet, to try again later. */
    public static boolean update(RealmMapClient.Data data) {
        if (!registered) {
            registered = true;
            MapIconEvent.MINIMAP.register(RealmMapIcons::addIcons);
            MapIconEvent.LARGE_MAP.register(RealmMapIcons::addIcons);
            RaidLines lines = new RaidLines();
            FTBChunksAPI.clientApi().registerMinimapComponent(lines);
            // Re-setting it as it stands makes FTB Chunks lay out its minimap lines again, with ours among them.
            FTBChunksAPI.clientApi().setMinimapComponentEnabled(lines, FTBChunksAPI.clientApi().isMinimapComponentEnabled(lines));
        }
        FTBChunksAPI.clientApi().requestMinimapIconRefresh();
        boolean done = true;
        Set<String> dims = new HashSet<>();
        data.nodes().values().forEach(node -> dims.add(node.dim()));
        for (String dim : dims) {
            if (CLEARED.contains(dim)) {
                continue;
            }
            Optional<WaypointManager> manager = manager(dim);
            if (manager.isEmpty()) {
                done = false;
                continue;
            }
            List<Waypoint> old = new ArrayList<>(manager.get().getAllWaypoints().stream().filter(point -> point.getName().startsWith(OLD_PREFIX)).toList());
            old.forEach(manager.get()::removeWaypoint);
            CLEARED.add(dim);
        }
        return done;
    }

    private static Optional<WaypointManager> manager(String dim) {
        ResourceLocation location = ResourceLocation.tryParse(dim);
        return location == null ? Optional.empty() : FTBChunksAPI.clientApi().getWaypointManager(ResourceKey.create(Registries.DIMENSION, location));
    }

    private static void addIcons(MapIconEvent event) {
        RealmMapClient.Data data = RealmMapClient.latest();
        if (data == null) {
            return;
        }
        String dim = event.getDimension().location().toString();
        data.nodes().values().stream().filter(node -> PLACES.contains(node.type()) && node.dim().equals(dim)).forEach(node -> event.add(new Place(node)));
    }

    /** One realm place on the map. */
    private record Place(RealmMapClient.Node node) implements MapIcon {
        @Override
        public Vec3 getPos(float partialTick) {
            return new Vec3(node.x() + 0.5, node.y(), node.z() + 0.5);
        }

        @Override
        public boolean isIconOnEdge(MapType mapType, boolean outsideVisibleArea) {
            return mapType.isMinimap() && !node.warnings().isEmpty();
        }

        @Override
        public int getPriority() {
            return node.warnings().isEmpty() ? 0 : 10;
        }

        @Override
        public void addTooltip(TooltipList list) {
            list.add(Component.literal(node.name()).withColor(RealmMapClient.color(node.owner())));
            list.add(Component.literal(RealmScreen.describe(node)).withStyle(ChatFormatting.GRAY));
            node.warnings().forEach(warning -> list.add(Component.literal("! " + warning).withStyle(ChatFormatting.RED)));
            node.notes().forEach(note -> list.add(Component.literal(note).withStyle(ChatFormatting.YELLOW)));
            MapIcon.super.addTooltip(list);
        }

        @Override
        public boolean onMousePressed(BaseScreen screen, MouseButton button) {
            return false;
        }

        @Override
        public boolean onKeyPressed(BaseScreen screen, Key key) {
            return false;
        }

        @Override
        public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h, boolean outsideVisibleArea, int alpha) {
            int a = (alpha & 0xFF) << 24;
            int border = "capital".equals(node.type()) ? 0xE0B030 : 0x101010;
            int inset = "town".equals(node.type()) ? Math.max(1, w / 5) : 0;
            graphics.fill(x + inset, y + inset, x + w - inset, y + h - inset, a | border);
            graphics.fill(x + inset + 1, y + inset + 1, x + w - inset - 1, y + h - inset - 1, a | RealmMapClient.color(node.owner()));
            if (!"town".equals(node.type()) && w >= 6) {
                graphics.fill(x + w / 2 - 1, y + h / 2 - 1, x + w / 2 + 1, y + h / 2 + 1, a | 0x101010);
            }
            if (!node.warnings().isEmpty()) {
                Font font = Minecraft.getInstance().font;
                graphics.drawString(font, "!", x + w - 2, y - 4, a | 0xFF4040, true);
            }
        }
    }

    /** Under the minimap: the raids and marches due at dusk, soonest first. */
    private static final class RaidLines implements MinimapInfoComponent {
        private static final ResourceLocation ID = KingdomSim.id("realm_raids");

        @Override
        public ResourceLocation id() {
            return ID;
        }

        @Override
        public Component displayName() {
            return Component.literal("Realm raids");
        }

        @Override
        public Component description() {
            return Component.literal("Raids and marches due at dusk, with their countdowns");
        }

        private static List<Component> lines() {
            RealmMapClient.Data data = RealmMapClient.latest();
            List<Component> lines = new ArrayList<>();
            if (data == null) {
                return lines;
            }
            data.raids().stream().sorted(java.util.Comparator.comparingLong(RealmMapClient.Raid::resolveAt)).limit(MAX_RAID_LINES).forEach(raid -> {
                boolean kingdom = "kingdom".equals(raid.nation());
                long seconds = data.until(raid) / 1000;
                String time = seconds / 60 + ":" + String.format("%02d", seconds % 60);
                lines.add(kingdom ? Component.literal("March on " + data.name(raid.to()) + " " + time).withStyle(ChatFormatting.AQUA)
                        : Component.literal("Raid on " + data.name(raid.to()) + " " + time).withStyle(ChatFormatting.RED));
            });
            return lines;
        }

        @Override
        public boolean shouldRender(MinimapContext context) {
            return !lines().isEmpty();
        }

        @Override
        public int height(MinimapContext context) {
            return computeLineHeight(context.minecraft(), lines().size()) + 1;
        }

        @Override
        public void render(MinimapContext context, GuiGraphics graphics, Font font) {
            List<Component> lines = lines();
            int lineHeight = font.lineHeight + 2;
            for (int i = 0; i < lines.size(); i++) {
                drawCenteredText(font, graphics, lines.get(i), i * lineHeight);
            }
        }
    }
}
