package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

/**
 * Gizmo for the spot light: the beam cone.
 *
 * <p>Both cones are drawn — outer solid, inner faint — because the gap between them is the whole
 * character of the beam, and it is impossible to judge from two numbers in a panel.</p>
 */
public class SpotLightFormRenderer extends LightFormRenderer<SpotLightForm>
{
    public SpotLightFormRenderer(SpotLightForm form)
    {
        super(form);
    }

    @Override
    protected Icon icon()
    {
        return LightIcons.SPOT;
    }

    @Override
    protected void buildGizmo(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a)
    {
        /* Only as long as it is useful to look at: a 128-block beam gizmo is a wall, not a hint. */
        float length = Math.min(this.form.range.get(), 12F);

        cone(buffer, matrix, length, this.form.angle.get(), r, g, b, a);
        cone(buffer, matrix, length, this.form.effectiveInnerAngle(), r, g, b, a * 0.35F);
    }

    @Override
    protected void buildGrabHandles(BufferBuilder buffer, Matrix4f matrix, FormRenderingContext context)
    {
        /* The same cap the visible guide draws — grabbing what you see, not where the real beam ends. */
        float length = Math.min(this.form.range.get(), 12F);
        float grab = com.bbsvfx.vfxlights.client.render.LightGuide.grabThickness(length);
        float outerR = (float) (Math.tan(Math.toRadians(this.form.angle.get() * 0.5F)) * length);

        int index = context.getPickingIndex();

        com.bbsvfx.vfxlights.client.render.LightGuide.ringZ(buffer, matrix, 0F, 0F, length, outerR, grab,
            stencilR(index), stencilG(index), stencilB(index), 1F);
        context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RADIUS);

        float inner = this.form.effectiveInnerAngle();

        if (inner < this.form.angle.get())
        {
            float innerR = (float) (Math.tan(Math.toRadians(inner * 0.5F)) * length);

            index = context.getPickingIndex();

            com.bbsvfx.vfxlights.client.render.LightGuide.ringZ(buffer, matrix, 0F, 0F, length, innerR, grab,
                stencilR(index), stencilG(index), stencilB(index), 1F);
            context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_INNER);
        }

        /* A fat disc on the cap — the range handle you can actually hit. */
        index = context.getPickingIndex();

        com.bbsvfx.vfxlights.client.render.LightGuide.discZ(buffer, matrix, 0F, 0F, length,
            Math.max(length * 0.1F, 0.05F), stencilR(index), stencilG(index), stencilB(index), 1F);
        context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RANGE);
    }

    private static void cone(BufferBuilder buffer, Matrix4f matrix, float length, float angleDegrees,
        float r, float g, float b, float a)
    {
        float radius = (float) (Math.tan(Math.toRadians(angleDegrees * 0.5F)) * length);

        circle(buffer, matrix, 0F, 0F, length, radius, 1F, 0F, 0F, 0F, 1F, 0F, r, g, b, a);

        line(buffer, matrix, 0F, 0F, 0F, radius, 0F, length, r, g, b, a);
        line(buffer, matrix, 0F, 0F, 0F, -radius, 0F, length, r, g, b, a);
        line(buffer, matrix, 0F, 0F, 0F, 0F, radius, length, r, g, b, a);
        line(buffer, matrix, 0F, 0F, 0F, 0F, -radius, length, r, g, b, a);
    }
}
