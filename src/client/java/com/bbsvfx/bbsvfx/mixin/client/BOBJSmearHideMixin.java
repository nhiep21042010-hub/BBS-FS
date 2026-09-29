package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.bobj.BOBJArmature;
import mchorse.bbs_mod.bobj.BOBJBone;
import mchorse.bbs_mod.bobj.BOBJLoader;
import mchorse.bbs_mod.cubic.ModelInstance;
import mchorse.bbs_mod.cubic.model.bobj.BOBJModel;
import mchorse.bbs_mod.cubic.render.vao.BOBJModelVAO;
import mchorse.bbs_mod.forms.renderers.ModelFormRenderer;
import mchorse.bbs_mod.ui.framework.elements.utils.StencilMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.SmearRenderState;

import java.util.Set;

/**
 * Per-bone smear isolation for BOBJ models. The cubic path hides the non-smeared bones during an
 * echo copy by zeroing their pose colour alpha ({@code Model.applyPose} feeds it into every group's
 * render colour) — but {@code BOBJModel.applyPose} never applies the pose colour (a BBS TODO), so on
 * .bobj actors every smear copy rendered the WHOLE model: a smear keyed to one bone "smeared the
 * whole body", and the whole-actor ghost copies trailing the motion read as "mini actors".
 *
 * <p>There is no per-vertex colour buffer in this VAO, so instead the triangles owned by the hidden
 * bones are collapsed into degenerate (zero-area) ones. The hook sits at the last seam before the VBO
 * upload in {@code updateMesh} — after any {@code processData} variant (base or "simple" reweighting)
 * — with the final CPU-skinned vertices in {@code tmpVertices}. A vertex whose influencing bones are
 * ALL hidden snaps to the centroid of its triangle's shown vertices (a fully hidden triangle snaps to
 * its first vertex), so every triangle touching only hidden bones collapses to a point/line and never
 * rasterises. Rigid (single-bone) skinning hides cleanly; smooth-weighted seam triangles collapse
 * instead of drawing a sliver.</p>
 */
@Mixin(value = BOBJModelVAO.class, remap = false)
public abstract class BOBJSmearHideMixin
{
    @Shadow
    public BOBJLoader.CompiledData data;

    @Shadow
    public BOBJArmature armature;

    @Shadow
    private float[] tmpVertices;

    /** Lazily built bone-index → bone-name lookup (indices are 0..n-1, see {@code BOBJArmature.initArmature}). */
    @Unique
    private String[] bbsvfx$boneNames;

    @Inject(
        method = "updateMesh",
        at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL15;glBufferData(I[FI)V", ordinal = 0))
    private void bbsvfx$collapseHiddenBones(StencilMap stencilMap, CallbackInfo ci)
    {
        Set<String> hide = SmearRenderState.hide;

        if (!SmearRenderState.active || hide == null || hide.isEmpty())
        {
            return;
        }

        /* Only the smear target's own model collapses — nested body-part models rendering inside the
         * same copy pass keep their geometry (their bone names could collide with the parent's). */
        if (!(SmearRenderState.target instanceof ModelFormRenderer renderer))
        {
            return;
        }

        ModelInstance model = renderer.getModel();

        if (model == null || !(model.model instanceof BOBJModel bobj) || !bobj.getVaos().contains((BOBJModelVAO) (Object) this))
        {
            return;
        }

        if (this.bbsvfx$boneNames == null)
        {
            this.bbsvfx$boneNames = new String[this.armature.orderedBones.size()];

            for (BOBJBone bone : this.armature.orderedBones)
            {
                this.bbsvfx$boneNames[bone.index] = bone.name;
            }
        }

        float[] vertices = this.tmpVertices;
        int count = vertices.length / 3;
        boolean[] vertexHidden = new boolean[count];

        for (int i = 0; i < count; i++)
        {
            boolean hasWeight = false;
            boolean shown = false;

            for (int w = 0; w < 4; w++)
            {
                float weight = this.data.weightData[i * 4 + w];

                if (weight <= 0F)
                {
                    continue;
                }

                hasWeight = true;

                int boneIndex = this.data.boneIndexData[i * 4 + w];
                String name = boneIndex >= 0 && boneIndex < this.bbsvfx$boneNames.length ? this.bbsvfx$boneNames[boneIndex] : null;

                if (name == null || !hide.contains(name))
                {
                    shown = true;
                    break;
                }
            }

            /* Weightless vertices ride whatever bone owns the mesh — keep them (same as the skinning
             * loop, which leaves them at rest). */
            vertexHidden[i] = hasWeight && !shown;
        }

        /* Collapse hidden vertices onto the centroid of their triangle's shown vertices; a fully
         * hidden triangle collapses onto its first vertex — zero area, nothing rasterises. */
        for (int t = 0; t * 3 + 2 < count; t++)
        {
            int v0 = t * 3;
            int v1 = t * 3 + 1;
            int v2 = t * 3 + 2;

            if (!vertexHidden[v0] && !vertexHidden[v1] && !vertexHidden[v2])
            {
                continue;
            }

            float cx = 0F;
            float cy = 0F;
            float cz = 0F;
            int shown = 0;

            for (int v = v0; v <= v2; v++)
            {
                if (!vertexHidden[v])
                {
                    cx += vertices[v * 3];
                    cy += vertices[v * 3 + 1];
                    cz += vertices[v * 3 + 2];
                    shown++;
                }
            }

            if (shown > 0)
            {
                cx /= shown;
                cy /= shown;
                cz /= shown;
            }
            else
            {
                cx = vertices[v0 * 3];
                cy = vertices[v0 * 3 + 1];
                cz = vertices[v0 * 3 + 2];
            }

            for (int v = v0; v <= v2; v++)
            {
                if (vertexHidden[v])
                {
                    vertices[v * 3] = cx;
                    vertices[v * 3 + 1] = cy;
                    vertices[v * 3 + 2] = cz;
                }
            }
        }
    }
}
