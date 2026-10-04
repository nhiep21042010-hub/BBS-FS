package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.utils.Area;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.vfxlights.client.LightGuideDrag;

/**
 * The same grab-handle hook as {@link UIPickableFormRendererMixin}, but for the film editor's world
 * viewport. Dragging there writes the keyframe at the cursor (resolved when the drag starts), so the
 * film's own evaluation keeps owning the property — see {@link LightGuideDrag}.
 */
@Mixin(value = UIFilmController.class, remap = false)
public abstract class UIFilmControllerMixin
{
    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true)
    private void vfxlights$grabGuideHandle(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (LightGuideDrag.tryStartFilm((UIFilmController) (Object) this, context))
        {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "subMouseReleased", at = @At("HEAD"), cancellable = true)
    private void vfxlights$releaseGuideHandle(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (LightGuideDrag.mouseReleased())
        {
            cir.setReturnValue(true);
        }
    }

    /* BBS 2.7 dropped renderPickingPreview; renderHUD is the per-frame UI-pass hook now, and the
     * preview viewport the gizmo is drawn into is getGizmoArea(). */
    @Inject(method = "renderHUD", at = @At("HEAD"))
    private void vfxlights$updateGuideDrag(UIContext context, mchorse.bbs_mod.ui.film.PreviewHud hud, Area navBlock, CallbackInfo ci)
    {
        Area area = ((UIFilmController) (Object) this).getGizmoArea();

        /* The world-pass gizmo, flushed over the preview blit in the UI pass (see GizmoPass) —
         * before the picking preview so its hover highlights composite on top, like BBS's gizmo. */
        com.bbsvfx.vfxlights.client.render.GizmoPass.renderInUI(context, area);

        LightGuideDrag.update((UIFilmController) (Object) this, context);
    }
}
