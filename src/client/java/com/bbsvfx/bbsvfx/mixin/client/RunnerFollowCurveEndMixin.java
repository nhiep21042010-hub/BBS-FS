package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.camera.clips.CameraClipContext;
import mchorse.bbs_mod.camera.controller.CameraWorkCameraController;
import mchorse.bbs_mod.camera.controller.RunnerCameraController;
import mchorse.bbs_mod.camera.data.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.camera.FollowCurveClip;

/**
 * Compose the follow-curve modifier at a clip's exclusive-end boundary in the editor preview.
 *
 * <p>Camera clips are active over {@code [tick, tick + duration)}, so at the exclusive-end tick BBS
 * evaluates NO clip and instead shows the edited clip's final point in isolation ({@code applyLast}) —
 * the modifier stack (e.g. a {@link FollowCurveClip}) is skipped. That makes clicking a path/keyframe
 * clip's LAST point (which seeks to that exclusive-end tick) not move the camera along the curve, even
 * though a follow-curve is driving it (its progress reaches the curve end exactly there).</p>
 *
 * <p>Fix: after BBS applies the edited clip's last point, also {@code applyLast} any follow-curve clip
 * that ends at the same tick, so its curve-end position composes on top (position from the curve, angle
 * kept from the edited clip when align is off). Editor-only (guarded off during playback), so the actual
 * render is unchanged.</p>
 */
@Mixin(value = RunnerCameraController.class, remap = false)
public abstract class RunnerFollowCurveEndMixin
{
    @Inject(method = "applyEditedClipEnd", at = @At("TAIL"))
    private void bbsvfx$composeFollowCurveAtEnd(int ticks, CallbackInfo ci)
    {
        CameraWorkCameraController self = (CameraWorkCameraController) (Object) this;
        CameraClipContext context = self.getContext();
        Position position = self.getPosition();

        if (context == null || context.clips == null || context.playing)
        {
            return;
        }

        for (FollowCurveClip clip : context.clips.getClips(FollowCurveClip.class))
        {
            if (clip.enabled.get() && clip.tick.get() + clip.duration.get() == ticks)
            {
                clip.applyLast(context, position);
            }
        }
    }
}
