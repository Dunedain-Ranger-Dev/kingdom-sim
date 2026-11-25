package io.github.ranger3462.ddkingdom.realm;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The dwarves' mine gate ({@link DwarvenQuests}): built into the dwarves' capital, it takes players in good standing
 * into their old mines (Deep Mines), and takes the Mithril they bring back. It only opens at the capital, and can't be
 * broken in survival.
 */
public class MineGateBlock extends Block {
    public MineGateBlock() {
        super(BlockBehaviour.Properties.of().mapColor(MapColor.DEEPSLATE).sound(SoundType.DEEPSLATE_BRICKS).strength(-1.0f, 3600000.0f).noLootTable());
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!stack.is(DwarvenQuests.MITHRIL)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            DwarvenQuests.useGate(serverPlayer, pos, stack);
        }
        return ItemInteractionResult.sidedSuccess(level.isClientSide());
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (!level.isClientSide() && player instanceof ServerPlayer serverPlayer) {
            DwarvenQuests.useGate(serverPlayer, pos, ItemStack.EMPTY);
        }
        return InteractionResult.sidedSuccess(level.isClientSide());
    }
}
