package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;
import com.bbsvfx.vfxlights.forms.PointLightForm;

/** Gizmo for the point light: the emitting sphere plus rays, the universal shorthand for a lamp. */
public class PointLightFormRenderer extends LightFormRenderer<PointLightForm>
{
    public PointLightFormRenderer(PointLightForm form)
    {
        super(form);
    }

    @Override
    protected Icon icon()
    {
        return LightIcons.POINT;
    }

    @Override
    protected void buildGizmo(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a)
    {
        /* The capped radius, not the dial: the wireframe has to be the bulb that actually lights the
         * scene, or a lamp dialled past half its own reach would draw a sphere nothing matches. */
        float radius = Math.max(com.bbsvfx.vfxlights.light.Light.capSourceRadius(
            this.form.sourceRadius.get(), this.form.range.get()), 0.15F);

        sphere(buffer, matrix, 0F, 0F, 0F, radius, r, g, b, a);

        float inner = radius * 1.4F;
        float outer = radius * 2.6F;

        line(buffer, matrix, inner, 0F, 0F, outer, 0F, 0F, r, g, b, a);
        line(buffer, matrix, -inner, 0F, 0F, -outer, 0F, 0F, r, g, b, a);
        line(buffer, matrix, 0F, inner, 0F, 0F, outer, 0F, r, g, b, a);
        line(buffer, matrix, 0F, -inner, 0F, 0F, -outer, 0F, r, g, b, a);
        line(buffer, matrix, 0F, 0F, inner, 0F, 0F, outer, r, g, b, a);
        line(buffer, matrix, 0F, 0F, -inner, 0F, 0F, -outer, r, g, b, a);
    }

    @Override
    protected void buildGrabHandles(BufferBuilder buffer, Matrix4f matrix, FormRenderingContext context)
    {
        /* The bulb the gizmo draws — a fat ring per plane, so one faces the camera from any angle. */
        float radius = Math.max(com.bbsvfx.vfxlights.light.Light.capSourceRadius(
            this.form.sourceRadius.get(), this.form.range.get()), 0.15F);
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
