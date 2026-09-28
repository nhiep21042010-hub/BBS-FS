package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.film.replays.tracks.TrackId;
import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.forms.BlockForm;
import mchorse.bbs_mod.forms.renderers.FormRenderer;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.forms.renderers.utils.FormColorBlend;
import mchorse.bbs_mod.graphics.Draw;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.block.BlockState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.EmptyBlockView;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.DestructionBoxForm;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Renders a {@link DestructionBoxForm}: each captured block is drawn at its shatter-displaced position,
 * so animating the form's {@code destruction} scatters the structure.
 *
 * <p>Two render paths for large structures:</p>
 * <ul>
 *   <li><b>GPU (default, vanilla)</b> — {@link DestructionVAO} bakes the structure into a static VBO once
 *   and the {@code destruction_box} vertex shader does the whole shatter (displacement + tumble) from
 *   uniforms, so animating is ~free on the CPU. Custom core shaders aren't picked up by Iris, so under an
 *   active shaderpack this path is skipped for the CPU path below.</li>
 *   <li><b>CPU (Iris fallback)</b> — draw every block via {@code renderBlockAsEntity} but (a) batch into a
 *   single {@code consumers.draw()} instead of BBS {@code BlockFormRenderer}'s per-block flush, and
 *   (b) occlusion-cull fully-enclosed static blocks (a solid cube collapses to its ~1-thick shell at rest;
 *   culling lifts as the shatter front exposes interior faces).</li>
 * </ul>
 */
public class DestructionBoxFormRenderer extends FormRenderer<DestructionBoxForm>
{
    /** Master switch for the GPU path (VBO + shatter vertex shader). Falls back to CPU under Iris. */
    public static boolean USE_GPU = true;

    /** Whether a block state is an opaque full cube (so it hides the neighbour behind it). Shared cache. */
    private static final Map<BlockState, Boolean> OCCLUDES = new HashMap<>();

    private final DestructionVAO vao = new DestructionVAO();
    private List<DestructionBlock> vaoList;
    private int vaoSize = -1;
    private int vaoSeed = Integer.MIN_VALUE;

    /** Reused only to source the default form colour/additive flag for the shared colour substitute. */
    private final BlockForm blockForm = new BlockForm();
    private final Color color = new Color();
    private final Vector3f scratch = new Vector3f();

    /* Occlusion lookup, rebuilt only when the block list changes: packed position -> block index, and a
     * parallel occluder flag per block. `progress` is refilled every frame (pass 1). */
    private final Long2IntOpenHashMap posToIndex = new Long2IntOpenHashMap();
    private boolean[] occludes = new boolean[0];
    private float[] progress = new float[0];
    private List<DestructionBlock> cachedList;
    private int cachedSize = -1;

    /* Physics mode. Bakes live in DestructionPhysics' GLOBAL content-keyed cache (BBS re-copies the
     * form to its entity on edits, killing renderer instances — per-renderer caches would rebake
     * constantly). Here we only keep: the blocks content hash (recomputed when the list instance
     * changes), a debounce for requesting new bakes, and the last bake we drew (fallback while the
     * worker simulates a new one). */
    private long physContentHash;
    private List<DestructionBlock> physContentList;
    private int physContentSize = -1;

    /**
     * The form the PHYSICS params are read from. The scene entity renders a COPY of the replay's form;
     * editing a param in the panel changes the ORIGINAL, the copy goes stale, and the bake key never
     * changed — the user had to delete/re-add a keyframe (forcing a re-copy) to see any edit. All
     * bake-input reads go through this (the replay's original form via the smear bridge); only the
     * animated {@code destruction} scrub is read from the copy (applyProperties drives it per frame).
     */
    private DestructionBoxForm physSrc;

    /* Live physics status for the editor panel (read by UIDestructionBoxFormPanel each frame). */
    public static final int PHYS_STATUS_NONE = 0;
    public static final int PHYS_STATUS_UNAVAILABLE = 1;
    public static final int PHYS_STATUS_BAKING = 2;
    public static final int PHYS_STATUS_BAKING_PREVIOUS = 3;
    public static final int PHYS_STATUS_READY = 4;

    public int physStatus = PHYS_STATUS_NONE;
    public int physStatusUnits;
    public int physStatusSteps;
    public float physStatusDuration;

    private DestructionBoxForm physSourceForm(FormRenderingContext context)
    {
        if (context.entity != null && SmearReplayState.has(context.entity)
            && SmearReplayState.replay.form.get() instanceof DestructionBoxForm original)
        {
            return original;
        }

        return this.form;
    }
    /* LOD tier split cache: tier A = the physMaxBodies blocks nearest the point (simulated), tier C =
     * the far rest (ballistic/static shell). Recomputed when the list, its size, the cap or the point
     * change; the arrays are only read while a cap is active. */
    private List<DestructionBlock> tierAList;
    private int[] tierCIdx;
    private float tierMinY;
    private List<DestructionBlock> tierSrcList;
    private int tierSrcSize = -1;
    private int tierSrcCap;
    private float tierPx, tierPy, tierPz;

    private long physWantKey;
    private long physWantSince = -1L;
    private DestructionPhysics.Bake physLast;
    private float[] physPose = new float[0];
    private boolean[] physUnitMoved = new boolean[0];
    private final Quaternionf physRot = new Quaternionf();

    public DestructionBoxFormRenderer(DestructionBoxForm form)
    {
        super(form);
        this.posToIndex.defaultReturnValue(-1);
    }

    @Override
    protected void renderInUI(UIContext context, int x1, int y1, int x2, int y2)
    {
        /* v1: no thumbnail. */
    }

    @Override
    protected void render3D(FormRenderingContext context)
    {
        /* The IRIS SHADOW PASS is NOT skipped anymore — the structure/foliage render into the pack's
         * shadow map with the same vanilla-program recipe BBS models use ("no shadows from the blocks"
         * report). Only the FX overlays (fire/smoke/dust quads) stay out of it — see renderExplosionFx. */
        if (context.stencilMap != null)
        {
            return;
        }

        List<DestructionBlock> blocks = this.form.blocks.getAllTyped();

        if (blocks.isEmpty())
        {
            return;
        }

        /* The whole structure is lit by ONE sample — by default the actor's, taken at its own position
         * = the box MIN CORNER, which can sit buried in terrain (a feathered capture leaves corner
         * blocks in the world) → the entire structure renders black. Sample the sky-facing CENTRE-TOP
         * of the box instead; the base FormRenderer.render restores context.light afterwards. */
        if (context.type != FormRenderType.PREVIEW && context.entity != null)
        {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();

            if (mc.world != null)
            {
                org.joml.Vector3f bounds = this.localBoundsCenterTop(blocks);
                org.joml.Vector3f ft = this.form.transform.get().translate;
                net.minecraft.util.math.BlockPos sample = net.minecraft.util.math.BlockPos.ofFloored(
                    context.entity.getX() + ft.x + bounds.x,
                    context.entity.getY() + ft.y + bounds.y,
                    context.entity.getZ() + ft.z + bounds.z);

                context.light = net.minecraft.client.render.WorldRenderer.getLightmapCoordinates(mc.world, sample);
            }
        }

        /* Physics mode renders from the PhysX bake (CPU batched path; per-block transforms can't be
         * shader uniforms). Falls through to the parametric paths when PhysX is unavailable. The MODE
         * gate reads the SOURCE form (see physSourceForm) so toggling it applies without re-copying. */
        if (this.physSourceForm(context).physicsMode.get() && this.renderPhysics(context, blocks))
        {
            return;
        }

        /* GPU shatter path (vanilla only): a custom core shader isn't picked up by Iris, so under an
         * active shaderpack fall back to the CPU path (batching + occlusion culling, which Iris handles). */
        if (USE_GPU && DestructionShader.PROGRAM != null && !BBSRendering.isIrisShadersEnabled())
        {
            this.renderGpu(context, blocks);
            return;
        }

        this.ensureOcclusionCache(blocks);

        Vector3f center = this.form.center();
        Vector3f point = this.form.point();
        float maxDist = this.form.maxDistanceToPoint(point);
        MatrixStack stack = context.stack;

        /* Pass 1: each block's shatter progress (0 = still on its original spot). Needed up front so the
         * culling test below can see whether a neighbour has moved. */
        for (int i = 0; i < blocks.size(); i++)
        {
            DestructionBlock block = blocks.get(i);

            this.scratch.set(block.x.get(), block.y.get(), block.z.get());
            this.progress[i] = this.form.localProgress(this.scratch, point, maxDist);
        }

        /* One colour substitute for the whole structure (all blocks share the form's tint) — set once,
         * not per block. Mirrors BBS BlockFormRenderer.render3D's colour handling. */
        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

        this.color.set(context.color);
        FormColorBlend.blend(this.color, this.blockForm.color.get());
        consumers.setSubstitute(BBSRendering.getColorConsumer(this.color));

        /* BlockFormRenderer enables blend per drawn layer via this hijack; set it once for the batch. */
        CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());

