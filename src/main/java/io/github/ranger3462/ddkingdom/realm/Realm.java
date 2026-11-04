package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * The realm's map (DD-94, docs/design/realm.md): its nodes, and the links between them. Links make borders: a kingdom
 * node linked to a monster nation's is on the border. Saved with the world (in the overworld's data). Nodes are
 * hand-placed and linked with the /realm commands.
 */
public final class Realm extends SavedData {
    private static final String NAME = "dungeondelving_realm";
    /** Node ids: lowercase letters, digits and underscores. */
    public static final Pattern ID = Pattern.compile("[a-z0-9_]{1,32}");

    private final Map<String, RealmNode> nodes = new LinkedHashMap<>();
    /** Links, each stored once as "a|b" with a before b. */
    private final Set<String> links = new LinkedHashSet<>();

    // --- Turns (DD-95, see RealmTurns) ---

    /** How many turns the realm has taken. */
    int turn;
    /** When the last turn was due, in real time (milliseconds); 0 until the clock first starts. */
    long lastTurnMillis;
    /** The kingdom's own stats: its standing army and treasury. */
    final Map<String, Integer> kingdom = new LinkedHashMap<>(Map.of("army", 10));
    /** Each faction's food and trade (DD-103, see RealmEconomy), by faction. */
    final Map<String, Map<String, Integer>> purses = new LinkedHashMap<>();
    /** Raids declared and not yet resolved. */
    final List<Raid> raids = new ArrayList<>();
    // --- The realm by the simulator's rules (docs/design/realm-cutover.md, see SimRealm and RealmSimTurns) ---

    /** The simulator's realm, once started (/realm sim start); null until then. */
    private SimRealm sim;
    /** Where each of the simulator's nodes is in the world, by its id. */
    private final Map<String, Place> simPlaces = new LinkedHashMap<>();

    /** Whether the realm's nodes mirror the simulator's (RealmSimTurns.mirror): its raids are the simulator's held fights. Not saved. */
    boolean mirrored;

    /** A place in the world. */
    public record Place(ResourceKey<Level> dimension, BlockPos pos) {}

    /** The most recent happenings, oldest first. */
    final java.util.Deque<LogEntry> log = new java.util.ArrayDeque<>();
    static final int LOG_SIZE = 100;

    /** A monster nation's raid on a town, from one of its strongholds, declared on a turn and resolved at dusk ({@code resolveAt}, real time). */
    public record Raid(String nation, String stronghold, String town, int declared, long resolveAt) {}

    /** What sort of thing a log entry is, for the realm menu's news to colour and filter by. */
    public enum LogKind {
        /** Building up, fortifying, mustering: the routine of a turn. */
        PREPARE,
        /** A raid or march declared, a rally, a nation falling back. */
        MARCH,
        /** A fight and how it went. */
        BATTLE,
        /** A place changing hands. */
        CAPTURE,
        /** A town growing, rebuilding or falling to ruin. */
        TOWN,
        /** Food and trade: running short, troops deserting. */
        SUPPLY,
        OTHER
    }

    /** Something that happened on a turn, of a {@code kind}, about a faction ({@code who}, or empty). News is told to the players too. */
    public record LogEntry(int turn, String text, boolean news, LogKind kind, String who) {
        public LogEntry(int turn, String text, boolean news) {
            this(turn, text, news, LogKind.OTHER, "");
        }
    }

    /** News not yet told to the players (not saved: told once, while the server runs). */
    final List<LogEntry> untold = new ArrayList<>();

