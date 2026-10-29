package io.github.ranger3462.ddkingdom.command;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.realm.Realm;
import io.github.ranger3462.ddkingdom.realm.RealmNode;
import io.github.ranger3462.ddkingdom.realm.RealmTurns;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Building the realm's map by hand (DD-94, docs/design/realm.md), for operators:
 * <ul>
 * <li>{@code /realm}: how many nodes and links there are.</li>
 * <li>{@code /realm list [type]} and {@code /realm info <id>}: what's there.</li>
 * <li>{@code /realm node add <id> <type> [owner]}: a node where you stand. {@code remove}, {@code move} (to where you
 * stand), {@code rename}, {@code owner}, {@code kind} (a site's or event's: watchtower, bridge, caravan...), {@code set}
 * (a stat).</li>
 * <li>{@code /realm link <a> <b>} and {@code /realm unlink <a> <b>}: the links that make borders.</li>
 * <li>{@code /realm clock}: the turn, the kingdom's army, each faction's food and trade, raids declared, and the time to the next turn.
 * <li>{@code /realm purse <faction> [food|trade <amount>]}: a faction's food and trade, and its takings a turn; or sets one.
 * {@code /realm turn [count]}: take turns now. {@code /realm log [count]}: what's happened lately (DD-95).</li>
 * <li>{@code /realm blueprint save <id> <radius> [down] [up]}: snapshot the blocks around a node, to rebuild it from after
 * raids. {@code info}, {@code damage <id> <points>} (as a sacking would), {@code repair <id>} (all of it, now) (DD-97).</li>
 * <li>{@code /realm raid <town>}: the strongest stronghold bordering a town raids it, in 10 seconds: fought if you're
 * there, rolled if not (DD-98).</li>
 * <li>{@code /challenge} (anyone, see RealmCapitals): call a pushed-back nation's champion out at its capital (DD-101).</li>
 * <li>{@code /realm danger}: the danger level where you stand, and why (DD-100).</li>
 * <li>{@code /realm side town|raiders}: which side you fight for in the raid you're at (the town's, unless you say).</li>
 * <li>{@code /realm guards <town>}: how its guards look. {@code skin set|mixed|<name>} (our guard set, the game's built-in
 * faces, or one skin from textures/entity/town_guard/), {@code color <dye>|none}, {@code post add} (where you stand) and
 * {@code post clear} (its garrison's posts, see RealmGarrison).</li>
 * </ul>
 */
@EventBusSubscriber(modid = KingdomSim.MODID)
public final class RealmCommands {
    private static final SuggestionProvider<CommandSourceStack> NODES = (context, builder) -> SharedSuggestionProvider.suggest(
            Realm.get(context.getSource().getServer()).nodes().stream().map(node -> node.id), builder);
    private static final SuggestionProvider<CommandSourceStack> TYPES = (context, builder) -> SharedSuggestionProvider.suggest(
            Arrays.stream(RealmNode.Type.values()).map(RealmNode.Type::id), builder);
    private static final SuggestionProvider<CommandSourceStack> OWNERS = (context, builder) -> SharedSuggestionProvider.suggest(
            Stream.concat(Stream.of(RealmNode.KINGDOM, RealmNode.NONE), io.github.ranger3462.ddkingdom.realm.RealmFactions.nations().stream()), builder);

    private static final SuggestionProvider<CommandSourceStack> GUARD_SKINS = (context, builder) -> SharedSuggestionProvider.suggest(
            Stream.concat(Stream.of(io.github.ranger3462.ddkingdom.entity.TownGuard.SKIN_SET, io.github.ranger3462.ddkingdom.entity.TownGuard.SKIN_MIXED),
                    java.util.stream.IntStream.rangeClosed(1, io.github.ranger3462.ddkingdom.entity.TownGuard.GUARD_SKINS).mapToObj(n -> "guard_" + n)),
            builder);
    private static final SuggestionProvider<CommandSourceStack> GUARD_COLORS = (context, builder) -> SharedSuggestionProvider.suggest(
            Stream.concat(Stream.of("none"), Arrays.stream(net.minecraft.world.item.DyeColor.values()).map(net.minecraft.world.item.DyeColor::getName)), builder);

    private RealmCommands() {}

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("realm").requires(source -> source.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .executes(RealmCommands::summary)
                .then(Commands.literal("list").executes(context -> list(context, null))
                        .then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES)
                                .executes(context -> list(context, StringArgumentType.getString(context, "type")))))
                .then(Commands.literal("info").then(node("id").executes(RealmCommands::info)))
                .then(Commands.literal("clock").executes(RealmCommands::clock))
                .then(Commands.literal("purse").then(Commands.argument("faction", StringArgumentType.word())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                io.github.ranger3462.ddkingdom.realm.RealmEconomy.factions(realm(context)), builder))
                        .executes(RealmCommands::purse)
                        .then(Commands.argument("what", StringArgumentType.word())
                                .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(java.util.List.of("food", "trade"), builder))
                                .then(Commands.argument("amount", IntegerArgumentType.integer(0, 100000)).executes(RealmCommands::setPurse)))))
                .then(Commands.literal("danger").executes(RealmCommands::danger))
                .then(Commands.literal("raid").then(node("town").executes(RealmCommands::raid)))
                .then(Commands.literal("guards").then(node("town").executes(RealmCommands::guards)
                        .then(Commands.literal("skin").then(Commands.argument("skin", StringArgumentType.word()).suggests(GUARD_SKINS)
                                .executes(context -> guardLook(context, StringArgumentType.getString(context, "skin"), null))))
                        .then(Commands.literal("color").then(Commands.argument("color", StringArgumentType.word()).suggests(GUARD_COLORS)
                                .executes(context -> guardLook(context, null, StringArgumentType.getString(context, "color")))))
                        .then(Commands.literal("post").then(Commands.literal("add").executes(context -> post(context, true)))
                                .then(Commands.literal("clear").executes(context -> post(context, false))))))
                .then(Commands.literal("side").then(Commands.literal("town").executes(context -> side(context, false)))
                        .then(Commands.literal("raiders").executes(context -> side(context, true))))
                .then(Commands.literal("blueprint")
                        .then(Commands.literal("save").then(node("id").then(Commands.argument("radius", IntegerArgumentType.integer(1, 48))
                                .executes(context -> saveBlueprint(context, 8, 24))
                                .then(Commands.argument("down", IntegerArgumentType.integer(0, 32))
                                        .executes(context -> saveBlueprint(context, IntegerArgumentType.getInteger(context, "down"), 24))
                                        .then(Commands.argument("up", IntegerArgumentType.integer(0, 64)).executes(context -> saveBlueprint(context,
                                                IntegerArgumentType.getInteger(context, "down"), IntegerArgumentType.getInteger(context, "up"))))))))
                        .then(Commands.literal("info").then(node("id").executes(RealmCommands::blueprintInfo)))
                        .then(Commands.literal("damage").then(node("id").then(Commands.argument("points", IntegerArgumentType.integer(1, 1000))
                                .executes(RealmCommands::damage))))
                        .then(Commands.literal("repair").then(node("id").executes(RealmCommands::repair))))
                .then(Commands.literal("turn").executes(context -> turn(context, 1))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(context -> turn(context, IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("log").executes(context -> log(context, 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(context -> log(context, IntegerArgumentType.getInteger(context, "count")))))
                .then(Commands.literal("link").then(node("a").then(node("b").executes(context -> link(context, true)))))
                .then(Commands.literal("unlink").then(node("a").then(node("b").executes(context -> link(context, false)))))
                .then(Commands.literal("node")
                        .then(Commands.literal("add").then(Commands.argument("id", StringArgumentType.word())
                                .then(Commands.argument("type", StringArgumentType.word()).suggests(TYPES).executes(context -> add(context, null))
                                        .then(Commands.argument("owner", StringArgumentType.word()).suggests(OWNERS)
                                                .executes(context -> add(context, StringArgumentType.getString(context, "owner")))))))
                        .then(Commands.literal("remove").then(node("id").executes(RealmCommands::remove)))
                        .then(Commands.literal("move").then(node("id").executes(RealmCommands::move)))
                        .then(Commands.literal("rename").then(node("id").then(Commands.argument("name", StringArgumentType.greedyString())
                                .executes(RealmCommands::rename))))
                        .then(Commands.literal("owner").then(node("id").then(Commands.argument("owner", StringArgumentType.word()).suggests(OWNERS)
                                .executes(RealmCommands::owner))))
                        .then(Commands.literal("kind").then(node("id").then(Commands.argument("kind", StringArgumentType.word())
                                .executes(RealmCommands::kind))))
                        .then(Commands.literal("set").then(node("id").then(Commands.argument("stat", StringArgumentType.word())
                                .then(Commands.argument("value", IntegerArgumentType.integer(0, 1_000_000)).executes(RealmCommands::set)))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> node(String name) {
        return Commands.argument(name, StringArgumentType.word()).suggests(NODES);
    }

    private static Realm realm(CommandContext<CommandSourceStack> context) {
        return Realm.get(context.getSource().getServer());
    }

    /** The node named by an argument, or a message saying there's none. */
    private static Optional<RealmNode> find(CommandContext<CommandSourceStack> context, String argument) {
        String id = StringArgumentType.getString(context, argument);
        Optional<RealmNode> node = realm(context).node(id);
        if (node.isEmpty()) {
            Feedback.fail(context.getSource(), "There's no realm node " + id + ". /realm list shows them.");
        }
        return node;
    }

    private static int summary(CommandContext<CommandSourceStack> context) {
        Realm realm = realm(context);
        Map<RealmNode.Type, Long> counts = realm.nodes().stream().collect(Collectors.groupingBy(node -> node.type, Collectors.counting()));
        Feedback.tell(context.getSource(), "The realm: " + realm.nodes().size() + " nodes, " + realm.linkCount() + " links.");
        for (RealmNode.Type type : RealmNode.Type.values()) {
            Feedback.detail(context.getSource(), type.id() + ": " + counts.getOrDefault(type, 0L));
        }
        return realm.nodes().size();
    }

    private static int list(CommandContext<CommandSourceStack> context, String typeId) {
        Optional<RealmNode.Type> type = typeId == null ? Optional.empty() : RealmNode.Type.byId(typeId);
        if (typeId != null && type.isEmpty()) {
            Feedback.fail(context.getSource(), "Unknown node type " + typeId + ".");
            return 0;
        }
        Realm realm = realm(context);
        List<RealmNode> shown = realm.nodes().stream().filter(node -> type.isEmpty() || node.type == type.get()).toList();
        Feedback.tell(context.getSource(), shown.isEmpty() ? "No realm nodes yet." : "Realm nodes:");
        for (RealmNode node : shown) {
            Feedback.detail(context.getSource(), node.id + " (" + node.type.id() + ", " + node.owner() + ") at " + node.pos().toShortString()
                    + (realm.onBorder(node) ? ", on the border" : ""));
        }
        return shown.size();
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> found = find(context, "id");
        if (found.isEmpty()) {
            return 0;
        }
        RealmNode node = found.get();
        Realm realm = realm(context);
        CommandSourceStack source = context.getSource();
        Feedback.tell(source, node.name() + " (" + node.id + "): a " + node.type.id() + (node.kind().isEmpty() ? "" : " (" + node.kind() + ")")
                + ", held by " + node.owner() + (realm.onBorder(node) ? ", on the border" : ""));
        Feedback.detail(source, "At " + node.pos().toShortString() + " in " + node.dimension().location());
        if (!node.stats().isEmpty()) {
            Feedback.detail(source, "Stats: " + node.stats().entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", ")));
        }
        List<RealmNode> neighbors = realm.neighbors(node.id);
        Feedback.detail(source, neighbors.isEmpty() ? "No links." : "Linked to: " + neighbors.stream().map(n -> n.id + " (" + n.owner() + ")")
                .collect(Collectors.joining(", ")));
        return 1;
    }

    private static int add(CommandContext<CommandSourceStack> context, String ownerArgument) {
        CommandSourceStack source = context.getSource();
        String id = StringArgumentType.getString(context, "id");
        String typeId = StringArgumentType.getString(context, "type");
        if (!Realm.ID.matcher(id).matches()) {
            Feedback.fail(source, "A node id is up to 32 lowercase letters, digits and underscores.");
            return 0;
        }
        Optional<RealmNode.Type> type = RealmNode.Type.byId(typeId);
        if (type.isEmpty()) {
            Feedback.fail(source, "Unknown node type " + typeId + ": town, site, resource, stronghold, capital, battle or event.");
            return 0;
        }
        Optional<String> owner = ownerArgument != null ? Optional.of(ownerArgument) : Realm.defaultOwner(type.get());
        if (owner.isEmpty()) {
            Feedback.fail(source, "Say which monster nation holds it: /realm node add " + id + " stronghold <" + String.join("|", io.github.ranger3462.ddkingdom.realm.RealmFactions.nations()) + ">");
            return 0;
        }
        Optional<String> problem = Realm.ownerProblem(type.get(), owner.get());
        if (problem.isPresent()) {
            Feedback.fail(source, problem.get());
            return 0;
        }
        RealmNode node = new RealmNode(id, type.get(), owner.get(), source.getLevel().dimension(), BlockPos.containing(source.getPosition()));
        if (!realm(context).add(node)) {
            Feedback.fail(source, "There's already a realm node " + id + ".");
            return 0;
        }
        Feedback.tell(source, "Added " + type.get().id() + " " + id + ", held by " + owner.get() + ", at " + node.pos().toShortString() + ".");
        return 1;
    }

    private static int remove(CommandContext<CommandSourceStack> context) {
        String id = StringArgumentType.getString(context, "id");
        if (!realm(context).remove(id)) {
            Feedback.fail(context.getSource(), "There's no realm node " + id + ".");
            return 0;
        }
        Feedback.tell(context.getSource(), "Removed " + id + " and its links.");
        return 1;
    }

    private static int move(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> node = find(context, "id");
        node.ifPresent(found -> {
            CommandSourceStack source = context.getSource();
            realm(context).move(found, source.getLevel().dimension(), BlockPos.containing(source.getPosition()));
            Feedback.tell(source, "Moved " + found.id + " to " + found.pos().toShortString() + ".");
        });
        return node.isPresent() ? 1 : 0;
    }

    private static int rename(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> node = find(context, "id");
        node.ifPresent(found -> {
            realm(context).rename(found, StringArgumentType.getString(context, "name"));
            Feedback.tell(context.getSource(), found.id + " is now called " + found.name() + ".");
        });
        return node.isPresent() ? 1 : 0;
    }

    private static int owner(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> node = find(context, "id");
        if (node.isEmpty()) {
            return 0;
        }
        String owner = StringArgumentType.getString(context, "owner");
        Optional<String> problem = Realm.ownerProblem(node.get().type, owner, false);
        if (problem.isPresent()) {
            Feedback.fail(context.getSource(), problem.get());
            return 0;
        }
        realm(context).setOwner(node.get(), owner);
        Feedback.tell(context.getSource(), node.get().id + " is now held by " + owner + ".");
        RealmTurns.tellNews(context.getSource().getServer(), realm(context));
        return 1;
    }

    private static int kind(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> node = find(context, "id");
        node.ifPresent(found -> {
            realm(context).setKind(found, StringArgumentType.getString(context, "kind"));
            Feedback.tell(context.getSource(), found.id + " is a " + found.kind() + ".");
        });
        return node.isPresent() ? 1 : 0;
    }

    private static int set(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> node = find(context, "id");
        node.ifPresent(found -> {
            String stat = StringArgumentType.getString(context, "stat");
            int value = IntegerArgumentType.getInteger(context, "value");
            realm(context).setStat(found, stat, value);
            Feedback.tell(context.getSource(), found.id + "'s " + stat + " is now " + value + ".");
        });
        return node.isPresent() ? 1 : 0;
    }

    private static int saveBlueprint(CommandContext<CommandSourceStack> context, int down, int up) {
        Optional<RealmNode> found = find(context, "id");
        if (found.isEmpty()) {
            return 0;
        }
        RealmNode node = found.get();
        net.minecraft.server.level.ServerLevel level = context.getSource().getServer().getLevel(node.dimension());
        int radius = IntegerArgumentType.getInteger(context, "radius");
        BlockPos origin = node.pos().offset(-radius, -down, -radius);
        net.minecraft.core.Vec3i size = new net.minecraft.core.Vec3i(radius * 2 + 1, down + up + 1, radius * 2 + 1);
        if (level == null || !level.hasChunksAt(origin, origin.offset(size.getX() - 1, size.getY() - 1, size.getZ() - 1))) {
            Feedback.fail(context.getSource(), "Stand near " + node.id + " so all of it is loaded, then save again.");
            return 0;
        }
        var blueprint = io.github.ranger3462.ddkingdom.realm.Blueprint.capture(context.getSource().getServer(), node.id, level, origin, size);
        Feedback.tell(context.getSource(), "Saved " + node.id + "'s blueprint: " + blueprint.blockCount() + " blocks in a " + size.getX() + "x" + size.getY()
                + "x" + size.getZ() + " box. Raids can only break, and rebuilding only restores, what's in it.");
        return blueprint.blockCount();
    }

    private static int blueprintInfo(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> found = find(context, "id");
        if (found.isEmpty()) {
            return 0;
        }
        RealmNode node = found.get();
        var blueprint = io.github.ranger3462.ddkingdom.realm.Blueprint.of(context.getSource().getServer(), node.id);
        if (blueprint.isEmpty()) {
            Feedback.tell(context.getSource(), node.id + " has no blueprint. /realm blueprint save " + node.id + " <radius> makes one.");
            return 0;
        }
        var size = blueprint.get().size();
        Feedback.tell(context.getSource(), node.id + "'s blueprint: " + blueprint.get().blockCount() + " blocks, " + size.getX() + "x" + size.getY() + "x"
                + size.getZ() + " from " + blueprint.get().origin().toShortString() + ".");
        Feedback.detail(context.getSource(), "Owed damage " + node.stat("damage") + " (x" + io.github.ranger3462.ddkingdom.realm.RealmDamage.BLOCKS_PER_POINT
                + " blocks), wrecked " + node.stat("wrecked") + " blocks, rebuilding " + node.stat("repair") + ".");
        return 1;
    }

    private static int damage(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> found = find(context, "id");
        found.ifPresent(node -> {
            int points = IntegerArgumentType.getInteger(context, "points");
            realm(context).setStat(node, "damage", node.stat("damage") + points);
            Feedback.tell(context.getSource(), node.id + " is owed " + node.stat("damage") + " damage: it's broken when its area is loaded.");
        });
        return found.isPresent() ? 1 : 0;
    }

    private static int repair(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> found = find(context, "id");
        if (found.isEmpty()) {
            return 0;
        }
        RealmNode node = found.get();
        var blueprint = io.github.ranger3462.ddkingdom.realm.Blueprint.of(context.getSource().getServer(), node.id);
        net.minecraft.server.level.ServerLevel level = context.getSource().getServer().getLevel(node.dimension());
        if (blueprint.isEmpty() || level == null) {
            Feedback.fail(context.getSource(), node.id + " has no blueprint to rebuild from.");
            return 0;
        }
        int rebuilt = io.github.ranger3462.ddkingdom.realm.RealmDamage.rebuild(level, blueprint.get(), Integer.MAX_VALUE);
        Realm realm = realm(context);
        realm.setStat(node, "wrecked", 0);
        realm.setStat(node, "repair", 0);
        realm.setStat(node, "damage", 0);
        Feedback.tell(context.getSource(), "Rebuilt " + rebuilt + " blocks of " + node.id + ".");
        return rebuilt;
    }

    private static int raid(CommandContext<CommandSourceStack> context) {
        String town = StringArgumentType.getString(context, "town");
        Realm realm = realm(context);
        var raid = io.github.ranger3462.ddkingdom.realm.RealmRaids.declare(realm, town, System.currentTimeMillis() + 10_000L);
        if (raid.isEmpty()) {
            Feedback.fail(context.getSource(), "No raid: " + town + " must be a town or capital, or a stronghold or site the monsters don't hold, bordering a monster "
                    + "stronghold (a lost stronghold can also be raided by a pushed-back capital), or a monster-held town or capital bordering a kingdom town, "
                    + "with no raid on it already.");
            return 0;
        }
        RealmTurns.tellNews(context.getSource().getServer(), realm);
        if (RealmNode.KINGDOM.equals(raid.get().nation())) {
            Feedback.tell(context.getSource(), "The kingdom's army marches on " + town + ": its attack is rolled in 10 seconds.");
            return 1;
        }
        Feedback.tell(context.getSource(), "The " + raid.get().nation() + " raid " + town + " in 10 seconds. Be within "
                + io.github.ranger3462.ddkingdom.realm.RealmLiveRaids.PRESENCE + " blocks of it to fight it.");
        return 1;
    }

    private static int danger(CommandContext<CommandSourceStack> context) {
        BlockPos pos = BlockPos.containing(context.getSource().getPosition());
        Realm realm = realm(context);
        int level = io.github.ranger3462.ddkingdom.realm.RealmLevels.level(realm, context.getSource().getLevel().dimension(), pos);
        String nearest = realm.nodes().stream().filter(node -> node.dimension().equals(context.getSource().getLevel().dimension()))
                .min(java.util.Comparator.comparingDouble(node -> node.pos().distSqr(pos)))
                .map(node -> " Nearest node: " + node.id + " (" + node.owner() + "), " + (int) Math.sqrt(node.pos().distSqr(pos)) + " blocks.").orElse("");
        context.getSource().sendSuccess(() -> io.github.ranger3462.ddkingdom.realm.RealmLevels.describe(level).copy()
                .append(net.minecraft.network.chat.Component.literal(nearest).withStyle(net.minecraft.ChatFormatting.GRAY)), false);
        return level;
    }

    private static int guards(CommandContext<CommandSourceStack> context) {
        Optional<RealmNode> town = find(context, "town");
        town.ifPresent(found -> Feedback.tell(context.getSource(), found.id + "'s guards: skin " + (found.guardSkin().isEmpty() ? "set" : found.guardSkin())
                + ", color " + (found.guardColor().isEmpty() ? "none" : found.guardColor()) + "; "
                + io.github.ranger3462.ddkingdom.realm.RealmGarrison.strength(found) + " of "
                + io.github.ranger3462.ddkingdom.realm.RealmGarrison.fullStrength(found) + " standing ("
                + found.stat("fallen") + " fallen since dawn), at " + (found.posts().isEmpty() ? "a ring round its middle" : found.posts().size() + " posts") + "."));
        return town.isPresent() ? 1 : 0;
    }

    /** Adds a post where you stand to a town's garrison, or clears them all. */
    private static int post(CommandContext<CommandSourceStack> context, boolean add) {
        Optional<RealmNode> town = find(context, "town");
        if (town.isEmpty()) {
            return 0;
        }
        if (town.get().type != RealmNode.Type.TOWN) {
            Feedback.fail(context.getSource(), town.get().id + " isn't a town.");
            return 0;
        }
        Realm realm = Realm.get(context.getSource().getServer());
        if (add) {
            // On the ground under you: a guard can't walk to a post in the air.
            BlockPos at = BlockPos.containing(context.getSource().getPosition());
            net.minecraft.server.level.ServerLevel level = context.getSource().getLevel();
            while (at.getY() > level.getMinBuildHeight() && level.getBlockState(at.below()).getCollisionShape(level, at.below()).isEmpty()) {
                at = at.below();
            }
            realm.addPost(town.get(), at);
            Feedback.tell(context.getSource(), "Added a post for " + town.get().id + "'s guards at " + at.toShortString() + " (" + town.get().posts().size()
                    + " now; guards take them in turn).");
        } else {
            realm.clearPosts(town.get());
            Feedback.tell(context.getSource(), town.get().id + "'s guards stand in a ring round its middle again.");
        }
        return 1;
    }

    /** Sets a town's guard skin or color (whichever isn't null). */
    private static int guardLook(CommandContext<CommandSourceStack> context, String skin, String color) {
        Optional<RealmNode> town = find(context, "town");
        if (town.isEmpty()) {
            return 0;
        }
        if (town.get().type != RealmNode.Type.TOWN) {
            Feedback.fail(context.getSource(), town.get().id + " isn't a town.");
            return 0;
        }
        if (skin != null && !skin.matches("[a-z0-9_]+")) {
            Feedback.fail(context.getSource(), "A skin name is lower case letters, digits and _.");
            return 0;
        }
        if (color != null && !"none".equals(color) && net.minecraft.world.item.DyeColor.byName(color, null) == null) {
            Feedback.fail(context.getSource(), "No such color: " + color + ".");
            return 0;
        }
        String newSkin = skin != null ? (io.github.ranger3462.ddkingdom.entity.TownGuard.SKIN_SET.equals(skin) ? "" : skin) : town.get().guardSkin();
        String newColor = color != null ? ("none".equals(color) ? "" : color) : town.get().guardColor();
        realm(context).setGuardLook(town.get(), newSkin, newColor);
        return guards(context);
    }

    private static int side(CommandContext<CommandSourceStack> context, boolean raiders) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        net.minecraft.server.level.ServerPlayer player = context.getSource().getPlayerOrException();
        var live = io.github.ranger3462.ddkingdom.realm.RealmLiveRaids.near(player);
        if (live.isEmpty()) {
            Feedback.fail(context.getSource(), "There's no raid being fought near you.");
            return 0;
        }
        io.github.ranger3462.ddkingdom.realm.RealmLiveRaids.side(live.get(), player.getUUID(), raiders);
        Feedback.tell(context.getSource(), raiders ? "You fight for the raiders. The kingdom will remember." : "You fight for the town.");
        return 1;
    }

    /** {@code /realm purse <faction>}: its food and trade, and what comes in and goes out a turn. */
    private static int purse(CommandContext<CommandSourceStack> context) {
        Realm realm = realm(context);
        String faction = StringArgumentType.getString(context, "faction");
        io.github.ranger3462.ddkingdom.realm.RealmEconomy.Income income = io.github.ranger3462.ddkingdom.realm.RealmEconomy.income(realm, faction);
        Feedback.tell(context.getSource(), "The " + faction + ": food " + io.github.ranger3462.ddkingdom.realm.RealmEconomy.food(realm, faction)
                + ", trade " + io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, faction) + ".");
        Feedback.detail(context.getSource(), "Each turn: +" + income.food() + " food, +" + income.trade() + " trade; its troops eat " + income.upkeep() + ".");
        return 1;
    }

    /** {@code /realm purse <faction> <food|trade> <amount>}: sets it (for testing). */
    private static int setPurse(CommandContext<CommandSourceStack> context) {
        String faction = StringArgumentType.getString(context, "faction");
        String what = StringArgumentType.getString(context, "what");
        if (!what.equals("food") && !what.equals("trade")) {
            Feedback.fail(context.getSource(), "A purse holds food or trade.");
            return 0;
        }
        if (!Realm.validOwner(faction) || RealmNode.NONE.equals(faction)) {
            Feedback.fail(context.getSource(), "Unknown faction " + faction + ".");
            return 0;
        }
        io.github.ranger3462.ddkingdom.realm.RealmEconomy.set(realm(context), faction, what, IntegerArgumentType.getInteger(context, "amount"));
        Feedback.tell(context.getSource(), "The " + faction + "'s " + what + " is now " + IntegerArgumentType.getInteger(context, "amount") + ".");
        return 1;
    }

    private static int clock(CommandContext<CommandSourceStack> context) {
        Realm realm = realm(context);
        long seconds = RealmTurns.untilNext(realm, System.currentTimeMillis()) / 1000;
        Feedback.tell(context.getSource(), "Turn " + realm.turn() + ". The next in " + seconds / 60 + ":" + String.format("%02d", seconds % 60) + ".");
        Feedback.detail(context.getSource(), "The kingdom: " + realm.kingdomStats().entrySet().stream().map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.joining(", ")) + "; the border faces " + RealmTurns.threat(realm) + " monster military.");
        for (String faction : io.github.ranger3462.ddkingdom.realm.RealmEconomy.factions(realm)) {
            Feedback.detail(context.getSource(), (RealmNode.KINGDOM.equals(faction) ? "The kingdom" : "The " + faction + ": military " + RealmTurns.military(realm, faction))
                    + ", food " + io.github.ranger3462.ddkingdom.realm.RealmEconomy.food(realm, faction)
                    + ", trade " + io.github.ranger3462.ddkingdom.realm.RealmEconomy.trade(realm, faction));
        }
        for (Realm.Raid raid : realm.raids()) {
            long left = Math.max(0, raid.resolveAt() - System.currentTimeMillis()) / 1000;
            Feedback.detail(context.getSource(), "Raid declared on turn " + raid.declared() + ": the " + raid.nation() + " of " + raid.stronghold() + " on "
                    + raid.town() + ", at dusk in " + left / 60 + ":" + String.format("%02d", left % 60) + ".");
        }
        return realm.turn();
    }

    private static int turn(CommandContext<CommandSourceStack> context, int count) {
        Realm realm = realm(context);
        int from = realm.turn();
        var random = context.getSource().getLevel().getRandom();
        for (int i = 0; i < count; i++) {
            // Forced turns don't wait for dusk: each one's raids resolve before the next.
            RealmTurns.takeTurn(realm, random, System.currentTimeMillis());
            io.github.ranger3462.ddkingdom.realm.RealmRaids.resolveDue(realm, Long.MAX_VALUE, random);
        }
        RealmTurns.tellNews(context.getSource().getServer(), realm);
        Feedback.tell(context.getSource(), "Took " + count + (count == 1 ? " turn" : " turns") + " (now turn " + realm.turn() + "):");
        // What these turns did, the latest dozen at most.
        List<Realm.LogEntry> taken = realm.log().stream().filter(entry -> entry.turn() > from).toList();
        taken.subList(Math.max(0, taken.size() - 12), taken.size())
                .forEach(entry -> Feedback.detail(context.getSource(), "Turn " + entry.turn() + ": " + entry.text()));
        return realm.turn();
    }

    private static int log(CommandContext<CommandSourceStack> context, int count) {
        List<Realm.LogEntry> log = realm(context).log();
        if (log.isEmpty()) {
            Feedback.tell(context.getSource(), "Nothing has happened in the realm yet.");
            return 0;
        }
        Feedback.tell(context.getSource(), "The realm lately (the N menu's News tab shows it all):");
        int shownTurn = Integer.MIN_VALUE;
        for (Realm.LogEntry entry : log.subList(Math.max(0, log.size() - count), log.size())) {
            if (entry.turn() != shownTurn) {
                shownTurn = entry.turn();
                Feedback.send(context.getSource(), Component.literal("Turn " + entry.turn()).withStyle(ChatFormatting.YELLOW));
            }
            ChatFormatting color = switch (entry.kind()) {
                case BATTLE -> ChatFormatting.RED;
                case CAPTURE -> ChatFormatting.GOLD;
                case MARCH -> ChatFormatting.LIGHT_PURPLE;
                case TOWN -> ChatFormatting.GREEN;
                case SUPPLY -> ChatFormatting.YELLOW;
                default -> ChatFormatting.GRAY;
            };
            String tag = switch (entry.kind()) {
                case BATTLE -> "Battle";
                case CAPTURE -> "Taken";
                case MARCH -> "March";
                case TOWN -> "Town";
                case SUPPLY -> "Supply";
                case PREPARE -> "Routine";
                case OTHER -> "";
            };
            Feedback.send(context.getSource(), Component.literal("  " + (tag.isEmpty() ? "" : tag + "  ")).withStyle(color)
                    .append(Component.literal(entry.text()).withStyle(entry.kind() == Realm.LogKind.PREPARE ? ChatFormatting.DARK_GRAY : ChatFormatting.WHITE)));
        }
        return log.size();
    }

    private static int link(CommandContext<CommandSourceStack> context, boolean linking) {
        String a = StringArgumentType.getString(context, "a");
        String b = StringArgumentType.getString(context, "b");
        Realm realm = realm(context);
        boolean done = linking ? realm.link(a, b) : realm.unlink(a, b);
        if (!done) {
            Feedback.fail(context.getSource(), linking ? "Couldn't link " + a + " and " + b + ": both must exist, be different, and not be linked already."
                    : a + " and " + b + " aren't linked.");
            return 0;
        }
        Feedback.tell(context.getSource(), (linking ? "Linked " : "Unlinked ") + a + " and " + b + ".");
        return 1;
    }
}
