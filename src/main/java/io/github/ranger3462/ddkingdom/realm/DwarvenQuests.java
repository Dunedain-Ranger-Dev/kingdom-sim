package io.github.ranger3462.ddkingdom.realm;

import java.util.Optional;
import java.util.UUID;

import io.github.ranger3462.ddkingdom.KingdomConfig;
import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.integration.deepmines.DeepMinesLink;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The dwarves (KING-18, docs/design/dwarves.md): their capital, Karak Varn, holds the gate to their old mines (Deep
 * Mines). Everyone starts neutral with them. They ask for Mithril from the Mines: every {@code mithrilPerQuest} brought
 * to the gate finishes one of their quests and raises the player's standing by {@code standingPerMithrilQuest}. The
 * gate lets in players with at least {@code dwarvenStandingForMines} (neutral, to start: anyone).
 */
public final class DwarvenQuests {
    /** What the dwarves take as Mithril: Iron's Spells' raw Mithril and Mithril ingots, by default. */
    public static final TagKey<Item> MITHRIL = TagKey.create(Registries.ITEM, KingdomSim.id("mithril_tribute"));
    /** How near the dwarves' capital the mine gate must be to open. */
    public static final int GATE_RANGE = 96;

    /** What using the gate does. */
    public enum Gate {
        /** It isn't at the dwarves' capital (or they have none), so it stays shut. */
        NOT_AT_CAPITAL,
        /** Deep Mines isn't installed. */
        SEALED,
        /** The player's standing with the dwarves is too low. */
        TURNED_AWAY,
        /** In they go. */
        ENTER
    }

    private DwarvenQuests() {}

    /** The dwarves' capital, if the realm has one. */
    public static Optional<RealmNode> capital(Realm realm) {
        return RealmCapitals.capital(realm, RealmStanding.DWARVES);
    }

    /** Whether a spot is at the dwarves' capital: within {@link #GATE_RANGE} of it, in its dimension. */
    public static boolean atCapital(Realm realm, ResourceKey<Level> dimension, BlockPos pos) {
        return capital(realm).filter(cap -> cap.dimension().equals(dimension) && cap.pos().closerThan(pos, GATE_RANGE)).isPresent();
    }

    /** What the gate does for a player with this standing, at this spot, with Deep Mines installed or not. */
    public static Gate gate(boolean atCapital, boolean deepMines, int standing, int needed) {
        if (!atCapital) {
            return Gate.NOT_AT_CAPITAL;
        }
        if (!deepMines) {
            return Gate.SEALED;
        }
        return standing < needed ? Gate.TURNED_AWAY : Gate.ENTER;
    }

    /**
     * Hands Mithril in towards the dwarves' quests: all of it in the stack. Each {@code mithrilPerQuest} finishes a
     * quest, raising the player's standing. Returns how many quests that finished.
     */
    public static int handIn(RealmStanding standing, UUID player, ItemStack stack) {
        int perQuest = KingdomConfig.MITHRIL_PER_QUEST.get();
        int brought = standing.progress(player, RealmStanding.DWARVES) + stack.getCount();
        stack.setCount(0);
        int quests = brought / perQuest;
        standing.setProgress(player, RealmStanding.DWARVES, brought % perQuest);
        if (quests > 0) {
            standing.raise(player, RealmStanding.DWARVES, quests * KingdomConfig.STANDING_PER_MITHRIL_QUEST.get());
        }
        return quests;
    }

    /** How the dwarves see someone with this standing. */
    public static String rank(int standing) {
        if (standing < 0) {
            return "distrusted";
        }
        return standing < 10 ? "neutral" : standing < 30 ? "friendly" : standing < 60 ? "honored" : "revered";
    }

    /** Using the mine gate: hand in Mithril if they're holding some, or else go into the Mines if they may. */
    public static void useGate(ServerPlayer player, BlockPos gatePos, ItemStack held) {
        Realm realm = Realm.get(player.server);
        RealmStanding standing = RealmStanding.get(player.server);
        UUID id = player.getUUID();
        boolean atCapital = atCapital(realm, player.level().dimension(), gatePos);
        if (atCapital && held.is(MITHRIL)) {
            int quests = handIn(standing, id, held);
            int now = standing.standing(id, RealmStanding.DWARVES);
            int toGo = KingdomConfig.MITHRIL_PER_QUEST.get() - standing.progress(id, RealmStanding.DWARVES);
            player.sendSystemMessage(quests > 0
                    ? Component.translatable("message.ddkingdom.dwarves.quest_done", quests, now, Component.translatable("rank.ddkingdom." + rank(now)))
                            .withStyle(ChatFormatting.GOLD)
                    : Component.translatable("message.ddkingdom.dwarves.mithril_taken", toGo).withStyle(ChatFormatting.GRAY));
            return;
        }
        int mine = standing.standing(id, RealmStanding.DWARVES);
        int needed = KingdomConfig.DWARVEN_STANDING_FOR_MINES.get();
        switch (gate(atCapital, DeepMinesLink.installed(), mine, needed)) {
            case NOT_AT_CAPITAL -> player.displayClientMessage(Component.translatable("message.ddkingdom.dwarves.not_at_capital"), true);
            case SEALED -> player.displayClientMessage(Component.translatable("message.ddkingdom.dwarves.sealed"), true);
            case TURNED_AWAY -> player.sendSystemMessage(Component.translatable("message.ddkingdom.dwarves.turned_away", mine, needed)
                    .withStyle(ChatFormatting.RED));
            case ENTER -> {
                if (!DeepMinesLink.enter(player)) {
                    player.displayClientMessage(Component.translatable("message.ddkingdom.dwarves.sealed"), true);
                }
            }
        }
    }
}
