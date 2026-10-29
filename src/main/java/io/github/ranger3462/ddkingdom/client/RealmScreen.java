package io.github.ranger3462.ddkingdom.client;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.RealmMap;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The realm menu (N by default), in three tabs. <b>Map</b>: the realm's places in your dimension, coloured by owner,
 * with their links (red where they cross a border) and a red "!" on any in trouble; and a side panel with how the
 * kingdom and each nation stand, the raids under way, and either the places in trouble or the one you've clicked, its
 * stats in plain words. Drag to move the map, scroll to zoom. <b>News</b>: the realm's log by turn, newest first,
 * coloured by who and what, and filterable. <b>Nations</b>: a card for the kingdom and each nation, with its leaders,
 * strength and how it plays. The data comes from the server ({@link RealmMap}).
 */
public class RealmScreen extends Screen {
    public static final KeyMapping OPEN = new KeyMapping("key.ddkingdom.realm", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_N,
            "key.categories.ddkingdom");
    private static final int PANEL_WIDTH = 200;
    private static final int MARGIN = 8;
    private static final int TOP = 22;
    private static final int LINE = 10;
    private static final int GOLD = 0xFFE0A0;
    private static final int GREY = 0xA0A0A0;
    private static final int RED = 0xFF6060;
    private static final int YELLOW = 0xF0D060;

    /** The map's view: the world block at the middle of the map area, and pixels per block. */
    private double centerX;
    private double centerZ;
    private double scale = -1;
    private String shownDim;
    private String selected;
    private boolean dragged;
    /** The panel's clickable rows this frame: a node id each, by top y. */
    private final List<Object[]> rows = new ArrayList<>();
    /** The tabs' and filters' buttons this frame: what each does, and where it is. */
    private final List<Button> buttons = new ArrayList<>();
    private Tab tab = Tab.MAP;
    /** How far the news or nations list is scrolled, and how tall it was last frame. */
    private int scroll;
    private int contentHeight;
    private boolean everything;
    /** The faction the news is filtered to, or null for all. */
    private String newsOf;

    private enum Tab {
        MAP("Map"), NEWS("News"), NATIONS("Nations");

        final String label;

        Tab(String label) {
            this.label = label;
        }
    }

    private record Button(Runnable action, int x0, int y0, int x1, int y1) {
        boolean contains(double x, double y) {
            return x >= x0 && x < x1 && y >= y0 && y < y1;
        }
    }

    public RealmScreen() {
        super(Component.translatable("screen.ddkingdom.realm"));
    }

    @Override
    protected void init() {
        PacketDistributor.sendToServer(new RealmMap.Request());
        if (shownDim == null && minecraft.level != null) {
            shownDim = minecraft.level.dimension().location().toString();
        }
    }

    private int mapLeft() {
        return MARGIN;
    }

    private int mapRight() {
        return width - PANEL_WIDTH - MARGIN * 2;
    }

    private int mapTop() {
        return TOP;
    }

    private int mapBottom() {
        return height - MARGIN - 12;
    }

    private List<RealmMapClient.Node> shown(RealmMapClient.Data data) {
        return data.nodes().values().stream().filter(node -> node.dim().equals(shownDim)).toList();
    }

    /** Fits the view to the shown places (and the player, if they're in this dimension). */
    private void fit(RealmMapClient.Data data) {
        List<RealmMapClient.Node> nodes = shown(data);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (RealmMapClient.Node node : nodes) {
            minX = Math.min(minX, node.x());
            maxX = Math.max(maxX, node.x());
            minZ = Math.min(minZ, node.z());
            maxZ = Math.max(maxZ, node.z());
        }
        if (minecraft.player != null && minecraft.level != null && minecraft.level.dimension().location().toString().equals(shownDim)) {
            minX = Math.min(minX, minecraft.player.getX());
            maxX = Math.max(maxX, minecraft.player.getX());
            minZ = Math.min(minZ, minecraft.player.getZ());
            maxZ = Math.max(maxZ, minecraft.player.getZ());
        }
        if (minX == Double.MAX_VALUE) {
            centerX = 0;
            centerZ = 0;
            scale = 0.25;
            return;
        }
        centerX = (minX + maxX) / 2;
        centerZ = (minZ + maxZ) / 2;
        double spanX = Math.max(64, maxX - minX);
        double spanZ = Math.max(64, maxZ - minZ);
        scale = Math.min((mapRight() - mapLeft() - 60) / spanX, (mapBottom() - mapTop() - 40) / spanZ);
    }

    private int screenX(double x) {
        return (int) Math.round((mapLeft() + mapRight()) / 2.0 + (x - centerX) * scale);
    }

    private int screenY(double z) {
        return (int) Math.round((mapTop() + mapBottom()) / 2.0 + (z - centerZ) * scale);
    }

