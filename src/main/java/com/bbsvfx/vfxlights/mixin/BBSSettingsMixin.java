package com.bbsvfx.vfxlights.mixin;

import com.bbsvfx.vfxlights.VfxLightsAddon;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The addon's own <b>VFX LIGHTS</b> settings category — global dials that belong to the renderer
 * rather than to any one lamp, so they cannot live on a light form.
 *
 * <p>Injected at {@code TAIL}, after BBS has finished building all of its categories, because
 * {@code builder.category()} also flips the builder's "current category" and creating ours
 * mid-{@code register} would divert BBS's own following options into it.</p>
 *
 * <p>A category of our OWN rather than a row in the hub's "VFX" category, which the BBS VFX hub
 * builds from its own {@code TAIL} injection: {@code SettingsBuilder.category(id)} REPLACES whatever
 * group that id held, so two mods calling it for the same id would wipe each other depending on
 * which mixin happened to run first. Separate ids make the order irrelevant.</p>
 */
@Mixin(BBSSettings.class)
public abstract class BBSSettingsMixin
{
    @Inject(method = "register", at = @At("TAIL"))
    private static void vfxlights$registerCategory(SettingsBuilder builder, CallbackInfo ci)
    {
        builder.category("vfxlights", Icons.LIGHT);

        /* ONE preset row driving every quality knob (see VfxLightsAddon's table): resolution,
         * atlas memory, filtering, volumetric density, the actor mm-map. MODES rather than a bare
         * int — the four named levels are the whole point of the dial. Read by the render code
         * every frame; no callbacks, so a config-file value reaches the renderer like a click. */
        VfxLightsAddon.qualityPreset = builder.getInt("quality_preset",
            VfxLightsAddon.DEFAULT_QUALITY_PRESET, 0, VfxLightsAddon.MAX_QUALITY_PRESET);
        /* IKey.constant, not LightKeys: this mixin is common-side and the client package is
         * off-limits here. The level names are near-universal loanwords; the localized row label
         * and the comment tooltip carry what each level actually changes. */
        VfxLightsAddon.qualityPreset.modes(
            mchorse.bbs_mod.l10n.keys.IKey.constant("Low"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("Medium"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("High"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("Ultra"));

        /* The second skin layer's own dial (see VfxLightsAddon.actorPreset): users judge the
         * body's mm shadows apart from the set's, and the main preset's tile size should not
         * dictate their density. Off / Medium / High / Ultra — High tightens the fit cone for
         * density, Ultra tightens it to the coverage-math ceiling (close-up mode). */
        VfxLightsAddon.actorPreset = builder.getInt("actor_preset",
            VfxLightsAddon.DEFAULT_ACTOR_PRESET, 0, VfxLightsAddon.MAX_ACTOR_PRESET);
        VfxLightsAddon.actorPreset.modes(
            mchorse.bbs_mod.l10n.keys.IKey.constant("Off"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("Medium"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("High"),
            mchorse.bbs_mod.l10n.keys.IKey.constant("Ultra"));
    }
}
