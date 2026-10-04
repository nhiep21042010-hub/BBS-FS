package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.vfxlights.client.LightGuideDrag;

/**
 * Hooks the light gizmo's grab handles into the form editor's viewport: a click that lands on a handle
 * is claimed by the drag (and never reaches BBS's own picking), the drag itself updates from the render
 * tail, and releasing the mouse anywhere ends it. IRLite's hook points (qualet, MIT).
 */
@Mixin(value = UIPickableFormRenderer.class, remap = false)
public abstract class UIPickableFormRendererMixin
{
    @Inject(method = "subMouseClicked", at = @At("HEAD"), cancellable = true)
    private void vfxlights$grabGuideHandle(UIContext context, CallbackInfoReturnable<Boolean> cir)
    {
        if (LightGuideDrag.tryStart((UIPickableFormRenderer) (Object) this, context))
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

    @Inject(method = "renderUserModel", at = @At("TAIL"))
    private void vfxlights$updateGuideDrag(UIContext context, CallbackInfo ci)
    {
        LightGuideDrag.update((UIPickableFormRenderer) (Object) this, context);
    }
}
