package com.bbsvfx.vfxlights.client.shadow;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL30;

import java.util.HashMap;
import java.util.Map;

/**
 * One depth texture holding every light's shadow map, divided into square tiles.
 *
 * <p><b>Why a plain 2D atlas.</b> A point light needs six views, and the natural home for those is a
 * cube-map array — which is what IRLite uses. That is a GL 4.0 / ARB feature, and Minecraft asks GLFW for
 * a 3.2 core context; this codebase has already had a GL33 call abort the JVM outright on a machine whose
 * driver would not hand it over. Six ordinary tiles cost a little arithmetic in the shader and work
 * everywhere, which for a public mod is the better trade.</p>
 *
 * <p><b>Slots are leased, not owned.</b> Each light asks for tiles by id and keeps them while it stays in
 * shot. When the atlas is full, a light that has not been seen for a couple of frames is evicted; if
 * nothing can be freed, the newcomer is told "no tile" and simply renders without a shadow. Degrading one
 * light beats degrading all of them.</p>
 *
 * <p><b>Quality trades tile COUNT, not VRAM.</b> The Shadow quality setting picks the TILE side; the
 * ATLAS side keeps its own ladder (8192 → 12288 → 16384, grown on demand). So a step up in quality
 * quadruples the texels a lamp's map gets and quarters how many lamps can be shadowed at once, while
 * the texture allocated stays exactly the same size. There is nowhere else for the memory to come
 * from: 16384 is already the largest texture most drivers will hand out, and the top rung is 3 GiB.</p>
 */
public final class ShadowAtlas
{
    /** Tile sides the quality setting can pick — 512, 1024, 2048, 4096, 8192. */
    public static final int MIN_TILE_SIZE = 512;
    public static final int MAX_TILE_SIZE = 8192;
    /** The default tile side, and the literal the GLSL model falls back to when nothing is delivered. */
    public static final int DEFAULT_TILE_SIZE = 1024;

    /**
     * Atlas side in texels, grown on demand. The rungs are also the Shadow memory setting's steps:
     * 8192² is ~0.75 GiB of DEPTH24 + RGBA16, 12288² ~1.7, 16384² ~3.0, 24576² ~6.8, 32768² ~12.
     * Growth is demand-driven, so a rung the setting allows is not a rung anything pays for.
     *
     * <p>Past 16384 only NVIDIA follows: GL_MAX_TEXTURE_SIZE is 32768 there and 16384 on AMD and
     * Intel. The top two rungs are what makes the 8192 px tile reachable at all — three tiles across
     * is the floor (a point light's six faces), so 8192 px tiles need a 24576 atlas.</p>
     */
    private static final int[] SIDE_LADDER = { 8192, 12288, 16384, 24576, 32768 };

    /**
     * Side of one tile in texels — the quality dial. 1024 after 512 read as visibly pixelated in game;
     * 2048/4096 are there for stills and close-ups, where the extra sharpness is worth having only a
     * handful of shadowed lamps.
     */
    public static int TILE_SIZE = DEFAULT_TILE_SIZE;
    /** Tiles per row; follows from the atlas side and the tile side. */
    public static int TILES_ACROSS = SIDE_LADDER[0] / DEFAULT_TILE_SIZE;
    public static int TILE_COUNT = TILES_ACROSS * TILES_ACROSS;

    /** What the setting asked for, BEFORE the driver clamp — compared per frame to spot a change. */
    private static int requestedTile = DEFAULT_TILE_SIZE;
    /** Rung of {@link #SIDE_LADDER} currently allocated. */
    private static int sideStep;
    /** Rung of {@link #SIDE_LADDER} the Shadow memory setting allows the ladder to reach. */
    private static int budgetStep = com.bbsvfx.vfxlights.VfxLightsAddon.shadowMemoryStep();
    /** The largest atlas the DRIVER permits; measured once at first {@link #ensure()}. */
    private static int driverMax = SIDE_LADDER[0];
    private static boolean measured;
    /**
     * The smallest texture the driver has actually REFUSED this session, and everything at or above
     * it is off the table from then on. GL_MAX_TEXTURE_SIZE says what the driver will address, not
     * what it has free; without this memory a failed lease would keep asking for the size that just
     * ran out, rebuilding the atlas twice per frame for as long as the scene stayed full.
     */
    private static int refusedSide = Integer.MAX_VALUE;
    /** Every rung refused to allocate — shadows stay off until the quality changes. */
    private static boolean outOfMemory;
    /** Set by a failed lease; the NEXT frame reallocates one step larger and re-leases everything. */
    private static boolean growPending;
    /** Near plane for every shadow projection. Anything closer is inside the bulb. */
    public static final float NEAR = 0.05F;

    /** Frames a light may go unseen before its tiles can be taken. */
    private static final int STALE_FRAMES = 2;

    private static final boolean DEBUG = System.getProperty("vfxlights.shadow.debug") != null;

    private static int texture = -1;
    private static int colorTexture = -1;
    private static int fbo = -1;

