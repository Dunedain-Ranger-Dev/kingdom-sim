package io.github.ranger3462.ddkingdom.command;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmSimTurns;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import io.github.ranger3462.ddkingdom.realm.SimRealm;
import io.github.ranger3462.ddkingdom.sim.Game;
import io.github.ranger3462.ddkingdom.sim.Kingdom;
import io.github.ranger3462.ddkingdom.sim.Node;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * The realm by the simulator's rules (docs/design/realm-cutover.md, phase 2), for operators:
 * <ul>
 * <li>{@code /realm sim}: the round, the time to the next, each faction's land, army and stockpiles, and fights held for dusk.</li>
 * <li>{@code /realm sim start [seed]}: starts the realm afresh on the server's map ({@link SimRealm#DEFAULT_MAP}); {@code restart} replaces one already started.</li>
 * <li>{@code /realm sim place <node>}: where a node stands in the world (where you stand). {@code unplaced}: the nodes still to place.</li>
 * <li>{@code /realm sim info <node>}; {@code set <node> tier|garrison|defense|morale <value>}; {@code owner <node> <faction>|none}.</li>
 * <li>{@code /realm sim stock <faction> food|gold|res <amount>}: a faction's stockpile.</li>
 * <li>{@code /realm sim turn [count]}: play rounds now.</li>
 * </ul>
 * The realm plays these rounds only while {@code realmEngine} is {@code sim}.
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class SimCommands {
    private static final SuggestionProvider<CommandSourceStack> NODES = (context, builder) -> SharedSuggestionProvider.suggest(
            sim(context).map(s -> s.game.nodes.stream().map(n -> n.id)).orElse(Stream.empty()), builder);
    private static final SuggestionProvider<CommandSourceStack> FACTIONS = (context, builder) -> SharedSuggestionProvider.suggest(
            sim(context).map(s -> s.game.kingdoms.stream().map(k -> k.id)).orElse(Stream.empty()), builder);

    private SimCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("realm").requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("sim").executes(SimCommands::status)
                        .then(Commands.literal("start").executes(context -> start(context, false, (int) (System.currentTimeMillis() & 0x7FFFFFFF)))
                                .then(Commands.argument("seed", IntegerArgumentType.integer())
                                        .executes(context -> start(context, false, IntegerArgumentType.getInteger(context, "seed")))))
                        .then(Commands.literal("restart").executes(context -> start(context, true, (int) (System.currentTimeMillis() & 0x7FFFFFFF)))
                                .then(Commands.argument("seed", IntegerArgumentType.integer())
                                        .executes(context -> start(context, true, IntegerArgumentType.getInteger(context, "seed")))))
                        .then(Commands.literal("place").then(node().executes(SimCommands::place)))
                        .then(Commands.literal("unplaced").executes(SimCommands::unplaced))
                        .then(Commands.literal("info").then(node().executes(SimCommands::info)))
                        .then(Commands.literal("set").then(node().then(Commands.argument("stat", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("tier", "garrison", "defense", "morale"), builder))
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 100_000)).executes(SimCommands::set)))))
                        .then(Commands.literal("owner").then(node().then(Commands.argument("owner", StringArgumentType.word())
                                .suggests((context, builder) -> SharedSuggestionProvider.suggest(Stream.concat(Stream.of("none"),
                                        sim(context).map(s -> s.game.kingdoms.stream().map(k -> k.id)).orElse(Stream.empty())), builder))
                                .executes(SimCommands::owner))))
                        .then(Commands.literal("stock").then(Commands.argument("faction", StringArgumentType.word()).suggests(FACTIONS)
                                .then(Commands.argument("what", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(List.of("food", "gold", "res"), builder))
                                        .then(Commands.argument("amount", IntegerArgumentType.integer(0, 1_000_000)).executes(SimCommands::stock)))))
                        .then(Commands.literal("turn").executes(context -> turn(context, 1))
                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                        .executes(context -> turn(context, IntegerArgumentType.getInteger(context, "count")))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, String> node() {
        return Commands.argument("node", StringArgumentType.word()).suggests(NODES);
    }

    private static Realm realm(CommandContext<CommandSourceStack> context) {
        return Realm.get(context.getSource().getServer());
    }

    private static Optional<SimRealm> sim(CommandContext<CommandSourceStack> context) {
        return realm(context).sim();
    }

    /** The started simulator realm, or says there isn't one. */
    private static Optional<SimRealm> started(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> sim = sim(context);
        if (sim.isEmpty()) {
            Feedback.fail(context.getSource(), "The realm hasn't been started by the simulator's rules: /realm sim start.");
        }
        return sim;
    }

    /** The named node, or says there's no such node. */
    private static Optional<Node> node(CommandContext<CommandSourceStack> context, SimRealm sim) {
        String id = StringArgumentType.getString(context, "node");
        Node n = sim.game.node(id);
        if (n == null) {
            Feedback.fail(context.getSource(), "No node " + id + " on " + sim.mapId + ".");
        }
        return Optional.ofNullable(n);
    }

    private static int status(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        if (started.isEmpty()) {
            return 0;
        }
        SimRealm sim = started.get();
        Game game = sim.game;
        long seconds = sim.untilNext(System.currentTimeMillis(), RealmTurns.turnMillis()) / 1000;
        Feedback.tell(context.getSource(), "Round " + game.round + " on " + sim.mapId + ". The next in " + seconds / 60 + ":" + String.format("%02d", seconds % 60)
                + (KingdomConfig.simEngine() ? "." : " (but realmEngine is legacy, so it waits)."));
        for (Kingdom k : game.kingdoms) {
            int nodes = game.ownedNodes(k.id).size();
            if (k.minor && nodes == 0) {
                Feedback.detail(context.getSource(), k.name + ": not on the map (back from round " + k.nextSpawn + ").");
                continue;
            }
            Feedback.detail(context.getSource(), k.name + ": " + nodes + " nodes, army " + game.army(k.id) + ", food " + Math.round(k.food) + ", gold "
                    + Math.round(k.gold) + ", resources " + Math.round(k.res));
        }
        long now = System.currentTimeMillis();
        for (Game.HeldBattle b : game.held) {
            Long dusk = sim.dusk(b.id);
            long left = dusk == null ? 0 : Math.max(0, dusk - now) / 1000;
            Feedback.detail(context.getSource(), "Held for dusk: " + b.troops + " of " + game.kingdom(b.kid).name + " from " + game.node(b.from).name + " on "
                    + game.node(b.target).name + " (" + b.kind.name().toLowerCase(java.util.Locale.ROOT) + "), in " + left / 60 + ":"
                    + String.format("%02d", left % 60) + ".");
        }
        return game.round;
    }

    private static int start(CommandContext<CommandSourceStack> context, boolean restart, int seed) {
        Realm realm = realm(context);
        if (realm.sim().isPresent() && !restart) {
            Feedback.fail(context.getSource(), "The realm has already been started by the simulator's rules; /realm sim restart starts it over.");
            return 0;
        }
        SimRealm sim = SimRealm.start(SimRealm.DEFAULT_MAP, seed, System.currentTimeMillis());
        realm.startSim(sim);
        long placed = sim.game.nodes.stream().filter(n -> realm.simPlace(n.id).isPresent()).count();
        Feedback.tell(context.getSource(), "The realm starts afresh on " + sim.map().name + " (seed " + seed + "): " + sim.game.nodes.size() + " nodes, "
                + placed + " placed in the world.");
        if (placed < sim.game.nodes.size()) {
            Feedback.detail(context.getSource(), "Place the rest where they stand with /realm sim place <node> (/realm sim unplaced lists them).");
        }
        if (!KingdomConfig.simEngine()) {
            Feedback.detail(context.getSource(), "It plays only once realmEngine is sim, in config/ddkingdom-common.toml.");
        }
        return 1;
    }

    private static int place(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        Optional<Node> n = started.flatMap(sim -> node(context, sim));
        if (n.isEmpty()) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        realm(context).setSimPlace(n.get().id, context.getSource().getLevel().dimension(), pos);
        Feedback.tell(context.getSource(), n.get().name + " (" + n.get().id + ") stands at " + pos.toShortString() + ".");
        return 1;
    }

    private static int unplaced(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        if (started.isEmpty()) {
            return 0;
        }
        Realm realm = realm(context);
        List<Node> left = started.get().game.nodes.stream().filter(n -> realm.simPlace(n.id).isEmpty()).toList();
        Feedback.tell(context.getSource(), left.isEmpty() ? "Every node is placed." : left.size() + " nodes still to place:");
        left.forEach(n -> Feedback.detail(context.getSource(), n.id + ": " + n.name + " (" + n.type.label + ")"));
        return left.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        Optional<Node> found = started.flatMap(sim -> node(context, sim));
        if (found.isEmpty()) {
            return 0;
        }
        Game game = started.get().game;
        Node n = found.get();
        Feedback.tell(context.getSource(), n.name + " (" + n.id + "): " + n.tierName() + ", T" + n.tier + ", " + (n.owner == null ? "no one's" : game.kingdom(n.owner).name + "'s"));
        Feedback.detail(context.getSource(), "Garrison " + n.garrison + ", defense " + n.defense + ", morale " + n.morale + ", devastation "
                + Math.round(n.devastation * 100) + "%" + (n.upgrade != null ? ", upgrading to T" + n.upgrade.target + " in " + n.upgrade.turnsLeft + " turns" : ""));
        Feedback.detail(context.getSource(), "Linked to " + String.join(", ", game.neighbours(n.id)) + "."
                + realm(context).simPlace(n.id).map(p -> " Stands at " + p.pos().toShortString() + ".").orElse(" Not placed in the world."));
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        Optional<Node> found = started.flatMap(sim -> node(context, sim));
        if (found.isEmpty()) {
            return 0;
        }
        Node n = found.get();
        String stat = StringArgumentType.getString(context, "stat");
        int value = IntegerArgumentType.getInteger(context, "value");
        switch (stat) {
            case "tier" -> n.tier = Math.min(value, 5);
            case "garrison" -> n.garrison = value;
            case "defense" -> n.defense = value;
            case "morale" -> n.morale = Math.max(1, Math.min(value, 10));
            default -> {
                Feedback.fail(context.getSource(), "Set tier, garrison, defense or morale.");
                return 0;
            }
        }
        realm(context).setDirty();
        Feedback.tell(context.getSource(), n.name + ": " + stat + " " + value + ".");
        return 1;
    }

    private static int owner(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        Optional<Node> found = started.flatMap(sim -> node(context, sim));
        if (found.isEmpty()) {
            return 0;
        }
        Game game = started.get().game;
        Node n = found.get();
        String owner = StringArgumentType.getString(context, "owner");
        if (!"none".equals(owner) && game.kingdom(owner) == null) {
            Feedback.fail(context.getSource(), "No faction " + owner + ".");
            return 0;
        }
        n.owner = "none".equals(owner) ? null : owner;
        realm(context).setDirty();
        Feedback.tell(context.getSource(), n.name + " is now " + (n.owner == null ? "no one's" : game.kingdom(n.owner).name + "'s") + ".");
        return 1;
    }

    private static int stock(CommandContext<CommandSourceStack> context) {
        Optional<SimRealm> started = started(context);
        if (started.isEmpty()) {
            return 0;
        }
        Kingdom k = started.get().game.kingdom(StringArgumentType.getString(context, "faction"));
        if (k == null) {
            Feedback.fail(context.getSource(), "No such faction.");
            return 0;
        }
        String what = StringArgumentType.getString(context, "what");
        int amount = IntegerArgumentType.getInteger(context, "amount");
        switch (what) {
            case "food" -> k.food = amount;
            case "gold" -> k.gold = amount;
            case "res" -> k.res = amount;
            default -> {
                Feedback.fail(context.getSource(), "Set food, gold or res.");
                return 0;
            }
        }
        realm(context).setDirty();
        Feedback.tell(context.getSource(), k.name + ": " + what + " " + amount + ".");
        return 1;
    }

    private static int turn(CommandContext<CommandSourceStack> context, int count) {
        Optional<SimRealm> started = started(context);
        if (started.isEmpty()) {
            return 0;
        }
        int from = started.get().game.round;
        RealmSimTurns.playNow(context.getSource().getServer(), realm(context), started.get(), count);
        RealmTurns.tellNews(context.getSource().getServer(), realm(context));
        Feedback.tell(context.getSource(), "Played round " + from + (count > 1 ? " to " + (from + count - 1) : "") + ".");
        return count;
    }
}
