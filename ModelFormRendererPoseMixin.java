package com.bbsvfx.bbsvfx.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.forms.ModelForm;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.SmearRenderState;
import com.bbsvfx.bbsvfx.forms.IModelSmearChannels;
import com.bbsvfx.bbsvfx.forms.ISmearBone;

import java.util.Map;

/**
 * Folds the form's separate {@code smear_frames} keyframe channel into the resolved pose at
 * {@code getPose()} (so the rest of the smear render reads it from the pose like before), and — while
 * an echo copy is drawn ({@link SmearRenderState#active} for this renderer) — offsets/stretches each
 * smeared bone along its smear. (The {@code motion_lines} channel is read straight from the form by the
 * lines renderer, so it isn't merged here.) {@code getPose()} returns a fresh copy each call, so none
 * of this corrupts the form's stored pose.
 */
@Mixin(value = ModelFormRenderer.class, remap = false)
public abstract class ModelFormRendererPoseMixin
{
    @Shadow
    public abstract Form getForm();

    /**
     * Bone physics is stateful and time-based — there is no history to rewind, so re-running it for
     * every echo copy (~20 extra applies per frame) is pure cost with no correct result to show. The
     * crisp pass applies it normally; copies of physics-driven bones fall back to the keyframe pose.
     */
    @Inject(method = "applyPhysics", at = @At("HEAD"), cancellable = true)
    private void bbsvfx$skipPhysicsInCopies(mchorse.bbs_mod.forms.entities.IEntity target,
        mchorse.bbs_mod.cubic.ModelInstance model, float transition, org.joml.Matrix4f baseTransform, CallbackInfo ci)
    {
        if (SmearRenderState.active && (Object) this == SmearRenderState.target)
        {
            ci.cancel();
        }
    }

    @ModifyReturnValue(method = "getPose", at = @At("RETURN"))
    private Pose bbsvfx$offsetSmear(Pose pose)
    {
        /* Merge the smear channel into the resolved pose (always). */
        if (this.getForm() instanceof ModelForm && this.getForm() instanceof IModelSmearChannels channels)
        {
            bbsvfx$copySmear(pose, channels.bbsvfx$smearPose().get());
        }

        /* Per-bone blend: hide the bones marked for hiding this pass (so the blended bones can be isolated
         * into the offscreen composite, see BbsVfxBlendBoneState). Hide via the bone's COLOUR ALPHA (the
         * model multiplies each group's vertex colour by its pose colour, group.color.a), NOT scale — scale
         * propagates down the skeleton, so collapsing a parent moves its children (the "two heads"). Alpha
         * leaves every transform intact, so positions stay correct. getPose() returns only the MANUAL pose
         * (the animation is applied separately), so iterate the HIDE SET and pose.getOrCreate() — a bone
         * driven only by the animation isn't in the manual pose otherwise. (BBS 2.6 split the reading
         * get(), which now answers null, from the inserting getOrCreate().) */
        if (com.bbsvfx.bbsvfx.client.BbsVfxBlendBoneState.active && !com.bbsvfx.bbsvfx.client.BbsVfxBlendBoneState.hide.isEmpty())
        {
            for (String bone : com.bbsvfx.bbsvfx.client.BbsVfxBlendBoneState.hide)
            {
                pose.getOrCreate(bone).color.a = 0F;
            }
        }

        if (!SmearRenderState.active || SmearRenderState.target != this)
        {
            return pose;
        }

        /* Isolate to the smeared bones (hide the rest via colour alpha) — applies to BOTH the arc and the
         * vector pass, so an arc limb + a vector head can be smeared in the same frame without each pass
         * re-drawing the other's bones. */
        if (SmearRenderState.hide != null)
        {
            for (String bone : SmearRenderState.hide)
            {
                pose.getOrCreate(bone).color.a = 0F;
            }
        }

        /* Arc copy: the pose is already the historical (time-rewound) pose, so there's no vector to add. */
        if (SmearRenderState.arc)
        {
            /* Deform each shown (arc-smeared) bone: stretch along its length so the copy elongates (grows
             * longer than the limb), squash across (volume preserved), and shrink toward the tail — so
             * copies read as elongated smears that lose form, not clones. */
            float s = SmearRenderState.arcStretch;
            float taper = SmearRenderState.arcTaper;

            /* Stretch is the master switch for ALL geometric deformation (stretch + squash + taper-shrink).
             * 0 = no deformation → plain full-size multiples (a head turn wants this; the shrink otherwise
             * scales copies toward the bone pivot, e.g. the neck, dragging the heads down). */
            if (s > 0F)
            {
                float along = 1F + s;
                float across = s > 0F ? 1F / (float) Math.sqrt(along) : 1F;
                /* Gentle taper-shrink: strong shrink turned a head (which rotates in place, so its copies
                 * are concentric) into a tiny head-in-head. Keep it subtle so copies stay near full size;
                 * the real "loss of detail" is the Dissolve effect. */
                float shrink = 1F - 0.12F * taper;

                for (PoseTransform pt : pose.transforms.values())
                {
                    if (((ISmearBone) pt).bbsvfx$smearArc() > 0F)
                    {
                        pt.scale.x *= across * shrink;
                        pt.scale.y *= along * shrink;
                        pt.scale.z *= across * shrink;
                    }
                }
            }

            return pose;
        }

        float f = SmearRenderState.fraction;
        float stretch = Math.abs(f) * SmearRenderState.stretch;

        for (PoseTransform pt : pose.transforms.values())
        {
            ISmearBone smear = (ISmearBone) pt;

            if (!smear.bbsvfx$hasSmear())
            {
                continue;
            }

            float sx = smear.bbsvfx$smearX();
            float sy = smear.bbsvfx$smearY();
            float sz = smear.bbsvfx$smearZ();

            /* Offset the copy along the smear vector (tail position). */
            pt.translate.add(sx * f, sy * f, sz * f);

            /* Stretch the bone along the smear direction so copies elongate into a streak (the texture
             * stretches with the geometry). Per-axis scale weighted by the direction — coarser than a
             * true directional stretch but reads as a smear; grows toward the far copies. */
            if (stretch > 0F)
            {
                float len = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);

                if (len > 1e-5F)
                {
                    pt.scale.x *= 1F + stretch * Math.abs(sx) / len;
                    pt.scale.y *= 1F + stretch * Math.abs(sy) / len;
                    pt.scale.z *= 1F + stretch * Math.abs(sz) / len;
                }
            }
        }