    /* ── STATIC layer: a second depth+colour pair, same size and formats, holding every light's
     * baked STATIC shadow (world blocks and world glass — no entities, actors or forms). While
     * an animated caster is in range, the live tiles are restored from it with a blit and only
     * the dynamic pass redraws, instead of re-rendering the whole baked world every frame.
     * Allocated LAZILY (ensureStatic) on the first bake that can need the restore: a scene with
     * no animated casters never pays the second atlas' VRAM. ── */
    private static int staticTexture = -1;
    private static int staticColorTexture = -1;
    private static int staticFbo = -1;

    /** Which light owns each tile, and when it was last used. Regrown when the tile count changes. */
    private static long[] OWNER = new long[0];
    private static int[] LAST_USED = new int[0];
    private static final Map<Long, Integer> LEASES = new HashMap<>();

    private static int frame;

    /**
     * Whether the last {@link #lease} call returned an UNTOUCHED lease from a previous frame — the
     * tiles are still ours and still hold what we last rendered. This is the atlas half of the
     * shadow dirty-skip: a light whose lease survived, whose occluders did not rebuild and whose
     * state did not change can keep last frame's tiles verbatim.
     */
    private static boolean lastLeaseExisting;

    private ShadowAtlas()
    {
    }

    public static boolean lastLeaseWasExisting()
    {
        return lastLeaseExisting;
    }

    public static int getTexture()
    {
        return texture;
    }

    /** The colour map: RGB = transmittance through glass casters, A = the nearest glass' depth. */
    public static int getColorTexture()
    {
        return colorTexture;
    }

    /** The biggest atlas allowed right now: the driver's ceiling under the memory setting's — and
     *  under what the card actually has FREE. */
    private static int maxSide()
    {
        return Math.min(driverMax,
            Math.min(SIDE_LADDER[Math.max(0, Math.min(budgetStep, SIDE_LADDER.length - 1))],
                vramFitSide()));
    }

    /* ── VRAM budget (GL_NVX_gpu_memory_info) ──
     * The ladder and the driver both describe ceilings, not what the card can pay THIS frame: a
     * 24576² atlas is ~7 GiB and the memory setting's "allowance" is a wish, not a balance. On a
     * card where atlas + game + pack outgrow the VRAM the driver starts paging over PCIe, and the
     * frame rate does not degrade — it falls off a cliff (measured: Ultra 5 fps, unrecoverable by
     * any shader-side win). The budget gates GROWTH past the free memory minus a reserve, so a
     * weak card gets a smaller atlas and some unshadowed lamps instead of a slideshow. AMD/Intel
     * expose no free-memory query — there the setting's ladder is the only cap, as before. */
    private static final int GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX = 0x9049;
    /** Left for the game, the pack and the OS — the atlas may not eat into it. Overridable in MiB
     *  (-Dvfxlights.shadow.vramreserve=N) to rehearse a smaller card on a big one. */
    private static final long RESERVE_KB = Long.getLong("vfxlights.shadow.vramreserve", 2560L) * 1024L;
    /** Dev kill-switch (-Dvfxlights.shadow.novram): the pre-budget behaviour — the setting's ladder
     *  is the only ceiling, VRAM pressure be damned. */
    private static final boolean NO_VRAM_BUDGET = System.getProperty("vfxlights.shadow.novram") != null;
    private static boolean nvxMeasured;
    private static boolean nvxAvailable;
    private static int lastVramLogSide;
    private static int lastStaticVramLogSize;

    private static long availableKb()
    {
        if (NO_VRAM_BUDGET)
        {
            return Long.MAX_VALUE;
        }

        if (!nvxMeasured)
        {
            nvxMeasured = true;

            /* Core profile removed glGetString(GL_EXTENSIONS) — it returns null here, and the
             * string check just told a 12 GB NVIDIA card it had no NVX. Ask for the value
             * instead: an invalid enum answers GL_INVALID_ENUM, a working one answers KIB. */
            int probe = GL11.glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX);

            nvxAvailable = GL11.glGetError() == GL11.GL_NO_ERROR && probe > 0;

            if (!nvxAvailable)
            {
                log("shadow atlas: GL_NVX_gpu_memory_info is absent — VRAM budget off,"
                    + " the memory setting is the only cap");
            }
        }

