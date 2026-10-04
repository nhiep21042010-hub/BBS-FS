package com.bbsvfx.vfxlights;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bbsvfx.vfxlights.resources.VfxLightsAssetsSourcePack;

/**
 * BBS addon entry point, wired through the {@code bbs-addon} entrypoint in {@code fabric.mod.json}.
 *
 * <p><b>What this addon is.</b> Artificial lighting for BBS films. Its reason to exist is the two source
 * types nothing in Minecraft offers: AREA lights, which have physical size and therefore soft shadows and
 * shaped speculars, and AMBIENT fill, which controls how deep the shadows go. Point and spot round out
 * the set so a scene can be lit entirely from here.</p>
 *
 * <p><b>Lights are not only forms.</b> Placing a lamp by hand is one way to make light; the other is an
 * effect making its own. An explosion's fireball, a beam's core, burning debris — these are ephemeral
 * sources, born and dying by the hundred inside an effect, and no one is going to key them by hand. So
 * the registry this addon is built around accepts lights submitted per-frame from code as readily as it
 * collects them from the form tree, and that is what will let BBS VFX light the world with its own
 * explosions.</p>
 *
 * <p><b>Independent implementation.</b> Written from scratch against the BBS and Minecraft APIs.</p>
 */
public class VfxLightsAddon implements BBSAddonMod
{
    public static final String MOD_ID = "vfxlights";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /**
     * The quality preset, bound by {@code BBSSettingsMixin} as the addon's settings category is
     * built; null until then, and null means the default.
     *
     * <p>ONE preset that drives every cost knob the renderer has — the user asked for a single
     * obvious lever over granular dials. Each level sets, as one table:</p>
     * <ul>
     *   <li>shadow tile resolution (texels per lamp) and the atlas memory ceiling that buys lamps
     *       back at that resolution;</li>
     *   <li>shadow filtering (taps per pixel, kernel width);</li>
     *   <li>volumetric march density (samples per block) and its step caps;</li>
     *   <li>the actor-fitted mm shadow map (second skin layer) — off at Low.</li>
     * </ul>
     *
     * <p>Held here rather than in the shadow package because the settings mixin is common-side and
     * the renderer is client-only: plain ints the render code polls each frame keep the two apart,
     * and it also means none of them needs a change callback of its own.</p>
     */
    public static mchorse.bbs_mod.settings.values.numeric.ValueInt qualityPreset;

    /** Low, Medium (default — every value the mod shipped with before the dial existed), High, Ultra. */
    public static final int DEFAULT_QUALITY_PRESET = 1;
    public static final int MAX_QUALITY_PRESET = 3;

    /* The preset table. Volumetric: samples per marched block, the step floor, the cap and the
     * bright-beam cap (brighter beams show grain sooner).
     *
     * Ultra's tile is 2048, not 4096 — measured, not guessed: 35 cube tiles at 4096 px force the
     * 24576² atlas (~7 GiB of depth+colour), and on a 12 GiB card the frame cost stopped scaling
     * with shader work entirely (the tap-count classification changed nothing at 5 fps) — the
     * limit was VRAM capacity and cache locality, not ALU. At 2048 the same lamps fit 16384²
     * (~3 GiB, ×4 warmer caches) and Ultra's quality headroom lives in the filter and volumetric
     * columns, where it actually shows. */
    private static final int[] PRESET_TILE = { 512, 1024, 2048, 2048 };
    private static final int[] PRESET_MEMORY = { 0, 2, 2, 2 };
    private static final int[] PRESET_FILTER = { 0, 1, 2, 3 };
    private static final float[] PRESET_VOL_DENSITY = { 2.0F, 4.0F, 6.0F, 8.0F };
    private static final float[] PRESET_VOL_MIN = { 8F, 16F, 24F, 32F };
    private static final float[] PRESET_VOL_MAX = { 32F, 64F, 96F, 128F };
    private static final float[] PRESET_VOL_MAX_BRIGHT = { 48F, 96F, 128F, 160F };
    /* (The actor mm-map moved to its own preset below — see actorPreset.) */