    private static int size(RealmMapClient.Node node) {
        return switch (node.type()) {
            case "capital" -> 11;
            case "town", "stronghold" -> 8;
            case "site", "resource" -> 6;
            default -> 5;
        };
    }

    private RealmMapClient.Node nodeAt(RealmMapClient.Data data, double mouseX, double mouseY) {
        RealmMapClient.Node best = null;
        double bestDistance = 8 * 8;
        for (RealmMapClient.Node node : shown(data)) {
            double dx = screenX(node.x()) - mouseX;
            double dy = screenY(node.z()) - mouseY;
            if (dx * dx + dy * dy <= bestDistance) {
                best = node;
                bestDistance = dx * dx + dy * dy;
            }
        }
        return best;
    }

    private boolean inMap(double mouseX, double mouseY) {
        return mouseX >= mapLeft() && mouseX < mapRight() && mouseY >= mapTop() && mouseY < mapBottom();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        buttons.clear();
        graphics.drawString(font, title, MARGIN, 8, GOLD);
        int tabX = MARGIN + font.width(title) + 12;
        for (Tab each : Tab.values()) {
            tabX += button(graphics, each.label, tabX, 5, each == tab, mouseX, mouseY, () -> {
                tab = each;
                scroll = 0;
            }) + 4;
        }
        RealmMapClient.Data data = RealmMapClient.latest();
        if (data == null) {
            graphics.drawCenteredString(font, Component.literal("Waiting for the realm..."), width / 2, height / 2, GREY);
            return;
        }
        String turn = "Turn " + data.turn() + "   next in " + clock(data.untilNextTurn());
        graphics.drawString(font, turn, width - MARGIN - font.width(turn), 8, GREY);
        switch (tab) {
            case MAP -> {
                if (scale < 0) {
                    fit(data);
                }
                renderMap(graphics, data, mouseX, mouseY);
                renderPanel(graphics, data, mouseX, mouseY);
            }
            case NEWS -> renderNews(graphics, data, mouseX, mouseY);
            case NATIONS -> renderNations(graphics, data);
        }
    }

    /** A clickable chip, lit when {@code on}; returns its width. */
    private int button(GuiGraphics graphics, String label, int x, int y, boolean on, int mouseX, int mouseY, Runnable action) {
        int w = font.width(label) + 10;
        Button button = new Button(action, x, y, x + w, y + 14);
        graphics.fill(x, y, x + w, y + 14, on ? 0xFF6A5A30 : button.contains(mouseX, mouseY) ? 0xFF3A3A3A : 0xFF262626);
        graphics.fill(x, y + 13, x + w, y + 14, on ? 0xFFE0A040 : 0xFF505050);
        graphics.drawString(font, label, x + 5, y + 3, on ? 0xFFFFFF : 0xC0C0C0);
        buttons.add(button);
        return w;
    }

    private static String clock(long millis) {
        long seconds = millis / 1000;
        return seconds / 60 + ":" + String.format("%02d", seconds % 60);
    }

    /** A faction's name for people: "the kingdom", a nation's own name, or its id. */
    static String ownerName(RealmMapClient.Data data, String owner) {
        if ("kingdom".equals(owner)) {
            return "the kingdom";
        }
        if ("none".equals(owner) || owner.isEmpty()) {
            return "no one";
        }
        return data.nations().stream().filter(nation -> nation.id().equals(owner) && !nation.name().isEmpty()).findFirst()
                .map(RealmMapClient.Nation::name).orElse(owner);
    }

    private static String capitalised(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private int listTop() {
        return TOP + 20;
    }

    private int listBottom() {
        return height - MARGIN;
    }

    /** A filled box with a border, for the news and nations lists. */
    private void frame(GuiGraphics graphics, int x0, int y0, int x1, int y1) {
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF505050);
        graphics.fill(x0, y0, x1, y1, 0xF0181818);
    }

    private static final String[][] KINDS = { { "PREPARE", "Routine", "909090" }, { "MARCH", "March", "F0A040" }, { "BATTLE", "Battle", "FF6060" },
            { "CAPTURE", "Taken", "FFD040" }, { "TOWN", "Town", "80E080" }, { "SUPPLY", "Supply", "E8D8A0" }, { "OTHER", "", "C0C0C0" } };

    private static String[] kind(String name) {
        for (String[] kind : KINDS) {
            if (kind[0].equals(name)) {
                return kind;
            }
        }
        return KINDS[KINDS.length - 1];
    }

    private boolean shows(RealmMapClient.Entry entry) {
        if (newsOf != null && !newsOf.equals(entry.who())) {
            return false;
        }
        return everything || entry.news() || !("PREPARE".equals(entry.kind()) || "OTHER".equals(entry.kind()));
    }

