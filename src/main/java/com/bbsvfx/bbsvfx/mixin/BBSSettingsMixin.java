package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.settings.SettingsBuilder;
import mchorse.bbs_mod.settings.values.core.ValueGroup;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import com.bbsvfx.bbsvfx.BbsVfxAddon;
import com.bbsvfx.bbsvfx.module.VfxModule;
import com.bbsvfx.bbsvfx.module.VfxModules;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Example main-side mixin: registers the addon's settings inside BBS's own <b>personalization</b>
 * category as a nested "bbsvfx" group. Injected right after the second
 * {@code builder.category(String, Icon)} call in {@code BBSSettings.register} (ordinal 1 =
 * the personalization category).
 *
 * <p>This is the canonical pattern for an addon mixin — keep injections narrow ({@code @Inject} /
 * {@code @ModifyExpressionValue} / {@code @Redirect}) so they survive BBS upstream updates. The
 * group persists to config; note that the settings UI does not auto-render nested groups, so a
 * dedicated UI mixin is needed to show it (see {@code UISettingsOverlayPanelMixin} in the
 * resfreshed-addon reference project). For the template we only prove the registration works.</p>
 */
@Mixin(BBSSettings.class)
public abstract class BBSSettingsMixin
{
    @Inject(
        method = "register",
        at = @At(
            value = "INVOKE",
            target = "Lmchorse/bbs_mod/settings/SettingsBuilder;category(Ljava/lang/String;Lmchorse/bbs_mod/ui/utils/icons/Icon;)Lmchorse/bbs_mod/settings/SettingsBuilder;",
            ordinal = 1,
            shift = At.Shift.AFTER
        )
    )
    private static void bbsvfx$registerSettings(SettingsBuilder builder, CallbackInfo ci)
    {
        ValueBoolean enabled = new ValueBoolean("enabled", true);

        ValueGroup group = new ValueGroup("bbsvfx");
        group.icon = Icons.GEAR;
        group.add(enabled);

        builder.getCategory().add(group);

        BbsVfxAddon.enabled = enabled;
        BbsVfxAddon.settingsGroup = group;
    }

    /**
     * Registers the addon's own <b>VFX</b> category at the end of {@code BBSSettings.register} and adds
     * its toggles there. Injected at {@code TAIL} — after BBS has finished building all its categories —
     * because {@code builder.category()} also flips the builder's "current category", so creating ours
     * mid-{@code register} would divert BBS's own following options into it. As a result the VFX
     * category lists last in the settings sidebar.
     *
     * <p>{@code builder.category(id, icon)} both creates the category (UI auto-renders it from
     * {@code settings.categories}) and makes it current; {@code builder.getBoolean} then appends each
     * toggle to it and wires it into config (de)serialization. Labels come from {@code BbsVfxStrings}
     * at runtime ({@code bbs.config.vfx.*}).</p>
     */
    @Inject(method = "register", at = @At("TAIL"))
    private static void bbsvfx$registerVfxCategory(SettingsBuilder builder, CallbackInfo ci)
    {
        builder.category("vfx", Icons.SCENE);

        BbsVfxAddon.aeTracking = builder.getBoolean("ae_tracking", false);
        BbsVfxAddon.glbExport = builder.getBoolean("glb_export", false);
        BbsVfxAddon.destructionRoughness = builder.getInt("destruction_roughness", 0, 0, 100);

        /* One on/off toggle per installed VFX module (VFX LIGHTS, and future ones). Discovered from the
         * "vfx-module" entrypoints; the module reads its toggle through VfxModules.isEnabled(id) to gate
         * all of its work. Default ON so an installed module lights up out of the box. */
        for (VfxModule module : VfxModules.all())
        {
            ValueBoolean toggle = builder.getBoolean(VfxModules.configKey(module), true);
            VfxModules.bindToggle(module.id(), toggle);
        }
    }
}
