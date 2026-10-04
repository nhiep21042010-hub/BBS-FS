package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;

/**
 * Gizmo for ambient fill: the boundary of the volume it affects.
 *
 * <p>Ambient light is the one source with nothing to point at, so the only thing worth drawing is its
 * REACH — where the fill stops. That edge is otherwise completely invisible until something walks
 * through it and changes brightness.</p>
 */
public class AmbientLightFormRenderer extends LightFormRenderer<AmbientLightForm>
{
    public AmbientLightFormRenderer(AmbientLightForm form)
    {
        super(form);
    }

    @Override
    protected Icon icon()
    {
        return LightIcons.AMBIENT;
    }

    @Override
    protected void buildGizmo(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a)
    {
        if (this.form.getMode() == AmbientLightForm.Mode.HEMISPHERE)
        {
            /* Global in effect — draw a small marker plus the sky/ground split it stands for. */
            sphere(buffer, matrix, 0F, 0F, 0F, 0.5F, r, g, b, a);
            line(buffer, matrix, 0F, 0F, 0F, 0F, 1.2F, 0F, r, g, b, a);

            Color ground = this.form.groundColor.get();

            line(buffer, matrix, 0F, 0F, 0F, 0F, -1.2F, 0F, ground.r, ground.g, ground.b, a);

            return;
        }

        if (this.form.getVolume() == AmbientLightForm.Volume.BOX)
        {
            float hx = this.form.sizeX.get();
            float hy = this.form.sizeY.get();
            float hz = this.form.sizeZ.get();

            box(buffer, matrix, hx, hy, hz, r, g, b, a);

            /* The inner edge where falloff begins — the fill is even inside it and fades beyond. */
            float f = 1F - this.form.edgeFalloff.get();

            if (f < 0.999F)
            {
                box(buffer, matrix, hx * f, hy * f, hz * f, r, g, b, a * 0.4F);
            }
        }
        else
        {
            float radius = this.form.range.get();

            sphere(buffer, matrix, 0F, 0F, 0F, radius, r, g, b, a);

            float f = 1F - this.form.edgeFalloff.get();

            if (f < 0.999F)
            {
                sphere(buffer, matrix, 0F, 0F, 0F, radius * f, r, g, b, a * 0.4F);
            }
        }
    }

    @Override
    protected void buildGrabHandles(BufferBuilder buffer, Matrix4f matrix, FormRenderingContext context)
    {
        /* Only the spherical zone has a one-dimensional handle: a fat ring per plane at the fill's
         * boundary, dragging the reach itself. The box volume's three half-extents are panel dials. */
        if (this.form.getMode() != AmbientLightForm.Mode.ZONE
            || this.form.getVolume() != AmbientLightForm.Volume.SPHERE)
        {
            return;
        }

        float radius = this.form.range.get();
        float grab = com.bbsvfx.vfxlights.client.render.LightGuide.grabThickness(radius);

        int index = context.getPickingIndex();

        com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, radius, grab,
            1F, 0F, 0F, 0F, 1F, 0F, stencilR(index), stencilG(index), stencilB(index), 1F);
        context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RADIUS);

        index = context.getPickingIndex();

        com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, radius, grab,
            1F, 0F, 0F, 0F, 0F, 1F, stencilR(index), stencilG(index), stencilB(index), 1F);
        context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RADIUS);

        index = context.getPickingIndex();

        com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, radius, grab,
            0F, 1F, 0F, 0F, 0F, 1F, stencilR(index), stencilG(index), stencilB(index), 1F);
        context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RADIUS);
    }
}
