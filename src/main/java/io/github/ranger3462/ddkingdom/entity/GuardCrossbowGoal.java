package io.github.ranger3462.ddkingdom.entity;

import java.util.EnumSet;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * A town crossbowman's shooting: it keeps its target in sight within {@link #RANGE}, winds the crossbow
 * ({@link #CHARGE_TICKS}) and looses a bolt for its attack damage, then reloads. (Vanilla's crossbow goal only works for
 * hostile mobs.)
 */
public class GuardCrossbowGoal extends Goal {
    public static final double RANGE = 16.0;
    public static final int CHARGE_TICKS = 25;
    private static final int RELOAD_TICKS = 15;
    private final TownGuard guard;
    private int charge;
    private int reload;

    public GuardCrossbowGoal(TownGuard guard) {
        this.guard = guard;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        return guard.isCrossbowman() && guard.getTarget() != null && guard.getTarget().isAlive();
    }

    @Override
    public void start() {
        guard.setAggressive(true);
    }

    @Override
    public void stop() {
        guard.setAggressive(false);
        guard.setChargingCrossbow(false);
        charge = 0;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void tick() {
        LivingEntity target = guard.getTarget();
        if (target == null) {
            return;
        }
        guard.getLookControl().setLookAt(target, 30.0f, 30.0f);
        double distance = guard.distanceTo(target);
        boolean sees = guard.getSensing().hasLineOfSight(target);
        if (distance > RANGE - 2 || !sees) {
            guard.getNavigation().moveTo(target, 1.0);
        } else {
            guard.getNavigation().stop();
        }
        if (reload > 0) {
            reload--;
            return;
        }
        if (!sees || distance > RANGE) {
            charge = 0;
            guard.setChargingCrossbow(false);
            return;
        }
        guard.setChargingCrossbow(true);
        if (++charge >= CHARGE_TICKS) {
            shoot(target);
            charge = 0;
            reload = RELOAD_TICKS;
            guard.setChargingCrossbow(false);
        }
    }

    private void shoot(LivingEntity target) {
        ItemStack weapon = guard.getMainHandItem();
        AbstractArrow bolt = ProjectileUtil.getMobArrow(guard, new ItemStack(Items.ARROW), 1.0f, weapon);
        double dx = target.getX() - guard.getX();
        double dy = target.getY(0.33) - bolt.getY();
        double dz = target.getZ() - guard.getZ();
        bolt.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.15, dz, 3.15f, 2.0f);
        bolt.setBaseDamage(Math.max(2.0, guard.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * 0.6));
        bolt.pickup = AbstractArrow.Pickup.DISALLOWED;
        guard.level().addFreshEntity(bolt);
        guard.playSound(SoundEvents.CROSSBOW_SHOOT, 1.0f, 1.0f / (guard.getRandom().nextFloat() * 0.4f + 0.8f));
    }
}
