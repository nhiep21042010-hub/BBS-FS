package com.bbsvfx.vfxlights.mixin.client.iris;

import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.texture.TextureType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.vfxlights.client.shadow.ShadowAtlas;

/**
 * Hands our shadow atlas to every pack program that declares {@code vfxShadowAtlas}.
 *
 * <p>Iris assigns texture units only to sampler names it knows; an unknown uniform stays at unit 0 and
 * samples whatever happens to live there. Registering through the builder lets Iris allocate a real
 * unit and rebind it per program — and it quietly does nothing for programs that never declare the
 * name, which is every program the patch does not touch.</p>
 */
@Mixin(value = ProgramSamplers.Builder.class, remap = false)
public abstract class IrisSamplersMixin
{
    @Inject(method = "build", at = @At("HEAD"))
    private void vfxlights$addShadowAtlas(CallbackInfoReturnable<ProgramSamplers> cir)
    {
        try
        {
            ProgramSamplers.Builder self = (ProgramSamplers.Builder) (Object) this;

            /* Null sampler object is the documented "no sampler params" case; the supplier guards
             * against the atlas not existing yet (first frames before any shadowed light). */
            self.addDynamicSampler(TextureType.TEXTURE_2D,
                () -> Math.max(ShadowAtlas.getTexture(), 0), null, "vfxShadowAtlas");
            self.addDynamicSampler(TextureType.TEXTURE_2D,
                () -> Math.max(ShadowAtlas.getColorTexture(), 0), null, "vfxShadowColor");
            /* Character depth for cel shading — see CharacterMask. Cleared to 1.0, so with no actors
             * it disables cel by itself; programs that never declare the name ignore it. */
            self.addDynamicSampler(TextureType.TEXTURE_2D,
                () -> Math.max(com.bbsvfx.vfxlights.client.render.CharacterMask.getDepthTexture(), 0),
                null, "vfxCharMask");
            /* Actor category index for the groups filter — see CategoryMask. Cleared to 0 (no actor),
             * which no lamp's bitmask matches; only the patched pack ever samples it. */
            self.addDynamicSampler(TextureType.TEXTURE_2D,
                () -> Math.max(com.bbsvfx.vfxlights.client.render.CategoryMask.getColorTexture(), 0),
                null, "vfxGroupTex");
        }
        catch (Throwable ignored)
        {
            /* A changed builder API must not take the whole pipeline down — packs then simply run
             * without our shadows. */
        }
    }
}
