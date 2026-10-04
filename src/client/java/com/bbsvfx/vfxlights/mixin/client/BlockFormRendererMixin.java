package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.BlockFormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.client.light.GlassFormCollector;

/**
 * Feeds every rendered block form to the glass collector — render time is the only moment an animated
 * form's true transform exists (the same reason the lights collect there; see FormLightCollector).
 * HEAD, before the renderer's own −0.5 centring, so the captured matrix is the form's origin.
 *
 * <p>Also keeps glass forms OUT of this addon's shadow pass: glass transmits, and a pane that fans a
 * rainbow while its own silhouette blacks out the wall behind it contradicts itself. Same rule as the
 * world occluders — clear and stained pass, tinted keeps casting.</p>
 */
@Mixin(value = BlockFormRenderer.class, remap = false)
public abstract class BlockFormRendererMixin
{
    @Inject(method = "render3D", at = @At("HEAD"), cancellable = true)
    private void vfxlights$collectGlass(FormRenderingContext context, CallbackInfo ci)
    {
        Object form = ((BlockFormRenderer) (Object) this).getForm();

        if (!(form instanceof BlockForm blockForm))
        {
            return;
        }

        GlassFormCollector.collect(blockForm, context);

        if (com.bbsvfx.vfxlights.client.shadow.ShadowMapper.isActive()
            && blockForm.blockState.get() != null
            && com.bbsvfx.vfxlights.client.light.GlassDispersion.tintOf(
                blockForm.blockState.get().getBlock()) != null)
        {
            ci.cancel();
        }
    }
}
