package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import net.minecraft.client.render.BufferBuilder;
import org.joml.Matrix4f;
import com.bbsvfx.vfxlights.forms.AreaLightForm;

/**
 * Gizmo for the area light: the emitter outline plus a normal showing which way it faces.
 *
 * <p>The emitter lies in the local XY plane and radiates along +Z, so aiming it is just rotating the
 * form. Drawing the outline at true size matters more here than for other lights — with an area source
 * the size IS the softness, so seeing the rectangle is seeing the shadow it will make.</p>
 */
public class AreaLightFormRenderer extends LightFormRenderer<AreaLightForm>
{
    public AreaLightFormRenderer(AreaLightForm form)
    {
        super(form);
    }

    @Override
    protected Icon icon()
    {
        return LightIcons.AREA;
    }

    @Override
    protected void buildGizmo(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a)
    {
        float w = this.form.width.get();
        float h = this.form.height.get();
        float hw = w * 0.5F;
        float hh = h * 0.5F;

        switch (this.form.getShape())
        {
            case RECT:
                line(buffer, matrix, -hw, -hh, 0F, hw, -hh, 0F, r, g, b, a);
                line(buffer, matrix, hw, -hh, 0F, hw, hh, 0F, r, g, b, a);
                line(buffer, matrix, hw, hh, 0F, -hw, hh, 0F, r, g, b, a);
                line(buffer, matrix, -hw, hh, 0F, -hw, -hh, 0F, r, g, b, a);
                break;

            case DISC:
                circle(buffer, matrix, 0F, 0F, 0F, hw, 1F, 0F, 0F, 0F, 1F, 0F, r, g, b, a);
                break;

            case TUBE:
            {
                float t = this.form.thickness.get() * 0.5F;

                circle(buffer, matrix, -hw, 0F, 0F, t, 0F, 1F, 0F, 0F, 0F, 1F, r, g, b, a);
                circle(buffer, matrix, hw, 0F, 0F, t, 0F, 1F, 0F, 0F, 0F, 1F, r, g, b, a);
                line(buffer, matrix, -hw, t, 0F, hw, t, 0F, r, g, b, a);
                line(buffer, matrix, -hw, -t, 0F, hw, -t, 0F, r, g, b, a);
                line(buffer, matrix, -hw, 0F, t, hw, 0F, t, r, g, b, a);
                line(buffer, matrix, -hw, 0F, -t, hw, 0F, -t, r, g, b, a);
                break;
            }

            case SPHERE:
            default:
                sphere(buffer, matrix, 0F, 0F, 0F, hw, r, g, b, a);
                break;
        }

        /* Emission direction. A sphere radiates everywhere, so an arrow on it would be a lie. */
        if (this.form.getShape() != AreaLightForm.Shape.SPHERE)
        {
            float len = Math.min(this.form.range.get() * 0.25F, Math.max(w, h) * 1.5F);

            line(buffer, matrix, 0F, 0F, 0F, 0F, 0F, len, r, g, b, a);

            float tip = len * 0.15F;

            line(buffer, matrix, 0F, 0F, len, tip, 0F, len - tip, r, g, b, a);
            line(buffer, matrix, 0F, 0F, len, -tip, 0F, len - tip, r, g, b, a);
            line(buffer, matrix, 0F, 0F, len, 0F, tip, len - tip, r, g, b, a);
            line(buffer, matrix, 0F, 0F, len, 0F, -tip, len - tip, r, g, b, a);

            if (this.form.twoSided.get())
            {
                line(buffer, matrix, 0F, 0F, 0F, 0F, 0F, -len * 0.5F, r, g, b, a * 0.5F);
            }

            /* The reach: a faint stub continuing past the arrow to the (capped) range, with a small
             * end ring — the visible anchor of the range drag handle. Capped like the spot cone: a
             * 128-block gizmo is a wall, not a hint; the drag solve works past the cap regardless. */
            float reach = Math.min(this.form.range.get(), 12F);

            if (reach > len)
            {
                line(buffer, matrix, 0F, 0F, len, 0F, 0F, reach, r, g, b, a * 0.35F);
            }

            circle(buffer, matrix, 0F, 0F, reach, Math.max(reach * 0.008F, 0.04F),
                1F, 0F, 0F, 0F, 1F, 0F, r, g, b, a * 0.35F);
        }
    }

    @Override
    protected void buildGrabHandles(BufferBuilder buffer, Matrix4f matrix, FormRenderingContext context)
    {
        /* Grab handles must be BIG: the stencil readback counts a form's index only on the exact
         * pixel under the cursor (the hover-tolerance radius is reserved for BBS's own gizmo
         * handles), so a small disc is nearly unhittable — the "area не драгается" report. The
         * handles therefore ARE the edges they steer: pull the frame itself. */
        float w = this.form.width.get();
        float h = this.form.height.get();
        float hw = w * 0.5F;
        float hh = h * 0.5F;
        float grab = com.bbsvfx.vfxlights.client.render.LightGuide.grabThickness(Math.max(w, h));

        int index;

        switch (this.form.getShape())
        {
            case RECT:
                /* Right edge drags the width, top edge the height — the hit's coordinate on the
                 * grabbed axis stays ~the half-extent no matter where along the edge you land. */
                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.line(buffer, matrix, hw, -hh, 0F, hw, hh, 0F, grab,
                    stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);

                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.line(buffer, matrix, -hw, hh, 0F, hw, hh, 0F, grab,
                    stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_HEIGHT);
                break;

            case DISC:
                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.ringZ(buffer, matrix, 0F, 0F, 0F, hw, grab,
                    stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);
                break;

            case TUBE:
                /* End caps drag the length; the long sides would read a mid-tube hit as a shrink. */
                float t = this.form.thickness.get() * 0.5F;
                float cap = Math.max(t * 3F, Math.max(hw * 0.1F, 0.08F));

                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.discZ(buffer, matrix, hw, 0F, 0F, cap,
                    stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);

                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.discZ(buffer, matrix, -hw, 0F, 0F, cap,
                    stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);
                break;

            case SPHERE:
            default:
                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, hw, grab,
                    1F, 0F, 0F, 0F, 1F, 0F, stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);

                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, hw, grab,
                    1F, 0F, 0F, 0F, 0F, 1F, stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);

                index = context.getPickingIndex();

                com.bbsvfx.vfxlights.client.render.LightGuide.ring(buffer, matrix, 0F, 0F, 0F, hw, grab,
                    0F, 1F, 0F, 0F, 0F, 1F, stencilR(index), stencilG(index), stencilB(index), 1F);
                context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_WIDTH);
                break;
        }

        /* The reach handle: a fat disc at the end of the range stub, on the emission axis. A sphere
         * has no axis, so it gets no range handle (same reason it has no arrow). */
        if (this.form.getShape() != AreaLightForm.Shape.SPHERE)
        {
            float reach = Math.min(this.form.range.get(), 12F);

            index = context.getPickingIndex();

            com.bbsvfx.vfxlights.client.render.LightGuide.discZ(buffer, matrix, 0F, 0F, reach,
                Math.max(reach * 0.08F, 0.08F), stencilR(index), stencilG(index), stencilB(index), 1F);
            context.stencilMap.addPicking(this.form, LightGuideDrag.HANDLE_RANGE);
        }
    }
}
