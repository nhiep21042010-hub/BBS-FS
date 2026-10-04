package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.forms.entities.IEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Access to {@code BaseFilmController.getTransition} so the shadow pass samples film casters at the
 * SAME sub-tick time the visible film render uses: {@code 0} while the film is paused (the editor's
 * controller adds "while not playing"), the raw tickDelta while playing. The shadow pass used the raw
 * tickDelta unconditionally — on pause it kept sweeping a frozen caster between its recorded
 * prev/current positions at the 20 Hz tick sawtooth and re-sampling its pose at fractional sub-tick
 * time, while the visible actor stood frozen: the pause/scrub light tremor. Playing films get the
 * tickDelta back unchanged, so the fix is a no-op for them.
 */
@Mixin(value = BaseFilmController.class, remap = false)
public interface BaseFilmControllerAccessor
{
    @Invoker("getTransition")
    float vfxlights$getTransition(IEntity entity, float transition);
}