    public static Realm get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(new SavedData.Factory<>(Realm::new, Realm::load, null), NAME);
    }

    /** Who can own a node: the kingdom, a monster nation (a dungeon faction), or no one. */
    public static boolean validOwner(String owner) {
        return RealmNode.KINGDOM.equals(owner) || RealmNode.NONE.equals(owner) || RealmFactions.nations().contains(owner);
    }

    /** The owner a new node of a type starts with, when none is given: towns are the kingdom's; strongholds need a nation. */
    public static Optional<String> defaultOwner(RealmNode.Type type) {
        return switch (type) {
            case TOWN -> Optional.of(RealmNode.KINGDOM);
            case STRONGHOLD, CAPITAL -> Optional.empty();
            default -> Optional.of(RealmNode.NONE);
        };
    }

    /** Why a new node can't have this owner, if it can't: strongholds start as a nation's. */
    public static Optional<String> ownerProblem(RealmNode.Type type, String owner) {
        return ownerProblem(type, owner, true);
    }

    /**
     * Why a node can't have this owner, if it can't. A town can be anyone's. A new stronghold ({@code founding}) must
     * be a nation's; an existing one can be taken by the kingdom or cleared (none). A capital is a nation's, or the
     * kingdom's when it's founded, and is never cleared.
     */
    public static Optional<String> ownerProblem(RealmNode.Type type, String owner, boolean founding) {
        if (!validOwner(owner)) {
            return Optional.of("Unknown owner " + owner + ": use kingdom, none, or a monster nation (" + String.join(", ", RealmFactions.nations()) + ").");
        }
        if (type == RealmNode.Type.CAPITAL && (RealmNode.NONE.equals(owner) || (!founding && RealmNode.KINGDOM.equals(owner)))) {
            return Optional.of("A capital is a nation's home, or the kingdom's, and can't be taken (" + String.join(", ", RealmFactions.nations()) + ", or kingdom when it's made).");
        }
        if (founding && type == RealmNode.Type.STRONGHOLD && (RealmNode.KINGDOM.equals(owner) || RealmNode.NONE.equals(owner))) {
            return Optional.of("A stronghold belongs to a monster nation (" + String.join(", ", RealmFactions.nations()) + ").");
        }
        return Optional.empty();
    }

    // --- Nodes ---

    public Collection<RealmNode> nodes() {
        return Collections.unmodifiableCollection(nodes.values());
    }

    public Optional<RealmNode> node(String id) {
        return Optional.ofNullable(nodes.get(id));
    }

    /** Adds a node; false if the id is taken. */
    public boolean add(RealmNode node) {
        if (nodes.putIfAbsent(node.id, node) != null) {
            return false;
        }
        if (node.type == RealmNode.Type.RESOURCE && node.kind().isEmpty()) {
            node.setKind(RealmEconomy.Kind.FARMLAND.id());
        }
        setDirty();
        return true;
    }

    /** Removes a node and its links; false if there's no such node. */
    public boolean remove(String id) {
        if (nodes.remove(id) == null) {
            return false;
        }
        links.removeIf(link -> ends(link)[0].equals(id) || ends(link)[1].equals(id));
        setDirty();
        return true;
    }

    public void rename(RealmNode node, String name) {
        node.setName(name);
        setDirty();
    }

    /**
     * Gives a node to an owner. A stronghold, site or town changing hands is news: monsters taking one make the land around it more
     * dangerous (see RealmLevels); losing one calms it.
     */
    public void setOwner(RealmNode node, String owner) {
        boolean wasMonsters = node.monsters();
        String was = node.owner();
        node.setOwner(owner);
        if (!was.equals(owner)) {
            node.stats().remove("blight"); // A new owner's land starts clean (DD-102).
            node.stats().remove("securing");
            Optional<RealmNode> home = node.type == RealmNode.Type.STRONGHOLD && wasMonsters ? RealmCapitals.capital(this, was) : Optional.empty();
            int survivors = node.stat("military") / 2;
            if (home.isPresent() && survivors > 0) {
                // The survivors of a lost stronghold fall back to their capital (DD-104).
                home.get().stats().merge("military", survivors, Integer::sum);
                node.stats().put("military", 0);
                news(LogKind.MARCH, was, "The survivors of " + node.name() + ", " + survivors + " strong, fall back to " + home.get().name() + ".");
            }
        }
        boolean holdable = node.type == RealmNode.Type.STRONGHOLD || node.type == RealmNode.Type.SITE || node.type == RealmNode.Type.TOWN;
        if (holdable && wasMonsters && node.monsters() && !was.equals(owner)) {
            news(LogKind.CAPTURE, owner, "The " + RealmFactions.get(owner).displayName() + " have taken " + node.name() + " from the " + RealmFactions.get(was).displayName() + ".");
        } else if (holdable && !wasMonsters && node.monsters()) {
            news(LogKind.CAPTURE, owner, "The " + RealmFactions.get(owner).displayName() + " have taken " + node.name() + ": the land around it grows more dangerous.");
        } else if (holdable && wasMonsters && !node.monsters()) {
            news(LogKind.CAPTURE, owner, node.name() + (node.kingdoms() ? " has been taken by the kingdom" : " has been cleared") + ": the land around it calms.");
        }
        setDirty();
    }

    public void setKind(RealmNode node, String kind) {
        node.setKind(kind);
        setDirty();
    }

    /** Sets how a town's guards look: a skin setting and a color (blank for none). */
    public void setGuardLook(RealmNode node, String skin, String color) {
        node.setGuardLook(skin, color);
        setDirty();
    }

    /** Adds a post for a town's garrison (see RealmGarrison). */
    public void addPost(RealmNode node, BlockPos post) {
        node.addPost(post);
        setDirty();
    }

    /** Clears a town's posts: its garrison stands in a ring round its middle again. */
    public void clearPosts(RealmNode node) {
        node.clearPosts();
        setDirty();
    }

    public void move(RealmNode node, ResourceKey<Level> dimension, BlockPos pos) {
        node.moveTo(dimension, pos);
        setDirty();
    }

    public void setStat(RealmNode node, String stat, int value) {
        node.setStat(stat, value);
        setDirty();
    }

    // --- Links ---

    private static String key(String a, String b) {
        return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
    }

    private static String[] ends(String link) {
        return link.split("\\|", 2);
    }

    /** Links two nodes; false if either is missing, they're the same node, or they're already linked. */
    public boolean link(String a, String b) {
        if (a.equals(b) || !nodes.containsKey(a) || !nodes.containsKey(b) || !links.add(key(a, b))) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean unlink(String a, String b) {
        if (!links.remove(key(a, b))) {
            return false;
        }
        setDirty();
        return true;
    }

    public boolean linked(String a, String b) {
        return links.contains(key(a, b));
    }

    /** Every link, as the two node ids it joins. */
    public List<String[]> links() {
        return links.stream().map(Realm::ends).toList();
    }

    /** The nodes linked to one. */
    public List<RealmNode> neighbors(String id) {
        List<RealmNode> neighbors = new ArrayList<>();
        for (String link : links) {
            String[] ends = ends(link);
            String other = ends[0].equals(id) ? ends[1] : ends[1].equals(id) ? ends[0] : null;
            if (other != null && nodes.containsKey(other)) {
                neighbors.add(nodes.get(other));
            }
        }
        return neighbors;
    }

    /** Whether a node is on the border: the kingdom's and linked to a monster nation's, or the other way round. */
    public boolean onBorder(RealmNode node) {
        if (!node.kingdoms() && !node.monsters()) {
            return false;
        }
        return neighbors(node.id).stream().anyMatch(other -> node.kingdoms() ? other.monsters() : other.kingdoms());
    }

    public int linkCount() {
        return links.size();
    }

    // --- Turns ---

    public int turn() {
        return turn;
    }

    public long lastTurnMillis() {
        return lastTurnMillis;
    }

    public int kingdomStat(String stat) {
        return kingdom.getOrDefault(stat, 0);
    }

    public Map<String, Integer> kingdomStats() {
        return Collections.unmodifiableMap(kingdom);
    }

    /** A faction's food and trade (unset ones are RealmEconomy.START). */
    public Map<String, Integer> purse(String faction) {
        return Collections.unmodifiableMap(purses.getOrDefault(faction, Map.of()));
    }

    public void setPurse(String faction, String what, int amount) {
        purses.computeIfAbsent(faction, key -> new LinkedHashMap<>()).put(what, amount);
        setDirty();
    }

    public List<Raid> raids() {
        return Collections.unmodifiableList(raids);
    }

    public List<LogEntry> log() {
        return List.copyOf(log);
    }

    public void addKingdomStat(String stat, int amount) {
        kingdom.merge(stat, amount, Integer::sum);
        setDirty();
    }

    void addStat(RealmNode node, String stat, int amount) {
        node.setStat(stat, node.stat(stat) + amount);
        setDirty();
    }

    void record(String text) {
        record(LogKind.OTHER, "", text);
    }

    void record(LogKind kind, String who, String text) {
        record(text, false, kind, who);
    }

    /** Records something the players should hear about too. */
    void news(String text) {
        news(LogKind.OTHER, "", text);
    }

    void news(LogKind kind, String who, String text) {
        record(text, true, kind, who);
    }

    private void record(String text, boolean news, LogKind kind, String who) {
        LogEntry entry = new LogEntry(turn, text, news, kind, who);
        log.addLast(entry);
        while (log.size() > LOG_SIZE) {
            log.removeFirst();
        }
        if (news) {
            untold.add(entry);
        }
        setDirty();
    }

    /** Takes the news not yet told to players. */
    public List<LogEntry> takeNews() {
        List<LogEntry> news = List.copyOf(untold);
        untold.clear();
        return news;
    }

    /** The monster nations that hold a stronghold: the ones that take turns. */
    public List<String> nations() {
        return nodes.values().stream().filter(node -> (node.type == RealmNode.Type.STRONGHOLD || node.type == RealmNode.Type.CAPITAL) && node.monsters())
                .map(RealmNode::owner).distinct().toList();
    }

    // --- The simulator's realm ---

    public Optional<SimRealm> sim() {
        return Optional.ofNullable(sim);
    }

    /**
     * Starts the realm afresh by the simulator's rules. A simulator node with the same id as one of this realm's
     * nodes is placed where that one is.
     */
    public void startSim(SimRealm fresh) {
        sim = fresh;
        for (io.github.ranger3462.ddkingdom.sim.Node n : fresh.game.nodes) {
            RealmNode same = nodes.get(n.id);
            if (same != null && !simPlaces.containsKey(n.id)) {
                simPlaces.put(n.id, new Place(same.dimension(), same.pos()));
            }
        }
        setDirty();
    }

    /** Where a simulator node is in the world, if it's been placed. */
    public Optional<Place> simPlace(String id) {
        return Optional.ofNullable(simPlaces.get(id));
    }

    public void setSimPlace(String id, ResourceKey<Level> dimension, BlockPos pos) {
        simPlaces.put(id, new Place(dimension, pos));
        setDirty();
    }

    /** Records what the simulator's realm did: news is told to players too. */
    void recordSim(LogKind kind, String who, String text, boolean news) {
        record(text, news, kind, who);
    }

    // --- Saving ---

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag nodeList = new ListTag();
        nodes.values().forEach(node -> nodeList.add(node.save()));
        tag.put("nodes", nodeList);
        ListTag linkList = new ListTag();
        links.forEach(link -> linkList.add(StringTag.valueOf(link)));
        tag.put("links", linkList);
        tag.putInt("turn", turn);
        tag.putLong("last_turn_millis", lastTurnMillis);
        CompoundTag kingdomTag = new CompoundTag();
        kingdom.forEach(kingdomTag::putInt);
        tag.put("kingdom", kingdomTag);
        CompoundTag purseTag = new CompoundTag();
        purses.forEach((faction, purse) -> {
            CompoundTag one = new CompoundTag();
            purse.forEach(one::putInt);
            purseTag.put(faction, one);
        });
        tag.put("purses", purseTag);
        ListTag raidList = new ListTag();
        for (Raid raid : raids) {
            CompoundTag raidTag = new CompoundTag();
            raidTag.putString("nation", raid.nation());
            raidTag.putString("stronghold", raid.stronghold());
            raidTag.putString("town", raid.town());
            raidTag.putInt("declared", raid.declared());
            raidTag.putLong("resolve_at", raid.resolveAt());
            raidList.add(raidTag);
        }
        tag.put("raids", raidList);
        ListTag logList = new ListTag();
        for (LogEntry entry : log) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putInt("turn", entry.turn());
            entryTag.putString("text", entry.text());
            entryTag.putBoolean("news", entry.news());
            entryTag.putString("kind", entry.kind().name());
            entryTag.putString("who", entry.who());
            logList.add(entryTag);
        }
        tag.put("log", logList);
        if (sim != null) {
            tag.putByteArray("sim", sim.save().getBytes(java.nio.charset.StandardCharsets.UTF_8)); // too big for an NBT string
        }
        ListTag placeList = new ListTag();
        simPlaces.forEach((id, place) -> {
            CompoundTag one = new CompoundTag();
            one.putString("id", id);
            one.putString("dimension", place.dimension().location().toString());
            one.putInt("x", place.pos().getX());
            one.putInt("y", place.pos().getY());
            one.putInt("z", place.pos().getZ());
            placeList.add(one);
        });
        tag.put("sim_places", placeList);
        return tag;
    }

    static Realm load(CompoundTag tag, HolderLookup.Provider registries) {
        Realm realm = new Realm();
        for (Tag entry : tag.getList("nodes", Tag.TAG_COMPOUND)) {
            RealmNode.load((CompoundTag) entry).ifPresent(node -> realm.nodes.put(node.id, node));
        }
        for (Tag entry : tag.getList("links", Tag.TAG_STRING)) {
            String[] ends = ends(entry.getAsString());
            if (ends.length == 2 && realm.nodes.containsKey(ends[0]) && realm.nodes.containsKey(ends[1])) {
                realm.links.add(key(ends[0], ends[1]));
            }
        }
        realm.turn = tag.getInt("turn");
        realm.lastTurnMillis = tag.getLong("last_turn_millis");
        CompoundTag kingdomTag = tag.getCompound("kingdom");
        for (String stat : kingdomTag.getAllKeys()) {
            realm.kingdom.put(stat, kingdomTag.getInt(stat));
        }
        CompoundTag purseTag = tag.getCompound("purses");
        for (String faction : purseTag.getAllKeys()) {
            CompoundTag one = purseTag.getCompound(faction);
            one.getAllKeys().forEach(what -> realm.purses.computeIfAbsent(faction, key -> new LinkedHashMap<>()).put(what, one.getInt(what)));
        }
        Integer treasury = realm.kingdom.remove("treasury"); // Saved before purses (DD-103): the kingdom's treasury was its trade.
        if (treasury != null && !realm.purse(RealmNode.KINGDOM).containsKey(RealmEconomy.TRADE)) {
            realm.purses.computeIfAbsent(RealmNode.KINGDOM, key -> new LinkedHashMap<>()).put(RealmEconomy.TRADE, treasury);
        }
        for (Tag entry : tag.getList("raids", Tag.TAG_COMPOUND)) {
            CompoundTag raid = (CompoundTag) entry;
            realm.raids.add(new Raid(raid.getString("nation"), raid.getString("stronghold"), raid.getString("town"), raid.getInt("declared"),
                    raid.getLong("resolve_at")));
        }
        if (tag.contains("sim")) {
            try {
                realm.sim = SimRealm.load(new String(tag.getByteArray("sim"), java.nio.charset.StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                io.github.ranger3462.ddkingdom.KingdomSim.LOGGER.error("The realm's simulator game didn't load; it waits to be started again", e);
            }
        }
        for (Tag entry : tag.getList("sim_places", Tag.TAG_COMPOUND)) {
            CompoundTag one = (CompoundTag) entry;
            net.minecraft.resources.ResourceLocation dimension = net.minecraft.resources.ResourceLocation.tryParse(one.getString("dimension"));
            if (dimension != null) {
                realm.simPlaces.put(one.getString("id"), new Place(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dimension),
                        new BlockPos(one.getInt("x"), one.getInt("y"), one.getInt("z"))));
            }
        }
        for (Tag entry : tag.getList("log", Tag.TAG_COMPOUND)) {
            CompoundTag logged = (CompoundTag) entry;
            realm.log.addLast(new LogEntry(logged.getInt("turn"), logged.getString("text"), logged.getBoolean("news"), logKind(logged.getString("kind")),
                    logged.getString("who")));
        }
        return realm;
    }

    static LogKind logKind(String name) {
        try {
            return LogKind.valueOf(name);
        } catch (IllegalArgumentException e) {
            return LogKind.OTHER; // Saved before kinds were.
        }
    }

    /** A copy through saving and loading, for tests. */
    public Realm reloaded(HolderLookup.Provider registries) {
        return load(save(new CompoundTag(), registries), registries);
    }
}
