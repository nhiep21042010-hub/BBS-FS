package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.api.client.events.L10nReloadEvent;
import mchorse.bbs_mod.api.Subscribe;
import mchorse.bbs_mod.l10n.L10n;
import com.bbsvfx.bbsvfx.module.VfxModule;
import com.bbsvfx.bbsvfx.module.VfxModules;

/**
 * Supplies localized labels for the addon's "bbsvfx" settings group at runtime instead of
 * shipping a string source pack. On every {@link L10nReloadEvent} (and once at client init) it sets
 * the {@code content} of the lang keys directly on the loaded string map, picking en/ru by the
 * current language. Keeps base BBS string files untouched and follows language switches.
 *
 * <p>Key prefix mirrors the settings path: {@code bbs.config.personalization.bbsvfx.*}.</p>
 */
public class BbsVfxStrings
{
    private static final String PREFIX = "bbs.config.personalization.bbsvfx.";
    private static final String VFX_PREFIX = "bbs.config.vfx.";

    public static void apply(L10n l10n)
    {
        if (l10n == null)
        {
            return;
        }

        boolean ru = "ru_ru".equals(BBSSettings.language.get());

        set(l10n, "title", "BBS VFX", "BBS VFX", ru);
        set(l10n, "enabled", "Enabled", "Включено", ru);
        set(l10n, "enabled-comment",
            "Master switch for the BBS VFX addon.",
            "Главный переключатель аддона BBS VFX.", ru);

        /* The addon's own "VFX" settings category (sidebar title). */
        set(l10n, VFX_PREFIX, "title", "VFX", "VFX", ru);

        /* "AE tracking" toggle in the VFX category. */
        set(l10n, VFX_PREFIX, "ae_tracking", "AE tracking", "Трекинг AE", ru);
        set(l10n, VFX_PREFIX, "ae_tracking-comment",
            "Also export an After Effects camera script (.jsx) next to the rendered video.",
            "Дополнительно экспортировать скрипт камеры для After Effects (.jsx) рядом с видео.", ru);

        /* "GLB export" toggle in the VFX category. */
        set(l10n, VFX_PREFIX, "glb_export", "GLB export", "Экспорт GLB", ru);
        set(l10n, VFX_PREFIX, "glb_export-comment",
            "Also export a binary glTF (.glb) animated camera next to the rendered video, for Blender.",
            "Дополнительно экспортировать анимированную камеру в binary glTF (.glb) рядом с видео, для Blender.", ru);

        /* "Capture edge roughness" slider in the VFX category (destruction wand). */
        set(l10n, VFX_PREFIX, "destruction_roughness", "Capture edge roughness (%)", "Рваность краёв захвата (%)", ru);
        set(l10n, VFX_PREFIX, "destruction_roughness-comment",
            "Destruction wand: feather the selection edges with noise so the cut hole and the structure get an organic ragged outline instead of a perfect box. 0 = exact selection.",
            "Жезл разрушения: края выделения зашумляются, чтобы вырез и структура имели рваный органичный контур вместо идеального параллелепипеда. 0 = точное выделение.", ru);

        /* One row per installed VFX module (VFX LIGHTS, …). Label = the module's own display name;
         * comment invites toggling. Keys mirror the toggles added in BBSSettingsMixin. */
        for (VfxModule module : VfxModules.all())
        {
            String key = VFX_PREFIX + VfxModules.configKey(module);
            l10n.getKey(key).content = module.displayName();
            l10n.getKey(key + "-comment").content = ru
                ? "Включить или выключить модуль " + module.displayName() + "."
                : "Enable or disable the " + module.displayName() + " module.";
        }

        /* Display name of the "Follow curve" camera modifier clip (its type title in the camera editor). */
        l10n.getKey("bbs.ui.camera.clips.bbsvfx:follow_curve").content = ru ? "Движение по кривой" : "Follow curve";

        /* Display name of the "Impact" camera modifier clip (impact-frame full-screen effect). */
        l10n.getKey("bbs.ui.camera.clips.bbsvfx:impact").content = ru ? "Импакт-кадр" : "Impact frame";
    }

    private static void set(L10n l10n, String suffix, String en, String ru, boolean useRu)
    {
        set(l10n, PREFIX, suffix, en, ru, useRu);
    }

    private static void set(L10n l10n, String prefix, String suffix, String en, String ru, boolean useRu)
    {
        l10n.getKey(prefix + suffix).content = useRu ? ru : en;
    }

    // Public — the BBS event bus invokes @Subscribe methods via reflection.
    @Subscribe
    public void onL10nReload(L10nReloadEvent event)
    {
        apply(event.l10n);
    }
}