    /** The News tab: the realm's log by turn, newest first, each entry tagged with what it was and coloured by whom. */
    private void renderNews(GuiGraphics graphics, RealmMapClient.Data data, int mouseX, int mouseY) {
        int x = MARGIN;
        int y = TOP;
        x += button(graphics, "Important", x, y, !everything, mouseX, mouseY, () -> {
            everything = false;
            scroll = 0;
        }) + 4;
        x += button(graphics, "Everything", x, y, everything, mouseX, mouseY, () -> {
            everything = true;
            scroll = 0;
        }) + 12;
        Set<String> factions = new LinkedHashSet<>();
        factions.add("kingdom");
        data.nations().forEach(nation -> factions.add(nation.id()));
        data.log().forEach(entry -> {
            if (!entry.who().isEmpty() && !"none".equals(entry.who())) {
                factions.add(entry.who());
            }
        });
        x += button(graphics, "All", x, y, newsOf == null, mouseX, mouseY, () -> {
            newsOf = null;
            scroll = 0;
        }) + 4;
        for (String faction : factions) {
            String name = capitalised(ownerName(data, faction));
            int w = font.width(name) + 10;
            if (x + w > width - MARGIN) {
                break;
            }
            button(graphics, name, x, y, faction.equals(newsOf), mouseX, mouseY, () -> {
                newsOf = faction;
                scroll = 0;
            });
            graphics.fill(x + 1, y + 1, x + 3, y + 13, 0xFF000000 | RealmMapClient.color(faction));
            x += w + 4;
        }

        int x0 = MARGIN, x1 = width - MARGIN, y0 = listTop(), y1 = listBottom();
        frame(graphics, x0, y0, x1, y1);
        graphics.enableScissor(x0, y0, x1, y1);
        int tagWidth = 0;
        for (String[] kind : KINDS) {
            tagWidth = Math.max(tagWidth, font.width(kind[1]));
        }
        int textX = x0 + 10 + tagWidth + 8, textWidth = x1 - textX - 8;
        int top = y0 + 6 - scroll;
        int[] row = { top };
        List<RealmMapClient.Entry> log = new ArrayList<>(data.log());
        java.util.Collections.reverse(log);
        int shownTurn = Integer.MIN_VALUE;
        boolean any = false;
        for (RealmMapClient.Entry entry : log) {
            if (!shows(entry)) {
                continue;
            }
            any = true;
            if (entry.turn() != shownTurn) {
                shownTurn = entry.turn();
                if (row[0] > top) {
                    row[0] += 4;
                }
                String header = entry.turn() == data.turn() ? "Turn " + entry.turn() + " (this turn)" : "Turn " + entry.turn();
                graphics.drawString(font, header, x0 + 6, row[0], GOLD);
                graphics.fill(x0 + 10 + font.width(header), row[0] + 4, x1 - 8, row[0] + 5, 0xFF404040);
                row[0] += LINE + 3;
            }
            String[] kind = kind(entry.kind());
            int color = entry.who().isEmpty() ? 0xC0C0C0 : RealmMapClient.color(entry.who());
            List<FormattedCharSequence> lines = font.split(Component.literal(entry.text()), textWidth);
            graphics.fill(x0 + 6, row[0] - 1, x0 + 8, row[0] + lines.size() * LINE - 2, 0xFF000000 | color);
            graphics.drawString(font, kind[1], x0 + 12, row[0], Integer.parseInt(kind[2], 16));
            int textColor = "PREPARE".equals(entry.kind()) ? 0xA8A8A8 : 0xF0F0F0;
            for (FormattedCharSequence line : lines) {
                graphics.drawString(font, line, textX, row[0], textColor);
                row[0] += LINE;
            }
            row[0] += 2;
        }
        if (!any) {
            graphics.drawString(font, data.log().isEmpty() ? "Nothing has happened in the realm yet." : "Nothing to show with these filters.", x0 + 6,
                    row[0], GREY);
            row[0] += LINE;
        }
        contentHeight = row[0] - top + 6;
        graphics.disableScissor();
        scrollbar(graphics, x1, y0, y1);
    }

