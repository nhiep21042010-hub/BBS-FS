package com.bbsvfx.vfxlights.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import com.bbsvfx.vfxlights.client.LightFormRenderer;
import com.bbsvfx.vfxlights.forms.LightForm;

import java.util.ArrayList;
import java.util.List;

/**
 * Deferred gizmo pass. Light form renderers run INSIDE the entity-render matrix block (between the
 * form renderer's push/pop); drawing world-space geometry right there proved fatal — with the
 * dashboard open the immediate draw interacted with BBS's batched form rendering and the frame died
 * one checkpoint later with "Pose stack not empty". The project's own lesson applies: never draw
 * mid-draw. So render3D only RECORDS the gizmo (matrix + colour) and this pass draws them later,
 * outside every matrix contract.
 *
 * <p><b>Two flush points.</b> With no BBS screen up the queue is drawn at world-render
 * AFTER_TRANSLUCENT, as before. With the dashboard open the world renders into BBS's off-screen
 * framebuffer and the film preview BLITS it into the viewport rect in the UI pass — a gizmo drawn
 * into that texture is part of the picture, not of the editor. So while the dashboard is current the
 * queue is carried over to the UI pass ({@link #renderInUI}), where it draws straight onto the main
 * framebuffer over the blit, through the same viewport/projection setup BBS's own film gizmo uses
 * ({@code Gizmo#renderInterface}): the projection captured during the world render, the GL viewport
 * mapped to the preview rect, and the view matrix folded into the vertices (the UI model-view is
 * identity, which is exactly what that pattern relies on).</p>
 */
public final class GizmoPass
{
    private static final List<Entry> QUEUE = new ArrayList<>();
    /** Carried from the world pass to the UI flush when the dashboard is open. */
    private static final List<Entry> PENDING = new ArrayList<>();

    /** Reused every frame (the Tessellator pattern) — one 8K allocation per frame is exactly the
     * churn the light pool exists to avoid. */
    private static final BufferBuilder BUFFER = new BufferBuilder(8192);

    /** The world render's matrices, captured at AFTER_TRANSLUCENT for the UI flush: the projection
     * is what the off-screen world image was rendered with (the preview blit maps it to the viewport
     * rect), the model-view holds the camera rotation the recorded matrices are relative to. */
    private static final Matrix4f CAPTURED_PROJECTION = new Matrix4f();
    private static final Matrix4f CAPTURED_MODELVIEW = new Matrix4f();

    private GizmoPass()
    {
    }

    public static void submit(LightFormRenderer<? extends LightForm> renderer, Matrix4f matrix,
        float r, float g, float b, float a)
    {
        QUEUE.add(new Entry(renderer, matrix, r, g, b, a));
    }

    /** True while the film world renders into BBS's off-screen framebuffer and the gizmo belongs to
     * the UI pass instead of that texture. */
    private static boolean uiFlushExpected()
    {
        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = mchorse.bbs_mod.BBSModClient.getDashboardIfCreated();

        return dashboard != null && mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu() == dashboard;
    }

    /** World-render AFTER_TRANSLUCENT: with the dashboard open, carry the queue to the UI pass and
     * snapshot the matrices; otherwise draw right here, as before. */
    public static void renderAndClear(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context)
    {
        if (QUEUE.isEmpty())
        {
            /* Nothing recorded this frame — a stale carry-over must not linger either. */
            PENDING.clear();

            return;
        }

        if (uiFlushExpected())
        {
            PENDING.clear();
            PENDING.addAll(QUEUE);
            QUEUE.clear();

            CAPTURED_PROJECTION.set(RenderSystem.getProjectionMatrix());
            CAPTURED_MODELVIEW.set(RenderSystem.getModelViewMatrix());

            return;
        }

        /* OWN buffer, never the shared Tessellator — no batch collisions by construction. */
        BufferBuilder buffer = BUFFER;

        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        for (Entry entry : QUEUE)
        {
            entry.renderer.drawGizmoInto(buffer, entry.matrix, entry.r, entry.g, entry.b, entry.a);
        }

        QUEUE.clear();

        stateForGuideDraw();

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        stateAfterGuideDraw();
    }

    /**
     * UI-pass flush over the film preview's viewport — the counterpart of BBS's
     * {@code Gizmo#renderInterface}. Called from the film controller's render tail (after the preview
     * blit, so the gizmo lands on top of the world image but under the pick highlights).
     *
     * @param area the preview viewport rect ({@code UIFilmController#getGizmoArea})
     */
    public static void renderInUI(mchorse.bbs_mod.ui.framework.UIContext context,
        mchorse.bbs_mod.ui.utils.Area area)
    {
        if (PENDING.isEmpty() || area == null)
        {
            return;
        }

        /* The UI batcher holds unflushed quads — a raw GL interleave would corrupt their order. */
        context.batcher.flush();

        mchorse.bbs_mod.utils.MatrixStackUtils.cacheMatrices();
        RenderSystem.setProjectionMatrix(new Matrix4f(CAPTURED_PROJECTION), VertexSorter.BY_Z);

        /* Map NDC onto the preview rect — the same mapping the blit applies to the world texture,
         * so the gizmo lines up with the image and the frustum clips it to the viewport. */
        mchorse.bbs_mod.ui.utils.UIUtils.viewportArea(area);

        BufferBuilder buffer = BUFFER;
        Matrix4f baked = new Matrix4f();

        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);

        for (Entry entry : PENDING)
        {
            /* The recorded matrix is relative to the world render's model-view (camera rotation);
             * the UI model-view is identity, so fold it in here. */
            entry.renderer.drawGizmoInto(buffer, baked.set(CAPTURED_MODELVIEW).mul(entry.matrix),
                entry.r, entry.g, entry.b, entry.a);
        }

        PENDING.clear();

        stateForGuideDraw();

        BufferRenderer.drawWithGlobalProgram(buffer.end());

        stateAfterGuideDraw();

        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.viewport(0, 0, mc.getWindow().getFramebufferWidth(), mc.getWindow().getFramebufferHeight());
        mchorse.bbs_mod.utils.MatrixStackUtils.restoreMatrices();

        /* Leave the depth state the UI expects after a 3D interlude (always-pass) — the same exit
         * state as BBS's own gizmo and the form editor's model pass. */
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
    }

    /** The IRLite state recipe (qualet, MIT): vanilla position_color shader, no depth test, no
     * cull, no depth writes. Plain triangles are ordinary geometry, so every Iris pack rasterises
     * them identically — position_color carries no normals/UV/lightmap for a pack to transform,
     * and disabling the depth test keeps the guide readable through the set it lights. */
    private static void stateForGuideDraw()
    {
        RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        /* Force the modulator to white: PositionColor multiplies our vertex colours by
         * ColorModulator, and packs leave it at whatever their pipeline last set — IterationRP
         * leaves it BLACK, so every gizmo line renders black and vanishes against the night sky. */
        RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.depthMask(false);
        RenderSystem.disableDepthTest();
    }

    private static void stateAfterGuideDraw()
    {
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
    }

    private record Entry(LightFormRenderer<? extends LightForm> renderer, Matrix4f matrix,
        float r, float g, float b, float a)
    {
    }
}