        int light = context.light;
        int overlay = context.overlay;

        /* Pass 2: render every block that isn't fully hidden. */
        for (int i = 0; i < blocks.size(); i++)
        {
            DestructionBlock block = blocks.get(i);
            int bx = block.x.get(), by = block.y.get(), bz = block.z.get();

            if (this.progress[i] == 0F && this.isHidden(bx, by, bz))
            {
                continue;
            }

            this.scratch.set(bx, by, bz);
            Vector3f p = this.form.displacedPosition(i, this.scratch, center, point, this.progress[i]);
            boolean shattered = this.progress[i] > 0F;

            stack.push();

            /*
             * BBS's BlockForm renderer itself translates by (-0.5, 0, -0.5) before drawing the [0,1]
             * cube, i.e. it centres the block on X/Z but anchors Y at the bottom. To line the structure
             * up exactly with the original MC blocks we pivot at the block's true centre (p + 0.5 on all
             * axes), apply the tumble there (spins in place), then translate (0, -0.5, 0) plus BlockForm's
             * own (-0.5, 0, -0.5) — combined to (-0.5, -0.5, -0.5) — so the cube lands back on that centre.
             */
            stack.translate(p.x + 0.5F, p.y + 0.5F, p.z + 0.5F);

            if (shattered)
            {
                Quaternionf spin = this.form.blockSpin(i, this.progress[i]);

                stack.multiply(spin);
            }

            stack.translate(-0.5F, -0.5F, -0.5F);

            /* Per-block light captured pre-cut; -1 = old save → the single actor sample. */
            int bLight = block.light.get() >= 0 ? block.light.get() : light;

            DestructionBlockDraw.draw(block.blockState(), block.tint.get(), block.blockState().getRenderingSeed(new net.minecraft.util.math.BlockPos(block.x.get(), block.y.get(), block.z.get())), stack.peek(), consumers, bLight, overlay);

            stack.pop();
        }

        /* Single flush for the whole structure. */
        consumers.draw();
        consumers.setSubstitute(null);
        CustomVertexConsumerProvider.clearRunnables();
        RenderSystem.enableDepthTest();

