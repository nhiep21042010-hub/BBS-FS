package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static access to {@code BBSRendering.iris} so the shadow pass can re-render film actors as if NO
 * shader pack were active. With the flag up, BBS's model VAO sets up the IRIS vertex-attribute layout
 * while the pack-replaced vanilla program re-binds the PACK's framebuffer on use — the actor's depth
 * lands in the pack's buffer instead of the shadow tile and the tile stays empty. Flipping the flag
 * routes program choice AND attribute layout down the plain no-pack path, which packs cannot touch.
 * (Same lesson, same fix as BBS VFX's offscreen silhouette replays.)
 */
@Mixin(value = BBSRendering.class, remap = false)
public interface BBSRenderingIrisAccessor
{
    @Accessor("iris")
    static boolean vfxlights$getIris()
    {
        throw new AssertionError();
    }

    @Accessor("iris")
    static void vfxlights$setIris(boolean iris)
    {
        throw new AssertionError();
    }
}
