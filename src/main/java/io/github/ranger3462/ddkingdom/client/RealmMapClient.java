package io.github.ranger3462.ddkingdom.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** The realm as last sent by the server (realm.RealmMap), for the realm menu and FTB Chunks' map. */
public final class RealmMapClient {
    public record Node(String id, String name, String type, String owner, String kind, String dim, int x, int y, int z, Map<String, Integer> stats,
            List<String> warnings, List<String> notes, List<Fact> facts) {
        public int stat(String stat) {
            return stats.getOrDefault(stat, 0);
        }

        public boolean kingdoms() {
            return "kingdom".equals(owner);
        }

        public boolean monsters() {
            return !"kingdom".equals(owner) && !"none".equals(owner);
        }
    }

    /** A line of a place's stats: "Defense: 34", and what it means. */
    public record Fact(String label, String value, String hint) {}

    public record Nation(String id, int military, int strongholds, int towns, boolean pushedBack, String name, String leader, String champion,
            String capital, String about, boolean marching, Purse purse) {}

    /** A faction's food and trade, a turn's income and upkeep, and its wealth factor (in hundredths). */
    public record Purse(int food, int trade, int foodIn, int tradeIn, int upkeep, int factor) {
        static Purse of(CompoundTag tag) {
            return new Purse(tag.getInt("food"), tag.getInt("trade"), tag.getInt("foodIn"), tag.getInt("tradeIn"), tag.getInt("upkeep"),
                    tag.contains("factor") ? tag.getInt("factor") : 100);
        }

        /** "Food 12 (+4, troops eat 1)    Trade 20 (+3)    Grows x1.4". */
        public String line() {
            return "Food " + food + " (+" + foodIn + (upkeep > 0 ? ", troops eat " + upkeep : "") + ")    Trade " + trade + " (+" + tradeIn
                    + ")    Grows x" + String.format(java.util.Locale.ROOT, "%.1f", factor / 100.0);
        }
    }

    /** A realm log entry: its kind is one of Realm.LogKind's names. */
    public record Entry(int turn, String text, boolean news, String kind, String who) {}

    public record Raid(String nation, String from, String to, long resolveAt) {}

    public record Data(Map<String, Node> nodes, List<String[]> links, Map<String, Integer> kingdom, List<Nation> nations, List<Raid> raids,
            List<Entry> log, String kingdomAbout, Purse kingdomPurse, int turn, long lastTurn, long turnLength, long serverNow, long receivedAt) {
        /** The server's clock now, by how long it's been since this came. */
        public long serverTime() {
            return serverNow + (System.currentTimeMillis() - receivedAt);
        }

        public long untilNextTurn() {
            return lastTurn == 0 ? turnLength : Math.max(0, lastTurn + turnLength - serverTime());
        }

        public long until(Raid raid) {
            return Math.max(0, raid.resolveAt() - serverTime());
        }

        public String name(String id) {
            Node node = nodes.get(id);
            return node == null ? id : node.name();
        }
    }

    private static Data latest;
    private static boolean waypointsPending;

    private RealmMapClient() {}

    public static Data latest() {
        return latest;
    }

