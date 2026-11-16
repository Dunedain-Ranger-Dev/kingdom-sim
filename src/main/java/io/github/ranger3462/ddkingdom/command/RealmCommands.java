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
 * The realm's world side, for operators (the realm itself is the simulator's: {@code /realm sim}, see SimCommands):
 * <ul>
 * <li>{@code /realm}: how many nodes and links there are.</li>
 * <li>{@code /realm list [type]} and {@code /realm info <id>}: what's there.</li>
 * <li>{@code /realm log [count]}: what's happened lately (DD-95).</li>
 * <li>{@code /realm blueprint save <id> <radius> [down] [up]}: snapshot the blocks around a node, to rebuild it from after
 * raids. {@code info}, {@code damage <id> <points>} (as a sacking would), {@code repair <id>} (all of it, now) (DD-97).</li>
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
                .then(Commands.literal("danger").executes(RealmCommands::danger))
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
                .then(Commands.literal("log").executes(context -> log(context, 10))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
                                .executes(context -> log(context, IntegerArgumentType.getInteger(context, "count"))))));
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

}