        /* In the form editor preview only, draw a marker so the attractor point is visible/placeable. */
        if (context.type == FormRenderType.PREVIEW)
        {
            float s = 0.35F;

            Draw.renderBox(stack, point.x - s, point.y - s, point.z - s, s * 2F, s * 2F, s * 2F, 0.2F, 0.9F, 1F);
        }
    }

    /**
     * Physics-mode render: ensure the PhysX bake is current (debounced against slider drags), sample
     * every block's centre+rotation at {@code destruction}, then draw with the same batched CPU path
     * (single colour substitute, single flush) and the same occlusion culling for still-intact blocks.
     * Returns false when PhysX is unavailable so the caller falls back to the parametric modes.
     */
    private boolean renderPhysics(FormRenderingContext context, List<DestructionBlock> blocks)
    {
        if (!DestructionPhysics.available())
        {
            this.physStatus = PHYS_STATUS_UNAVAILABLE;

            return false;
        }

        /* Read all bake params from the SOURCE form (the replay's original — the entity's copy goes
         * stale on panel edits); only the animated destruction scrub comes from the copy. */
        this.physSrc = this.physSourceForm(context);

        if (this.physSrc != this.form)
        {
            blocks = this.physSrc.blocks.getAllTyped();
        }

        /* LOD tier split: with a body cap, only the blocks NEAREST the point (epicenter) are simulated
         * (tier A); the far bulk (tier C) flies on closed-form ballistics — GPU-instanced on vanilla, a
         * static rest shell under Iris. This is what keeps 50-100k-block scans real-time. */
        List<DestructionBlock> allBlocks = blocks;
        int tierCap = this.physSrc.physMaxBodies.get();
        int[] tierC = null;

        if (tierCap > 0 && blocks.size() > tierCap)
        {
            this.ensureTierSplit(blocks, tierCap);
            tierC = this.tierCIdx;
            blocks = this.tierAList;
        }

        /* World collision needs the structure's world placement = the actor position. The form-editor
         * preview has no actor, so it bakes plane-only (different cache key). */
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        boolean withWorld = this.physSrc.physWorld.get()
            && context.type != FormRenderType.PREVIEW
            && context.entity != null
            && mc.world != null;

        long key = this.physKey(blocks, withWorld, context);
        DestructionPhysics.Bake bake = DestructionPhysics.cached(key);
        boolean fresh = bake != null;

        if (bake == null)
        {
            /* Not baked yet: debounce the request (a slider drag changes the key every frame — wait for
             * it to sit still), EXCEPT the very first bake which fires immediately. The worker simulates
             * off-thread; meanwhile we draw the previous bake, or the structure at rest. */
            long now = System.currentTimeMillis();

            if (key != this.physWantKey)
            {
                this.physWantKey = key;
                this.physWantSince = now;
            }

            if ((this.physLast == null || now - this.physWantSince > 350L) && !DestructionPhysics.isPending(key))
            {
                DestructionPhysics.request(key, this.physInput(blocks, allBlocks, withWorld, context, mc));
            }

            bake = this.physLast != null && this.physLast.count == blocks.size() ? this.physLast : null;
        }

        this.physLast = bake;

        this.physStatus = bake == null
            ? PHYS_STATUS_BAKING
            : (fresh ? PHYS_STATUS_READY : PHYS_STATUS_BAKING_PREVIOUS);

        if (bake != null)
        {
            this.physStatusUnits = bake.units;
            this.physStatusSteps = bake.steps;
            this.physStatusDuration = bake.duration;
        }

        this.ensureOcclusionCache(blocks);

        float t = this.form.destruction.get();
        MatrixStack stack = context.stack;
        Vector3f pos = this.scratch;

        /* Tier C (LOD far bulk): one instanced ballistic draw on vanilla; a build-once static rest
         * shell under Iris (custom core shaders aren't picked up by packs). Time axis matches the sim:
         * destruction 0..1 spans the bake's settled duration. */
        if (tierC != null && tierC.length > 0)
        {
            float simTime = t * (bake != null ? bake.duration : this.physSrc.physDuration.get());
            DestructionBallisticVAO ballistic = null;

            if (!BBSRendering.isIrisShadersEnabled() && DestructionShader.BALLISTIC != null)
            {
                /* Terrain-aware landing shares the world-collision gating (and the actor origin) with
                 * the sim — the form-editor preview and world-collision-off fall back to the plane. */
                org.joml.Vector3f ft = this.physSrc.transform.get().translate;
                double ox = withWorld ? context.entity.getX() + ft.x : 0D;
                double oy = withWorld ? context.entity.getY() + ft.y : 0D;
                double oz = withWorld ? context.entity.getZ() + ft.z : 0D;

                ballistic = DestructionBallisticVAO.of(
                    this.physBallisticKey(allBlocks, withWorld, ox, oy, oz), this.physSrc, allBlocks, tierC,
                    this.tierMinY, withWorld ? mc.world : null, ox, oy, oz);
            }

            if (ballistic != null)
            {
                ballistic.render(stack, simTime, this.physSrc.physGravity.get(),
                    this.physSrc.physBounciness.get(), context.light, context.overlay);
            }
            else
            {
                /* Iris, no instancing support, or a failed build — the far bulk stays a static shell. */
                DestructionStaticVAO shell = DestructionStaticVAO.of(this.physStaticKey(allBlocks), allBlocks, tierC);

                if (shell != null)
                {
                    this.color.set(context.color);
                    FormColorBlend.blend(this.color, this.blockForm.color.get());
                    shell.render(stack, this.color.r, this.color.g, this.color.b, this.color.a, context.light, context.overlay);
                }
            }
        }

        /* Units: whole blocks and (with sub-block shatter) the 8 sectors of a shattered block. */
        int unitCount = bake != null ? bake.units : blocks.size();

        if (this.physPose.length < unitCount * 7)
        {
            this.physPose = new float[unitCount * 7];
        }

        if (this.physUnitMoved.length < unitCount)
        {
            this.physUnitMoved = new boolean[unitCount];
        }

        java.util.Arrays.fill(this.progress, 0, blocks.size(), 0F);

        /* Pass 1: sample the bake (or the rest pose while the worker is still simulating); per-block
         * progress (any unit moved) feeds the culling test. */
        for (int u = 0; u < unitCount; u++)
        {
            int bi = bake != null ? bake.unitBlock[u] : u;
            int oct = bake != null ? bake.unitOctant[u] : -1;
            DestructionBlock block = blocks.get(bi);
            int o = u * 7;

            float rx = block.x.get() + (oct < 0 ? 0.5F : (oct & 1) == 0 ? 0.25F : 0.75F);
            float ry = block.y.get() + (oct < 0 ? 0.5F : (oct & 2) == 0 ? 0.25F : 0.75F);
            float rz = block.z.get() + (oct < 0 ? 0.5F : (oct & 4) == 0 ? 0.25F : 0.75F);

            if (bake == null)
            {
                this.physPose[o] = rx;
                this.physPose[o + 1] = ry;
                this.physPose[o + 2] = rz;
                this.physPose[o + 3] = 0F;
                this.physPose[o + 4] = 0F;
                this.physPose[o + 5] = 0F;
                this.physPose[o + 6] = 1F;
                this.physUnitMoved[u] = false;

                continue;
            }

            bake.sample(u, t, pos, this.physRot);

            this.physPose[o] = pos.x;
            this.physPose[o + 1] = pos.y;
            this.physPose[o + 2] = pos.z;
            this.physPose[o + 3] = this.physRot.x;
            this.physPose[o + 4] = this.physRot.y;
            this.physPose[o + 5] = this.physRot.z;
            this.physPose[o + 6] = this.physRot.w;

            float dx = pos.x - rx;
            float dy = pos.y - ry;
            float dz = pos.z - rz;
            boolean moved = dx * dx + dy * dy + dz * dz > 1e-6F || Math.abs(this.physRot.w) < 0.99999F;

            this.physUnitMoved[u] = moved;

            if (moved)
            {
                this.progress[bi] = 1F;
            }
        }

        /* Fast path: one CPU transform loop into a VBO + a single draw with the VANILLA entity shader
         * (Iris swaps in the pack's program → works under shaderpacks, unlike a custom core shader).
         * The per-block consumer-chain path below stays for the rest pose / VAO build failures. */
        if (DestructionPhysVAO.USE_VAO && bake != null)
        {
            DestructionPhysVAO vaoPath = DestructionPhysVAO.of(bake, blocks);

            if (vaoPath != null)
            {
                this.color.set(context.color);
                FormColorBlend.blend(this.color, this.blockForm.color.get());
                vaoPath.render(this.physPose, unitCount, stack, this.color.r, this.color.g, this.color.b, this.color.a,
                    context.light, context.overlay);

                this.renderExplosionFx(context, allBlocks, bake, t, stack);

                if (context.type == FormRenderType.PREVIEW)
                {
                    Vector3f point = this.physSrc.point();
                    float s = 0.35F;

                    Draw.renderBox(stack, point.x - s, point.y - s, point.z - s, s * 2F, s * 2F, s * 2F, 1F, 0.6F, 0.2F);
                }

                return true;
            }
        }

        CustomVertexConsumerProvider consumers = FormUtilsClient.getProvider();

        this.color.set(context.color);
        FormColorBlend.blend(this.color, this.blockForm.color.get());
        consumers.setSubstitute(BBSRendering.getColorConsumer(this.color));
        CustomVertexConsumerProvider.hijackVertexFormat((l) -> RenderSystem.enableBlend());

        int light = context.light;
        int overlay = context.overlay;

        for (int u = 0; u < unitCount; u++)
        {
            int bi = bake != null ? bake.unitBlock[u] : u;
            int oct = bake != null ? bake.unitOctant[u] : -1;
            DestructionBlock block = blocks.get(bi);

            /* Blocks with all units at rest cull like intact blocks (8 assembled sectors look whole). */
            if (this.progress[bi] == 0F && this.isHidden(block.x.get(), block.y.get(), block.z.get()))
            {
                continue;
            }

            int o = u * 7;

            stack.push();

            /* The bake stores unit CENTRES; same centre-pivot dance as the parametric CPU path
             * (BlockForm's own renderer offsets by (-0.5, 0, -0.5) with Y anchored at the bottom). */
            stack.translate(this.physPose[o], this.physPose[o + 1], this.physPose[o + 2]);

            if (this.physUnitMoved[u])
            {
                this.physRot.set(this.physPose[o + 3], this.physPose[o + 4], this.physPose[o + 5], this.physPose[o + 6]);
                stack.multiply(this.physRot);
            }

            /* Per-block light captured pre-cut; -1 = old save → the single actor sample. */
            int bLight = block.light.get() >= 0 ? block.light.get() : light;

            if (oct >= 0 && DestructionSectorDraw.canDraw(block.blockState()))
            {
                /* Honest sector: move to BLOCK-space origin (the unit centre is the octant centre) and
                 * emit the octant's UV-cropped piece of the block's own texture. */
                stack.translate(
                    -((oct & 1) == 0 ? 0.25F : 0.75F),
                    -((oct & 2) == 0 ? 0.25F : 0.75F),
                    -((oct & 4) == 0 ? 0.25F : 0.75F));
                DestructionSectorDraw.draw(block.blockState(), block.tint.get(), oct, stack.peek(), consumers, bLight, overlay);
                stack.pop();

                continue;
            }

            if (oct >= 0)
            {
                /* Fallback for non-full-cube models: the whole block model at half scale. The scale
                 * also halves the -0.5 centring offset below. */
                stack.scale(0.5F, 0.5F, 0.5F);
            }

            stack.translate(-0.5F, -0.5F, -0.5F);

            DestructionBlockDraw.draw(block.blockState(), block.tint.get(), block.blockState().getRenderingSeed(new net.minecraft.util.math.BlockPos(block.x.get(), block.y.get(), block.z.get())), stack.peek(), consumers, bLight, overlay);

            stack.pop();
        }

        consumers.draw();
        consumers.setSubstitute(null);
        CustomVertexConsumerProvider.clearRunnables();
        RenderSystem.enableDepthTest();

        this.renderExplosionFx(context, allBlocks, bake, t, stack);

        /* Editor preview only: the explosion epicenter marker (same as the point-mode marker). */
        if (context.type == FormRenderType.PREVIEW)
        {
            Vector3f point = this.physSrc.point();
            float s = 0.35F;

            Draw.renderBox(stack, point.x - s, point.y - s, point.z - s, s * 2F, s * 2F, s * 2F, 1F, 0.6F, 0.2F);
        }

        return true;
    }

    /* FX support: site k of the fire = tier-A subset block k (same distance sort), so flames can ride
     * the baked debris; block → first-unit map cached per bake. */
    private DestructionPhysics.Bake fxBake;
    private int[] fxFirstUnit;

    /** Fire + smoke + dust overlay of the explosion form — same scrub clock as the debris. */
    private void renderExplosionFx(FormRenderingContext context, List<DestructionBlock> allBlocks,
        DestructionPhysics.Bake bake, float t, MatrixStack stack)
    {
        if (!(this.physSrc instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion))
        {
            return;
        }

        float simTime = t * (bake != null ? bake.duration : this.physSrc.physDuration.get());
        double ox = 0D, oy = 0D, oz = 0D;

        if (context.entity != null)
        {
            org.joml.Vector3f ft = this.physSrc.transform.get().translate;

            ox = context.entity.getX() + ft.x;
            oy = context.entity.getY() + ft.y;
            oz = context.entity.getZ() + ft.z;

            /* The "Rescan foliage" button needs the actor's true WORLD origin — the form editor's
             * PREVIEW renders with a dummy entity at ~0,0,0 and was overwriting the note every frame
             * (the rescan then scanned unloaded world around the origin: "0/180917 columns loaded"). */
            if (context.type != FormRenderType.PREVIEW)
            {
                DestructionCapture.noteOrigin(explosion, ox, oy, oz);
            }
        }

        if (bake != null && this.fxBake != bake)
        {
            this.fxFirstUnit = new int[bake.count];
            java.util.Arrays.fill(this.fxFirstUnit, -1);

            for (int u = 0; u < bake.units; u++)
            {
                int b = bake.unitBlock[u];

                if (this.fxFirstUnit[b] < 0)
                {
                    this.fxFirstUnit[b] = u;
                }
            }

            this.fxBake = bake;
        }

        /* Environment bend: the captured foliage proxies (trees pivot at roots, plants at bases),
         * swaying on the wind front's damped oscillator. Drawn even at simTime 0 — the proxies REPLACE
         * the cut real blocks, so at rest they must simply look like the world did. */
        ExplosionFoliageVAO foliage = ExplosionFoliageVAO.of(explosion);

        if (foliage != null)
        {
            this.color.set(context.color);
            FormColorBlend.blend(this.color, this.blockForm.color.get());
            foliage.render(explosion, simTime, stack, this.color.r, this.color.g, this.color.b, this.color.a,
                context.light, context.overlay);

            /* Leaf shed + birds off the captured trees (world placement only — the bursts go through
             * the deferred particle pass). */
            if (context.entity != null && simTime > 0F && !BBSRendering.isIrisShadowPass())
            {
                BbsVfxExplosionFx.renderFoliageBursts(explosion, foliage, simTime, ox, oy, oz, context.light);
            }
        }

        /* The fire/smoke/dust overlay must NOT render into the pack's shadow map (flames casting
         * shadows); only the solid geometry above does. */
        if (!BBSRendering.isIrisShadowPass())
        {
            BbsVfxExplosionFx.render(explosion, allBlocks, this.physBlocksContentHash(allBlocks),
                simTime, stack, context.light, ox, oy, oz, context.entity != null,
                bake, bake != null ? this.fxFirstUnit : null, bake != null ? this.physPose : null);
        }
    }

    /**
     * The global cache key: block CONTENT (not list identity — BBS copies the form, and the copy's bake
     * is byte-identical) plus every bake-relevant param (NOT destruction — that's the scrub), plus the
     * actor position when world colliders are in play.
     */
    /** Bump when BAKE-AFFECTING logic changes (unit layout, shatter selection, collider building...) —
     *  the content key can't see code, so a stale PERSISTED bake would otherwise resurrect the old
     *  behaviour (e.g. pre-gate trapdoor shatter duplicates surviving the non-full-cube fix). */
    private static final int BAKE_LOGIC_VERSION = 6;

    private long physKey(List<DestructionBlock> blocks, boolean withWorld, FormRenderingContext context)
    {
        long h = this.physBlocksContentHash(blocks);

        h = h * 31 + BAKE_LOGIC_VERSION;

        h = h * 31 + this.physSrc.seed.get();
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirYaw.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirPitch.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirStrength.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.radialStrength.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.randomAmount.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.rotationAmount.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physDuration.get());
        h = h * 31 + this.physSrc.physMaxBodies.get();
        h = h * 31 + this.physGravityKeyHash(context);

        /* Wind (explosion form): the epicenter is hashed here too — the explosion-strength condition
         * below skips it when only the wind uses the point. */
        if (this.physSrc instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion
            && (explosion.windStrength.get() != 0F || explosion.windAmbientStrength.get() != 0F))
        {
            h = h * 31 + Float.floatToIntBits(explosion.windStrength.get());
            h = h * 31 + Float.floatToIntBits(explosion.windFrontSpeed.get());
            h = h * 31 + Float.floatToIntBits(explosion.windDecay.get());
            h = h * 31 + Float.floatToIntBits(explosion.windAmbientYaw.get());
            h = h * 31 + Float.floatToIntBits(explosion.windAmbientStrength.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointX.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointY.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointZ.get());
        }
        h = h * 31 + Float.floatToIntBits(this.physSrc.physFriction.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physBounciness.get());
        h = h * 31 + (withWorld ? 1 : 0);
        h = h * 31 + DestructionPhysics.rebakeNonce;

        float explosion = this.physSrc.physExplosionStrength.get();
        boolean wave = this.physSrc.physWave.get();

        h = h * 31 + Float.floatToIntBits(explosion);
        h = h * 31 + (wave ? 1 : 0);
        h = h * 31 + (this.physSrc.physSupport.get() ? 1 : 0);

        int clusterSize = this.physSrc.physClusterSize.get();

        h = h * 31 + clusterSize;

        if (clusterSize > 1)
        {
            h = h * 31 + Float.floatToIntBits(this.physSrc.physClusterStrength.get());
        }

        float shatter = this.physSrc.physShatter.get();

        h = h * 31 + Float.floatToIntBits(shatter);

        if (shatter > 0F)
        {
            h = h * 31 + Float.floatToIntBits(this.physSrc.physShatterStrength.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionRadius.get());
        }

        if (wave)
        {
            h = h * 31 + Float.floatToIntBits(this.physSrc.physWaveTime.get());
            h = h * 31 + (this.physSrc.invertOrder.get() ? 1 : 0);
        }

        if (explosion != 0F || wave || this.physSrc.physSupport.get() || this.physSrc.physShatter.get() > 0F)
        {
            /* The epicenter/radius/cone only shape the sim while the explosion is on — keying them
             * unconditionally would rebake on every gizmo drag of an inactive point. */
            h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionRadius.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionCone.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointX.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointY.get());
            h = h * 31 + Float.floatToIntBits(this.physSrc.pointZ.get());
        }

        if (withWorld)
        {
            /* World-only inputs: keying them into the plane-only preview bake would rebake it for no
             * reason on every margin drag. Position quantized so sub-block actor jitter doesn't thrash
             * the bake; a real move does rebake. */
            h = h * 31 + Float.floatToIntBits(this.physSrc.physWorldMargin.get());
            /* Whole-block quantization: sub-block interpolation noise on the entity position must not
             * flip the key (a flipping key = two hot cache entries + eviction ping-pong = bake loop).
             * The form's TRANSFORM translate is part of the world placement too — moving the box via
             * the transform track/gizmo after capture must re-bake with colliders at the new spot
             * (tester request: "rebake automatically based on its current transform values"). */
            org.joml.Vector3f ft = this.physSrc.transform.get().translate;

            h = h * 31 + Math.round(context.entity.getX() + ft.x);
            h = h * 31 + Math.round(context.entity.getY() + ft.y);
            h = h * 31 + Math.round(context.entity.getZ() + ft.z);
        }
        else
        {
            /* The ground plane only exists in the plane-only bake. */
            h = h * 31 + (this.physSrc.physGround.get() ? 1 : 0);
        }

        return h;
    }

    /**
     * The gravity part of the bake key. When the replay KEYFRAMES gravity, `applyProperties` writes a
     * different interpolated value into the form every frame — hashing the form value would rebake
     * per frame (the "structure jerks at the gravity key" bug). Instead hash the CHANNEL's keyframes
     * (the whole curve is baked into one sim); the static form value only counts when unkeyframed.
     */
    private long physGravityKeyHash(FormRenderingContext context)
    {
        mchorse.bbs_mod.utils.keyframes.KeyframeChannel channel = this.physGravityChannel(context);

        if (channel == null)
        {
            return Float.floatToIntBits(this.physSrc.physGravity.get());
        }

        long h = 7L;

        for (Object o : channel.getKeyframes())
        {
            mchorse.bbs_mod.utils.keyframes.Keyframe kf = (mchorse.bbs_mod.utils.keyframes.Keyframe) o;

            h = h * 31 + Float.floatToIntBits(kf.getTick());
            h = h * 31 + Float.floatToIntBits(((Number) kf.getValue()).floatValue());
        }

        return h;
    }

    /** The replay's keyframed phys_gravity channel for THIS entity, or null (no replay / not keyframed). */
    private mchorse.bbs_mod.utils.keyframes.KeyframeChannel physGravityChannel(FormRenderingContext context)
    {
        if (context.entity == null || !SmearReplayState.has(context.entity))
        {
            return null;
        }

        mchorse.bbs_mod.utils.keyframes.KeyframeChannel channel = SmearReplayState.replay.properties.get(TrackId.property("", "phys_gravity"));

        return channel == null || channel.isEmpty() ? null : channel;
    }

    /**
     * Sample the keyframed gravity over the sim: the destruction channel's 0→1 ramp defines which film
     * ticks the sim spans, and the gravity curve is read across exactly that range. One sample per step.
     */
    private float[] physGravityCurve(FormRenderingContext context, int samples)
    {
        mchorse.bbs_mod.utils.keyframes.KeyframeChannel gravity = this.physGravityChannel(context);

        if (gravity == null)
        {
            return null;
        }

        /* The sim's film-time span: the destruction ramp (last tick at the minimum before the first tick
         * of the maximum). Fallback = the gravity channel's own keyframe span. */
        float t0, t1;
        mchorse.bbs_mod.utils.keyframes.KeyframeChannel destruction = SmearReplayState.replay.properties.get(TrackId.property("", "destruction"));

        if (destruction != null && !destruction.isEmpty() && destruction.getKeyframes().size() >= 2)
        {
            java.util.List<?> kfs = destruction.getKeyframes();

            t0 = ((mchorse.bbs_mod.utils.keyframes.Keyframe) kfs.get(0)).getTick();
            t1 = ((mchorse.bbs_mod.utils.keyframes.Keyframe) kfs.get(kfs.size() - 1)).getTick();
        }
        else
        {
            java.util.List<?> kfs = gravity.getKeyframes();

            t0 = ((mchorse.bbs_mod.utils.keyframes.Keyframe) kfs.get(0)).getTick();
            t1 = ((mchorse.bbs_mod.utils.keyframes.Keyframe) kfs.get(kfs.size() - 1)).getTick();
        }

        float[] curve = new float[Math.max(2, samples)];

        for (int i = 0; i < curve.length; i++)
        {
            float tick = t0 + (t1 - t0) * (i / (float) (curve.length - 1));

            curve[i] = ((Number) gravity.interpolate(tick)).floatValue();
        }

        return curve;
    }

    /** Stable per-state ids for the content hash — identityHashCode changes every JVM run, which would
     * orphan every PERSISTED bake on restart; the state string is stable given the same mod set. */
    private static final Map<net.minecraft.block.BlockState, Integer> STATE_IDS = new java.util.IdentityHashMap<>();

    private static int physStateId(net.minecraft.block.BlockState state)
    {
        Integer cached = STATE_IDS.get(state);

        if (cached == null)
        {
            cached = String.valueOf(state).hashCode();
            STATE_IDS.put(state, cached);
        }

        return cached;
    }

    /** Content hash of the captured blocks, cached by list instance. Must stay STABLE across sessions
     * (it keys the on-disk bakes). */
    private long physBlocksContentHash(List<DestructionBlock> blocks)
    {
        if (blocks == this.physContentList && blocks.size() == this.physContentSize)
        {
            return this.physContentHash;
        }

        long h = 1L;

        for (DestructionBlock block : blocks)
        {
            h = h * 31 + block.x.get();
            h = h * 31 + block.y.get();
            h = h * 31 + block.z.get();
            h = h * 31 + physStateId(block.blockState());
        }

        this.physContentList = blocks;
        this.physContentSize = blocks.size();
        this.physContentHash = h;

        return h;
    }

    /**
     * Snapshot everything the worker-thread simulation needs (it must not touch the form or the world):
     * rest positions, launch velocities, and the static colliders. With world collision the real blocks
     * around the actor become the colliders and the ground plane is SKIPPED — debris must land on the
     * actual terrain, not on an invisible floor at the structure's base (plane stays as the fallback for
     * the form-editor preview / world collision off).
     */
    /**
     * Split the structure for the LOD cap: sort every block by distance² to the point (epicenter) and
     * keep the nearest {@code cap} as tier A (the PhysX sim); the rest become tier C indices (into the
     * full list). Also finds the structure's minY — tier C's ballistic landing plane.
     */
    private void ensureTierSplit(List<DestructionBlock> blocks, int cap)
    {
        Vector3f point = this.physSrc.point();

        if (blocks == this.tierSrcList && blocks.size() == this.tierSrcSize && cap == this.tierSrcCap
            && point.x == this.tierPx && point.y == this.tierPy && point.z == this.tierPz)
        {
            return;
        }

        int n = blocks.size();

        /* Index sort without boxing: high bits = distance² float bits (monotonic for non-negative
         * floats), low 20 bits = index (cap 200k blocks < 1M). */
        long[] keys = new long[n];
        float minY = Float.POSITIVE_INFINITY;

        for (int i = 0; i < n; i++)
        {
            DestructionBlock block = blocks.get(i);
            float dx = block.x.get() + 0.5F - point.x;
            float dy = block.y.get() + 0.5F - point.y;
            float dz = block.z.get() + 0.5F - point.z;
            float d2 = dx * dx + dy * dy + dz * dz;

            keys[i] = ((long) Float.floatToIntBits(d2) << 20) | i;
            minY = Math.min(minY, block.y.get());
        }

        java.util.Arrays.sort(keys);

        List<DestructionBlock> tierA = new java.util.ArrayList<>(cap);
        int[] tierCIndices = new int[n - cap];

        for (int k = 0; k < n; k++)
        {
            int i = (int) (keys[k] & 0xFFFFF);

            if (k < cap)
            {
                tierA.add(blocks.get(i));
            }
            else
            {
                tierCIndices[k - cap] = i;
            }
        }

        this.tierAList = tierA;
        this.tierCIdx = tierCIndices;
        this.tierMinY = minY;
        this.tierSrcList = blocks;
        this.tierSrcSize = n;
        this.tierSrcCap = cap;
        this.tierPx = point.x;
        this.tierPy = point.y;
        this.tierPz = point.z;
    }

    /** Cache key of the tier-C ballistic VBO: content + every input of v0/spin/landing. */
    private long physBallisticKey(List<DestructionBlock> allBlocks, boolean withWorld, double ox, double oy, double oz)
    {
        long h = this.physBlocksContentHash(allBlocks);

        h = h * 31 + 1;

        /* Terrain-aware landing samples the world around the actor — moving the actor re-solves it
         * (whole-block quantized, same anti-jitter trick as the bake key). */
        h = h * 31 + (withWorld ? 1 : 0);

        if (withWorld)
        {
            h = h * 31 + Math.round(ox);
            h = h * 31 + Math.round(oy);
            h = h * 31 + Math.round(oz);
        }

        h = h * 31 + this.physSrc.physMaxBodies.get();
        h = h * 31 + this.physSrc.seed.get();
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirYaw.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirPitch.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.dirStrength.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.radialStrength.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.randomAmount.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.rotationAmount.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionStrength.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionRadius.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physExplosionCone.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointX.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointY.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointZ.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.physGravity.get());

        /* Wind shapes the flight AND the precomputed landings. */
        if (this.physSrc instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion)
        {
            h = h * 31 + Float.floatToIntBits(explosion.windStrength.get());
            h = h * 31 + Float.floatToIntBits(explosion.windFrontSpeed.get());
            h = h * 31 + Float.floatToIntBits(explosion.windDecay.get());
            h = h * 31 + Float.floatToIntBits(explosion.windAmbientYaw.get());
            h = h * 31 + Float.floatToIntBits(explosion.windAmbientStrength.get());
        }

        return h;
    }

    /** Cache key of the tier-C static shell (Iris): content + whatever moves the tier membership. */
    private long physStaticKey(List<DestructionBlock> allBlocks)
    {
        long h = this.physBlocksContentHash(allBlocks);

        h = h * 31 + 2;
        h = h * 31 + this.physSrc.physMaxBodies.get();
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointX.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointY.get());
        h = h * 31 + Float.floatToIntBits(this.physSrc.pointZ.get());

        return h;
    }

    private DestructionPhysics.BakeInput physInput(List<DestructionBlock> blocks, List<DestructionBlock> allBlocks,
        boolean withWorld, FormRenderingContext context, net.minecraft.client.MinecraftClient mc)
    {
        DestructionPhysics.BakeInput input = new DestructionPhysics.BakeInput();
        int count = blocks.size();

        input.count = count;
        input.rest = new int[count * 3];
        input.linVel = new float[count * 3];
        input.angVel = new float[count * 3];
        input.gravity = this.physSrc.physGravity.get();
        input.friction = this.physSrc.physFriction.get();
        input.restitution = this.physSrc.physBounciness.get();
        input.duration = this.physSrc.physDuration.get();
        input.gravityCurve = this.physGravityCurve(context, (int) (input.duration * 30F) + 1);

        /* Global wind (explosion form only): the blast front + ambient drift, applied per step in the
         * sim — the same inside-one-bake pattern as the animated gravity. */
        if (this.physSrc instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion)
        {
            Vector3f epic = this.physSrc.point();

            input.windStrength = explosion.windStrength.get();
            input.windFrontSpeed = explosion.windFrontSpeed.get();
            input.windDecay = explosion.windDecay.get();
            input.windAmbX = explosion.windAmbientX();
            input.windAmbZ = explosion.windAmbientZ();
            input.epicX = epic.x;
            input.epicY = epic.y;
            input.epicZ = epic.z;
        }

        if (input.gravityCurve != null)
        {
            input.gravity = input.gravityCurve[0];
        }

        Vector3f center = this.physSrc.center();
        Vector3f scratch = new Vector3f();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;

        for (int i = 0; i < count; i++)
        {
            DestructionBlock block = blocks.get(i);
            int bx = block.x.get(), by = block.y.get(), bz = block.z.get();

            input.rest[i * 3] = bx;
            input.rest[i * 3 + 1] = by;
            input.rest[i * 3 + 2] = bz;

            minX = Math.min(minX, bx); minY = Math.min(minY, by); minZ = Math.min(minZ, bz);
            maxX = Math.max(maxX, bx); maxY = Math.max(maxY, by); maxZ = Math.max(maxZ, bz);

            scratch.set(bx, by, bz);

            Vector3f vel = this.physSrc.initialVelocity(i, scratch, center);

            input.linVel[i * 3] = vel.x;
            input.linVel[i * 3 + 1] = vel.y;
            input.linVel[i * 3 + 2] = vel.z;

            Vector3f ang = this.physSrc.initialAngularVelocity(i);

            input.angVel[i * 3] = ang.x;
            input.angVel[i * 3 + 1] = ang.y;
            input.angVel[i * 3 + 2] = ang.z;
        }

        if (withWorld)
        {
            /* The world origin of the structure = actor position + the form transform's translate (the
             * box may have been moved via the transform track after capture). Rotation/scale of the
             * whole structure vs world colliders is NOT modelled — colliders stay axis-aligned. */
            org.joml.Vector3f ft = this.physSrc.transform.get().translate;
            double ox = context.entity.getX() + ft.x;
            double oy = context.entity.getY() + ft.y;
            double oz = context.entity.getZ() + ft.z;

            /* The structure's own cells in world space — excluded from the collider scan (the capture's
             * async world cut may not have landed on the client yet; see physScanWorld). Built from the
             * FULL list: with an LOD tier split the sim only gets tier A, but the not-yet-cut leftovers
             * of tier C would otherwise still become bogus colliders. */
            it.unimi.dsi.fastutil.longs.LongOpenHashSet restCells = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(allBlocks.size() * 2);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet restColumns = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(allBlocks.size() / 4);
            int wx = (int) Math.round(ox), wy = (int) Math.round(oy), wz = (int) Math.round(oz);

            for (DestructionBlock cell : allBlocks)
            {
                restCells.add(BlockPos.asLong(wx + cell.x.get(), wy + cell.y.get(), wz + cell.z.get()));
                restColumns.add(((long) (wx + cell.x.get()) << 32) ^ ((wz + cell.z.get()) & 0xFFFFFFFFL));
            }

            input.worldBoxes = this.physScanWorld(mc.world, ox, oy, oz, minX, minY, minZ, maxX, maxY, maxZ, restCells, restColumns);
        }
        else if (this.physSrc.physGround.get())
        {
            input.groundY = minY;
            input.groundCx = center.x;
            input.groundCz = center.z;
        }

        /* Release scheduling + support anchors — AFTER the rest[] fill above (an earlier version read
         * rest[] before it was populated, so every block got the same release time = no visible wave). */
        boolean support = this.physSrc.physSupport.get();
        int clusterSize = this.physSrc.physClusterSize.get();
        float shatter = this.physSrc.physShatter.get();

        if (shatter > 0F)
        {
            /* Sub-block shatter zone: blocks within (explosion radius × fraction) of the epicenter are
             * pre-split into 8 glued sectors. Capped — every shattered block is 8 bodies. */
            Vector3f point = this.physSrc.point();
            float radius = Math.max(1F, this.physSrc.physExplosionRadius.get()) * shatter;
            int budget = 3000;
            boolean[] shattered = new boolean[count];
            boolean any = false;

            for (int i = 0; i < count && budget > 0; i++)
            {
                float d = new Vector3f(input.rest[i * 3] + 0.5F, input.rest[i * 3 + 1] + 0.5F, input.rest[i * 3 + 2] + 0.5F).distance(point);

                /* Only blocks whose sectors can be honestly UV-cropped (full cubes) shatter — anything
                 * else (trapdoors, slabs, stairs) hit the half-scale whole-model fallback, which reads
                 * as the block DUPLICATED into identical mini copies (tester report). They stay whole. */
                if (d < radius && DestructionSectorDraw.canDraw(blocks.get(i).state.get()))
                {
                    shattered[i] = true;
                    any = true;
                    budget--;
                }
            }

            if (budget == 0)
            {
                System.err.println("[bbsvfx] Destruction physics: sub-block shatter cap (3000 blocks) hit, shrink the zone");
            }

            if (any)
            {
                input.shattered = shattered;
                input.shatterBreak = this.physSrc.physShatterStrength.get();
            }
        }

        if (clusterSize > 1)
        {
            input.clusterIds = this.physClusters(input, clusterSize);
            input.clusterBreak = this.physSrc.physClusterStrength.get();

            /* A rigid chunk must LAUNCH as one: per-block velocities (explosion falloff, random) differ
             * between members, and those mismatched impulses tear every joint on the very first step —
             * the clusters were invisible. Average the members' velocities per cluster. */
            float[] sumLin = new float[count * 3];
            float[] sumAng = new float[count * 3];
            int[] members = new int[count];

            for (int i = 0; i < count; i++)
            {
                /* Shattered blocks are excluded from clusters (they have their own sector glue). */
                if (input.shattered != null && input.shattered[i])
                {
                    continue;
                }

                int c = input.clusterIds[i];

                sumLin[c * 3] += input.linVel[i * 3];
                sumLin[c * 3 + 1] += input.linVel[i * 3 + 1];
                sumLin[c * 3 + 2] += input.linVel[i * 3 + 2];
                sumAng[c * 3] += input.angVel[i * 3];
                sumAng[c * 3 + 1] += input.angVel[i * 3 + 1];
                sumAng[c * 3 + 2] += input.angVel[i * 3 + 2];
                members[c]++;
            }

            for (int i = 0; i < count; i++)
            {
                if (input.shattered != null && input.shattered[i])
                {
                    continue;
                }

                int c = input.clusterIds[i];
                float n = members[c];

                input.linVel[i * 3] = sumLin[c * 3] / n;
                input.linVel[i * 3 + 1] = sumLin[c * 3 + 1] / n;
                input.linVel[i * 3 + 2] = sumLin[c * 3 + 2] / n;
                input.angVel[i * 3] = sumAng[c * 3] / n;
                input.angVel[i * 3 + 1] = sumAng[c * 3 + 1] / n;
                input.angVel[i * 3 + 2] = sumAng[c * 3 + 2] / n;
            }
        }

        if (this.physSrc.physWave.get())
        {
            /* Staged release: the wave front leaves the epicenter at t=0 and reaches the farthest
             * block at physWaveTime; invertOrder (shared with point mode) releases far blocks first. */
            Vector3f point = this.physSrc.point();
            float maxDist = Math.max(1e-3F, this.physSrc.maxDistanceToPoint(point));
            float waveTime = Math.max(0F, this.physSrc.physWaveTime.get());
            boolean invert = this.physSrc.invertOrder.get();

            input.releaseTimes = new float[count];

            for (int i = 0; i < count; i++)
            {
                float d = new Vector3f(input.rest[i * 3], input.rest[i * 3 + 1], input.rest[i * 3 + 2]).distance(point) / maxDist;

                input.releaseTimes[i] = (invert ? 1F - d : d) * waveTime;
            }
        }
        else if (support)
        {
            /* Support-only: the initial damage is the explosion radius (knocked out at t=0); everything
             * else stays intact until the connectivity check finds it unsupported. */
            Vector3f point = this.physSrc.point();
            float explosion = this.physSrc.physExplosionStrength.get();
            float radius = Math.max(1F, this.physSrc.physExplosionRadius.get());

            input.releaseTimes = new float[count];

            for (int i = 0; i < count; i++)
            {
                float d = new Vector3f(input.rest[i * 3] + 0.5F, input.rest[i * 3 + 1] + 0.5F, input.rest[i * 3 + 2] + 0.5F).distance(point);

                input.releaseTimes[i] = explosion > 0F && d < radius ? 0F : Float.POSITIVE_INFINITY;
            }
        }

        if (input.clusterIds != null && input.releaseTimes != null)
        {
            /* A cluster is one rigid chunk — release it as a unit (earliest member wins), else a joint
             * from a released block to a still-kinematic cluster-mate pins the debris to the wall. */
            float[] clusterMin = new float[count];

            java.util.Arrays.fill(clusterMin, Float.POSITIVE_INFINITY);

            for (int i = 0; i < count; i++)
            {
                int c = input.clusterIds[i];

                clusterMin[c] = Math.min(clusterMin[c], input.releaseTimes[i]);
            }

            for (int i = 0; i < count; i++)
            {
                input.releaseTimes[i] = clusterMin[input.clusterIds[i]];
            }
        }

        if (support)
        {
            /* Anchors: what counts as "standing on the ground". With world collision — blocks with a
             * solid real block right below; otherwise the structure's bottom layer. */
            input.anchors = new boolean[count];

            for (int i = 0; i < count; i++)
            {
                if (withWorld)
                {
                    BlockPos below = BlockPos.ofFloored(
                        context.entity.getX() + input.rest[i * 3] + 0.5D,
                        context.entity.getY() + input.rest[i * 3 + 1] - 0.5D,
                        context.entity.getZ() + input.rest[i * 3 + 2] + 0.5D);

                    input.anchors[i] = !mc.world.getBlockState(below).getCollisionShape(mc.world, below).isEmpty();
                }
                else
                {
                    input.anchors[i] = input.rest[i * 3 + 1] == minY;
                }
            }
        }

        return input;
    }

    /** Cap on world-collider statics so a huge margin over dense terrain can't stall the bake. */
    private static final int MAX_WORLD_COLLIDERS = 60000;

    /**
     * Deterministic fracture clusters: seeds visited in a seed-hashed order, each growing over
     * unassigned 6-neighbours up to {@code size} blocks (BFS → compact-ish chunks). Every block ends
     * up in exactly one cluster (isolated leftovers become their own).
     */
    private int[] physClusters(DestructionPhysics.BakeInput input, int size)
    {
        int count = input.count;
        Long2IntOpenHashMap index = new Long2IntOpenHashMap(count);

        index.defaultReturnValue(-1);

        for (int i = 0; i < count; i++)
        {
            index.put(BlockPos.asLong(input.rest[i * 3], input.rest[i * 3 + 1], input.rest[i * 3 + 2]), i);
        }

        int seed = this.physSrc.seed.get() ^ 0x51ab3e1;
        int[] order = new int[count];

        for (int i = 0; i < count; i++)
        {
            order[i] = i;
        }

        it.unimi.dsi.fastutil.ints.IntArrays.quickSort(order, (a, b) ->
        {
            int ha = (seed ^ a * 0x9E3779B9) * 0x85EBCA6B;
            int hb = (seed ^ b * 0x9E3779B9) * 0x85EBCA6B;

            return Integer.compare(ha, hb);
        });

        int[] ids = new int[count];
        int[] queue = new int[count];
        int[][] offsets = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

        java.util.Arrays.fill(ids, -1);

        int nextId = 0;

        for (int s : order)
        {
            if (ids[s] != -1)
            {
                continue;
            }

            int id = nextId++;
            int head = 0, tail = 0;

            ids[s] = id;
            queue[tail++] = s;

            int grown = 1;

            while (head < tail && grown < size)
            {
                int cur = queue[head++];

                for (int[] off : offsets)
                {
                    if (grown >= size)
                    {
                        break;
                    }

                    int nb = index.get(BlockPos.asLong(
                        input.rest[cur * 3] + off[0],
                        input.rest[cur * 3 + 1] + off[1],
                        input.rest[cur * 3 + 2] + off[2]));

                    if (nb >= 0 && ids[nb] == -1)
                    {
                        ids[nb] = id;
                        queue[tail++] = nb;
                        grown++;
                    }
                }
            }
        }

        return ids;
    }

    /** Surface filter: fully enclosed blocks are unreachable and don't need a collider. Cells of the
     *  structure itself count as AIR — the capture's world cut lands asynchronously, so at bake time
     *  they may still hold the original blocks (see {@link #physScanWorld}). */
    private boolean physExposed(net.minecraft.world.World world, BlockPos pos, BlockPos.Mutable neighbour,
        it.unimi.dsi.fastutil.longs.LongOpenHashSet restCells)
    {
        for (net.minecraft.util.math.Direction dir : net.minecraft.util.math.Direction.values())
        {
            neighbour.set(pos, dir);

            if (restCells.contains(neighbour.asLong())
                || world.getBlockState(neighbour).getCollisionShape(world, neighbour).isEmpty())
            {
                return true;
            }
        }

        return false;
    }

    /**
     * Effective horizontal scan margin: the slider value, or — at 0 — an AUTO estimate of how far the
     * debris can fly (launch speed × a few seconds of travel, plus slack for rolling), clamped to 8..64.
     */
    private int physMargin()
    {
        float manual = this.physSrc.physWorldMargin.get();

        if (manual > 0.5F)
        {
            return Math.round(manual);
        }

        float speed = this.physSrc.dirStrength.get() + this.physSrc.radialStrength.get()
            + this.physSrc.randomAmount.get() + this.physSrc.physExplosionStrength.get();

        /* The wind's net velocity kick (impulse ≈ strength × decay) + the ambient push carry debris
         * well past the impulse-only estimate. */
        if (this.physSrc instanceof com.bbsvfx.bbsvfx.forms.ExplosionForm explosion)
        {
            speed += explosion.windStrength.get() * explosion.windDecay.get()
                + explosion.windAmbientStrength.get() * 2F;
        }

        float travel = Math.min(this.physSrc.physDuration.get(), 4F);

        return Math.min(64, Math.max(8, Math.round(speed * travel * 0.5F + 8F)));
    }

    /**
     * Turn the real blocks around the structure into static collider boxes (centre xyz + half extents,
     * structure-local). Only SURFACE blocks (at least one non-solid neighbour) qualify — buried blocks
     * can never be touched, and skipping them keeps dense terrain to a few thousand statics. Runs on the
     * render thread (world access is not thread-safe); the result is handed to the bake worker.
     *
     * <p>{@code restCells} = the structure's own cells in world space. The capture cuts the real blocks
     * on the SERVER thread, so at bake time the client world may still hold them — without the skip the
     * first bake gets static colliders exactly coincident with the debris (which pins it / lets it
     * tunnel into the void) and a "buried" crater floor with no colliders at all. Treating those cells
     * as air makes the pre-cut scan produce exactly the post-cut colliders, killing the race.</p>
     */
    private float[] physScanWorld(net.minecraft.world.World world, double ox, double oy, double oz,
        int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
        it.unimi.dsi.fastutil.longs.LongOpenHashSet restCells,
        it.unimi.dsi.fastutil.longs.LongOpenHashSet restColumns)
    {
        int margin = this.physMargin();
        /* Debris slides DOWN a slope far past the horizontal margin — scan deep below the structure
         * regardless (the surface filter keeps the collider count sane), else it falls into the void. */
        int down = Math.min(96, Math.max(32, margin * 4));
        it.unimi.dsi.fastutil.floats.FloatArrayList boxes = new it.unimi.dsi.fastutil.floats.FloatArrayList();

        int fromX = (int) Math.floor(ox + minX - margin);
        int fromY = (int) Math.floor(oy + minY - down);
        int fromZ = (int) Math.floor(oz + minZ - margin);
        int toX = (int) Math.floor(ox + maxX + 1 + margin);
        int toY = (int) Math.floor(oy + maxY + 1 + margin);
        int toZ = (int) Math.floor(oz + maxZ + 1 + margin);

        /* Per-column surface height over the whole detailed region, ONE heightmap pass: everything
         * more than a couple of blocks BELOW its column's surface (and outside the cut's own columns —
         * crater walls stay) is a cave/underground face the debris can never touch. The bottom-up
         * y-loop used to burn the whole 60k collider budget on cave walls and TRUNCATE before ever
         * reaching the terrain surface — debris fell straight through the world (tester: "world
         * collision off works, on doesn't"). */
        int spanX = toX - fromX + 1;
        int spanZ = toZ - fromZ + 1;
        int[] surface = new int[spanX * spanZ];

        for (int x = fromX; x <= toX; x++)
        {
            for (int z = fromZ; z <= toZ; z++)
            {
                surface[(x - fromX) * spanZ + (z - fromZ)] =
                    world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);
            }
        }

        BlockPos.Mutable p = new BlockPos.Mutable();
        BlockPos.Mutable neighbour = new BlockPos.Mutable();

        /* Full-cube surface blocks are 2D-RECT-MERGED per Y slice — X-runs of equal extent merged
         * across consecutive Z rows (same technique as the ring below); raw per-block or even per-row
         * statics blow the cap on any decent margin over terrain. Partial shapes (slabs, stairs, ...)
         * are emitted individually with their own bounding box. */
        java.util.HashMap<Long, int[]> innerOpen = new java.util.HashMap<>();
        java.util.HashMap<Long, int[]> innerNext = new java.util.HashMap<>();

        inner:
        for (int y = fromY; y <= toY; y++)
        {
            innerOpen.clear();
            innerNext.clear();

            for (int z = fromZ; z <= toZ + 1; z++)
            {
                int runStart = Integer.MIN_VALUE;

                if (z <= toZ)
                {
                    for (int x = fromX; x <= toX + 1; x++)
                    {
                        boolean fullCube = false;
                        net.minecraft.util.math.Box box = null;

                        if (x <= toX && !restCells.contains(BlockPos.asLong(x, y, z))
                            && (y >= surface[(x - fromX) * spanZ + (z - fromZ)] - 2
                                || restColumns.contains(((long) x << 32) ^ (z & 0xFFFFFFFFL))))
                        {
                            p.set(x, y, z);

                            net.minecraft.util.shape.VoxelShape shape = world.getBlockState(p).getCollisionShape(world, p);

                            if (!shape.isEmpty() && this.physExposed(world, p, neighbour, restCells))
                            {
                                box = shape.getBoundingBox();
                                fullCube = box.minX <= 1e-4D && box.minY <= 1e-4D && box.minZ <= 1e-4D
                                    && box.maxX >= 1D - 1e-4D && box.maxY >= 1D - 1e-4D && box.maxZ >= 1D - 1e-4D;
                            }
                        }

                        if (fullCube)
                        {
                            if (runStart == Integer.MIN_VALUE)
                            {
                                runStart = x;
                            }

                            continue;
                        }

                        if (runStart != Integer.MIN_VALUE)
                        {
                            /* Row run [runStart, x-1]: extend last row's rect or open a new one. */
                            long key = ((long) (runStart - fromX) << 24) | (x - fromX);
                            int[] rect = innerOpen.remove(key);

                            if (rect == null)
                            {
                                rect = new int[] {z, z, runStart, x};
                            }
                            else
                            {
                                rect[1] = z;
                            }

                            innerNext.put(key, rect);
                            runStart = Integer.MIN_VALUE;
                        }

                        if (box != null)
                        {
                            boxes.add((float) (x + (box.minX + box.maxX) * 0.5D - ox));
                            boxes.add((float) (y + (box.minY + box.maxY) * 0.5D - oy));
                            boxes.add((float) (z + (box.minZ + box.maxZ) * 0.5D - oz));
                            boxes.add((float) ((box.maxX - box.minX) * 0.5D));
                            boxes.add((float) ((box.maxY - box.minY) * 0.5D));
                            boxes.add((float) ((box.maxZ - box.minZ) * 0.5D));
                        }
                    }
                }

                /* Rects not continued by this row are done. */
                for (int[] rect : innerOpen.values())
                {
                    boxes.add((float) ((rect[2] + rect[3]) * 0.5D - ox));
                    boxes.add((float) (y + 0.5D - oy));
                    boxes.add((float) ((rect[0] + rect[1] + 1) * 0.5D - oz));
                    boxes.add((rect[3] - rect[2]) * 0.5F);
                    boxes.add(0.5F);
                    boxes.add((rect[1] + 1 - rect[0]) * 0.5F);
                }

                innerOpen.clear();

                java.util.HashMap<Long, int[]> swap = innerOpen;

                innerOpen = innerNext;
                innerNext = swap;

                if (boxes.size() / 6 >= MAX_WORLD_COLLIDERS - 10000)
                {
                    /* Stop the DETAILED scan but still build the heightmap ring below (with the
                     * reserved budget) — bailing out entirely left the distant terrain with no
                     * colliders at all, and most debris fell into the void. */
                    System.err.println("[bbsvfx] Destruction physics: world collider cap hit on the detailed scan, shrink the margin");

                    break inner;
                }
            }
        }

        /* Outer heightmap ring: debris rolls DOWN slopes far past any sane margin and then falls into
         * the void. Around the detailed scan, cover the terrain SURFACE (top 4 blocks per column, from
         * the heightmap — 1 lookup per column) out to a wide radius. Overhangs/caves out there don't
         * matter; the rolling debris only ever meets the surface. Strips are merged in 2D — X-runs of
         * equal height, then equal runs across consecutive Z rows — or mountainous terrain (a new height
         * every column) blows the collider cap. */
        int outer = 128;
        int ringFromX = fromX - outer, ringToX = toX + outer;
        int ringFromZ = fromZ - outer, ringToZ = toZ + outer;
        int bottom = world.getBottomY();

        /* Open rectangles from the previous row: key = (x0, x1, top) packed, value = {zStart, zLast}. */
        java.util.HashMap<Long, int[]> open = new java.util.HashMap<>();
        java.util.HashMap<Long, int[]> next = new java.util.HashMap<>();

        for (int z = ringFromZ; z <= ringToZ + 1; z++)
        {
            boolean innerZ = z >= fromZ && z <= toZ;
            int runStart = Integer.MIN_VALUE;
            int runTop = Integer.MIN_VALUE;

            if (z <= ringToZ)
            {
                for (int x = ringFromX; x <= ringToX + 1; x++)
                {
                    int top = Integer.MIN_VALUE;

                    if (x <= ringToX && !(innerZ && x >= fromX && x <= toX))
                    {
                        /* MOTION_BLOCKING, NOT the no-leaves variant: the server only syncs
                         * MOTION_BLOCKING + WORLD_SURFACE heightmaps to the CLIENT — NO_LEAVES reads
                         * as empty here, which silently produced ring strips at the world bottom
                         * (debris "fell into the void" past the detailed scan). */
                        int t = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, x, z);

                        /* Unloaded/empty columns report the world bottom — nothing to collide with. */
                        if (t > bottom + 1)
                        {
                            /* Quantize UP to even: canopy tops change height every column and defeat
                             * the rect merge — an unquantized forest ring alone blew the 60k collider
                             * cap (truncated ring = void falls again). Far debris resting ≤1 block
                             * high is unreadable at ring distances. */
                            top = (t + 1) & ~1;
                        }
                    }

                    if (top != Integer.MIN_VALUE && top == runTop)
                    {
                        continue;
                    }

                    if (runStart != Integer.MIN_VALUE)
                    {
                        /* Row run [runStart, x-1] at runTop: extend the rect from the previous row, or open one. */
                        long key = ((long) (runStart - ringFromX) << 40) | ((long) (x - ringFromX) << 20) | (runTop & 0xFFFFF);
                        int[] rect = open.remove(key);

                        if (rect == null)
                        {
                            rect = new int[] {z, z, runStart, x, runTop};
                        }
                        else
                        {
                            rect[1] = z;
                        }

                        next.put(key, rect);
                    }

                    runStart = top != Integer.MIN_VALUE ? x : Integer.MIN_VALUE;
                    runTop = top;
                }
            }

            /* Rects not continued by this row are done — emit them. */
            for (int[] rect : open.values())
            {
                boxes.add((float) ((rect[2] + rect[3]) * 0.5D - ox));
                boxes.add((float) (rect[4] - 2D - oy));
                boxes.add((float) ((rect[0] + rect[1] + 1) * 0.5D - oz));
                boxes.add((rect[3] - rect[2]) * 0.5F);
                boxes.add(2F);
                boxes.add((rect[1] + 1 - rect[0]) * 0.5F);

                if (boxes.size() / 6 >= MAX_WORLD_COLLIDERS)
                {
                    System.err.println("[bbsvfx] Destruction physics: world collider cap (" + MAX_WORLD_COLLIDERS + ") hit on the heightmap ring");

                    return boxes.toFloatArray();
                }
            }

            open.clear();

            java.util.HashMap<Long, int[]> swap = open;

            open = next;
            next = swap;
        }

        return boxes.toFloatArray();
    }

    /** Stage B1 GPU path: bake the structure into a VBO (rebuilt only on block-list change) and draw it. */
    private void renderGpu(FormRenderingContext context, List<DestructionBlock> blocks)
    {
        int seed = this.form.seed.get();

        if (blocks != this.vaoList || blocks.size() != this.vaoSize || seed != this.vaoSeed)
        {
            this.vao.build(this.form, blocks);
            this.vaoList = blocks;
            this.vaoSize = blocks.size();
            this.vaoSeed = seed;
        }

        this.vao.render(this.form, context.stack, context.light, context.overlay);

        if (context.type == FormRenderType.PREVIEW)
        {
            Vector3f point = this.form.point();
            float s = 0.35F;

            Draw.renderBox(context.stack, point.x - s, point.y - s, point.z - s,
                s * 2F, s * 2F, s * 2F, 0.2F, 0.9F, 1F);
        }
    }

    /** True when every one of the six neighbours is a present, static, opaque-full-cube occluder. */
    private boolean isHidden(int x, int y, int z)
    {
        return this.occludingNeighbour(x + 1, y, z)
            && this.occludingNeighbour(x - 1, y, z)
            && this.occludingNeighbour(x, y + 1, z)
            && this.occludingNeighbour(x, y - 1, z)
            && this.occludingNeighbour(x, y, z + 1)
            && this.occludingNeighbour(x, y, z - 1);
    }

    private boolean occludingNeighbour(int x, int y, int z)
    {
        int idx = this.posToIndex.get(BlockPos.asLong(x, y, z));

        return idx >= 0 && this.occludes[idx] && this.progress[idx] == 0F;
    }

    /** Rebuild the position->index map and occluder flags; only runs when the block list actually changes. */
    /* Light-sample point: local box centre (X/Z) at one block above the top (Y). Recomputed only when
     * the block list changes (same invalidation as the occlusion cache). */
    private List<DestructionBlock> lightBoundsList;
    private int lightBoundsSize = -1;
    private final Vector3f lightSample = new Vector3f();

    private Vector3f localBoundsCenterTop(List<DestructionBlock> blocks)
    {
        if (this.lightBoundsList == blocks && this.lightBoundsSize == blocks.size())
        {
            return this.lightSample;
        }

        int minX = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (DestructionBlock block : blocks)
        {
            minX = Math.min(minX, block.x.get());
            minZ = Math.min(minZ, block.z.get());
            maxX = Math.max(maxX, block.x.get());
            maxY = Math.max(maxY, block.y.get());
            maxZ = Math.max(maxZ, block.z.get());
        }

        this.lightBoundsList = blocks;
        this.lightBoundsSize = blocks.size();
        this.lightSample.set((minX + maxX + 1) * 0.5F, maxY + 1.5F, (minZ + maxZ + 1) * 0.5F);

        return this.lightSample;
    }

    private void ensureOcclusionCache(List<DestructionBlock> blocks)
    {
        int size = blocks.size();

        if (blocks == this.cachedList && size == this.cachedSize)
        {
            return;
        }

        this.cachedList = blocks;
        this.cachedSize = size;

        if (this.occludes.length < size)
        {
            this.occludes = new boolean[size];
            this.progress = new float[size];
        }

        this.posToIndex.clear();

        for (int i = 0; i < size; i++)
        {
            DestructionBlock block = blocks.get(i);

            this.posToIndex.put(BlockPos.asLong(block.x.get(), block.y.get(), block.z.get()), i);
            this.occludes[i] = isOccluder(block.blockState());
        }
    }

    /** Package-private: DestructionStaticVAO reuses it for its rest-adjacency face culling. */
    static boolean isOccluder(BlockState state)
    {
        Boolean cached = OCCLUDES.get(state);

        if (cached == null)
        {
            boolean occluder;

            try
            {
                occluder = state.isOpaqueFullCube(EmptyBlockView.INSTANCE, BlockPos.ORIGIN);
            }
            catch (Exception e)
            {
                occluder = false;
            }

            cached = occluder;
            OCCLUDES.put(state, cached);
        }

        return cached;
    }
}