    /** The Nations tab: a card for the kingdom and each nation in the field. */
    private void renderNations(GuiGraphics graphics, RealmMapClient.Data data) {
        int x0 = MARGIN, x1 = width - MARGIN, y0 = TOP, y1 = listBottom();
        frame(graphics, x0, y0, x1, y1);
        graphics.enableScissor(x0, y0, x1, y1);
        int columns = x1 - x0 >= 520 ? 2 : 1;
        int cardWidth = (x1 - x0 - 12 - (columns - 1) * 8) / columns;
        int top = y0 + 6 - scroll;
        int[] columnY = new int[columns];
        java.util.Arrays.fill(columnY, top);
        Collection<RealmMapClient.Node> nodes = data.nodes().values();
        List<Object[]> cards = new ArrayList<>();
        long towns = nodes.stream().filter(node -> "town".equals(node.type())).count();
        long held = nodes.stream().filter(node -> "town".equals(node.type()) && node.kingdoms()).count();
        long strongholds = nodes.stream().filter(node -> "stronghold".equals(node.type()) && node.kingdoms()).count();
        long troubled = nodes.stream().filter(node -> !node.warnings().isEmpty()).count();
        List<String> kingdomStatus = new ArrayList<>();
        kingdomStatus.add(troubled == 0 ? "All quiet" : troubled + (troubled == 1 ? " place" : " places") + " in trouble");
        data.raids().stream().filter(raid -> "kingdom".equals(raid.nation()))
                .forEach(raid -> kingdomStatus.add("Marching on " + data.name(raid.to()) + " (" + clock(data.until(raid)) + ")"));
        cards.add(new Object[] { "kingdom", "The Kingdom", "",
                "Army " + data.kingdom().getOrDefault("army", 0) + "    Towns " + held + " of " + towns + "    Strongholds " + strongholds
                        + "    Resources " + nodes.stream().filter(node -> "resource".equals(node.type()) && node.kingdoms()).count(),
                kingdomStatus, data.kingdomAbout(), data.kingdomPurse().line() });
        for (RealmMapClient.Nation nation : data.nations()) {
            List<String> leaders = new ArrayList<>();
            if (!nation.leader().isEmpty()) {
                leaders.add("Led by " + nation.leader());
            }
            if (!nation.capital().isEmpty()) {
                leaders.add("from " + nation.capital());
            }
            if (!nation.champion().isEmpty()) {
                leaders.add("champion " + nation.champion());
            }
            List<String> status = new ArrayList<>();
            if (nation.pushedBack()) {
                status.add("Pushed back: rallying at home");
            }
            if (nation.marching()) {
                status.add("On the march");
            }
            data.raids().stream().filter(raid -> raid.nation().equals(nation.id()))
                    .forEach(raid -> status.add("Raiding " + data.name(raid.to()) + " (" + clock(data.until(raid)) + ")"));
            if (status.isEmpty()) {
                status.add("Biding its time");
            }
            cards.add(new Object[] { nation.id(), capitalised(nation.name().isEmpty() ? nation.id() : nation.name()), String.join(", ", leaders),
                    "Troops " + nation.military() + "    Strongholds " + nation.strongholds() + (nation.towns() > 0 ? "    Towns " + nation.towns() : "")
                            + resources(nodes, nation.id()),
                    status, nation.about(), nation.purse().line() });
        }
        if (data.nations().isEmpty()) {
            cards.add(new Object[] { "none", "No nations in the field", "", "", List.of(), "Monster nations take turns once they hold a stronghold.", "" });
        }
        for (Object[] card : cards) {
            int column = 0;
            for (int i = 1; i < columns; i++) {
                if (columnY[i] < columnY[column]) {
                    column = i;
                }
            }
            int cx = x0 + 6 + column * (cardWidth + 8);
            columnY[column] = card(graphics, card, cx, columnY[column], cardWidth) + 8;
        }
        int bottom = top;
        for (int y : columnY) {
            bottom = Math.max(bottom, y);
        }
        contentHeight = bottom - top + 6;
        graphics.disableScissor();
        scrollbar(graphics, x1, y0, y1);
    }

    private static String resources(Collection<RealmMapClient.Node> nodes, String owner) {
        long held = nodes.stream().filter(node -> "resource".equals(node.type()) && owner.equals(node.owner())).count();
        return held > 0 ? "    Resources " + held : "";
    }