        return nvxAvailable
            ? GL11.glGetInteger(GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX) : Long.MAX_VALUE;
    }

    /**
     * The largest ladder rung whose ~12 B/texel cost fits the free VRAM past the reserve — never
     * below the base rung: 0.75 GiB fits anywhere the game itself runs, and a hard zero would
     * brick shadows exactly on the low-end cards this exists to keep playable. Read per call:
     * free memory moves with the scene, and a refused growth must un-refuse when VRAM frees up.
     */
    private static int vramFitSide()
    {
        long budget = availableKb() - RESERVE_KB;
        int fit = SIDE_LADDER[0];

        if (budget > 0)
        {
            for (int side : SIDE_LADDER)
            {
                if ((long) side * side * 12L / 1024L <= budget)
                {
                    fit = side;
                }
                else
                {
                    break;
                }
            }
        }

        return fit;
    }

    /** The atlas side at a rung, never past what the driver or the memory setting hands out. */
    private static int sideFor(int step)
    {
        return Math.min(SIDE_LADDER[Math.min(step, SIDE_LADDER.length - 1)], maxSide());
    }

    /** Whether a bigger atlas is still available — a failed lease asks for one. */
    private static boolean canGrow()
    {
        return sideStep + 1 < SIDE_LADDER.length
            && sideFor(sideStep) < maxSide()
            && sideFor(sideStep + 1) < refusedSide;
    }

    /**
     * The first rung worth allocating: three tiles across, so one point light's six faces fit
     * somewhere. At 1024 the smallest rung is already 8 across and this returns 0 — the ladder is
     * unchanged for the default quality; at 4096 it skips a 2x2 atlas no cube light could ever use.
     */
    private static int startStep()
    {
        for (int i = 0; i < SIDE_LADDER.length; i++)
        {
            if (sideFor(i) / TILE_SIZE >= 3)
            {
                return i;
            }
        }

        return SIDE_LADDER.length - 1;
    }

    /**
     * The tile side actually usable: at least three tiles across at the top rung, so a point light's
     * six faces can fit somewhere on the ladder. A step the hardware or the memory setting cannot
     * host is served one step lower rather than as a lamp that can never have a shadow — 8192 px
     * tiles need a 24576 atlas, so they ask for both an NVIDIA-class texture limit and the memory
     * setting raised past its default.
     */
    private static int clampTile(int wanted)
    {
        int tile = Math.max(MIN_TILE_SIZE, Math.min(wanted, MAX_TILE_SIZE));

        while (tile > MIN_TILE_SIZE && tile > maxSide() / 3)
        {
            tile >>= 1;
        }

        return tile;
    }

    private static void measure()
    {
        if (measured)
        {
            return;
        }

        measured = true;
        driverMax = Math.min(SIDE_LADDER[SIDE_LADDER.length - 1],
            GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE));
    }

    /** Allocate the texture and framebuffer on first use. */
    public static void ensure()
    {
        if (texture != -1 || outOfMemory)
        {
            return;
        }

        measure();

        /* Read the settings HERE as well as in beginFrame, so the very first allocation of a session
         * is already the right size — the config is loaded long before this runs, and allocating a
         * default atlas only to throw it away one line later would cost a spare 770 MiB round trip. */
        requestedTile = com.bbsvfx.vfxlights.VfxLightsAddon.shadowTileSize();
        budgetStep = com.bbsvfx.vfxlights.VfxLightsAddon.shadowMemoryStep();

        TILE_SIZE = clampTile(requestedTile);
        sideStep = Math.max(sideStep, startStep());

        /* The DRIVER limit is only a ceiling, not the size. Taking the maximum up front allocated
         * 2.5 GiB of texture (16384² of DEPTH16 + RGBA16) on any modern card — for 256 tiles, which
         * is 42 shadowed point lights at once, a scene that does not exist. The atlas starts at the
         * ladder's first rung and grows a step only when a lease actually fails.
         *
         * Stepping DOWN happens too: the top rung is 3 GiB, and a driver that refuses it answers with
         * GL_OUT_OF_MEMORY and an incomplete texture — shadows that sample garbage. Better a smaller
         * atlas, said out loud in the log, than a silently broken one. */
        while (sideStep >= 0)
        {
            TILES_ACROSS = Math.max(1, sideFor(sideStep) / TILE_SIZE);
            TILE_COUNT = TILES_ACROSS * TILES_ACROSS;

            /* Before the allocation, not after: the bookkeeping arrays are indexed by TILE_COUNT and
             * release() may be called even on a frame where the texture never came up. */
            if (OWNER.length < TILE_COUNT)
            {
                OWNER = new long[TILE_COUNT];
                LAST_USED = new int[TILE_COUNT];
            }

            if (TILES_ACROSS * TILE_SIZE < refusedSide && allocate())
            {
                return;
            }

            sideStep--;
        }

        sideStep = 0;
        outOfMemory = true;

        log("shadow atlas: no size could be allocated — lights render without shadows. Lower the"
            + " Shadow quality setting or free VRAM.");
    }

    private static long megabytes(int side)
    {
        /* DEPTH24 pads to 4 bytes per texel, the RGBA16 colour map takes 8. */
        return (long) side * side * 12L / (1024L * 1024L);
    }

    /** One allocation attempt at the current {@link #TILES_ACROSS}; false when the driver refused. */
    private static boolean allocate()
    {
        int size = TILE_SIZE * TILES_ACROSS;

        /* Drain whatever error some earlier call left behind, or the check below blames us for it.
         * Bounded: a lost context returns an error forever and an unbounded drain would hang. */
        for (int i = 0; i < 32 && GL11.glGetError() != GL11.GL_NO_ERROR; i++)
        {
        }

        texture = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(texture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);

        /* Border of 1.0 = "infinitely far": anything sampled outside a tile reads as unoccluded rather
         * than as a wall, so a receiver just past the edge of a map is lit instead of black. */
        GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_BORDER_COLOR,
            new float[] { 1F, 1F, 1F, 1F });

        /* 24-bit depth. Sixteen WAS the precision bottleneck, measured: one quantum in world units is
         * 2z²/b / 65535 with b = 2*far*near/(far-near), so at NEAR 0.05 and range 10 a receiver eight
         * blocks out quantises at ~19 mm — while the shading bias there is ~19.5 mm. Bias equal to one
         * quantum is exactly the marginal case that prints self-shadow contours: nested arcs of acne on
         * a flat floor, the same failure the colour map's alpha had at 8 bits (see below) and cured the
         * same way. 24 bits drops the quantum to ~0.07 mm and leaves the bias 250x of headroom.
         *
         * Fixed-point 24, not DEPTH_COMPONENT32F: both occupy 4 bytes here (D24 pads to D24X8), but
         * float depth concentrates its precision near zero, which is where the perspective divide has
         * already piled it up — the two compound and the far half of the range comes out WORSE than
         * fixed point. Reversed-Z would flip that, and needs glClipControl (GL 4.5); MC asks for 3.2.
         *
         * Cost: the depth plane goes 2 -> 4 bytes per texel, ~+20% on the atlas as a whole (the RGBA16
         * colour map at 8 bytes dominates it). 8x8 ~ 640 -> 770 MiB, 16x16 ~ 2.5 -> 3.0 GiB. */
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24,
            size, size, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

        /* The COLOUR map rides alongside the depth: RGB holds what glass casters tint the light to
         * (white = untouched), A holds the nearest glass' depth so shading can ask "is this receiver
         * BEHIND the pane" — a receiver between lamp and glass must not pick up its colour. */
        colorTexture = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(colorTexture);
        /* NEAREST, not linear: the ALPHA channel is a DEPTH (nearest glass), and interpolating a
         * depth against the background's 1.0 built a band along every glass silhouette where the
         * "receiver behind glass" test flipped — untinted white gashes along every cell edge, every
         * colour, blocks and panes alike (the stained-window saga's true culprit). Flat tints lose
         * nothing to nearest sampling. */
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_BORDER_COLOR,
            new float[] { 1F, 1F, 1F, 1F });
        /* RGBA16, not RGBA8: the alpha is a DEPTH (nearest glass) and 8 bits over a lamp-scale range
         * is ~0.13 block per level. The "receiver behind glass" gate needs a slack larger than one
         * level to avoid acne, and that same slack rejected receivers sitting just behind the pane -
         * white staircase gashes tracing the 8-bit depth-quantization contours. 16 bits is 0.0005
         * block per level; the gate slack drops to a hair and the staircase dissolves. */
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA16,
            size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, null);

        if (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* GL_OUT_OF_MEMORY, almost always: the textures exist but hold nothing. Drop them and
             * let the caller try a smaller rung — an incomplete attachment would sample garbage. */
            GlStateManager._deleteTexture(texture);
            GlStateManager._deleteTexture(colorTexture);

            texture = -1;
            colorTexture = -1;
            refusedSide = Math.min(refusedSide, size);

            log("shadow atlas: the driver refused " + size + "² (~" + megabytes(size)
                + " MiB) — stepping down and not asking for that size again");

            return false;
        }

        fbo = GlStateManager.glGenFramebuffers();

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, texture, 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, colorTexture, 0);

        /* Depth-only by DEFAULT — the glass pass flips the draw buffer on for itself and back off. */
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        for (int i = 0; i < TILE_COUNT; i++)
        {
            OWNER[i] = Long.MIN_VALUE;
        }

        log("shadow atlas: " + TILES_ACROSS + "x" + TILES_ACROSS + " tiles of " + TILE_SIZE + "px ("
            + size + "², ~" + megabytes(size) + " MiB), ceiling " + maxSide()
            + " (driver " + driverMax + ", memory setting " + SIDE_LADDER[budgetStep] + ")");

        return true;
    }

    /**
     * Drop the atlas so the next {@link #ensure()} builds a fresh one.
     *
     * <p>Deleting through GlStateManager, not raw GL: MC caches texture bindings per unit, and a raw
     * delete leaves the cache pointing at a recycled id — a later bind of the same id silently
     * no-ops.</p>
     */
    private static void discard()
    {
        if (texture != -1)
        {
            GlStateManager._deleteTexture(texture);
        }

        if (colorTexture != -1)
        {
            GlStateManager._deleteTexture(colorTexture);
        }

        if (fbo != -1)
        {
            GlStateManager._glDeleteFramebuffers(fbo);
        }

        /* The static layer dies with the atlas it mirrors. Every lease is dropped below, so all
         * lights re-bake into a lazily re-created layer — and a scene with no dynamics never
         * re-pays the second atlas after a grow or a quality change. */
        if (staticTexture != -1)
        {
            GlStateManager._deleteTexture(staticTexture);
        }

        if (staticColorTexture != -1)
        {
            GlStateManager._deleteTexture(staticColorTexture);
        }

        if (staticFbo != -1)
        {
            GlStateManager._glDeleteFramebuffers(staticFbo);
        }

        staticTexture = -1;
        staticColorTexture = -1;
        staticFbo = -1;

        texture = -1;
        colorTexture = -1;
        fbo = -1;

        LEASES.clear();
    }

    /** Whether the static layer's textures currently exist. */
    public static boolean staticAllocated()
    {
        return staticFbo != -1;
    }

    /**
     * Allocate the STATIC layer on first use — the same size and texture formats as the live
     * atlas, because the restore path blits texel-to-texel. Lazy on purpose: only a bake that a
     * dynamic frame could ask to restore (a dynamic caster in range, or the layer already up)
     * pays the doubled VRAM. All format rationale lives with {@link #ensure()}.
     */
    public static void ensureStatic()
    {
        if (staticFbo != -1 || texture == -1)
        {
            return;
        }

        int previousFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        int size = TILE_SIZE * TILES_ACROSS;

        /* The double ask doubles the VRAM bill too — refuse it up front when the free memory past
         * the reserve cannot pay, instead of allocating into the driver's swap and discovering the
         * cliff mid-frame. The glGetError retry below stays as the backstop for what the query
         * could not see. */
        if ((long) size * size * 12L / 1024L > availableKb() - RESERVE_KB)
        {
            /* Once per size, not per dynamic frame: the retry is lazy and asks every frame. */
            if (size != lastStaticVramLogSize)
            {
                lastStaticVramLogSize = size;

                log("shadow atlas: VRAM budget refused the static layer (" + size + "², ~"
                    + megabytes(size) + " MiB on top of the live atlas) — dynamic frames redraw"
                    + " in full until VRAM frees up");
            }

            return;
        }

        staticTexture = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(staticTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_BORDER_COLOR,
            new float[] { 1F, 1F, 1F, 1F });
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL14.GL_DEPTH_COMPONENT24,
            size, size, 0, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, null);

        staticColorTexture = TextureUtil.generateTextureId();

        GlStateManager._bindTexture(staticColorTexture);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
        GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
        GL11.glTexParameterfv(GL11.GL_TEXTURE_2D, GL13.GL_TEXTURE_BORDER_COLOR,
            new float[] { 1F, 1F, 1F, 1F });
        GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL11.GL_RGBA16,
            size, size, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_SHORT, null);

        if (GL11.glGetError() != GL11.GL_NO_ERROR)
        {
            /* The live atlas fit, the DOUBLE ask did not (GL_OUT_OF_MEMORY): drop the incomplete
             * pair and stay unallocated — ShadowMapper falls back to baking straight into the
             * live tiles, the pre-split behaviour, instead of sampling a broken attachment.
             * retried lazily on the next dynamic frame, so freed VRAM self-heals. */
            GlStateManager._deleteTexture(staticTexture);
            GlStateManager._deleteTexture(staticColorTexture);
            staticTexture = -1;
            staticColorTexture = -1;

            log("shadow atlas: the driver refused the static layer (" + size + "², ~"
                + megabytes(size) + " MiB on top of the live atlas) — dynamic frames redraw"
                + " in full until VRAM frees up");

            return;
        }

        staticFbo = GlStateManager.glGenFramebuffers();

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, staticFbo);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_DEPTH_ATTACHMENT,
            GL11.GL_TEXTURE_2D, staticTexture, 0);
        GlStateManager._glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0,
            GL11.GL_TEXTURE_2D, staticColorTexture, 0);

        /* Depth-only by default, exactly like the live FBO — the glass pass and the blit name
         * the colour attachment explicitly when they need it. */
        GL11.glDrawBuffer(GL11.GL_NONE);
        GL11.glReadBuffer(GL11.GL_NONE);

        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
    }

    /** Start a frame's allocation round. */
    public static void beginFrame()
    {
        frame++;

        /* Reallocation happens ONLY here, at a frame boundary: every tile is re-leased and
         * re-rendered each frame anyway, so dropping all leases while nothing is mid-draw is safe.
         * Rebuilding at the moment lease() failed would have deleted a texture other lights already
         * drew into this frame. The light that was refused renders unshadowed for that one frame. */
        int wanted = com.bbsvfx.vfxlights.VfxLightsAddon.shadowTileSize();
        int wantedBudget = com.bbsvfx.vfxlights.VfxLightsAddon.shadowMemoryStep();

        if (wanted != requestedTile || wantedBudget != budgetStep)
        {
            log("shadow quality changed: tile " + requestedTile + "px -> " + wanted
                + "px, memory cap " + SIDE_LADDER[budgetStep] + "² -> " + SIDE_LADDER[wantedBudget] + "²");

            requestedTile = wanted;
            budgetStep = wantedBudget;
            /* Back to the smallest atlas: the tile count changes, so the demand-driven growth has to
             * be re-measured against the new size rather than inherited from the old one — including
             * a growth the OLD size asked for, which says nothing about the new one. */
            sideStep = 0;
            growPending = false;
            outOfMemory = false;
            /* A size the driver refused is still refused; but a LOWER memory cap may now make the
             * refused rung unreachable anyway, and raising the cap deserves a fresh try at the rungs
             * that were never attempted. refusedSide is a measured fact — it stays. */

            discard();
            ensure();

            return;
        }

        if (growPending)
        {
            growPending = false;

            if (texture != -1 && canGrow())
            {
                sideStep++;

                log("shadow atlas full — growing to " + sideFor(sideStep) + "²");

                discard();
                ensure();
            }
            else if (texture != -1 && sideStep + 1 < SIDE_LADDER.length
                && SIDE_LADDER[sideStep + 1] <= Math.min(driverMax,
                    SIDE_LADDER[Math.max(0, Math.min(budgetStep, SIDE_LADDER.length - 1))])
                && SIDE_LADDER[sideStep + 1] > vramFitSide()
                && SIDE_LADDER[sideStep + 1] != lastVramLogSide)
            {
                /* Said once per rung, not per frame: a perpetually full scene asks every frame. */
                lastVramLogSide = SIDE_LADDER[sideStep + 1];

                log("shadow atlas: VRAM budget refused growth to " + SIDE_LADDER[sideStep + 1]
                    + "² (~" + megabytes(SIDE_LADDER[sideStep + 1]) + " MiB) — only ~"
                    + (availableKb() - RESERVE_KB) / 1024L
                    + " MiB free past the reserve. Lamps past the current atlas render unshadowed;"
                    + " lower the Shadow quality setting or free VRAM.");
            }
        }
    }

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger("vfxlights");

    private static void log(String message)
    {
        LOG.info(message);
    }

    private static int freeTileCount()
    {
        int free = 0;

        for (int i = 0; i < TILE_COUNT; i++)
        {
            if (OWNER[i] == Long.MIN_VALUE)
            {
                free++;
            }
        }

        return free;
    }

    private static int longestFreeRun()
    {
        int longest = 0;
        int current = 0;

        for (int i = 0; i < TILE_COUNT; i++)
        {
            if (OWNER[i] == Long.MIN_VALUE)
            {
                current++;
                longest = Math.max(longest, current);
            }
            else
            {
                current = 0;
            }
        }

        return longest;
    }

    /**
     * Lease {@code count} consecutive tiles for {@code lightId}, returning the first tile's index, or
     * -1 when the atlas has nothing to spare.
     */
    public static int lease(long lightId, int count)
    {
        lastLeaseExisting = false;

        /* No atlas — nothing was allocated, or every rung was refused. Unshadowed beats sampling a
         * texture that does not exist. */
        if (texture == -1)
        {
            return -1;
        }

        Integer existing = LEASES.get(lightId);

        if (existing != null)
        {
            /* Validate the existing lease actually OWNS all count tiles. Two ways it can fail: a
             * spot animated past 120° switches 1 tile -> 6 (usesCube), and eviction can hand our
             * slots to another light. Blindly returning the old start rendered into slots owned by
             * OTHER lamps — their shadows flickered with ours. */
            boolean ownsAll = existing + count <= TILE_COUNT;

            if (ownsAll)
            {
                for (int i = existing; i < existing + count; i++)
                {
                    if (OWNER[i] != lightId)
                    {
                        ownsAll = false;

                        break;
                    }
                }
            }

            if (ownsAll)
            {
                touch(existing, count);
                lastLeaseExisting = true;

                return existing;
            }

            release(lightId);
        }

        int start = findRun(count, false);

        if (start < 0)
        {
            /* Nothing free — take from whoever has been out of shot longest. */
            start = findRun(count, true);
        }

        if (start < 0)
        {
            /* Ask for a bigger atlas next frame instead of degrading forever — demand-driven
             * growth, capped by the driver. This frame the light stays unshadowed. */
            if (canGrow())
            {
                growPending = true;
            }

            if (DEBUG)
            {
                log("lease failed: lightId=" + lightId + " count=" + count
                    + " free=" + freeTileCount() + "/" + TILE_COUNT
                    + " longestRun=" + longestFreeRun()
                    + (canGrow() ? " (growth queued)" : " (at driver cap)"));
            }

            return -1;
        }

        for (int i = start; i < start + count; i++)
        {
            long previous = OWNER[i];

            if (previous != Long.MIN_VALUE)
            {
                LEASES.remove(previous);
            }

            OWNER[i] = lightId;
            LAST_USED[i] = frame;
        }

        LEASES.put(lightId, start);

        return start;
    }

    private static void touch(int start, int count)
    {
        for (int i = start; i < Math.min(start + count, TILE_COUNT); i++)
        {
            LAST_USED[i] = frame;
        }
    }

    /** First run of {@code count} tiles that is free, or (when allowed) stale enough to reclaim. */
    private static int findRun(int count, boolean allowStale)
    {
        for (int start = 0; start + count <= TILE_COUNT; start++)
        {
            boolean usable = true;

            for (int i = start; i < start + count; i++)
            {
                boolean free = OWNER[i] == Long.MIN_VALUE;
                boolean stale = allowStale && frame - LAST_USED[i] >= STALE_FRAMES;

                if (!free && !stale)
                {
                    usable = false;

                    break;
                }
            }

            if (usable)
            {
                return start;
            }
        }

        return -1;
    }

    /** Bind the atlas for rendering into {@code tile} and clear it. */
    public static void beginTile(int tile)
    {
        rebindTile(tile);

        /* Colour clears to WHITE with alpha 1: "no glass on this ray" — the tint multiplies as a
         * no-op and the depth-in-alpha test can never trigger. */
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._clearColor(1F, 1F, 1F, 1F);
        GlStateManager._clearDepth(1.0D);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GlStateManager._colorMask(false, false, false, false);
    }

    /** Sub-rect {@code rebindTile} narrows the scissor to, tile-local texels {x0,y0,x1,y1}; null =
     *  the whole tile. Rides every rebind — including the ones inside the entity/glass passes — so
     *  the dynamic overlay stays clipped to its casters' projected bbox no matter how often vanilla
     *  layers make the pass re-aim the tile (IRLite's partial-tile lesson: the fill was the bake). */
    private static int[] subRect;

    /** Clip subsequent tile work to {@code rect} (tile-local texels) until a null clears it. */
    public static void setSubRect(int[] rect)
    {
        subRect = rect;
    }

    /**
     * Re-establish the framebuffer, viewport and scissor for {@code tile} WITHOUT clearing.
     *
     * <p>Needed because the entity pass runs vanilla render layers, and some of those bind the MAIN
     * framebuffer as part of their setup (item entities do, outside fabulous graphics). After that,
     * everything meant for the tile must be re-aimed at it or it lands on the screen instead.</p>
     */
    public static void rebindTile(int tile)
    {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);

        int x = (tile % TILES_ACROSS) * TILE_SIZE;
        int y = (tile / TILES_ACROSS) * TILE_SIZE;

        GlStateManager._viewport(x, y, TILE_SIZE, TILE_SIZE);

        /* Scissor as well as viewport: clears and stray draws must not wipe the neighbouring tiles. */
        GlStateManager._enableScissorTest();

        if (subRect != null)
        {
            int x0 = Math.max(0, Math.min(TILE_SIZE, subRect[0]));
            int y0 = Math.max(0, Math.min(TILE_SIZE, subRect[1]));
            int x1 = Math.max(x0, Math.min(TILE_SIZE, subRect[2]));
            int y1 = Math.max(y0, Math.min(TILE_SIZE, subRect[3]));

            GlStateManager._scissorBox(x + x0, y + y0, x1 - x0, y1 - y0);

            return;
        }

        GlStateManager._scissorBox(x, y, TILE_SIZE, TILE_SIZE);
    }

    /** Finish drawing tiles and restore whatever framebuffer and viewport the caller had. */
    public static void end(int previousFbo, int[] previousViewport)
    {
        GlStateManager._disableScissorTest();
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, previousFbo);
        GlStateManager._viewport(previousViewport[0], previousViewport[1],
            previousViewport[2], previousViewport[3]);
    }

    /** Bind the STATIC layer for rendering into {@code tile} and clear it — {@link #beginTile}'s
     * twin for the static bake. */
    public static void beginStaticTile(int tile)
    {
        rebindStaticTile(tile);

        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GlStateManager._colorMask(true, true, true, true);
        GlStateManager._clearColor(1F, 1F, 1F, 1F);
        GlStateManager._clearDepth(1.0D);
        GlStateManager._clear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT, false);
        GL11.glDrawBuffer(GL11.GL_NONE);
        GlStateManager._colorMask(false, false, false, false);
    }

    /** Re-establish the STATIC framebuffer, viewport and scissor for {@code tile} without
     * clearing — needed when a pass rebinds the live atlas (or the main framebuffer) mid-draw,
     * the same reason {@link #rebindTile} exists. */
    public static void rebindStaticTile(int tile)
    {
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, staticFbo);

        int x = (tile % TILES_ACROSS) * TILE_SIZE;
        int y = (tile / TILES_ACROSS) * TILE_SIZE;

        GlStateManager._viewport(x, y, TILE_SIZE, TILE_SIZE);

        GlStateManager._enableScissorTest();
        GlStateManager._scissorBox(x, y, TILE_SIZE, TILE_SIZE);
    }

    /**
     * Copy {@code count} tiles of the static layer over the same tiles of the live atlas, depth
     * AND colour. This is the cheap half of the static/live split: while a dynamic caster is in
     * range the finished static bake is blitted back each frame and only the dynamic pass draws
     * on top, instead of re-rendering the whole baked world per frame.
     *
     * <p>FBO-to-FBO {@code glBlitFramebuffer}, not {@code glCopyImageSubData}: the copy is one
     * call there, but that entry point is GL 4.3 and Minecraft asks for a 3.2 core context — this
     * codebase already lost a machine to a too-new GL call (see the class javadoc). NEAREST
     * because the texel grids are identical, and the colour map's alpha IS a depth: filtering it
     * would invent values between texels.</p>
     */
    public static void blitStaticToLive(int firstTile, int count)
    {
        blitStaticToLive(firstTile, count, -1);
    }

    /**
     * The masked variant: only tiles whose bit is set in {@code faceMask} are copied. A dynamic
     * frame restores just the faces a caster actually looks at (plus the ones it looked at LAST
     * frame — they still hold its silhouette); faces with no caster traffic keep the pixels they
     * already had, so the blit's fill cost follows the casters, not the tile count.
     */
    public static void blitStaticToLive(int firstTile, int count, int faceMask)
    {
        if (staticFbo == -1)
        {
            return;
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissorWas = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, staticFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);

        /* Both FBOs rest with read/draw buffer NONE (depth-only default) — the colour half of
         * the blit goes nowhere unless attachment 0 is named explicitly. */
        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

        /* The tile passes keep scissor on; it would clip the blit to the last tile's rect. */
        if (scissorWas)
        {
            GlStateManager._disableScissorTest();
        }

        for (int i = 0; i < count; i++)
        {
            if ((faceMask & (1 << i)) == 0)
            {
                continue;
            }

            int tile = firstTile + i;
            int x = (tile % TILES_ACROSS) * TILE_SIZE;
            int y = (tile / TILES_ACROSS) * TILE_SIZE;

            GL30.glBlitFramebuffer(x, y, x + TILE_SIZE, y + TILE_SIZE,
                x, y, x + TILE_SIZE, y + TILE_SIZE,
                GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }

        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glDrawBuffer(GL11.GL_NONE);

        if (scissorWas)
        {
            GlStateManager._enableScissorTest();
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    /**
     * The rect variant of the masked blit: per face only the casters' projected bbox is copied
     * ({@code rects} flat {x0,y0,x1,y1} × count faces, tile-local texels — a full-tile rect where
     * no tighter box was computed). A moving caster dirties only its own silhouette's texels, so
     * the restore follows the bbox instead of the whole face — at 1024px+ tiles the full-face copy
     * was the larger half of a dynamic frame's fill.
     */
    public static void blitStaticToLive(int firstTile, int count, int faceMask, int[] rects)
    {
        if (rects == null)
        {
            blitStaticToLive(firstTile, count, faceMask);

            return;
        }

        if (staticFbo == -1)
        {
            return;
        }

        int previousRead = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int previousDraw = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
        boolean scissorWas = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, staticFbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fbo);

        GL11.glReadBuffer(GL30.GL_COLOR_ATTACHMENT0);
        GL11.glDrawBuffer(GL30.GL_COLOR_ATTACHMENT0);

        if (scissorWas)
        {
            GlStateManager._disableScissorTest();
        }

        for (int i = 0; i < count; i++)
        {
            if ((faceMask & (1 << i)) == 0)
            {
                continue;
            }

            int tile = firstTile + i;
            int x = (tile % TILES_ACROSS) * TILE_SIZE;
            int y = (tile / TILES_ACROSS) * TILE_SIZE;
            int x0 = Math.max(0, Math.min(TILE_SIZE, rects[i * 4]));
            int y0 = Math.max(0, Math.min(TILE_SIZE, rects[i * 4 + 1]));
            int x1 = Math.max(x0, Math.min(TILE_SIZE, rects[i * 4 + 2]));
            int y1 = Math.max(y0, Math.min(TILE_SIZE, rects[i * 4 + 3]));

            if (x1 == x0 || y1 == y0)
            {
                continue;
            }

            GL30.glBlitFramebuffer(x + x0, y + y0, x + x1, y + y1,
                x + x0, y + y0, x + x1, y + y1,
                GL11.GL_DEPTH_BUFFER_BIT | GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);
        }

        GL11.glReadBuffer(GL11.GL_NONE);
        GL11.glDrawBuffer(GL11.GL_NONE);

        if (scissorWas)
        {
            GlStateManager._enableScissorTest();
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, previousRead);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, previousDraw);
    }

    public static void release(long lightId)
    {
        Integer start = LEASES.remove(lightId);

        if (start == null)
        {
            return;
        }

        for (int i = 0; i < TILE_COUNT; i++)
        {
            if (OWNER[i] == lightId)
            {
                OWNER[i] = Long.MIN_VALUE;
            }
        }
    }
}
