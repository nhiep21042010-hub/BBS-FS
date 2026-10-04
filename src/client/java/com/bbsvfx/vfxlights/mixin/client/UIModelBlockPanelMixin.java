package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.client.render.GizmoPass;

/**
 * Flushes the light gizmos in the model block editor (the panel a right-click on a model block
 * opens). The world behind it renders before the UI, so drawing at the head of the panel's render
 * lands the wireframes over the world and under the panel's own widgets — the same layering the
 * film controller's flush gets after the preview blit. The viewport is the whole menu area, matching
 * the fullscreen world render behind (the panel's own gizmo uses the same frame).
 */
@Mixin(value = UIModelBlockPanel.class, remap = false)
public abstract class UIModelBlockPanelMixin
{
    @Inject(method = "render", at = @At("HEAD"))
    private void vfxlights$renderLightGizmos(UIContext context, CallbackInfo ci)
    {
        GizmoPass.renderInUI(context, ((UIModelBlockPanel) (Object) this).getGizmoArea());
    }
}
