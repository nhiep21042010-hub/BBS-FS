package com.bbsvfx.bbsvfx.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.IBlendBone;

/**
 * Adds a per-bone blend (a {@link com.bbsvfx.bbsvfx.forms.BlendMode} index + a 0..1 strength) to BBS's
 * {@link PoseTransform}, woven into every pose op so it serialises / interpolates / animates like the
 * built-in channels (mirrors {@code PoseTransformSmearMixin}). The mode is a discrete category, so it
 * STEPS (holds the "from" keyframe's value) instead of tweening through other modes; the factor lerps
 * smoothly.
 */
@Mixin(value = PoseTransform.class, remap = false)
public abstract class PoseTransformBlendMixin implements IBlendBone
{
    @Unique private int bbsvfx$bMode;
    @Unique private float bbsvfx$bFactor = 1F;

    @Override
    public int bbsvfx$blendMode()
    {
        return this.bbsvfx$bMode;
    }

    @Override
    public void bbsvfx$setBlendMode(int mode)
    {
        this.bbsvfx$bMode = mode;
    }

    @Override
    public float bbsvfx$blendFactor()
    {
        return this.bbsvfx$bFactor;
    }

    @Override
    public void bbsvfx$setBlendFactor(float factor)
    {
        this.bbsvfx$bFactor = factor;
    }

    @Inject(method = "identity", at = @At("TAIL"))
    private void bbsvfx$identity(CallbackInfo ci)
    {
        this.bbsvfx$bMode = 0;
        this.bbsvfx$bFactor = 1F;
    }

    @Inject(method = "lerp(Lmchorse/bbs_mod/utils/pose/Transform;F)V", at = @At("TAIL"))
    private void bbsvfx$lerp(Transform transform, float a, CallbackInfo ci)
    {
        if (transform instanceof IBlendBone o)
        {
            this.bbsvfx$bMode = a < 1F ? this.bbsvfx$bMode : o.bbsvfx$blendMode();
            this.bbsvfx$bFactor = Lerps.lerp(this.bbsvfx$bFactor, o.bbsvfx$blendFactor(), a);
        }
    }

    @Inject(method = "lerp(Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/interps/IInterp;F)V", at = @At("TAIL"))
    private void bbsvfx$lerpKeyframes(Transform preA, Transform a, Transform b, Transform postB, IInterp interp, float x, CallbackInfo ci)
    {
        if (a instanceof IBlendBone a1 && b instanceof IBlendBone b1)
        {
            /* Mode steps (hold the left keyframe); factor interpolates. */
            this.bbsvfx$bMode = a1.bbsvfx$blendMode();
            this.bbsvfx$bFactor = (float) interp.interpolate(IInterp.context.set(a1.bbsvfx$blendFactor(), a1.bbsvfx$blendFactor(), b1.bbsvfx$blendFactor(), b1.bbsvfx$blendFactor(), x));
        }
    }

    @Inject(method = "autoLerp", at = @At("TAIL"))
    private void bbsvfx$autoLerp(Transform preA, Transform a, Transform b, Transform postB, float pt, float at, float bt, float qt, boolean clamped, float x, CallbackInfo ci)
    {
        if (a instanceof IBlendBone a1)
        {
            this.bbsvfx$bMode = a1.bbsvfx$blendMode();
            this.bbsvfx$bFactor = a instanceof IBlendBone && b instanceof IBlendBone b1
                ? Lerps.lerp(a1.bbsvfx$blendFactor(), b1.bbsvfx$blendFactor(), x)
                : a1.bbsvfx$blendFactor();
        }
    }

    @ModifyReturnValue(method = "equals", at = @At("RETURN"))
    private boolean bbsvfx$equals(boolean original, Object obj)
    {
        if (obj instanceof IBlendBone o)
        {
            return original && this.bbsvfx$bMode == o.bbsvfx$blendMode() && this.bbsvfx$bFactor == o.bbsvfx$blendFactor();
        }

        return original;
    }

    @Inject(method = "copy(Lmchorse/bbs_mod/utils/pose/Transform;)V", at = @At("TAIL"))
    private void bbsvfx$copy(Transform transform, CallbackInfo ci)
    {
        if (transform instanceof IBlendBone o)
        {
            this.bbsvfx$bMode = o.bbsvfx$blendMode();
            this.bbsvfx$bFactor = o.bbsvfx$blendFactor();
        }
    }

    @Inject(method = "toData", at = @At("TAIL"))
    private void bbsvfx$toData(MapType data, CallbackInfo ci)
    {
        if (this.bbsvfx$bMode != 0 || this.bbsvfx$bFactor != 1F)
        {
            /* LEGACY DATA KEYS: "xavin_blend_*" predate the xavin → bbsvfx rename; they are written
             * into saved poses, so the names must stay as-is for old scenes/films to load. */
            data.putInt("xavin_blend_mode", this.bbsvfx$bMode);
            data.putFloat("xavin_blend_factor", this.bbsvfx$bFactor);
        }
    }

    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbsvfx$fromData(MapType data, CallbackInfo ci)
    {
        this.bbsvfx$bMode = data.has("xavin_blend_mode") ? data.getInt("xavin_blend_mode") : 0;
        this.bbsvfx$bFactor = data.has("xavin_blend_factor") ? data.getFloat("xavin_blend_factor") : 1F;
    }
}
