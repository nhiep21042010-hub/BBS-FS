package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.utils.icons.Icon;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import org.joml.Matrix4f;
import com.bbsvfx.vfxlights.client.light.FormLightCollector;
import com.bbsvfx.vfxlights.forms.LightForm;

/**
 * Common rendering for every light: a wireframe gizmo showing where the source is, how big it is and
 * which way it points.
 *
 * <p><b>A light has no appearance of its own</b> — what it produces is other surfaces getting brighter.
 * That leaves nothing to draw and nothing to grab, so without a gizmo an area light is an invisible,
 * unselectable rectangle floating in the set. The gizmo is drawn in the light's own colour, which also
 * makes a rig of a dozen lamps readable at a glance.</p>
 *
 * <p><b>Why it is gated on the editor being open.</b> A gizmo in a rendered shot is a bug, and a director
 * scrubbing a finished film should see lighting, not diagrams. Keying it to an open BBS screen means it is
 * present exactly while someone is authoring and gone the moment they are watching — no toggle to
 * remember, and nothing that can accidentally end up in an export.</p>
 */
public abstract class LightFormRenderer<T extends LightForm> extends FormRenderer<T>
{
    /** Enough segments that a circle reads as round at working distance without flooding the buffer. */
    protected static final int CIRCLE_SEGMENTS = 32;

    /**
     * Cell height from which the preview gets the full treatment. The morph picker's cell is 60&times;80
     * ({@code UIFormCategory.CELL_WIDTH/HEIGHT}); the replay list, the selector and the body part slots
     * all pass 40&times;40 (and the replay list then clips that to 40&times;20), so the preview has to
     * size itself off the cell rather than assume the big one.
     */
    private static final int LARGE_CELL = 56;

    /** Reused across preview renders (the Tessellator pattern) — allocating a BufferBuilder per
     * preview draw was steady garbage in the editor, in a project that pools lights to avoid churn. */
    private static final BufferBuilder PREVIEW_BUFFER = new BufferBuilder(2048);

    public LightFormRenderer(T form)
    {
        super(form);
    }

    /** Draw this light's wireframe into {@code buffer}, in form-local space. */
    protected abstract void buildGizmo(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a);

    /** The glyph this light is known by — the same one its editor tab wears. */
    protected abstract Icon icon();

    /**
     * The light's portrait in a UI list (the morph picker, the replay list, a body part slot).
     *
     * <p><b>A light has nothing to photograph.</b> Every other form can just draw itself into the cell;
     * a lamp's whole appearance is other surfaces getting brighter, so a preview has to say what the
     * lamp IS and what it would do. This draws the type glyph over a radial glow in the light's own
     * colour, its brightness following {@link LightForm#intensity} — a rig of lamps then reads at a
     * glance: warm ones glow warm, a dimmed one sits quiet, the shape says point or spot or panel.</p>
     *
     * <p>In a big cell the glyph is drawn at double size — an INTEGER factor, so the pixel art stays
     * crisp — because a 16px icon alone in a 60&times;80 cell reads as an afterthought. Small cells
     * keep it at 1:1.</p>
     */
    @Override
    protected final void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        Color color = this.form.effectiveColor();
        Icon icon = this.icon();

        int cell = Math.min(x2 - x1, y2 - y1);
        boolean large = cell >= LARGE_CELL;

        int rgb = color.getRGBColor() & 0xFFFFFF;
        int cx = (x1 + x2) / 2;
        int cy = (y1 + y2) / 2;

        /* Glow: full alpha at the centre, out to nothing at the radius. Alpha rides intensity on a
         * soft curve, so a fill light doesn't read like a searchlight in the list.
         *
         * ★The core offset is 0 on purpose. dropCircleShadow draws the core as a FLAT fan of that
         * radius and only then fades outwards, so any offset above zero prints a hard-edged glossy
         * disc in the middle of the glow — which is what BBS's own use of it passes zero for. */
        float punch = Math.min(1F, this.form.intensity.get() / 6F);
        int alpha = 0x28 + (int) (0x58 * punch);
        int radius = Math.max(6, Math.round(cell * 0.42F));

