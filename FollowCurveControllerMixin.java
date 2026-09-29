package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.film.Film;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.forms.CurveForm;

import java.util.List;
import java.util.Map;

/**
 * "Follow curve" for actors (B-live): each render frame, after the controller has positioned actors
 * and applied their form properties, any actor whose replay has {@code xavin_follow_enabled} and a
 * curve target is snapped onto that curve at the (keyframed) {@code follow_offset} arc-length.
 * Position + horizontal facing go on the entity (bodyYaw); slope pitch and banking roll go on the form
 * transform (entities cannot roll).
 *
 * <p>Hooked at {@code startRenderFrame} TAIL — it runs every frame (playback and paused preview), with
 * all entities created and the animated {@code follow_offset} already applied.</p>
 */
@Mixin(BaseFilmController.class)
public abstract class FollowCurveControllerMixin
{
    @Shadow @Final public Map<String, IEntity> entities;
    @Shadow @Final public Film film;

    @Inject(method = "startRenderFrame", at = @At("TAIL"))
    private void bbsvfx$followCurves(float transition, CallbackInfo ci)
    {
        if (this.film == null)
        {
            return;
        }

        List<Replay> replays = this.film.replays.getList();

        /* BBS 2.6 keys the entity map by replay id, not by the replay's index in the list. */
        for (Map.Entry<String, IEntity> entry : this.entities.entrySet())
        {
            Replay replay = this.bbsvfx$replayById(replays, entry.getKey());

            if (replay != null)
            {
                this.bbsvfx$applyFollow(replays, replay, entry.getValue(), transition);
            }
        }
    }

    /** The replay this entity belongs to. */
    private Replay bbsvfx$replayById(List<Replay> replays, String id)
    {
        for (Replay replay : replays)
        {
            if (replay.getId().equals(id))
            {
                return replay;
            }
        }

        return null;
    }

    private void bbsvfx$applyFollow(List<Replay> replays, Replay replay, IEntity entity, float transition)
    {
        Form form = entity.getForm();

        if (form == null
            || !(replay.get("xavin_follow_enabled") instanceof ValueBoolean enabled) || !enabled.get()
            || !(replay.get("xavin_follow_target") instanceof ValueInt targetValue))
        {
            return;
        }

        int target = targetValue.get();

        /* The saved property is still the target replay's INDEX, so it goes through the list to
         * reach the id the entity map is keyed by since BBS 2.6. */
        if (target < 0 || target >= replays.size())
        {
            return;
        }

        IEntity curveEntity = this.entities.get(replays.get(target).getId());

        if (curveEntity == null || curveEntity == entity || !(curveEntity.getForm() instanceof CurveForm curve))
        {
            return;
        }

        /* Animated travel (already applied to the form property this frame). */
        float off = form.get("follow_offset") instanceof ValueFloat offset
            ? (float) Math.max(0D, Math.min(1D, offset.get())) : 0F;

        Vector3f localPos = new Vector3f();
        Vector3f localTangent = new Vector3f();

        curve.arcPoint(off, localPos, localTangent);

        Matrix4f world = FilmMatrices.getMatrixForRenderWithRotation(curveEntity, 0D, 0D, 0D, transition);
        Vector3f worldPos = world.transformPosition(new Vector3f(localPos));

        entity.setPosition(worldPos.x, worldPos.y, worldPos.z);
        entity.setPrevX(worldPos.x);
        entity.setPrevY(worldPos.y);
        entity.setPrevZ(worldPos.z);

        boolean align = !(replay.get("xavin_follow_align") instanceof ValueBoolean alignValue) || alignValue.get();

        if (!align)
        {
            return;
        }

        Vector3f worldTangent = world.transformDirection(new Vector3f(localTangent));

        if (worldTangent.lengthSquared() < 1e-9F)
        {
            return;
        }

        worldTangent.normalize();

        float yaw = (float) Math.toDegrees(Math.atan2(-worldTangent.x, worldTangent.z));

        entity.setBodyYaw(yaw);
        entity.setYaw(yaw);
        entity.setHeadYaw(yaw);
        entity.setPrevBodyYaw(yaw);
        entity.setPrevYaw(yaw);
        entity.setPrevHeadYaw(yaw);

        float pitch = (float) Math.asin(Math.max(-1F, Math.min(1F, -worldTangent.y)));
        float roll = (float) Math.toRadians(curve.rotationAt(off).z);

        form.transform.get().rotate.x = pitch;
        form.transform.get().rotate.z = roll;
    }
}
