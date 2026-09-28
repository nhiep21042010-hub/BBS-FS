package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.camera.data.Angle;
import mchorse.bbs_mod.camera.data.Position;
import mchorse.bbs_mod.film.BaseFilmController;
import mchorse.bbs_mod.film.FilmMatrices;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.entities.IEntity;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.utils.clips.Clip;
import mchorse.bbs_mod.utils.clips.ClipContext;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.camera.FollowCurveClip;
import com.bbsvfx.bbsvfx.forms.CurveForm;

import java.util.List;

/**
 * Client side of {@link FollowCurveClip}: samples the targeted curve actor at an arc-length offset
 * (advancing with the clip's progress) and drives the camera position there, optionally aiming the
 * camera down the curve's tangent.
 */
public class FollowCurveClientClip extends FollowCurveClip
{
    @Override
    protected void applyClip(ClipContext context, Position position)
    {
        List<IEntity> entities = this.getEntities(context);

        if (entities.isEmpty())
        {
            return;
        }

        IEntity entity = entities.get(0);
        Form form = entity == null ? null : entity.getForm();

        if (!(form instanceof CurveForm curve))
        {
            return;
        }

        /* Keyframed arc-length offset along the curve (0..1). */
        float offset = (float) Math.max(0D, Math.min(1D, this.progress.interpolate(context.relativeTick + context.transition)));

        Vector3f localPos = new Vector3f();
        Vector3f localTangent = new Vector3f();

        curve.arcPoint(offset, localPos, localTangent);

        /* The actor's absolute world matrix (camera-relative origin 0,0,0 -> world space). */
        Matrix4f world = FilmMatrices.getMatrixForRenderWithRotation(entity, 0D, 0D, 0D, context.transition);

        /* Fold in the form's own transform (the "Transform" track: raise/rotate/scale). The renderer
         * bakes it into the drawn curve via applyTransforms, but getMatrixForRenderWithRotation omits it,
         * so without this the camera would ride the un-transformed (rest) curve. */
        FormRenderer<?> renderer = FormUtilsClient.getRenderer(curve);

        if (renderer instanceof CurveFormRenderer curveRenderer)
        {
            world.mul(curveRenderer.formTransformMatrix(context.transition));
        }

        Vector3f worldPos = world.transformPosition(new Vector3f(localPos));

        /* Positional nudge (the inherited offset point), in world space. */
        position.point.set(worldPos.x + this.offset.get().x, worldPos.y + this.offset.get().y, worldPos.z + this.offset.get().z);

        if (this.align.get())
        {
            Vector3f worldTangent = world.transformDirection(new Vector3f(localTangent));

            if (worldTangent.lengthSquared() > 1e-9F)
            {
                worldTangent.normalize();

                Angle angle = Angle.angle(worldTangent.x, worldTangent.y, worldTangent.z);

                position.angle.yaw = angle.yaw;
                position.angle.pitch = angle.pitch;
            }
        }
    }

    @Override
    protected Clip create()
    {
        return new FollowCurveClientClip();
    }
}
