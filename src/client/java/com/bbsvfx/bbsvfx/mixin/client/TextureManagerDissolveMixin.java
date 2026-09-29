package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.graphics.texture.Texture;
import mchorse.bbs_mod.graphics.texture.TextureManager;
import mchorse.bbs_mod.resources.Link;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.bbsvfx.client.SmearRenderState;
import com.bbsvfx.bbsvfx.client.BbsVfxDissolveTexture;

/**
 * Smear-frame dissolve under external shader packs. While a dissolving smear copy is drawn
 * ({@link SmearRenderState#active} with {@code dissolve > 0}), every texture the renderer resolves is
 * swapped for a noise-holed variant ({@link BbsVfxDissolveTexture}) — the entity shader's own alpha test
 * then cuts the holes, so the dissolve looks the same with or without a shader pack (where our
 * {@code Dissolve} core-shader uniform never runs). Outside the copy passes this is a no-op.
 *
 * <p>{@code getTexture(Link, int, boolean)} is the single funnel every other resolve/bind path delegates
 * to, so hooking it covers the model, its body parts, items and armour uniformly.</p>
 */
@Mixin(value = TextureManager.class, remap = false)
public abstract class TextureManagerDissolveMixin
{
    @Inject(
        method = "getTexture(Lmchorse/bbs_mod/resources/Link;IZ)Lmchorse/bbs_mod/graphics/texture/Texture;",
        at = @At("RETURN"),
        cancellable = true)
    private void bbsvfx$dissolveTexture(Link link, int filter, boolean silent, CallbackInfoReturnable<Texture> cir)
    {
        if (!SmearRenderState.active || SmearRenderState.dissolve <= 0F)
        {
            return;
        }

        /* Pure cache lookup — get() never builds a GL texture here (that happens at processPending,
         * end of the world render). Building mid-draw corrupted state and crashed the matrix stack. */
        Texture original = cir.getReturnValue();
        Texture holed = BbsVfxDissolveTexture.get(link, SmearRenderState.dissolve, original);

        if (holed != original)
        {
            cir.setReturnValue(holed);
        }
    }
}
