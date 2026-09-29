package com.bbsvfx.bbsvfx.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.utils.interps.AutoBezier;
import mchorse.bbs_mod.utils.interps.IInterp;
import mchorse.bbs_mod.utils.interps.Lerps;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import mchorse.bbs_mod.utils.pose.Transform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.ISmearBone;

/**
 * Adds a per-bone smear vector to BBS's {@link PoseTransform}, woven into every place the engine
 * handles a pose channel (identity / lerp / autoLerp / equals / copy / add / toData / fromData) so it
 * serialises, interpolates and animates exactly like the built-in {@code fix} / {@code lighting}
 * channels. The vector is the bone's manual smear (direction × length); the render hook reads it to
 * trail that bone. Mirrors the {@code fix}/{@code lighting} handling already in PoseTransform.
 */
@Mixin(value = PoseTransform.class, remap = false)
public abstract class PoseTransformSmearMixin implements ISmearBone
{
    @Unique private float bbsvfx$sx;
    @Unique private float bbsvfx$sy;
    @Unique private float bbsvfx$sz;
    @Unique private float bbsvfx$count;
    @Unique private float bbsvfx$falloff;
    @Unique private float bbsvfx$dissolve;
    @Unique private float bbsvfx$manual;
    @Unique private float bbsvfx$arc;
    @Unique private float bbsvfx$time;
    @Unique private float bbsvfx$stretch;
    @Unique private float bbsvfx$density;
    @Unique private float bbsvfx$opacity;
    @Unique private float bbsvfx$lines;
    @Unique private float bbsvfx$linesCount;
    @Unique private float bbsvfx$linesWidth;
    @Unique private float bbsvfx$linesSpread;
    @Unique private float bbsvfx$lox;
    @Unique private float bbsvfx$loy;
    @Unique private float bbsvfx$loz;
    @Unique private float bbsvfx$linesTexture;

    @Override
    public float bbsvfx$smearX()
    {
        return this.bbsvfx$sx;
    }

    @Override
    public float bbsvfx$smearY()
    {
        return this.bbsvfx$sy;
    }

    @Override
    public float bbsvfx$smearZ()
    {
        return this.bbsvfx$sz;
    }

    @Override
    public void bbsvfx$setSmear(float x, float y, float z)
    {
        this.bbsvfx$sx = x;
        this.bbsvfx$sy = y;
        this.bbsvfx$sz = z;
    }

    @Override
    public float bbsvfx$smearCount()
    {
        return this.bbsvfx$count;
    }

    @Override
    public void bbsvfx$setSmearCount(float count)
    {
        this.bbsvfx$count = count;
    }

    @Override
    public float bbsvfx$smearFalloff()
    {
        return this.bbsvfx$falloff;
    }

    @Override
    public void bbsvfx$setSmearFalloff(float falloff)
    {
        this.bbsvfx$falloff = falloff;
    }

    @Override
    public float bbsvfx$smearDissolve()
    {
        return this.bbsvfx$dissolve;
    }

    @Override
    public void bbsvfx$setSmearDissolve(float dissolve)
    {
        this.bbsvfx$dissolve = dissolve;
    }

    @Override
    public float bbsvfx$smearManual()
    {
        return this.bbsvfx$manual;
    }

    @Override
    public void bbsvfx$setSmearManual(float manual)
    {
        this.bbsvfx$manual = manual;
    }

    @Override
    public float bbsvfx$smearArc()
    {
        return this.bbsvfx$arc;
    }

    @Override
    public void bbsvfx$setSmearArc(float arc)
    {
        this.bbsvfx$arc = arc;
    }

    @Override
    public float bbsvfx$smearTime()
    {
        return this.bbsvfx$time;
    }

    @Override
    public void bbsvfx$setSmearTime(float time)
    {
        this.bbsvfx$time = time;
    }

    @Override
    public float bbsvfx$smearStretch()
    {
        return this.bbsvfx$stretch;
    }

    @Override
    public void bbsvfx$setSmearStretch(float stretch)
    {
        this.bbsvfx$stretch = stretch;
    }

    @Override
    public float bbsvfx$smearDensity()
    {
        return this.bbsvfx$density;
    }

