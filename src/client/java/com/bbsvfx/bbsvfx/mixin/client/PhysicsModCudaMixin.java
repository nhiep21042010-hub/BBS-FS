package com.bbsvfx.bbsvfx.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Physics Mod coexistence: both mods bundle {@code physx.*} — when ours wins the classpath, Physics
 * Mod's startup CUDA probe loads {@code PxCudaTopLevelFunctions}, whose static initialiser calls a
 * native ({@code __sizeOf}) that doesn't exist in our stock physx-jni natives → UnsatisfiedLinkError
 * at game init. Probe the class load OURSELVES first, guarded: if it initialises (their CUDA-patched
 * natives won — Physics Mod Pro keeps its CUDA acceleration), fall through to their original check;
 * if it fails, report no CUDA so they take their CPU path. Their public physx API is a signature
 * subset of ours (verified against physics-mod 3.0.18 free AND pro v171l), so the Java side links
 * whichever classes win.
 *
 * <p>Applied only when physicsmod is present ({@code BbsVfxClientMixinPlugin}); {@code require = 0} so
 * a future Physics Mod refactor degrades to their own behaviour instead of a mixin crash.</p>
 */
@Mixin(targets = "net.diebuddies.physics.StarterClient", remap = false)
public abstract class PhysicsModCudaMixin
{
    @Inject(method = "isCudaAvailable()Z", at = @At("HEAD"), cancellable = true, require = 0)
    private static void bbsvfx$guardCudaProbe(CallbackInfoReturnable<Boolean> cir)
    {
        try
        {
            /* Triggers the static init — the exact thing that crashed. Success = CUDA natives present. */
            Class.forName("physx.common.PxCudaTopLevelFunctions");
        }
        catch (Throwable t)
        {
            cir.setReturnValue(false);
        }
    }
}
