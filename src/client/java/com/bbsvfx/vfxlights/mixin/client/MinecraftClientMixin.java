package com.bbsvfx.vfxlights.mixin.client;

import mchorse.bbs_mod.ui.framework.UIScreen;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * Drops persisted lights when a BBS UI screen closes.
 *
 * <p>Authored lights are collected while the world renders, but the editor also renders form previews
 * near the origin and keeps a persisted copy of any keyed light for {@code PERSIST_GRACE} frames so
 * frustum-culled lamps do not flicker. When the editor closes, those preview lights stop being
 * collected but the persisted copy lingers, causing a bright flash for two seconds. Clearing persisted
 * state on the transition from a BBS screen to anything else removes the after-image immediately.</p>
 */
@Mixin(MinecraftClient.class)
public class MinecraftClientMixin
{
    @Inject(method = "setScreen", at = @At("HEAD"))
    private void vfxlights$onScreenChange(Screen screen, CallbackInfo ci)
    {
        MinecraftClient self = (MinecraftClient) (Object) this;
        Screen current = self.currentScreen;

        if (current instanceof UIScreen && !(screen instanceof UIScreen))
        {
            LightRegistry.clearAllPersisted();
        }
    }
}