    @Override
    public void bbsvfx$setSmearDensity(float density)
    {
        this.bbsvfx$density = density;
    }

    @Override
    public float bbsvfx$smearOpacity()
    {
        return this.bbsvfx$opacity;
    }

    @Override
    public void bbsvfx$setSmearOpacity(float opacity)
    {
        this.bbsvfx$opacity = opacity;
    }

    @Override
    public float bbsvfx$smearLines()
    {
        return this.bbsvfx$lines;
    }

    @Override
    public void bbsvfx$setSmearLines(float lines)
    {
        this.bbsvfx$lines = lines;
    }

    @Override
    public float bbsvfx$smearLinesCount()
    {
        return this.bbsvfx$linesCount;
    }

    @Override
    public void bbsvfx$setSmearLinesCount(float count)
    {
        this.bbsvfx$linesCount = count;
    }

    @Override
    public float bbsvfx$smearLinesWidth()
    {
        return this.bbsvfx$linesWidth;
    }

    @Override
    public void bbsvfx$setSmearLinesWidth(float width)
    {
        this.bbsvfx$linesWidth = width;
    }

    @Override
    public float bbsvfx$smearLinesSpread()
    {
        return this.bbsvfx$linesSpread;
    }

    @Override
    public void bbsvfx$setSmearLinesSpread(float spread)
    {
        this.bbsvfx$linesSpread = spread;
    }

    @Override
    public float bbsvfx$smearLinesOffsetX()
    {
        return this.bbsvfx$lox;
    }

    @Override
    public float bbsvfx$smearLinesOffsetY()
    {
        return this.bbsvfx$loy;
    }

    @Override
    public float bbsvfx$smearLinesOffsetZ()
    {
        return this.bbsvfx$loz;
    }

    @Override
    public void bbsvfx$setSmearLinesOffset(float x, float y, float z)
    {
        this.bbsvfx$lox = x;
        this.bbsvfx$loy = y;
        this.bbsvfx$loz = z;
    }

    @Override
    public float bbsvfx$smearLinesTexture()
    {
        return this.bbsvfx$linesTexture;
    }

    @Override
    public void bbsvfx$setSmearLinesTexture(float texture)
    {
        this.bbsvfx$linesTexture = texture;
    }

    @Inject(method = "identity", at = @At("TAIL"))
    private void bbsvfx$identity(CallbackInfo ci)
    {
        this.bbsvfx$sx = this.bbsvfx$sy = this.bbsvfx$sz = 0F;
        this.bbsvfx$count = this.bbsvfx$falloff = this.bbsvfx$dissolve = 0F;
        this.bbsvfx$manual = this.bbsvfx$arc = this.bbsvfx$time = this.bbsvfx$stretch = this.bbsvfx$density = this.bbsvfx$opacity = 0F;
        this.bbsvfx$lines = this.bbsvfx$linesCount = this.bbsvfx$linesWidth = this.bbsvfx$linesSpread = 0F;
        this.bbsvfx$lox = this.bbsvfx$loy = this.bbsvfx$loz = 0F;
        this.bbsvfx$linesTexture = 0F;
    }

    /**
     * STEP semantics for every smear/lines field: these are per-keyframe CONFIG (on/off toggles, counts,
     * densities), not animation — tweening them produced epsilon residues (e.g. bezier tails of 1e-6)
     * that still classified bones as "smeared", giving ghost copies / mini-model / one-frame flicker on
     * films where smear was keyed on SOME pose keyframes ("smear without smear keyframes" tester bug).
     * A keyframe's smear config holds until the next keyframe.
     */
    @org.spongepowered.asm.mixin.Unique
    private void bbsvfx$stepFrom(ISmearBone o)
    {
        this.bbsvfx$sx = o.bbsvfx$smearX();
        this.bbsvfx$sy = o.bbsvfx$smearY();
        this.bbsvfx$sz = o.bbsvfx$smearZ();
        this.bbsvfx$count = o.bbsvfx$smearCount();
        this.bbsvfx$falloff = o.bbsvfx$smearFalloff();
        this.bbsvfx$dissolve = o.bbsvfx$smearDissolve();
        this.bbsvfx$manual = o.bbsvfx$smearManual();
        this.bbsvfx$arc = o.bbsvfx$smearArc();
        this.bbsvfx$time = o.bbsvfx$smearTime();
        this.bbsvfx$stretch = o.bbsvfx$smearStretch();
        this.bbsvfx$density = o.bbsvfx$smearDensity();
        this.bbsvfx$opacity = o.bbsvfx$smearOpacity();
        this.bbsvfx$lines = o.bbsvfx$smearLines();
        this.bbsvfx$linesCount = o.bbsvfx$smearLinesCount();
        this.bbsvfx$linesWidth = o.bbsvfx$smearLinesWidth();
        this.bbsvfx$linesSpread = o.bbsvfx$smearLinesSpread();
        this.bbsvfx$lox = o.bbsvfx$smearLinesOffsetX();
        this.bbsvfx$loy = o.bbsvfx$smearLinesOffsetY();
        this.bbsvfx$loz = o.bbsvfx$smearLinesOffsetZ();
        this.bbsvfx$linesTexture = o.bbsvfx$smearLinesTexture();
    }

