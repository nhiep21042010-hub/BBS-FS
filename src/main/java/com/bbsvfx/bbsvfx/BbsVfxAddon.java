package com.bbsvfx.bbsvfx;

import mchorse.bbs_mod.api.BBSAddonMod;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.api.events.RegisterSourcePacksEvent;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.utils.keyframes.factories.KeyframeFactories;
import com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories;
import com.bbsvfx.bbsvfx.resources.BbsVfxAssetsSourcePack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BBS addon entry point (server/common side). Wired through the {@code bbs-addon} entrypoint in
 * {@code fabric.mod.json}; BBS discovers every {@link BBSAddonMod} and forwards events to its
 * {@link Subscribe @Subscribe} methods.
 *
 * <p>This is the template skeleton — it does three things and nothing else:</p>
 * <ol>
 *   <li>registers an {@link mchorse.bbs_mod.resources.ISourcePack} so the addon can serve its own
 *       assets / override BBS's (see {@link BbsVfxAssetsSourcePack});</li>
 *   <li>holds the addon's settings as static fields, populated by
 *       {@code com.bbsvfx.bbsvfx.mixin.BBSSettingsMixin};</li>
 *   <li>logs a line at construction so you can confirm the addon loaded.</li>
 * </ol>
 *
 * <p>Replace the example {@link #enabled} setting and the empty source pack with the real feature
 * once the skeleton is verified in-game.</p>
 */
public class BbsVfxAddon implements BBSAddonMod
{
    public static final String MOD_ID = "bbsvfx";

    private static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** Example master toggle for the addon. Default true. Registered by {@code BBSSettingsMixin}. */
    public static ValueBoolean enabled;

    /** Nested settings group under BBS's personalization category. */
    public static ValueGroup settingsGroup;

    /**
     * "AE tracking" toggle in the addon's VFX category. When on, every video render also writes an
     * After Effects camera script ({@code .jsx}) next to the clip. Registered by
     * {@code BBSSettingsMixin}; consumed by {@code com.bbsvfx.bbsvfx.client.BbsVfxAeTracker}.
     */
    public static ValueBoolean aeTracking;

    /**
     * "GLB export" toggle in the addon's VFX category. When on, every video render also writes a
     * binary glTF ({@code .glb}) camera next to the clip — an animated perspective camera for Blender
     * (Replay-Mod-style). Registered by {@code BBSSettingsMixin}; consumed by {@code BbsVfxAeTracker}
     * via {@code com.bbsvfx.bbsvfx.client.BbsVfxGlbWriter}.
     */
    public static ValueBoolean glbExport;

    /**
     * "Capture edge roughness" (0..100%) in the VFX category: when > 0, the destruction wand capture
     * FEATHERS the box edges — blocks near the selection faces are skipped with a noise probability
     * rising toward the boundary, so the cut hole and the captured structure get an organic ragged
     * outline instead of a perfect rectangle (tester request). Registered by {@code BBSSettingsMixin};
     * consumed by {@code com.bbsvfx.bbsvfx.client.DestructionCapture}.
     */
    public static mchorse.bbs_mod.settings.values.numeric.ValueInt destructionRoughness;

    public BbsVfxAddon()
    {
        /* Register the smear / lines pose keyframe factories in BBS's global factory registry. A
         * KeyframeChannel serialises its factory by its identity key here (and resolves it back on
         * load) — without this, the smear_frames / motion_lines channels can't round-trip and fall back
         * to the plain "pose" factory. Must run before any film/scene is loaded (mod init does). */
        KeyframeFactories.FACTORIES.put("smear_frames", BbsVfxPoseFactories.SMEAR);
        KeyframeFactories.FACTORIES.put("motion_lines", BbsVfxPoseFactories.LINES);

        /* The single whole-form smear channel (non-model forms) — its own factory instance. */
        KeyframeFactories.FACTORIES.put("smear", BbsVfxPoseFactories.FORM_SMEAR);

        /* The blend_mode channel uses its own factory instance (dropdown editor + step interpolation);
         * key it here so the channel round-trips instead of falling back to the plain integer factory. */
        KeyframeFactories.FACTORIES.put("blend_mode", com.bbsvfx.bbsvfx.forms.BbsVfxKeyframeFactories.BLEND_MODE);

        /* The per-bone blend Pose channel (bone list + per-bone mode); dedicated pose factory instance. */
        KeyframeFactories.FACTORIES.put("blend", com.bbsvfx.bbsvfx.forms.BbsVfxPoseFactories.BLEND);

        LOG.info("BBS VFX loaded (bbs-addon entrypoint)");
    }

    /**
     * Register the addon's source pack with BBS's {@link mchorse.bbs_mod.resources.AssetProvider}.
     *
     * <p>{@code AssetProvider} serves the FIRST pack whose {@code hasAsset} matches, so the choice of
     * call matters:</p>
     * <ul>
     *   <li>{@code register(...)} — appends; use it to add NEW assets in your own namespace.</li>
     *   <li>{@code registerFirst(...)} — prepends; required to OVERRIDE assets BBS already serves
     *       (BBS adds its internal pack before posting this event).</li>
     * </ul>
     *
     * <p>{@link Subscribe @Subscribe} methods must be public — BBS's event bus invokes them via
     * reflection.</p>
     */
    @Subscribe
    public void registerSourcePacks(RegisterSourcePacksEvent event)
    {
        event.provider.registerFirst(new BbsVfxAssetsSourcePack());
    }
}
