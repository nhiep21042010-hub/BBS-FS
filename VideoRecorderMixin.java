package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.utils.VideoRecorder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.BbsVfxAeTracker;

import java.io.File;

/**
 * Client mixin that piggybacks the AE camera capture on BBS's own video recorder lifecycle, so a
 * camera sample is taken for exactly the frames that get encoded. See {@link BbsVfxAeTracker} for the
 * capture/export logic; the "AE tracking" toggle gates whether anything is written.
 */
@Mixin(VideoRecorder.class)
public abstract class VideoRecorderMixin
{
    @Shadow
    public abstract boolean isRecording();

    /** Begin a capture session once recording has actually started. */
    @Inject(method = "startRecording", at = @At("RETURN"))
    private void bbsvfx$onStartRecording(String movieName, File audioFile, int textureId, int width, int height, CallbackInfo ci)
    {
        if (this.isRecording())
        {
            BbsVfxAeTracker.start(width, height);
        }
    }

    /** Sample the camera for each encoded frame. */
    @Inject(method = "recordFrame", at = @At("RETURN"))
    private void bbsvfx$onRecordFrame(CallbackInfo ci)
    {
        if (this.isRecording())
        {
            BbsVfxAeTracker.capture();
        }
    }

    /** Write the AE script before recording state is torn down. */
    /* Descriptor pinned: 2.4 added a stopRecording(boolean) overload that the no-arg delegates to —
     * a bare name selector would match both and fire finish() twice per stop. */
    @Inject(method = "stopRecording()V", at = @At("HEAD"))
    private void bbsvfx$onStopRecording(CallbackInfo ci)
    {
        BbsVfxAeTracker.finish();
    }
}