    @Inject(method = "lerp(Lmchorse/bbs_mod/utils/pose/Transform;F)V", at = @At("TAIL"))
    private void bbsvfx$lerp(Transform transform, float a, CallbackInfo ci)
    {
        /* Pose blending: step at the midpoint (keep ours below 0.5, take the other's above). */
        if (transform instanceof ISmearBone o && a >= 0.5F)
        {
            this.bbsvfx$stepFrom(o);
        }
    }

    @Inject(method = "lerp(Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/pose/Transform;Lmchorse/bbs_mod/utils/interps/IInterp;F)V", at = @At("TAIL"))
    private void bbsvfx$lerpKeyframes(Transform preA, Transform a, Transform b, Transform postB, IInterp interp, float x, CallbackInfo ci)
    {
        /* Keyframe interpolation: STEP — the segment's left keyframe's smear config holds throughout. */
        if (a instanceof ISmearBone a1)
        {
            this.bbsvfx$stepFrom(a1);
        }
    }

    @Inject(method = "autoLerp", at = @At("TAIL"))
    private void bbsvfx$autoLerp(Transform preA, Transform a, Transform b, Transform postB, float pt, float at, float bt, float qt, boolean clamped, float x, CallbackInfo ci)
    {
        /* Keyframe interpolation (bezier): STEP — see bbsvfx$stepFrom. Bezier tails were the worst
         * offender, leaking 1e-6-scale residues several keyframes away from the smeared one. */
        if (a instanceof ISmearBone a1)
        {
            this.bbsvfx$stepFrom(a1);
        }
    }

    @ModifyReturnValue(method = "equals", at = @At("RETURN"))
    private boolean bbsvfx$equals(boolean original, Object obj)
    {
        if (obj instanceof ISmearBone o)
        {
            return original
                && this.bbsvfx$sx == o.bbsvfx$smearX() && this.bbsvfx$sy == o.bbsvfx$smearY() && this.bbsvfx$sz == o.bbsvfx$smearZ()
                && this.bbsvfx$count == o.bbsvfx$smearCount() && this.bbsvfx$falloff == o.bbsvfx$smearFalloff()
                && this.bbsvfx$dissolve == o.bbsvfx$smearDissolve()
                && this.bbsvfx$manual == o.bbsvfx$smearManual()
                && this.bbsvfx$arc == o.bbsvfx$smearArc() && this.bbsvfx$time == o.bbsvfx$smearTime()
                && this.bbsvfx$stretch == o.bbsvfx$smearStretch() && this.bbsvfx$density == o.bbsvfx$smearDensity()
                && this.bbsvfx$opacity == o.bbsvfx$smearOpacity()
                && this.bbsvfx$lines == o.bbsvfx$smearLines() && this.bbsvfx$linesCount == o.bbsvfx$smearLinesCount()
                && this.bbsvfx$linesWidth == o.bbsvfx$smearLinesWidth() && this.bbsvfx$linesSpread == o.bbsvfx$smearLinesSpread()
                && this.bbsvfx$lox == o.bbsvfx$smearLinesOffsetX() && this.bbsvfx$loy == o.bbsvfx$smearLinesOffsetY() && this.bbsvfx$loz == o.bbsvfx$smearLinesOffsetZ()
                && this.bbsvfx$linesTexture == o.bbsvfx$smearLinesTexture();
        }

        return original;
    }