    /** Draws one nation's card; returns its bottom. */
    @SuppressWarnings("unchecked")
    private int card(GuiGraphics graphics, Object[] card, int x, int y, int w) {
        String faction = (String) card[0];
        int color = RealmMapClient.color(faction);
        int inner = w - 14;
        int height = 6 + LINE + 2;
        List<FormattedCharSequence> leaders = ((String) card[2]).isEmpty() ? List.of() : font.split(Component.literal((String) card[2]), inner);
        List<FormattedCharSequence> about = font.split(Component.literal((String) card[5]), inner);
        List<String> status = (List<String>) card[4];
        String purse = (String) card[6];
        height += leaders.size() * LINE + (((String) card[3]).isEmpty() ? 0 : LINE + 2) + (purse.isEmpty() ? 0 : LINE + 2) + status.size() * LINE + 3
                + about.size() * LINE + 4;
        graphics.fill(x, y, x + w, y + height, 0xFF222222);
        graphics.fill(x, y, x + 3, y + height, 0xFF000000 | color);
        int tx = x + 9;
        int[] row = { y + 6 };
        graphics.drawString(font, Component.literal((String) card[1]).withStyle(ChatFormatting.BOLD), tx, row[0], color);
        row[0] += LINE + 2;
        for (FormattedCharSequence line : leaders) {
            graphics.drawString(font, line, tx, row[0], GREY);
            row[0] += LINE;
        }
        if (!((String) card[3]).isEmpty()) {
            graphics.drawString(font, (String) card[3], tx, row[0], 0xFFFFFF);
            row[0] += LINE + 2;
        }
        if (!purse.isEmpty()) {
            graphics.drawString(font, purse, tx, row[0], 0xE8D8A0);
            row[0] += LINE + 2;
        }
        for (String line : status) {
            boolean bad = line.startsWith("Raiding") || line.startsWith("On the march") || line.contains("in trouble");
            graphics.drawString(font, line, tx, row[0], bad ? YELLOW : 0x80FF80);
            row[0] += LINE;
        }
        row[0] += 3;
        for (FormattedCharSequence line : about) {
            graphics.drawString(font, line, tx, row[0], 0xB8B8B8);
            row[0] += LINE;
        }
        return y + height;
    }

    /** A scrollbar on the list's right edge, when it's taller than its box. */
    private void scrollbar(GuiGraphics graphics, int x1, int y0, int y1) {
        int view = y1 - y0;
        scroll = Mth.clamp(scroll, 0, Math.max(0, contentHeight - view));
        if (contentHeight <= view) {
            return;
        }
        int barHeight = Math.max(12, view * view / contentHeight);
        int barTop = y0 + (view - barHeight) * scroll / Math.max(1, contentHeight - view);
        graphics.fill(x1 - 4, y0, x1 - 1, y1, 0xFF2A2A2A);
        graphics.fill(x1 - 4, barTop, x1 - 1, barTop + barHeight, 0xFF8A8A8A);
    }

    private void renderMap(GuiGraphics graphics, RealmMapClient.Data data, int mouseX, int mouseY) {
        int x0 = mapLeft(), y0 = mapTop(), x1 = mapRight(), y1 = mapBottom();
        graphics.fill(x0 - 1, y0 - 1, x1 + 1, y1 + 1, 0xFF505050);
        graphics.fill(x0, y0, x1, y1, 0xF0141A20);
        graphics.drawString(font, Component.literal(dimName(shownDim)), x0 + 4, y0 + 4, GREY);
        graphics.enableScissor(x0, y0, x1, y1);
        for (String[] link : data.links()) {
            RealmMapClient.Node a = data.nodes().get(link[0]);
            RealmMapClient.Node b = data.nodes().get(link[1]);
            if (a == null || b == null || !a.dim().equals(shownDim) || !b.dim().equals(shownDim)) {
                continue;
            }
            boolean border = (a.kingdoms() && b.monsters()) || (a.monsters() && b.kingdoms());
            line(graphics, screenX(a.x()), screenY(a.z()), screenX(b.x()), screenY(b.z()), border ? 0xFFC04040 : 0xFF606870);
        }
        for (RealmMapClient.Node node : shown(data)) {
            int sx = screenX(node.x()), sy = screenY(node.z()), half = size(node) / 2;
            int outline = node.id().equals(selected) ? 0xFFFFFFFF : "capital".equals(node.type()) ? 0xFFE0C060 : 0xFF000000;
            graphics.fill(sx - half - 1, sy - half - 1, sx + half + 2, sy + half + 2, outline);
            graphics.fill(sx - half, sy - half, sx + half + 1, sy + half + 1, 0xFF000000 | RealmMapClient.color(node.owner()));
            if ("resource".equals(node.type())) {
                // A diamond: the square's corners cut back, with a light middle.
                graphics.fill(sx - half - 1, sy - half - 1, sx - half + 1, sy - half + 1, 0xF0141A20);
                graphics.fill(sx + half, sy - half - 1, sx + half + 2, sy - half + 1, 0xF0141A20);
                graphics.fill(sx - half - 1, sy + half, sx - half + 1, sy + half + 2, 0xF0141A20);
                graphics.fill(sx + half, sy + half, sx + half + 2, sy + half + 2, 0xF0141A20);
                graphics.fill(sx, sy, sx + 1, sy + 1, 0xFFFFFFFF);
            }
            if ("stronghold".equals(node.type()) || "capital".equals(node.type())) {
                graphics.fill(sx - 1, sy - 1, sx + 2, sy + 2, 0xFF000000);
            }
            if ("town".equals(node.type()) && node.stat("tier") == 0) {
                graphics.fill(sx - half, sy, sx + half + 1, sy + 1, 0xFF000000); // Ruins: a crack across.
            }
            graphics.drawCenteredString(font, Component.literal(node.name()), sx, sy + half + 3, 0xD0D0D0);
            if (!node.warnings().isEmpty()) {
                graphics.drawString(font, Component.literal("!").withStyle(ChatFormatting.BOLD), sx + half + 2, sy - half - 6, RED);
            }
        }
        if (minecraft.player != null && minecraft.level != null && minecraft.level.dimension().location().toString().equals(shownDim)) {
            int px = screenX(minecraft.player.getX()), py = screenY(minecraft.player.getZ());
            graphics.fill(px - 2, py - 2, px + 3, py + 3, 0xFF000000);
            graphics.fill(px - 1, py - 1, px + 2, py + 2, 0xFFFFFFFF);
            graphics.drawString(font, Component.literal("You"), px + 5, py - 4, 0xFFFFFF);
        }
        graphics.disableScissor();
        renderLegend(graphics, data, x0, y1 + 3);
        if (inMap(mouseX, mouseY)) {
            RealmMapClient.Node hovered = nodeAt(data, mouseX, mouseY);
            if (hovered != null) {
                List<Component> lines = new ArrayList<>();
                lines.add(Component.literal(hovered.name()).withStyle(ChatFormatting.BOLD));
                lines.add(Component.literal(describe(hovered)).withStyle(ChatFormatting.GRAY));
                hovered.warnings().forEach(warning -> lines.add(Component.literal("! " + warning).withStyle(ChatFormatting.RED)));
                graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
            }
        }
    }