    /** The active preset index, clamped into the table; the default when settings are not built yet. */
    public static int qualityPreset()
    {
        return qualityPreset == null ? DEFAULT_QUALITY_PRESET
            : Math.max(0, Math.min(qualityPreset.get(), MAX_QUALITY_PRESET));
    }

    /** The tile side the preset asks for, in texels. The atlas clamps it against the driver and the
     *  memory rung; nothing else should. */
    public static int shadowTileSize()
    {
        return PRESET_TILE[qualityPreset()];
    }

    /** How far up the atlas' size ladder growth may go — an index, read by ShadowAtlas. */
    public static int shadowMemoryStep()
    {
        return PRESET_MEMORY[qualityPreset()];
    }

    /** Shadow filtering: 0 fast, 1 balanced, 2 high, 3 ultra. Shipped to the shaders as a float. */
    public static int shadowFilterStep()
    {
        return PRESET_FILTER[qualityPreset()];
    }

    /** Volumetric march: samples per block of travelled path. */
    public static float volStepsPerBlock()
    {
        return PRESET_VOL_DENSITY[qualityPreset()];
    }

    /** Volumetric march: the step floor (short beams) and the caps (long / bright beams). */
    public static float volMinSteps()
    {
        return PRESET_VOL_MIN[qualityPreset()];
    }

    public static float volMaxSteps()
    {
        return PRESET_VOL_MAX[qualityPreset()];
    }

    public static float volMaxStepsBright()
    {
        return PRESET_VOL_MAX_BRIGHT[qualityPreset()];
    }

    /** The actor-fitted millimetre shadow map (second skin layer) has its OWN dial — measured
     * 2026-08-08: users judge the body's shadows separately from the set's, and the main preset's
     *  tile size trades their density against atlas VRAM in ways the second layer should not
     *  inherit. 0 = off, 1 = on, 2 = on with a tightened fit cone (more texels on the body),
     *  3 = the sharpest fit the coverage math still holds (see actorFitScale). */
    public static mchorse.bbs_mod.settings.values.numeric.ValueInt actorPreset;
    public static final int DEFAULT_ACTOR_PRESET = 1;
    public static final int MAX_ACTOR_PRESET = 3;

    public static int actorPreset()
    {
        return actorPreset == null ? DEFAULT_ACTOR_PRESET
            : Math.max(0, Math.min(actorPreset.get(), MAX_ACTOR_PRESET));
    }

    /** The actor-fitted millimetre shadow map (second skin layer) — gated by its own preset. */
    public static boolean qualityActorTiles()
    {
        return actorPreset() > 0;
    }

    /** Fit-cone scale for the second layer's High mode: a 0.75-tightened cone lands ~1.3× the
     *  texel density on the body while leaving limbs inside (0.6 measured too tight — posed
     *  actors lost the fitted map past the torso, "в фильме теней нет"). Ultra pushes 0.65
     *  (~1.27 mm texels at a 2048 tile vs High's 1.46): the density-vs-coverage ceiling without
     *  a 2x2 tile block — the atlas leases CONSECUTIVE tile ranges only, and a 2x2 block is not
     *  one, so more texels per cone is not on the table. Coverage at Ultra = 1.30 blocks
     *  (0.65×1.6×1.25 margin), above the body+deadzone bound (0.9+0.26 = 1.16) but tighter than
     *  High's 1.50 — Ultra is the close-up mode, wide choreography belongs to High. */
    public static float actorFitScale()
    {
        return actorPreset() >= 3 ? 0.65F : actorPreset() >= 2 ? 0.75F : 1.0F;
    }

    public VfxLightsAddon()
    {
        LOG.info("VFX LIGHTS loaded");
    }

    /**
     * Hand BBS the addon's own assets — the icon atlas the light editors are drawn with.
     *
     * <p>{@code registerFirst} rather than {@code register}: BBS adds its internal pack before posting
     * this event, and going first is what lets a pack win a path. {@link Subscribe @Subscribe} methods
     * must be public — the event bus invokes them by reflection.</p>
     */
    @Subscribe
    public void registerSourcePacks(RegisterSourcePacksEvent event)
    {
        event.provider.registerFirst(new VfxLightsAssetsSourcePack());
    }
}
