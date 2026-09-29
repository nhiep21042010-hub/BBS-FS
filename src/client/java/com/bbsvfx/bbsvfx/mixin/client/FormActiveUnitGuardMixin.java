package com.bbsvfx.bbsvfx.mixin.client;

import com.mojang.blaze3d.platform.GlStateManager;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import org.lwjgl.opengl.GL13;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bulletproofs the "skin renders Linear-filtered" bug: guarantees the GL active texture unit is 0 before
 * every TOP-LEVEL form (actor) renders. A previous frame's multi-sampler post pass — ours or another
 * mod's — can leave the active unit at 1-2, desyncing GlStateManager's cache so the first-rendered
 * actor's skin bind/filter lands on the wrong unit (driver-dependent; surfaced on a GTX 1080 as a blurry
 * skin, only with certain films). The composite passes already reset on their own exit; this is the
 * per-actor safety net that holds regardless of which feature leaked. Cheap — a cached no-op when unit
 * is already 0. Top-level only ({@code getCurrentForm() == null}), so it doesn't fire per body part.
 */
@Mixin(value = FormUtilsClient.class, remap = false)
public abstract class FormActiveUnitGuardMixin
{
    @Inject(
        method = "render(Lmchorse/bbs_mod/forms/forms/Form;Lmchorse/bbs_mod/forms/renderers/FormRenderingContext;)V",
        at = @At("HEAD"))
    private static void bbsvfx$resetActiveUnit(Form form, FormRenderingContext context, CallbackInfo ci)
    {
        if (FormUtilsClient.getCurrentForm() == null)
        {
            GlStateManager._activeTexture(GL13.GL_TEXTURE0);
        }
    }
}