    private void renderLegend(GuiGraphics graphics, RealmMapClient.Data data, int x, int y) {
        Set<String> owners = new LinkedHashSet<>();
        owners.add("kingdom");
        data.nodes().values().forEach(node -> owners.add(node.owner()));
        for (String owner : owners) {
            String name = capitalised(ownerName(data, owner));
            graphics.fill(x, y + 1, x + 6, y + 7, 0xFF000000 | RealmMapClient.color(owner));
            graphics.drawString(font, Component.literal(name), x + 9, y, GREY);
            x += 14 + font.width(name);
        }
        graphics.drawString(font, Component.literal("! in trouble").withStyle(ChatFormatting.BOLD), x, y, RED);
        x += 6 + font.width("! in trouble");
        graphics.drawString(font, Component.literal("  drag to move, scroll to zoom"), x, y, 0x707070);
    }

    private void renderPanel(GuiGraphics graphics, RealmMapClient.Data data, int mouseX, int mouseY) {
        rows.clear();
        int x0 = width - PANEL_WIDTH - MARGIN, x1 = width - MARGIN;
        graphics.fill(x0 - 1, TOP - 1, x1 + 1, mapBottom() + 1, 0xFF505050);
        graphics.fill(x0, TOP, x1, mapBottom(), 0xF0181818);
        graphics.enableScissor(x0, TOP, x1, mapBottom());
        int x = x0 + 6, w = PANEL_WIDTH - 12;
        int[] y = { TOP + 6 };
        text(graphics, "The Kingdom", x, y, GOLD);
        text(graphics, "Army " + data.kingdom().getOrDefault("army", 0) + "   Food " + data.kingdomPurse().food() + "   Trade " + data.kingdomPurse().trade(), x, y, 0xFFFFFF);
        Collection<RealmMapClient.Node> nodes = data.nodes().values();
        long towns = nodes.stream().filter(node -> "town".equals(node.type())).count();
        long held = nodes.stream().filter(node -> "town".equals(node.type()) && node.kingdoms()).count();
        long strongholds = nodes.stream().filter(node -> "stronghold".equals(node.type()) && node.kingdoms()).count();
        long troubled = nodes.stream().filter(node -> !node.warnings().isEmpty()).count();
        text(graphics, "Towns " + held + "/" + towns + "   Strongholds held " + strongholds, x, y, 0xFFFFFF);
        text(graphics, troubled == 0 ? "Nowhere is in trouble" : troubled + (troubled == 1 ? " place" : " places") + " in trouble", x, y,
                troubled == 0 ? 0x80FF80 : RED);
        y[0] += 4;
        text(graphics, "Nations", x, y, GOLD);
        if (data.nations().isEmpty()) {
            text(graphics, "None in the field", x, y, GREY);
        }
        for (RealmMapClient.Nation nation : data.nations()) {
            String line = capitalised(ownerName(data, nation.id())) + ": " + nation.military() + " troops, " + nation.strongholds()
                    + (nation.strongholds() == 1 ? " stronghold" : " strongholds") + (nation.towns() > 0 ? ", " + nation.towns() + " towns" : "")
                    + (nation.pushedBack() ? " (pushed back)" : nation.marching() ? " (on the march)" : "");
            wrapped(graphics, line, x, y, w, RealmMapClient.color(nation.id()));
        }
        if (!data.raids().isEmpty()) {
            y[0] += 4;
            text(graphics, "Raids at dusk", x, y, GOLD);
            for (RealmMapClient.Raid raid : data.raids()) {
                long left = data.until(raid) / 1000;
                String who = "kingdom".equals(raid.nation()) ? "Kingdom's army" : capitalised(ownerName(data, raid.nation()));
                row(graphics, raid.to(), who + " -> " + data.name(raid.to()) + " (" + left / 60 + ":" + String.format("%02d", left % 60) + ")", x, y, w,
                        "kingdom".equals(raid.nation()) ? 0x80C0FF : RED, mouseX, mouseY);
            }
        }
        y[0] += 6;
        graphics.fill(x, y[0] - 3, x + w, y[0] - 2, 0xFF404040);
        RealmMapClient.Node node = selected == null ? null : data.nodes().get(selected);
        if (node != null) {
            text(graphics, node.name(), x, y, GOLD);
            wrapped(graphics, describe(node, ownerName(data, node.owner())), x, y, w, GREY);
            if (!node.dim().equals(shownDim)) {
                wrapped(graphics, "In " + dimName(node.dim()), x, y, w, GREY);
            }
            node.warnings().forEach(warning -> wrapped(graphics, "! " + warning, x, y, w, RED));
            node.notes().forEach(note -> wrapped(graphics, note, x, y, w, YELLOW));
            y[0] += 2;
            for (RealmMapClient.Fact fact : node.facts()) {
                String label = fact.label() + ": ";
                graphics.drawString(font, label, x, y[0], GREY);
                graphics.drawString(font, fact.value(), x + font.width(label), y[0], 0xFFFFFF);
                y[0] += LINE;
                if (!fact.hint().isEmpty()) {
                    for (FormattedCharSequence line : font.split(Component.literal(fact.hint()), w - 8)) {
                        graphics.drawString(font, line, x + 8, y[0], 0x808080);
                        y[0] += LINE;
                    }
                }
            }
            List<String> links = new ArrayList<>();
            for (String[] link : data.links()) {
                if (link[0].equals(node.id()) || link[1].equals(node.id())) {
                    links.add(link[0].equals(node.id()) ? link[1] : link[0]);
                }
            }
            y[0] += 2;
            text(graphics, links.isEmpty() ? "No links" : "Links", x, y, GOLD);
            for (String other : links) {
                RealmMapClient.Node linked = data.nodes().get(other);
                row(graphics, other, "- " + data.name(other) + (linked == null ? "" : " (" + ownerName(data, linked.owner()) + ")"), x, y, w,
                        linked == null ? GREY : RealmMapClient.color(linked.owner()), mouseX, mouseY);
            }
            y[0] += 2;
            wrapped(graphics, "Click empty map to go back to the trouble list", x, y, w, 0x707070);
        } else {
            text(graphics, "In trouble", x, y, GOLD);
            if (troubled == 0) {
                text(graphics, "All quiet", x, y, GREY);
            }
            for (RealmMapClient.Node trouble : nodes.stream().filter(other -> !other.warnings().isEmpty()).toList()) {
                row(graphics, trouble.id(), "! " + trouble.name() + ": " + trouble.warnings().get(0), x, y, w, RED, mouseX, mouseY);
            }
            y[0] += 4;
            wrapped(graphics, "Click a place on the map or in a list to see it", x, y, w, 0x707070);
        }
        graphics.disableScissor();
    }

