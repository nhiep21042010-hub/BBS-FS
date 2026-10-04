package com.bbsvfx.vfxlights.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The private fov getter — needed to rebuild the BASIC (bob-free) projection, whose inverse
 * extracts the bob transform (and with it the effective eye position) from the live projection
 * (see VolumetricPass). */
@Mixin(GameRenderer.class)
public interface GameRendererAccessor
{
    @Invoker("getFov")
    double vfxlights$getFov(Camera camera, float tickDelta, boolean changingFov);
}