    public static void receive(CompoundTag tag) {
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (Tag entry : tag.getList("nodes", Tag.TAG_COMPOUND)) {
            CompoundTag node = (CompoundTag) entry;
            Map<String, Integer> stats = new LinkedHashMap<>();
            CompoundTag statTag = node.getCompound("stats");
            statTag.getAllKeys().forEach(key -> stats.put(key, statTag.getInt(key)));
            nodes.put(node.getString("id"), new Node(node.getString("id"), node.getString("name"), node.getString("type"), node.getString("owner"),
                    node.getString("kind"), node.getString("dim"), node.getInt("x"), node.getInt("y"), node.getInt("z"), stats,
                    strings(node, "warnings"), strings(node, "notes"), facts(node)));
        }
        List<String[]> links = new ArrayList<>();
        for (Tag entry : tag.getList("links", Tag.TAG_COMPOUND)) {
            links.add(new String[] { ((CompoundTag) entry).getString("a"), ((CompoundTag) entry).getString("b") });
        }
        Map<String, Integer> kingdom = new LinkedHashMap<>();
        CompoundTag kingdomTag = tag.getCompound("kingdom");
        kingdomTag.getAllKeys().forEach(key -> kingdom.put(key, kingdomTag.getInt(key)));
        List<Nation> nations = new ArrayList<>();
        for (Tag entry : tag.getList("nations", Tag.TAG_COMPOUND)) {
            CompoundTag nation = (CompoundTag) entry;
            nations.add(new Nation(nation.getString("id"), nation.getInt("military"), nation.getInt("strongholds"), nation.getInt("towns"),
                    nation.getBoolean("pushedBack"), nation.getString("name"), nation.getString("leader"), nation.getString("champion"),
                    nation.getString("capital"), nation.getString("about"), nation.getBoolean("marching"), Purse.of(nation.getCompound("purse"))));
        }
        List<Raid> raids = new ArrayList<>();
        for (Tag entry : tag.getList("raids", Tag.TAG_COMPOUND)) {
            CompoundTag raid = (CompoundTag) entry;
            raids.add(new Raid(raid.getString("nation"), raid.getString("from"), raid.getString("to"), raid.getLong("resolveAt")));
        }
        List<Entry> log = new ArrayList<>();
        for (Tag entry : tag.getList("log", Tag.TAG_COMPOUND)) {
            CompoundTag logged = (CompoundTag) entry;
            log.add(new Entry(logged.getInt("turn"), logged.getString("text"), logged.getBoolean("news"), logged.getString("kind"), logged.getString("who")));
        }
        latest = new Data(nodes, links, kingdom, nations, raids, log, tag.getString("kingdomAbout"), Purse.of(tag.getCompound("kingdomPurse")), tag.getInt("turn"), tag.getLong("lastTurn"), tag.getLong("turnLength"),
                tag.getLong("now"), System.currentTimeMillis());
        waypointsPending = true;
        updateWaypoints();
    }

    private static List<Fact> facts(CompoundTag node) {
        List<Fact> facts = new ArrayList<>();
        for (Tag entry : node.getList("facts", Tag.TAG_COMPOUND)) {
            CompoundTag fact = (CompoundTag) entry;
            facts.add(new Fact(fact.getString("label"), fact.getString("value"), fact.getString("hint")));
        }
        return facts;
    }

    private static List<String> strings(CompoundTag tag, String key) {
        List<String> values = new ArrayList<>();
        tag.getList(key, Tag.TAG_STRING).forEach(value -> values.add(value.getAsString()));
        return values;
    }

    /** Puts the realm on FTB Chunks' map, when it's installed and ready (otherwise tries again shortly). */
    private static void updateWaypoints() {
        if (!waypointsPending || latest == null || Minecraft.getInstance().level == null) {
            return;
        }
        if (!ModList.get().isLoaded("ftbchunks")) {
            waypointsPending = false;
            return;
        }
        waypointsPending = !io.github.ranger3462.ddkingdom.integration.ftbchunks.RealmMapIcons.update(latest);
    }

    /** A colour for an owner, as 0xRRGGBB: the kingdom blue, each nation its own, no one grey. */
    public static int color(String owner) {
        return switch (owner) {
            case "kingdom" -> 0x4F8CFF;
            case "none" -> 0x9A9A9A;
            case "goblins" -> 0x6FCF4F;
            case "orcs" -> 0xD0603A;
            case "undead" -> 0xA070E0;
            case "kobolds" -> 0xC9A227;
            case "elementals" -> 0x4FD8E8;
            default -> 0x808080 | (owner.hashCode() & 0x7F7F7F);
        };
    }

    @EventBusSubscriber(modid = KingdomSim.MODID, value = Dist.CLIENT)
    public static final class Ticks {
        private Ticks() {}

        @SubscribeEvent
        public static void onClientTick(ClientTickEvent.Post event) {
            if (waypointsPending && Minecraft.getInstance().level != null && Minecraft.getInstance().level.getGameTime() % 40 == 0) {
                updateWaypoints();
            }
        }
    }
}