        context.batcher.dropCircleShadow(cx, cy, radius, 0, 32, (alpha << 24) | rgb, rgb);

        float glyph = large ? icon.w * 2F : icon.w;

        context.batcher.texturedBox(BBSModClient.getTextures().getTexture(icon.texture), readable(rgb),
            cx - glyph / 2F, cy - glyph / 2F, glyph, glyph,
            icon.x, icon.y, icon.x + icon.w, icon.y + icon.h, icon.textureW, icon.textureH);
    }

    /**
     * The lamp's colour, pulled toward the theme's opposite end so the glyph stays legible: a deep
     * blue lamp would otherwise print a nearly black glyph on BBS's dark surface, and a warm white one
     * would vanish into a light theme.
     */
    private static int readable(int rgb)
    {
        return 0xFF000000 | (mchorse.bbs_mod.BBSSettings.lightSurfaces()
            ? mix(rgb, 0x000000, 0.35F)
            : mix(rgb, 0xFFFFFF, 0.5F));
    }

    private static int mix(int rgb, int target, float factor)
    {
        int out = 0;

        for (int shift = 0; shift <= 16; shift += 8)
        {
            int a = (rgb >> shift) & 0xFF;
            int b = (target >> shift) & 0xFF;

            out |= (int) (a + (b - a) * factor) << shift;
        }

        return out;
    }

    /** Reused for the stencil grab handles (the Tessellator pattern) — the pick pass runs per UI
     * frame and deserves no steadier garbage than the previews get. */
    private static final BufferBuilder HANDLE_BUFFER = new BufferBuilder(2048);

    @Override
    protected void render3D(FormRenderingContext context)
    {
        /* Registering the light here rather than from a tree walk is what makes a lamp attached to a
         * BONE work at all — see FormLightCollector. */
        FormLightCollector.collect(this.form, context);

        /* Packs render entities AGAIN in the Iris shadow pass with the sun's ortho matrices: a gizmo
         * submitted from there lands in the sky as a duplicate wireframe (the Complementary /
         * Eclipse "floating fragment" report). */
        if (mchorse.bbs_mod.client.BBSRendering.isIrisShadowPass())
        {
            return;
        }

        /* The stencil pick pass renders this same form into the picking framebuffer, which is bound
         * RIGHT NOW — grab handles must be drawn inline here (deferring them to GizmoPass would paint
         * stencil colours into the world frame). Everything is solid triangles with the vanilla
         * position_color shader, the colour encoding the picking index the readback decodes — the
         * IRLite approach (qualet, MIT), which is what makes the handles draggable at all. */
        if (context.isPicking())
        {
            if (context.stencilMap != null && context.stencilMap.increment
                && (context.modelRenderer
                    || (context.type == mchorse.bbs_mod.forms.renderers.FormRenderType.ENTITY
                        && LightGuideDrag.isReplayEditorActive())))
            {
                BufferBuilder handles = HANDLE_BUFFER;

                com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
                /* The index is encoded in the colour bytes — a tinted modulator would corrupt it. */
                com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
                com.mojang.blaze3d.systems.RenderSystem.disableBlend();
                com.mojang.blaze3d.systems.RenderSystem.disableCull();
                com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
                com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();

                handles.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
                this.buildGrabHandles(handles, context.stack.peek().getPositionMatrix(), context);

                net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(handles.end());

                com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
                com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
                com.mojang.blaze3d.systems.RenderSystem.enableCull();
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            }

            return;
        }

        /* The drag solver intersects the mouse ray in form-local space — it needs this frame's matrix
         * even while the guide itself is gated away (film playing, F8 held). */
        if (context.modelRenderer || context.type == mchorse.bbs_mod.forms.renderers.FormRenderType.ENTITY)
        {
            LightGuideDrag.captureGuideMatrix(this.form, context.stack.peek().getPositionMatrix());
        }

        Color color = this.form.effectiveColor();

        if (context.ui || context.modelRenderer)
        {
            /* Effect preview BEFORE the wireframe: the gizmo says WHERE the lamp is, this says
             * WHAT it does — glow orb, ground pool on the preview's grid, the beam cone — all from
             * the live dials, so an edit shows the moment a trackpad moves. An approximation on
             * purpose: the preview has no scene and no depth, nothing here tries to be lighting. */
            BufferBuilder fx = PREVIEW_BUFFER;

            fx.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
            this.buildEffect(fx, context.stack.peek().getPositionMatrix());

            com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.blendFunc(
                com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE);
            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.disableCull();

            net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(fx.end());

            /* Preview contexts (the form editor's viewport, item renderer): draw inline, right here —
             * the deferred GizmoPass only understands world-render matrices; a preview matrix would
             * leak into the world frame. UNGATED by gizmosVisible(): the preview IS an editing
             * surface, and the world session gates (film panel state, the F8 axes toggle) have no
             * meaning there — with them applied, the gizmo silently vanished from the form editor
             * and dial edits showed no response at all (the "в редакторе не видно изменений"
             * report).
             *
             * Plain TRIANGLES with position_color, no GL_LINES: wide lines are clamped to 1px by most
             * drivers and left to the pack's mercy by Iris — ribbons and thin boxes rasterise the same
             * everywhere (the IRLite approach). */
            BufferBuilder buffer = PREVIEW_BUFFER;

            buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
            this.buildGizmo(buffer, context.stack.peek().getPositionMatrix(), color.r, color.g, color.b, 0.85F);

            com.mojang.blaze3d.systems.RenderSystem.setShader(net.minecraft.client.render.GameRenderer::getPositionColorProgram);
            com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1F, 1F, 1F, 1F);
            com.mojang.blaze3d.systems.RenderSystem.enableBlend();
            com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
            com.mojang.blaze3d.systems.RenderSystem.disableCull();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
            com.mojang.blaze3d.systems.RenderSystem.disableDepthTest();

            net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(buffer.end());

            com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
            com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
            com.mojang.blaze3d.systems.RenderSystem.enableCull();

            return;
        }

        /* World render: the session gates apply here (a gizmo must never leak into a film frame). */
        if (!gizmosVisible())
        {
            return;
        }

        /* World render: RECORD only — drawing happens in GizmoPass, either at world-render
         * AFTER_TRANSLUCENT (no dashboard) or in the UI pass over the film preview (dashboard
         * open), never mid-draw. */
        com.bbsvfx.vfxlights.client.render.GizmoPass.submit(this,
            new Matrix4f(context.stack.peek().getPositionMatrix()), color.r, color.g, color.b, 0.85F);
    }

    /** Emit this light's gizmo into {@code buffer} — called by {@code GizmoPass} at world-render LAST. */
    public final void drawGizmoInto(BufferBuilder buffer, Matrix4f matrix, float r, float g, float b, float a)
    {
        this.buildGizmo(buffer, matrix, r, g, b, a);
    }

    /**
     * Gizmos ride the whole EDITING session: every lamp's wireframe shows while the dashboard is
     * open and the film is NOT playing back — a gaffer places one lamp against the rest of the
     * rig, so hiding the rig while editing was worse than wire noise. Playback, export (custom
     * size) and the director's own axes toggle (F8 / hold-to-hide) still hide them outright.
     *
     * <p>Gizmos are suppressed during our own shadow pass: a light attached to an actor (bodypart)
     * would otherwise render its wireframe into the shadow atlas, carving holes in the world's shadows.</p>
     */
    protected boolean gizmosVisible()
    {
        return com.bbsvfx.vfxlights.VfxLightsModule.isEnabled()
            && MinecraftClient.getInstance().currentScreen instanceof UIScreen
            /* BBS's film viewport / export renders the world into its own framebuffer with the
             * custom-size flag up — gizmos must not leak into the film frame. */
            && !mchorse.bbs_mod.client.BBSRendering.isCustomSize()
            && !com.bbsvfx.vfxlights.client.shadow.ShadowMapper.isActive()
            && !com.bbsvfx.vfxlights.client.render.CharacterMask.isCapturing()
            && mchorse.bbs_mod.ui.framework.UIBaseMenu.shouldRenderAxes()
            && this.editingSession();
    }

    /** True while an editing surface that should show this light's gizmo is up. */
    private boolean editingSession()
    {
        mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard = mchorse.bbs_mod.BBSModClient.getDashboardIfCreated();

        if (dashboard == null)
        {
            return false;
        }

        /* With the dashboard current, the answer depends on WHICH panel is showing. The film editor
         * shows every lamp's wireframe (a gaffer places one lamp against the rest of the rig). The
         * model block panel — opened by right-clicking a block — shows only the lights of THAT
         * block; ModelBlockRenderTracker holds the position of the block whose form is being
         * rendered right now. Any other panel is not a lighting surface. */
        if (mchorse.bbs_mod.ui.framework.UIScreen.getCurrentMenu() == dashboard)
        {
            mchorse.bbs_mod.ui.dashboard.panels.UIDashboardPanel current = dashboard.getPanels().panel;

            if (current instanceof mchorse.bbs_mod.ui.model_blocks.UIModelBlockPanel modelPanel)
            {
                mchorse.bbs_mod.blocks.entities.ModelBlockEntity edited = modelPanel.getModelBlock();
                net.minecraft.util.math.BlockPos rendering =
                    com.bbsvfx.vfxlights.client.light.ModelBlockRenderTracker.get();

                return edited != null && rendering != null && rendering.equals(edited.getPos());
            }

            if (!(current instanceof mchorse.bbs_mod.ui.film.UIFilmPanel))
            {
                return false;
            }
        }

        return filmNotPlaying(dashboard);
    }

    /** True while the dashboard is up and the film is not playing back. */
    private static boolean filmNotPlaying(mchorse.bbs_mod.ui.dashboard.UIDashboard dashboard)
    {
        mchorse.bbs_mod.ui.film.UIFilmPanel panel = dashboard.getPanel(mchorse.bbs_mod.ui.film.UIFilmPanel.class);

        if (panel == null)
        {
            return false;
        }

        mchorse.bbs_mod.ui.film.controller.UIFilmController controller = panel.getController();

        /* isPlaying() dereferences the controller's UIContext — null whenever the controller isn't
         * attached (fresh dashboard, another panel active) — and a NullPointerException here once
         * escaped mid entity-render and surfaced a frame later as "Pose stack not empty". Detached
         * means "not playing": the dashboard is open and nothing runs. */
        return controller.getContext() == null || !controller.isPlaying();
    }

    /**
     * Invisible grab handles for mouse dragging, emitted into the stencil pick pass. Default none —
     * only the lights whose shape maps to a draggable handle (spot cone, point radius) override this.
     * Each handle: draw with the colour of {@code context.getPickingIndex()}, then register it with
     * {@code context.stencilMap.addPicking(form, handle)} so the readback can name what was grabbed.
     */
    protected void buildGrabHandles(BufferBuilder buffer, Matrix4f matrix, FormRenderingContext context)
    {
    }

    /* The stencil readback decodes an index straight out of the pixel's colour bytes. */
    protected static float stencilR(int index)
    {
        return (index & 255) / 255F;
    }

    protected static float stencilG(int index)
    {
        return ((index >> 8) & 255) / 255F;
    }

    protected static float stencilB(int index)
    {
        return ((index >> 16) & 255) / 255F;
    }

    /** A straight segment as a thin triangle box — GL_LINES survives no shaderpack. */
    protected static void line(BufferBuilder b, Matrix4f m, float x1, float y1, float z1,
        float x2, float y2, float z2, float r, float g, float bl, float a)
    {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float dz = z2 - z1;
        float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        com.bbsvfx.vfxlights.client.render.LightGuide.line(b, m, x1, y1, z1, x2, y2, z2,
            com.bbsvfx.vfxlights.client.render.LightGuide.wireThickness(length), r, g, bl, a);
    }

    /**
     * A circle of radius {@code radius} on the plane spanned by the two given unit axes, centred at the
     * given point — drawn as a flat triangle ribbon. Axes rather than a plane enum so the same routine
     * draws the three orthogonal rings of a sphere and the mouth of a cone.
     */
    protected static void circle(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius,
        float ax, float ay, float az, float bx, float by, float bz, float r, float g, float bl, float a)
    {
        com.bbsvfx.vfxlights.client.render.LightGuide.ring(b, m, cx, cy, cz, radius,
            com.bbsvfx.vfxlights.client.render.LightGuide.wireThickness(radius),
            ax, ay, az, bx, by, bz, r, g, bl, a);
    }

    /** Wireframe box centred on the origin, with the given half-extents. */
    protected static void box(BufferBuilder b, Matrix4f m, float hx, float hy, float hz,
        float r, float g, float bl, float a)
    {
        for (int i = 0; i < 4; i++)
        {
            float sy = (i & 1) == 0 ? -hy : hy;
            float sz = (i & 2) == 0 ? -hz : hz;

            line(b, m, -hx, sy, sz, hx, sy, sz, r, g, bl, a);
        }

        for (int i = 0; i < 4; i++)
        {
            float sx = (i & 1) == 0 ? -hx : hx;
            float sz = (i & 2) == 0 ? -hz : hz;

            line(b, m, sx, -hy, sz, sx, hy, sz, r, g, bl, a);
        }

        for (int i = 0; i < 4; i++)
        {
            float sx = (i & 1) == 0 ? -hx : hx;
            float sy = (i & 2) == 0 ? -hy : hy;

            line(b, m, sx, sy, -hz, sx, sy, hz, r, g, bl, a);
        }
    }

    /**
     * The lamp's EFFECT for editor previews: a glow orb at the source, the ground pool on the
     * preview's grid plane, and the beam cone for spots — every piece driven by the live dials
     * (colour, intensity, range, beam/haze), so the editor answers a tweak immediately. This is
     * deliberately an artist's approximation: the preview renders no world and no depth, so the
     * real lighting pipeline has nothing to work with there.
     */
    protected void buildEffect(BufferBuilder b, Matrix4f m)
    {
        Color color = this.form.effectiveColor();
        float r = color.r;
        float g = color.g;
        float bl = color.b;
        float intensity = this.form.intensity.get();
        float range = this.form.range.get();
        com.bbsvfx.vfxlights.forms.values.LightAir air = this.form.air.get();

        /* Glow orb: three axis-crossed gradient fans read as a soft ball from any angle without
         * camera math. Alpha follows intensity. */
        float glowA = Math.min(0.85F, 0.10F + intensity * 0.07F);
        float glowR = 0.45F + Math.min(intensity, 10F) * 0.05F;

        fan(b, m, 0F, 0F, 0F, glowR, 1F, 0F, 0F, 0F, 1F, 0F, r, g, bl, glowA);
        fan(b, m, 0F, 0F, 0F, glowR, 1F, 0F, 0F, 0F, 0F, 1F, r, g, bl, glowA);
        fan(b, m, 0F, 0F, 0F, glowR, 0F, 1F, 0F, 0F, 0F, 1F, r, g, bl, glowA);

        /* Haze halo: a wider, fainter ball when the fog dial is up. */
        if (air.haze > 0.001F)
        {
            float hazeR = Math.min(range * 0.6F, 8F);
            float hazeA = Math.min(0.22F, air.haze * 0.05F);

            fan(b, m, 0F, 0F, 0F, hazeR, 1F, 0F, 0F, 0F, 1F, 0F, r, g, bl, hazeA);
            fan(b, m, 0F, 0F, 0F, hazeR, 1F, 0F, 0F, 0F, 0F, 1F, r, g, bl, hazeA);
            fan(b, m, 0F, 0F, 0F, hazeR, 0F, 1F, 0F, 0F, 0F, 1F, r, g, bl, hazeA);
        }

        /* Ground pool on the preview's grid plane (y = 0 in preview space): transform the point
         * under the lamp into form-local so a rotated/translated lamp pools in the right place. */
        Matrix4f inv = new Matrix4f(m).invert();
        org.joml.Vector4f below = inv.transform(new org.joml.Vector4f(m.m30(), 0F, m.m32(), 1F));
        float poolR = Math.min(range, 24F);
        float poolA = Math.min(0.30F, 0.04F + intensity * 0.025F);

        fan(b, m, below.x, below.y + 0.02F, below.z, poolR, 1F, 0F, 0F, 0F, 0F, 1F, r, g, bl, poolA);

        /* The beam cone for a spot: apex at the lamp, opening along +Z like the gizmo. */
        if (this.form instanceof com.bbsvfx.vfxlights.forms.SpotLightForm spot && air.beam > 0.001F)
        {
            float length = Math.min(range, 12F);
            float mouth = (float) Math.tan(Math.toRadians(spot.angle.get() * 0.5F)) * length;
            float coneA = Math.min(0.28F, 0.05F + air.beam * 0.05F);

            coneFan(b, m, length, mouth, r, g, bl, coneA);
        }
    }

    /** Filled gradient fan: opaque-ish centre (alpha a) dissolving to nothing at the ring. Drawn
     * as QUADS with a doubled edge vertex — the caller draws with additive blending. */
    protected static void fan(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius,
        float ax, float ay, float az, float bx, float by, float bz, float r, float g, float bl, float a)
    {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++)
        {
            double a0 = i / (double) CIRCLE_SEGMENTS * Math.PI * 2D;
            double a1 = (i + 1) / (double) CIRCLE_SEGMENTS * Math.PI * 2D;

            float x0 = cx + ax * (float) Math.cos(a0) * radius + bx * (float) Math.sin(a0) * radius;
            float y0 = cy + ay * (float) Math.cos(a0) * radius + by * (float) Math.sin(a0) * radius;
            float z0 = cz + az * (float) Math.cos(a0) * radius + bz * (float) Math.sin(a0) * radius;
            float x1 = cx + ax * (float) Math.cos(a1) * radius + bx * (float) Math.sin(a1) * radius;
            float y1 = cy + ay * (float) Math.cos(a1) * radius + by * (float) Math.sin(a1) * radius;
            float z1 = cz + az * (float) Math.cos(a1) * radius + bz * (float) Math.sin(a1) * radius;

            b.vertex(m, cx, cy, cz).color(r, g, bl, a).next();
            b.vertex(m, x0, y0, z0).color(r, g, bl, 0F).next();
            b.vertex(m, x1, y1, z1).color(r, g, bl, 0F).next();
            b.vertex(m, x1, y1, z1).color(r, g, bl, 0F).next();
        }
    }

    /** Translucent cone surface from the origin to a mouth ring at +Z — the beam's preview. */
    private static void coneFan(BufferBuilder b, Matrix4f m, float length, float mouth,
        float r, float g, float bl, float a)
    {
        for (int i = 0; i < CIRCLE_SEGMENTS; i++)
        {
            double a0 = i / (double) CIRCLE_SEGMENTS * Math.PI * 2D;
            double a1 = (i + 1) / (double) CIRCLE_SEGMENTS * Math.PI * 2D;

            float x0 = (float) Math.cos(a0) * mouth;
            float y0 = (float) Math.sin(a0) * mouth;
            float x1 = (float) Math.cos(a1) * mouth;
            float y1 = (float) Math.sin(a1) * mouth;

            b.vertex(m, 0F, 0F, 0F).color(r, g, bl, a).next();
            b.vertex(m, x0, y0, length).color(r, g, bl, 0F).next();
            b.vertex(m, x1, y1, length).color(r, g, bl, 0F).next();
            b.vertex(m, x1, y1, length).color(r, g, bl, 0F).next();
        }
    }

    /** Three orthogonal rings — the conventional way to read a sphere as a wireframe. */
    protected static void sphere(BufferBuilder b, Matrix4f m, float cx, float cy, float cz, float radius,
        float r, float g, float bl, float a)
    {
        circle(b, m, cx, cy, cz, radius, 1F, 0F, 0F, 0F, 1F, 0F, r, g, bl, a);
        circle(b, m, cx, cy, cz, radius, 1F, 0F, 0F, 0F, 0F, 1F, r, g, bl, a);
        circle(b, m, cx, cy, cz, radius, 0F, 1F, 0F, 0F, 0F, 1F, r, g, bl, a);
    }
}
