package io.github.ranger3462.ddkingdom.command;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * Chat feedback for this mod's commands. Messages go straight to the player running the command, because Minecraft
 * hides normal command feedback when a command runs inside a function or command block.
 */
final class Feedback {
    private Feedback() {}

    static void tell(CommandSourceStack source, String message) {
        send(source, Component.literal(message).withStyle(ChatFormatting.GOLD));
    }

    static void fail(CommandSourceStack source, String message) {
        send(source, Component.literal(message).withStyle(ChatFormatting.RED));
    }

    static void detail(CommandSourceStack source, String message) {
        send(source, Component.literal("  " + message).withStyle(ChatFormatting.GRAY));
    }

    static void send(CommandSourceStack source, Component message) {
        if (source.getEntity() instanceof ServerPlayer player) {
            player.sendSystemMessage(message);
        } else {
            source.sendSystemMessage(message);
        }
    }
}
