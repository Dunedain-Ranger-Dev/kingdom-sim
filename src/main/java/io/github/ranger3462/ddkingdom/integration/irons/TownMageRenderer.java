package io.github.ranger3462.ddkingdom.integration.irons;

import io.github.ranger3462.ddkingdom.KingdomSim;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMob;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMobModel;
import io.redspace.ironsspellbooks.entity.mobs.abstract_spell_casting_mob.AbstractSpellCastingMobRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws town battlemages with Iron's Spells' caster body (so its casting animations play) in their town's guard skin,
 * falling back to our first guard face if a named skin is missing. Client only.
 */
public class TownMageRenderer extends AbstractSpellCastingMobRenderer {
    private static final ResourceLocation FALLBACK = KingdomSim.id("textures/entity/town_guard/guard_1.png");

    public TownMageRenderer(EntityRendererProvider.Context context) {
        super(context, new Model());
    }

    private static final class Model extends AbstractSpellCastingMobModel {
        @Override
        public ResourceLocation getTextureResource(AbstractSpellCastingMob mob) {
            if (!(mob instanceof TownMage mage)) {
                return FALLBACK;
            }
            ResourceLocation skin = mage.skin();
            return Minecraft.getInstance().getResourceManager().getResource(skin).isPresent() ? skin : FALLBACK;
        }
    }
}