    private void text(GuiGraphics graphics, String text, int x, int[] y, int color) {
        graphics.drawString(font, Component.literal(text), x, y[0], color);
        y[0] += LINE;
    }

    private void wrapped(GuiGraphics graphics, String text, int x, int[] y, int width, int color) {
        for (FormattedCharSequence line : font.split(Component.literal(text), width)) {
            graphics.drawString(font, line, x, y[0], color);
            y[0] += LINE;
        }
    }

    /** A panel line that selects a node when clicked, highlighted under the mouse. */
    private void row(GuiGraphics graphics, String id, String text, int x, int[] y, int width, int color, int mouseX, int mouseY) {
        int top = y[0];
        List<FormattedCharSequence> lines = font.split(Component.literal(text), width);
        int bottom = top + lines.size() * LINE;
        if (mouseX >= x && mouseX < x + width && mouseY >= top - 1 && mouseY < bottom - 1) {
            graphics.fill(x - 2, top - 1, x + width + 2, bottom - 1, 0x30FFFFFF);
        }
        for (FormattedCharSequence line : lines) {
            graphics.drawString(font, line, x, y[0], color);
            y[0] += LINE;
        }
        rows.add(new Object[] { id, x, top - 1, x + width, bottom - 1 });
    }

    /** "Town, kingdom, tier 2 (village)", "Stronghold, goblins", and so on. */
    public static String describe(RealmMapClient.Node node) {
        return describe(node, node.owner());
    }

