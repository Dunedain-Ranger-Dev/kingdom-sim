package io.github.ranger3462.ddkingdom.realm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

/**
 * One place in the realm (DD-94, docs/design/realm.md): a town, site, stronghold, battle or event, hand-placed at a
 * position. It has an owner (the kingdom, a monster nation, or no one), a kind for sites and events (a watchtower, a
 * caravan), and stats by name (a town's defense, wealth and morale; a stronghold's military).
 */
public final class RealmNode {
    /** The owner of the kingdom's nodes. Monster nations own theirs by their dungeon faction id (goblins, orcs, undead). */
    public static final String KINGDOM = "kingdom";
    /** No owner: an unheld site, a battle, an event. */
    public static final String NONE = "none";

    public enum Type {
        /** A settlement: defense, wealth, morale, and its tier (starting as ruins, see RealmTiers). */
        TOWN(Map.of("defense", 10, "wealth", 10, "morale", 10, "tier", 0)),
        /** A place worth holding: its heat rises the longer it's held. */
        SITE(Map.of("heat", 0)),
        /** A monster nation's base: its military and how fortified it is. */
        STRONGHOLD(Map.of("military", 10, "fortification", 5)),
        /** Land that gives its holder food, trade or more each turn, by its kind (RealmEconomy.Kind): anyone can take it. */
        RESOURCE(Map.of("fortification", 3)),
        /**
         * A faction's home (DD-101, DD-104): a city with a town's defense, wealth and morale, and a garrison (its military).
         * It can be sacked but never taken, and sends its champion once its nation is pushed back.
         */
        CAPITAL(Map.of("military", 30, "rally", 0, "defense", 20, "wealth", 20, "morale", 10, "tier", RealmTiers.MAX_TIER)),
        /** A raid or attack in progress (made by the turns, later). */
        BATTLE(Map.of()),
        /** A caravan, plague, festival and the like (later). */
        EVENT(Map.of());

        /** The stats a new node of this type starts with. */
        public final Map<String, Integer> defaults;

        Type(Map<String, Integer> defaults) {
            this.defaults = defaults;
        }

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Optional<Type> byId(String id) {
            for (Type type : values()) {
                if (type.id().equals(id)) {
                    return Optional.of(type);
                }
            }
            return Optional.empty();
        }
    }

    public final String id;
    public final Type type;
    private String name;
    private String owner;
    private String kind = "";
    /** Who held it before its owner took it (blank if no one has), so a faction knows what it lost (DD-102). */
    private String lostBy = "";
    /** A town's guard look (/realm guards): skin setting (see TownGuard.pickSkin; blank is our set) and color (blank is none). */
    private String guardSkin = "";
    private String guardColor = "";
    /** Where a town's garrison stands (/realm guards <town> post), in turn; none means a ring round its middle (see RealmGarrison). */
    private final java.util.List<BlockPos> posts = new java.util.ArrayList<>();
    private ResourceKey<Level> dimension;
    private BlockPos pos;
    private final Map<String, Integer> stats = new LinkedHashMap<>();

    public RealmNode(String id, Type type, String owner, ResourceKey<Level> dimension, BlockPos pos) {
        this.id = id;
        this.type = type;
        this.name = id;
        this.owner = owner;
        this.dimension = dimension;
        this.pos = pos.immutable();
        stats.putAll(type.defaults);
    }

    public String name() {
        return name;
    }

    /** Whether people live here, with a town's defense, wealth and morale: a town or a capital. */
    public boolean settlement() {
        return type == Type.TOWN || type == Type.CAPITAL;
    }

    public String owner() {
        return owner;
    }

    public String lostBy() {
        return lostBy;
    }

    public String kind() {
        return kind;
    }

    public ResourceKey<Level> dimension() {
        return dimension;
    }

    public BlockPos pos() {
        return pos;
    }

    public Map<String, Integer> stats() {
        return stats;
    }

    public int stat(String stat) {
        return stats.getOrDefault(stat, 0);
    }

    void setName(String name) {
        this.name = name;
    }

    void setOwner(String owner) {
        if (!owner.equals(this.owner)) {
            lostBy = this.owner;
        }
        this.owner = owner;
    }

    void setKind(String kind) {
        this.kind = kind;
    }

    public String guardSkin() {
        return guardSkin;
    }

    public String guardColor() {
        return guardColor;
    }

    public java.util.List<BlockPos> posts() {
        return java.util.Collections.unmodifiableList(posts);
    }

    void addPost(BlockPos post) {
        posts.add(post.immutable());
    }

    void clearPosts() {
        posts.clear();
    }

    void setGuardLook(String skin, String color) {
        this.guardSkin = skin;
        this.guardColor = color;
    }

    void moveTo(ResourceKey<Level> dimension, BlockPos pos) {
        this.dimension = dimension;
        this.pos = pos.immutable();
    }

    void setStat(String stat, int value) {
        stats.put(stat, value);
    }

    /** Whether the kingdom holds it. */
    public boolean kingdoms() {
        return KINGDOM.equals(owner);
    }

    /** Whether a monster nation holds it. */
    public boolean monsters() {
        return !KINGDOM.equals(owner) && !NONE.equals(owner);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", id);
        tag.putString("type", type.id());
        tag.putString("name", name);
        tag.putString("owner", owner);
        tag.putString("kind", kind);
        tag.putString("lost_by", lostBy);
        tag.putString("guard_skin", guardSkin);
        tag.putString("guard_color", guardColor);
        if (!posts.isEmpty()) {
            tag.putLongArray("posts", posts.stream().mapToLong(BlockPos::asLong).toArray());
        }
        tag.putString("dimension", dimension.location().toString());
        tag.putLong("pos", pos.asLong());
        CompoundTag statTag = new CompoundTag();
        stats.forEach(statTag::putInt);
        tag.put("stats", statTag);
        return tag;
    }

    static Optional<RealmNode> load(CompoundTag tag) {
        Optional<Type> type = Type.byId(tag.getString("type"));
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("dimension"));
        if (type.isEmpty() || dimension == null) {
            return Optional.empty();
        }
        RealmNode node = new RealmNode(tag.getString("id"), type.get(), tag.getString("owner"), ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(tag.getLong("pos")));
        node.name = tag.getString("name");
        node.kind = tag.getString("kind");
        node.lostBy = tag.getString("lost_by");
        node.guardSkin = tag.getString("guard_skin");
        node.guardColor = tag.getString("guard_color");
        for (long post : tag.getLongArray("posts")) {
            node.posts.add(BlockPos.of(post));
        }
        node.stats.clear();
        CompoundTag stats = tag.getCompound("stats");
        for (String stat : stats.getAllKeys()) {
            node.stats.put(stat, stats.getInt(stat));
        }
        if (type.get() == Type.CAPITAL) {
            Type.CAPITAL.defaults.forEach(node.stats::putIfAbsent); // Capitals made before DD-104 become cities.
        }
        return Optional.of(node);
    }
}
