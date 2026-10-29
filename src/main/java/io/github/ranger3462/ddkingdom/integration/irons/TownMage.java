package io.github.ranger3462.ddkingdom.integration.irons;

import io.github.ranger3462.dungeondelving.integration.irons.SupportCastGoal;
import java.util.List;
import java.util.Optional;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.github.ranger3462.ddkingdom.entity.TownGuard;
import io.github.ranger3462.ddkingdom.realm.RealmGuard;
import io.redspace.ironsspellbooks.api.registry.AttributeRegistry;
import io.redspace.ironsspellbooks.api.registry.SpellRegistry;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMob;
import io.redspace.ironsspellbooks.entity.mobs.goals.WizardAttackGoal;
import io.redspace.ironsspellbooks.registries.MobEffectRegistry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.AbstractVillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

/**
 * A town battlemage (DD-98): a guard who fights with Iron's Spells, flinging firebolts and lightning at raiders while
 * mending hurt guards, villagers and the players fighting for the town. A quarter of a bigger town's guards (see
 * {@code RealmLiveRaids}). Wears the town's skin setting and color like the other guards (in wizard robes, see
 * {@code GuardGear}); its spells
 * hit harder the bigger its town. Only exists when Iron's Spells is installed. Not saved with the world.
 */
public class TownMage extends AbstractSpellCastingMob implements RealmGuard {
    private static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(TownMage.class, EntityDataSerializers.STRING);
    private static final ResourceLocation POWER = KingdomSim.id("town_tier_power");
    private boolean levelDirty;

    public TownMage(EntityType<? extends TownMage> type, Level level) {
        super(type, level);
        setPersistenceRequired();
        if (!level.isClientSide) {
            entityData.set(SKIN, TownGuard.pickSkin(TownGuard.SKIN_SET, level.getRandom()));
        }
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Mob.createMobAttributes().add(Attributes.MAX_HEALTH, 20.0).add(Attributes.ATTACK_DAMAGE, 2.0).add(Attributes.MOVEMENT_SPEED, 0.28)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, "guard_1");
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new SupportCastGoal(this, TownMage::isFriend, 16.0, 6.0, 60, 100, new SupportCastGoal.Mend(0, 120),
                List.of(new SupportCastGoal.Buff(SpellRegistry.FORTIFY_SPELL.get(), MobEffectRegistry.FORTIFY))).setSpellQuality(0.2f, 0.4f));
        goalSelector.addGoal(2, new WizardAttackGoal(this, 1.1, 30, 60)
                .setSpells(List.of(SpellRegistry.FIREBOLT_SPELL.get(), SpellRegistry.LIGHTNING_BOLT_SPELL.get()), List.of(), List.of(), List.of())
                .setSpellQuality(0.2f, 0.45f));
        goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Mob.class, 5, true, false,
                mob -> mob instanceof Enemy && io.github.ranger3462.ddkingdom.realm.RealmGarrison.mayEngage(this, mob)));
    }

    /** Who it looks after: guards, villagers and players (the raid's sides keep turncoats from being targeted by the town). */
    private static boolean isFriend(LivingEntity entity) {
        return entity instanceof RealmGuard || entity instanceof AbstractVillager || entity instanceof Player;
    }

    @Override
    public void setSkin(String skin) {
        entityData.set(SKIN, skin);
    }

    /** Health and gear like a guard's (robes rather than metal), and spell power by tier. */
    @Override
    public void equip(String townName, int wealth, int defense, int tier, Optional<DyeColor> color) {
        getAttribute(Attributes.MAX_HEALTH).setBaseValue(TownGuard.health(defense, tier) * 0.8);
        setHealth(getMaxHealth());
        AttributeInstance power = getAttribute(AttributeRegistry.SPELL_POWER);
        if (power != null) {
            power.addOrReplacePermanentModifier(new AttributeModifier(POWER, 0.15 * (tier - 1), AttributeModifier.Operation.ADD_MULTIPLIED_BASE));
        }
        setCustomName(Component.literal(townName + " Battlemage"));
        ItemStack[] robes = io.github.ranger3462.ddkingdom.entity.GuardGear.armor(io.github.ranger3462.ddkingdom.entity.GuardGear.Role.MAGE,
                TownGuard.gearTier(wealth, tier));
        EquipmentSlot[] slots = { EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET };
        for (int i = 0; i < slots.length; i++) {
            ItemStack piece = robes[i];
            if (color.isPresent() || piece.is(Items.LEATHER_CHESTPLATE) || piece.is(net.minecraft.tags.ItemTags.DYEABLE)) {
                TownGuard.colorPiece(level(), piece, color.orElse(DyeColor.PURPLE));
            }
            setItemSlot(slots[i], piece);
            setDropChance(slots[i], 0.0f);
        }
        levelDirty = true;
    }

    public ResourceLocation skin() {
        String skin = entityData.get(SKIN);
        if (skin.startsWith("minecraft:")) {
            return ResourceLocation.withDefaultNamespace("textures/entity/player/wide/" + skin.substring("minecraft:".length()) + ".png");
        }
        ResourceLocation texture = ResourceLocation.tryBuild(KingdomSim.MODID, "textures/entity/town_guard/" + skin + ".png");
        return texture != null ? texture : KingdomSim.id("textures/entity/town_guard/guard_1.png");
    }

    @Override
    public void tick() {
        super.tick();
        if (levelDirty && !level().isClientSide()) {
            levelDirty = false;
            io.github.ranger3462.ddkingdom.integration.professions.ProfessionsLink.refreshLevel(this);
        }
    }

    @Override
    public boolean canAttack(LivingEntity target) {
        return !(target instanceof RealmGuard) && super.canAttack(target);
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("skin", entityData.get(SKIN));
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("skin", net.minecraft.nbt.Tag.TAG_STRING)) {
            entityData.set(SKIN, tag.getString("skin"));
        }
    }
}
