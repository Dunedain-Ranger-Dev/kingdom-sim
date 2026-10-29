package io.github.ranger3462.ddkingdom.realm;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import io.github.ranger3462.ddkingdom.KingdomSim;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The realm menu's data (realm menu, see client.RealmScreen): every node with its stats, links and troubles, and how
 * the kingdom and each nation stand, sent to players when they log in, when they open the menu, and whenever the realm
 * changes (checked every {@link #PUSH_INTERVAL} ticks).
 * <p>
 * A node's <b>warnings</b> are what's bad for the kingdom: a raid coming, ruins, damage, low morale, being outmatched by
 * a neighbouring stronghold, or a town lost to a nation. Its <b>notes</b> are just how it stands: an upgrade under way, a
 * march on it, a capital rallying.
 */
public final class RealmMap {
    public static final int PUSH_INTERVAL = 100;
    /** Morale below this is a warning. */
    public static final int LOW_MORALE = 5;
    private static CompoundTag lastSent;

    private RealmMap() {}

    /** Client to server: send me the realm (on opening the menu). */
    public record Request() implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(KingdomSim.id("realm_map_request"));
        public static final StreamCodec<io.netty.buffer.ByteBuf, Request> STREAM_CODEC = StreamCodec.unit(new Request());

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }

        static void handle(Request payload, IPayloadContext context) {
            if (context.player() instanceof ServerPlayer player) {
                send(player);
            }
        }
    }

    /** Server to client: the realm, as {@link #build} makes it, with the server's clock ({@code now}) for countdowns. */
    public record Sync(CompoundTag data) implements CustomPacketPayload {
        public static final Type<Sync> TYPE = new Type<>(KingdomSim.id("realm_map"));
        public static final StreamCodec<io.netty.buffer.ByteBuf, Sync> STREAM_CODEC = StreamCodec.composite(ByteBufCodecs.COMPOUND_TAG, Sync::data, Sync::new);

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /** The realm as the menu shows it (without the clock, so two builds can be compared). */
    public static CompoundTag build(Realm realm) {
        CompoundTag tag = new CompoundTag();
        ListTag nodes = new ListTag();
        for (RealmNode node : realm.nodes()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", node.id);
            entry.putString("name", node.name());
            entry.putString("type", node.type.id());
            entry.putString("owner", node.owner());
            entry.putString("kind", node.kind());
            entry.putString("dim", node.dimension().location().toString());
            entry.putInt("x", node.pos().getX());
            entry.putInt("y", node.pos().getY());
            entry.putInt("z", node.pos().getZ());
            CompoundTag stats = new CompoundTag();
            node.stats().forEach(stats::putInt);
            entry.put("stats", stats);
            entry.put("warnings", strings(warnings(realm, node)));
            entry.put("notes", strings(notes(realm, node)));
            ListTag facts = new ListTag();
            for (Fact fact : facts(realm, node)) {
                CompoundTag factTag = new CompoundTag();
                factTag.putString("label", fact.label());
                factTag.putString("value", fact.value());
                factTag.putString("hint", fact.hint());
                facts.add(factTag);
            }
            entry.put("facts", facts);
            nodes.add(entry);
        }
        tag.put("nodes", nodes);
        ListTag links = new ListTag();
        for (String[] link : realm.links()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("a", link[0]);
            entry.putString("b", link[1]);
            links.add(entry);
        }
        tag.put("links", links);
        CompoundTag kingdom = new CompoundTag();
        realm.kingdomStats().forEach(kingdom::putInt);
        tag.put("kingdom", kingdom);
        ListTag nations = new ListTag();
        for (String nation : realm.nations()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", nation);
            entry.putInt("military", RealmTurns.military(realm, nation));
            entry.putInt("strongholds", RealmTurns.strongholds(realm, nation).size());
            entry.putInt("towns", RealmTurns.towns(realm, nation).size());
            entry.putBoolean("pushedBack", RealmCapitals.pushedBack(realm, nation));
            RealmFactions.Traits traits = RealmFactions.get(nation);
            entry.putString("name", traits.displayName());
            entry.putString("leader", traits.leader());
            entry.putString("champion", traits.champion());
            entry.putString("capital", traits.capital());
            entry.putString("about", about(traits));
            entry.putBoolean("marching", realm.nodes().stream().anyMatch(node -> nation.equals(node.owner()) && node.stat("bursting") > 0));
            entry.put("purse", purse(realm, nation));
            nations.add(entry);
        }
        tag.put("nations", nations);
        ListTag raids = new ListTag();
        for (Realm.Raid raid : realm.raids()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("nation", raid.nation());
            entry.putString("from", raid.stronghold());
            entry.putString("to", raid.town());
            entry.putLong("resolveAt", raid.resolveAt());
            raids.add(entry);
        }
        tag.put("raids", raids);
        ListTag log = new ListTag();
        for (Realm.LogEntry logged : realm.log()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("turn", logged.turn());
            entry.putString("text", logged.text());
            entry.putBoolean("news", logged.news());
            entry.putString("kind", logged.kind().name());
            entry.putString("who", logged.who());
            log.add(entry);
        }
        tag.put("log", log);
        tag.putString("kingdomAbout", about(RealmFactions.get(RealmNode.KINGDOM)));
        tag.put("kingdomPurse", purse(realm, RealmNode.KINGDOM));
        tag.putInt("turn", realm.turn());
        tag.putLong("lastTurn", realm.lastTurnMillis());
        tag.putLong("turnLength", RealmTurns.turnMillis());
        return tag;
    }

    /** A faction's food and trade, what comes in and goes out a turn, and how much its trade speeds it up. */
    private static CompoundTag purse(Realm realm, String faction) {
        CompoundTag purse = new CompoundTag();
        RealmEconomy.Income income = RealmEconomy.income(realm, faction);
        purse.putInt("food", RealmEconomy.food(realm, faction));
        purse.putInt("trade", RealmEconomy.trade(realm, faction));
        purse.putInt("foodIn", income.food());
        purse.putInt("tradeIn", income.trade());
        purse.putInt("upkeep", income.upkeep());
        purse.putInt("factor", (int) Math.round(RealmEconomy.factor(realm, faction) * 100));
        return purse;
    }

    /** A line of a place's stats as the menu shows it: "Defense: 34", with what it means ("20 its own, 14 from the army"). */
    public record Fact(String label, String value, String hint) {}

    /** Stats the facts already cover, or that are bookkeeping no one needs to see. */
    private static final java.util.Set<String> COVERED = java.util.Set.of("tier", "military", "fortification", "defense", "morale", "wealth",
            "upgrading", "damage", "repair", "wrecked", "rally", "securing", "blight", "bursting", "heat", "fallen");

    /** A place's stats in plain words, the most telling first. */
    public static List<Fact> facts(Realm realm, RealmNode node) {
        List<Fact> facts = new ArrayList<>();
        if (node.type == RealmNode.Type.TOWN) {
            int tier = RealmTiers.tier(node);
            facts.add(new Fact("Size", RealmTiers.name(tier), tier == 0 ? "the next raid that wins takes it" : "tier " + tier + " of 5"));
        } else if (node.type == RealmNode.Type.CAPITAL) {
            facts.add(new Fact("Size", "capital city", "can be sacked, never taken"));
        }
        RealmEconomy.kind(node).ifPresent(kind -> facts.add(new Fact("Gives", kind.gives, "to whoever holds it")));
        if (node.type == RealmNode.Type.RESOURCE && RealmEconomy.kind(node).isEmpty()) {
            facts.add(new Fact("Gives", "nothing yet", "set its kind: " + String.join(", ", RealmEconomy.kindIds())));
        }
        int own = RealmRaids.ownDefense(node);
        int defense = RealmRaids.defense(realm, node);
        int mines = RealmEconomy.mineDefense(realm, node);
        if (node.type == RealmNode.Type.STRONGHOLD || node.type == RealmNode.Type.CAPITAL || node.stat("military") > 0) {
            facts.add(new Fact("Troops", String.valueOf(node.stat("military")),
                    node.type == RealmNode.Type.CAPITAL && node.monsters() ? "its garrison: half adds to its defense" : node.monsters() ? "what it raids with" : "its garrison"));
        }
        List<String> from = new ArrayList<>();
        if (defense - own - mines > 0) {
            from.add((defense - own - mines) + " from the kingdom's army");
        }
        if (mines > 0) {
            from.add(mines + " from its mines");
        }
        if (node.type == RealmNode.Type.CAPITAL) {
            facts.add(new Fact("Defense", String.valueOf(defense), "doubled: a capital's defenders fight twice as hard"));
        } else {
            facts.add(new Fact("Defense", String.valueOf(defense), from.isEmpty() ? "what attackers must beat" : own + " its own, " + String.join(", ", from)));
        }
        if (node.settlement()) {
            int morale = node.stat("morale");
            facts.add(new Fact("Morale", String.valueOf(morale), morale < LOW_MORALE ? "low: its people may give up" : "how its people hold up"));
        }
        if (node.stat("wealth") > 0) {
            facts.add(new Fact("Wealth", String.valueOf(node.stat("wealth")), "lost when it's sacked"));
        }
        if (node.stat("upgrading") > 0) {
            facts.add(new Fact("Growing", node.stat("upgrading") + (node.stat("upgrading") == 1 ? " day" : " days") + " left", "no support until then"));
        }
        if (node.stat("damage") > 0 || node.stat("wrecked") > node.stat("repair")) {
            facts.add(new Fact("Damage", node.stat("repair") + " of " + node.stat("wrecked") + " rebuilt", "still rebuilding"));
        }
        if (node.stat("securing") > 0) {
            facts.add(new Fact("Securing", node.stat("securing") + (node.stat("securing") == 1 ? " turn" : " turns"), "fortifying it before raiding again"));
        }
        if (node.stat("blight") > 0) {
            facts.add(new Fact("Blight", node.stat("blight") + " of " + RealmLevels.MAX_BLIGHT, "the land around it grows more dangerous"));
        }
        if (node.stat("bursting") > 0) {
            facts.add(new Fact("On the march", "yes", "raiding until the odds turn"));
        }
        if (node.type == RealmNode.Type.CAPITAL && node.stat("rally") > 0) {
            facts.add(new Fact("Rallying", node.stat("rally") + " of " + RealmCapitals.RALLY_TURNS, "turns until it's back in the field"));
        }
        facts.add(new Fact("Danger nearby", "level " + RealmLevels.level(realm, node.dimension(), node.pos()), "how tough the monsters around it are"));
        node.stats().forEach((stat, value) -> {
            if (!COVERED.contains(stat) && value != 0) {
                facts.add(new Fact(Character.toUpperCase(stat.charAt(0)) + stat.substring(1).replace('_', ' '), String.valueOf(value), ""));
            }
        });
        return facts;
    }

    /** How a faction plays, in a line: "Grows fast. Attacks at 45%+ odds, the kingdom first. Secures what it takes." */
    public static String about(RealmFactions.Traits traits) {
        List<String> parts = new ArrayList<>();
        String grows = traits.growth() >= 1.2 ? "Grows fast" : traits.growth() <= 0.85 ? "Grows slowly" : "Grows steadily";
        parts.add(grows + ".");
        String whom = switch (traits.targets()) {
            case ANYONE -> "anyone";
            case KINGDOM -> "only the kingdom";
            case KINGDOM_FIRST -> "the kingdom first, other monsters when they're weak";
            case MONSTERS -> "the monster nations";
        };
        String keen = traits.aggression() >= 1.5 ? "Eager to fight: " : traits.aggression() <= 0.8 ? "Cautious: " : "";
        parts.add(keen + "attacks " + whom + " at " + Math.round(traits.attackBar() * 100) + "%+ odds.");
        if (traits.militaryBonus() > 0) {
            parts.add("Hits " + Math.round(traits.militaryBonus() * 100) + "% harder.");
        }
        if (traits.fortifyAfterCapture()) {
            parts.add("Secures what it takes before moving on.");
        }
        if (traits.homeRadius() > 0) {
            parts.add("Stays within " + traits.homeRadius() + " links of home.");
        }
        if (traits.burstRatio() > 0) {
            parts.add("Waits at its capital until it's " + (traits.burstRatio() == 2.0 ? "twice" : traits.burstRatio() + "x") + " as strong, then strikes.");
        }
        if (traits.raiseDead() > 0) {
            parts.add("Raises " + (traits.raiseDead() > 0.3 && traits.raiseDead() < 0.35 ? "a third" : Math.round(traits.raiseDead() * 100) + "%")
                    + " of the fallen after every battle.");
        }
        if (traits.blight() > 0) {
            parts.add("Blights the land it holds.");
        }
        RealmEconomy.Kind.byId(traits.affinity()).ifPresent(kind -> parts.add("Gets twice as much from " + kind.id().replace('_', ' ') + "s."));
        return String.join(" ", parts);
    }

    /** What's bad for the kingdom at a node. */
    public static List<String> warnings(Realm realm, RealmNode node) {
        List<String> warnings = new ArrayList<>();
        if (node.type == RealmNode.Type.TOWN && node.monsters()) {
            warnings.add("Taken by the " + node.owner());
            return warnings;
        }
        if (!node.kingdoms()) {
            return warnings;
        }
        for (Realm.Raid raid : realm.raids()) {
            if (raid.town().equals(node.id) && !RealmNode.KINGDOM.equals(raid.nation())) {
                String from = realm.node(raid.stronghold()).map(RealmNode::name).orElse(raid.stronghold());
                warnings.add("Raid at dusk: the " + raid.nation() + " of " + from);
            }
        }
        if (node.type == RealmNode.Type.TOWN && RealmTiers.tier(node) == 0) {
            warnings.add("In ruins: the next raid that wins takes it");
        }
        if (node.stat("damage") > 0 || node.stat("wrecked") > node.stat("repair")) {
            warnings.add("Damaged: still rebuilding");
        }
        if (node.type == RealmNode.Type.TOWN && node.stat("morale") < LOW_MORALE) {
            warnings.add("Low morale (" + node.stat("morale") + ")");
        }
        int defense = RealmRaids.defense(realm, node);
        Optional<RealmNode> threat = realm.neighbors(node.id).stream().filter(other -> other.type == RealmNode.Type.STRONGHOLD && other.monsters())
                .max(java.util.Comparator.comparingInt(other -> other.stat("military")));
        if (threat.isPresent() && threat.get().stat("military") > defense) {
            int own = RealmRaids.ownDefense(node);
            warnings.add("Outmatched: " + threat.get().name() + " has " + threat.get().stat("military") + " military against its " + defense + " defense"
                    + (defense > own ? " (" + own + " its own, " + (defense - own) + " from the kingdom's army)" : ""));
        }
        return warnings;
    }

    /** How a node stands, when it's not a warning. */
    public static List<String> notes(Realm realm, RealmNode node) {
        List<String> notes = new ArrayList<>();
        if (RealmTiers.upgrading(node)) {
            int days = node.stat("upgrading");
            notes.add("Upgrading to a " + RealmTiers.name(RealmTiers.tier(node) + 1) + ": " + days + (days == 1 ? " day" : " days") + " left, no support until then");
        }
        for (Realm.Raid raid : realm.raids()) {
            if (raid.town().equals(node.id) && RealmNode.KINGDOM.equals(raid.nation())) {
                notes.add("The kingdom's army marches on it at dusk");
            } else if (raid.town().equals(node.id) && !node.kingdoms()) {
                notes.add("The " + raid.nation() + " march on it at dusk");
            }
        }
        if (node.type == RealmNode.Type.CAPITAL && RealmCapitals.pushedBack(realm, node.owner())) {
            notes.add("Pushed back: rallying (" + node.stat("rally") + "/" + RealmCapitals.RALLY_TURNS + ")");
        }
        return notes;
    }

    private static ListTag strings(List<String> values) {
        ListTag list = new ListTag();
        values.forEach(value -> list.add(StringTag.valueOf(value)));
        return list;
    }

    private static Sync sync(Realm realm, CompoundTag built) {
        CompoundTag data = built.copy();
        data.putLong("now", System.currentTimeMillis());
        return new Sync(data);
    }

    public static void send(ServerPlayer player) {
        Realm realm = Realm.get(player.server);
        PacketDistributor.sendToPlayer(player, sync(realm, build(realm)));
    }

    /** Sends the realm to everyone if it's changed since it was last sent. */
    static void push(MinecraftServer server) {
        CompoundTag built = build(Realm.get(server));
        if (!built.equals(lastSent)) {
            lastSent = built;
            PacketDistributor.sendToAllPlayers(sync(Realm.get(server), built));
        }
    }

    @EventBusSubscriber(modid = KingdomSim.MODID)
    public static final class Payloads {
        private Payloads() {}

        @SubscribeEvent
        public static void onRegisterPayloads(RegisterPayloadHandlersEvent event) {
            event.registrar("1").playToServer(Request.TYPE, Request.STREAM_CODEC, Request::handle);
            // The handler only ever runs on the client, so the client class it names is never loaded on a server.
            event.registrar("1").playToClient(Sync.TYPE, Sync.STREAM_CODEC,
                    (payload, context) -> io.github.ranger3462.ddkingdom.client.RealmMapClient.receive(payload.data()));
        }
    }

    @EventBusSubscriber(modid = KingdomSim.MODID)
    public static final class Events {
        private Events() {}

        @SubscribeEvent
        public static void onLogIn(PlayerEvent.PlayerLoggedInEvent event) {
            if (event.getEntity() instanceof ServerPlayer player) {
                send(player);
            }
        }

        @SubscribeEvent
        public static void onServerTick(ServerTickEvent.Post event) {
            if (event.getServer().getTickCount() % PUSH_INTERVAL == 0) {
                push(event.getServer());
            }
        }
    }
}
