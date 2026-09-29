package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.client.BBSRendering;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Static access to {@code BBSRendering.iris} so the offscreen form replays (impact silhouette, blend
 * composite) can render as if NO shader pack were active. With the flag up, BBS's model VAO sets up the
 * IRIS vertex-attribute layout while the pack-replaced vanilla program either steals the framebuffer
 * (during the world pass) or mismatches BBS's own core program's layout — either way the offscreen draw
 * produces nothing. Flipping the flag routes program choice AND attribute layout down the plain no-pack
 * path, which shader packs cannot touch.
 */
@Mixin(value = BBSRendering.class, remap = false)
public interface BBSRenderingIrisAccessor
{
    @Accessor("iris")
    static boolean bbsvfx$getIris()
    {
        throw new AssertionError();
    }

    @Accessor("iris")
    static void bbsvfx$setIris(boolean iris)
    {
        throw new AssertionError();
    }
}