    @Inject(method = "copy(Lmchorse/bbs_mod/utils/pose/Transform;)V", at = @At("TAIL"))
    private void bbsvfx$copy(Transform transform, CallbackInfo ci)
    {
        if (transform instanceof ISmearBone o)
        {
            this.bbsvfx$sx = o.bbsvfx$smearX();
            this.bbsvfx$sy = o.bbsvfx$smearY();
            this.bbsvfx$sz = o.bbsvfx$smearZ();
            this.bbsvfx$count = o.bbsvfx$smearCount();
            this.bbsvfx$falloff = o.bbsvfx$smearFalloff();
            this.bbsvfx$dissolve = o.bbsvfx$smearDissolve();
            this.bbsvfx$manual = o.bbsvfx$smearManual();
            this.bbsvfx$arc = o.bbsvfx$smearArc();
            this.bbsvfx$time = o.bbsvfx$smearTime();
            this.bbsvfx$stretch = o.bbsvfx$smearStretch();
            this.bbsvfx$density = o.bbsvfx$smearDensity();
            this.bbsvfx$opacity = o.bbsvfx$smearOpacity();
            this.bbsvfx$lines = o.bbsvfx$smearLines();
            this.bbsvfx$linesCount = o.bbsvfx$smearLinesCount();
            this.bbsvfx$linesWidth = o.bbsvfx$smearLinesWidth();
            this.bbsvfx$linesSpread = o.bbsvfx$smearLinesSpread();
            this.bbsvfx$lox = o.bbsvfx$smearLinesOffsetX();
            this.bbsvfx$loy = o.bbsvfx$smearLinesOffsetY();
            this.bbsvfx$loz = o.bbsvfx$smearLinesOffsetZ();
            this.bbsvfx$linesTexture = o.bbsvfx$smearLinesTexture();
        }
    }

    @Inject(method = "add", at = @At("TAIL"))
    private void bbsvfx$add(Transform transform, CallbackInfo ci)
    {
        if (transform instanceof ISmearBone o)
        {
            this.bbsvfx$sx += o.bbsvfx$smearX();
            this.bbsvfx$sy += o.bbsvfx$smearY();
            this.bbsvfx$sz += o.bbsvfx$smearZ();
            this.bbsvfx$count += o.bbsvfx$smearCount();
            this.bbsvfx$falloff += o.bbsvfx$smearFalloff();
            this.bbsvfx$dissolve += o.bbsvfx$smearDissolve();
            this.bbsvfx$manual += o.bbsvfx$smearManual();
            this.bbsvfx$arc += o.bbsvfx$smearArc();
            this.bbsvfx$time += o.bbsvfx$smearTime();
            this.bbsvfx$stretch += o.bbsvfx$smearStretch();
            this.bbsvfx$density += o.bbsvfx$smearDensity();
            this.bbsvfx$opacity += o.bbsvfx$smearOpacity();
            this.bbsvfx$lines += o.bbsvfx$smearLines();
            this.bbsvfx$linesCount += o.bbsvfx$smearLinesCount();
            this.bbsvfx$linesWidth += o.bbsvfx$smearLinesWidth();
            this.bbsvfx$linesSpread += o.bbsvfx$smearLinesSpread();
            this.bbsvfx$lox += o.bbsvfx$smearLinesOffsetX();
            this.bbsvfx$loy += o.bbsvfx$smearLinesOffsetY();
            this.bbsvfx$loz += o.bbsvfx$smearLinesOffsetZ();
            this.bbsvfx$linesTexture += o.bbsvfx$smearLinesTexture();
        }
    }