        return pose;
    }

    @org.spongepowered.asm.mixin.Unique
    private static void bbsvfx$copySmear(Pose target, Pose source)
    {
        for (Map.Entry<String, PoseTransform> entry : source.transforms.entrySet())
        {
            ISmearBone src = (ISmearBone) entry.getValue();

            if (!src.bbsvfx$hasSmear() && src.bbsvfx$smearCount() == 0F && src.bbsvfx$smearFalloff() == 0F && src.bbsvfx$smearDissolve() == 0F)
            {
                continue;
            }

            ISmearBone dst = (ISmearBone) target.getOrCreate(entry.getKey());

            dst.bbsvfx$setSmear(src.bbsvfx$smearX(), src.bbsvfx$smearY(), src.bbsvfx$smearZ());
            dst.bbsvfx$setSmearCount(src.bbsvfx$smearCount());
            dst.bbsvfx$setSmearFalloff(src.bbsvfx$smearFalloff());
            dst.bbsvfx$setSmearDissolve(src.bbsvfx$smearDissolve());
            dst.bbsvfx$setSmearManual(src.bbsvfx$smearManual());
            dst.bbsvfx$setSmearArc(src.bbsvfx$smearArc());
            dst.bbsvfx$setSmearTime(src.bbsvfx$smearTime());
            dst.bbsvfx$setSmearStretch(src.bbsvfx$smearStretch());
            dst.bbsvfx$setSmearDensity(src.bbsvfx$smearDensity());
            dst.bbsvfx$setSmearOpacity(src.bbsvfx$smearOpacity());
        }
    }

    /**
     * {@code applyPose} folds an overlay pose (pose_overlay, additional overlays) into the base pose
     * but only for translate/rotate/scale — so a smear set on an overlay bone is otherwise lost. Add
     * the overlay's smear onto the target so smear works in pose_overlay too.
     */
    @Inject(method = "applyPose", at = @At("TAIL"))
    private void bbsvfx$mergeSmear(Pose targetPose, Pose pose, CallbackInfo ci)
    {
        for (Map.Entry<String, PoseTransform> entry : pose.transforms.entrySet())
        {
            ISmearBone overlay = (ISmearBone) entry.getValue();

            if (overlay.bbsvfx$hasSmear())
            {
                ISmearBone target = (ISmearBone) targetPose.getOrCreate(entry.getKey());

                target.bbsvfx$setSmear(
                    target.bbsvfx$smearX() + overlay.bbsvfx$smearX(),
                    target.bbsvfx$smearY() + overlay.bbsvfx$smearY(),
                    target.bbsvfx$smearZ() + overlay.bbsvfx$smearZ());
            }
        }
    }
}
