package com.bbsvfx.vfxlights.mixin.client.iris;

import net.irisshaders.iris.shaderpack.include.IncludeGraph;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.vfxlights.client.iris.PackPatcher;

import java.nio.file.Path;

/**
 * The whole shaderpack integration hangs on this one hook: every shader source Iris reads passes
 * through {@code IncludeGraph.readFile}, so serving and rewriting HERE gives a virtual filesystem —
 * patched packs that never change on disk. (The technique observed in Photonics; the implementation is
 * ours.)
 *
 * <p>HEAD serves files that do not exist in the pack — our injected GLSL library — before the real read
 * can throw {@code NoSuchFileException}. RETURN rewrites files that do exist.</p>
 */
@Mixin(value = IncludeGraph.class, remap = false)
public abstract class IrisSourceMixin
{
    @Inject(method = "readFile", at = @At("HEAD"), cancellable = true)
    private static void vfxlights$serveVirtual(Path path, CallbackInfoReturnable<String> cir)
    {
        String virtual = PackPatcher.serveVirtual(path);

        if (virtual != null)
        {
            cir.setReturnValue(virtual);
        }
    }

    @Inject(method = "readFile", at = @At("RETURN"), cancellable = true)
    private static void vfxlights$patchSource(Path path, CallbackInfoReturnable<String> cir)
    {
        String original = cir.getReturnValue();
        String patched = PackPatcher.transform(path, original);

        if (patched != null && !patched.equals(original))
        {
            cir.setReturnValue(patched);
        }
    }
}