    /** "A village of the kingdom", "A stronghold of the Redfang goblins", and so on. */
    static String describe(RealmMapClient.Node node, String ownerName) {
        String type = "town".equals(node.type()) ? TIERS[Mth.clamp(node.stat("tier"), 0, TIERS.length - 1)] : node.type();
        String kind = node.kind().isEmpty() ? "" : " (" + node.kind() + ")";
        String article = "town".equals(node.type()) && node.stat("tier") == 0 ? "" : "aeiou".indexOf(type.charAt(0)) >= 0 ? "An " : "A ";
        String what = article.isEmpty() ? capitalised(type) : article + type;
        return what + kind + (node.monsters() || node.kingdoms() ? " held by " + ownerName : ", held by no one");
    }

    private static final String[] TIERS = { "ruins", "hamlet", "village", "town", "market town", "city" };

    private static String dimName(String dim) {
        String path = dim.contains(":") ? dim.substring(dim.indexOf(':') + 1) : dim;
        return Character.toUpperCase(path.charAt(0)) + path.substring(1).replace('_', ' ');
    }

    /** A line of 1-pixel dots: GuiGraphics has no line drawing of its own. */
    private static void line(GuiGraphics graphics, int xa, int ya, int xb, int yb, int color) {
        int steps = Math.max(Math.abs(xb - xa), Math.abs(yb - ya));
        if (steps > 4000) {
            return;
        }
        for (int i = 0; i <= steps; i++) {
            int x = steps == 0 ? xa : xa + (xb - xa) * i / steps;
            int y = steps == 0 ? ya : ya + (yb - ya) * i / steps;
            graphics.fill(x, y, x + 1, y + 1, color);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        dragged = false;
        return super.mouseClicked(mouseX, mouseY, button) || button == 0;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        RealmMapClient.Data data = RealmMapClient.latest();
        if (button == 0 && !dragged) {
            for (Button each : List.copyOf(buttons)) {
                if (each.contains(mouseX, mouseY)) {
                    each.action().run();
                    return true;
                }
            }
        }
        if (button == 0 && !dragged && data != null && tab == Tab.MAP) {
            for (Object[] row : rows) {
                if (mouseX >= (int) row[1] && mouseX < (int) row[3] && mouseY >= (int) row[2] && mouseY < (int) row[4]) {
                    select(data, (String) row[0], true);
                    return true;
                }
            }
            if (inMap(mouseX, mouseY)) {
                RealmMapClient.Node node = nodeAt(data, mouseX, mouseY);
                selected = node == null ? null : node.id();
                return true;
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Selects a node, and if asked, shows its dimension and moves the map to it. */
    private void select(RealmMapClient.Data data, String id, boolean center) {
        RealmMapClient.Node node = data.nodes().get(id);
        if (node == null) {
            return;
        }
        selected = id;
        if (center) {
            if (!node.dim().equals(shownDim)) {
                shownDim = node.dim();
                fit(data);
            }
            centerX = node.x();
            centerZ = node.z();
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && tab == Tab.MAP && inMap(mouseX, mouseY) && scale > 0) {
            dragged = dragged || Math.abs(dragX) + Math.abs(dragY) > 0;
            centerX -= dragX / scale;
            centerZ -= dragY / scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (tab != Tab.MAP && scrollY != 0) {
            scroll -= (int) Math.round(scrollY * LINE * 3);
            return true;
        }
        if (inMap(mouseX, mouseY) && scale > 0 && scrollY != 0) {
            // Zoom about the mouse: the block under it stays under it.
            double worldX = centerX + (mouseX - (mapLeft() + mapRight()) / 2.0) / scale;
            double worldZ = centerZ + (mouseY - (mapTop() + mapBottom()) / 2.0) / scale;
            scale = Mth.clamp(scale * (scrollY > 0 ? 1.25 : 0.8), 0.005, 8.0);
            centerX = worldX - (mouseX - (mapLeft() + mapRight()) / 2.0) / scale;
            centerZ = worldZ - (mouseY - (mapTop() + mapBottom()) / 2.0) / scale;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (OPEN.matches(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @EventBusSubscriber(modid = KingdomSim.MODID, value = Dist.CLIENT)
    public static final class Keys {
        private Keys() {}

        @SubscribeEvent
        public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
            event.register(OPEN);
        }

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            Minecraft minecraft = Minecraft.getInstance();
            while (OPEN.consumeClick()) {
                if (minecraft.screen == null && minecraft.player != null) {
                    minecraft.setScreen(new RealmScreen());
                }
            }
        }
    }
}