    @Inject(method = "toData", at = @At("TAIL"))
    private void bbsvfx$toData(MapType data, CallbackInfo ci)
    {
        if (this.bbsvfx$sx != 0F || this.bbsvfx$sy != 0F || this.bbsvfx$sz != 0F
            || this.bbsvfx$count != 0F || this.bbsvfx$falloff != 0F || this.bbsvfx$dissolve != 0F
            || this.bbsvfx$manual != 0F
            || this.bbsvfx$arc != 0F || this.bbsvfx$time != 0F || this.bbsvfx$stretch != 0F || this.bbsvfx$density != 0F || this.bbsvfx$opacity != 0F
            || this.bbsvfx$lines != 0F || this.bbsvfx$linesCount != 0F || this.bbsvfx$linesWidth != 0F || this.bbsvfx$linesSpread != 0F
            || this.bbsvfx$lox != 0F || this.bbsvfx$loy != 0F || this.bbsvfx$loz != 0F
            || this.bbsvfx$linesTexture != 0F)
        {
            /* LEGACY DATA KEYS: the "xavin_smear_*" names predate the xavin → bbsvfx rename; they are
             * written into saved poses, so they must stay as-is for old scenes/films to load. */
            data.putFloat("xavin_smear_x", this.bbsvfx$sx);
            data.putFloat("xavin_smear_y", this.bbsvfx$sy);
            data.putFloat("xavin_smear_z", this.bbsvfx$sz);
            data.putFloat("xavin_smear_count", this.bbsvfx$count);
            data.putFloat("xavin_smear_falloff", this.bbsvfx$falloff);
            data.putFloat("xavin_smear_dissolve", this.bbsvfx$dissolve);
            data.putFloat("xavin_smear_manual", this.bbsvfx$manual);
            data.putFloat("xavin_smear_arc", this.bbsvfx$arc);
            data.putFloat("xavin_smear_time", this.bbsvfx$time);
            data.putFloat("xavin_smear_stretch", this.bbsvfx$stretch);
            data.putFloat("xavin_smear_density", this.bbsvfx$density);
            data.putFloat("xavin_smear_opacity", this.bbsvfx$opacity);
            data.putFloat("xavin_smear_lines", this.bbsvfx$lines);
            data.putFloat("xavin_smear_lines_count", this.bbsvfx$linesCount);
            data.putFloat("xavin_smear_lines_width", this.bbsvfx$linesWidth);
            data.putFloat("xavin_smear_lines_spread", this.bbsvfx$linesSpread);
            data.putFloat("xavin_smear_lines_ox", this.bbsvfx$lox);
            data.putFloat("xavin_smear_lines_oy", this.bbsvfx$loy);
            data.putFloat("xavin_smear_lines_oz", this.bbsvfx$loz);
            data.putFloat("xavin_smear_lines_texture", this.bbsvfx$linesTexture);
        }
    }

    @Inject(method = "fromData", at = @At("TAIL"))
    private void bbsvfx$fromData(MapType data, CallbackInfo ci)
    {
        this.bbsvfx$sx = data.getFloat("xavin_smear_x");
        this.bbsvfx$sy = data.getFloat("xavin_smear_y");
        this.bbsvfx$sz = data.getFloat("xavin_smear_z");
        this.bbsvfx$count = data.getFloat("xavin_smear_count");
        this.bbsvfx$falloff = data.getFloat("xavin_smear_falloff");
        this.bbsvfx$dissolve = data.getFloat("xavin_smear_dissolve");
        this.bbsvfx$manual = data.getFloat("xavin_smear_manual");
        this.bbsvfx$arc = data.getFloat("xavin_smear_arc");
        this.bbsvfx$time = data.getFloat("xavin_smear_time");
        this.bbsvfx$stretch = data.getFloat("xavin_smear_stretch");
        this.bbsvfx$density = data.getFloat("xavin_smear_density");
        this.bbsvfx$opacity = data.getFloat("xavin_smear_opacity");
        this.bbsvfx$lines = data.getFloat("xavin_smear_lines");
        this.bbsvfx$linesCount = data.getFloat("xavin_smear_lines_count");
        this.bbsvfx$linesWidth = data.getFloat("xavin_smear_lines_width");
        this.bbsvfx$linesSpread = data.getFloat("xavin_smear_lines_spread");
        this.bbsvfx$lox = data.getFloat("xavin_smear_lines_ox");
        this.bbsvfx$loy = data.getFloat("xavin_smear_lines_oy");
        this.bbsvfx$loz = data.getFloat("xavin_smear_lines_oz");
        this.bbsvfx$linesTexture = data.getFloat("xavin_smear_lines_texture");
    }
}
