package com.bbsvfx.bbsvfx.module;

import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import net.fabricmc.loader.api.FabricLoader;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of installed VFX modules. Collects every mod that declares a {@code "vfx-module"} entrypoint
 * and owns their enable/disable toggles (bound when the "VFX" settings category is built). This is the
 * hub side of the plug-in contract described on {@link VfxModule}.
 */
public final class VfxModules
{
    /** Fabric entrypoint key a module declares in its fabric.mod.json to plug into the hub. */
    public static final String ENTRYPOINT = "vfx-module";

    /**
     * Run whenever any module's toggle flips — set by the client to rebuild BBS's form picker so a
     * module's forms appear/disappear live (a module hides its forms from the picker while disabled).
     * Null on a dedicated server, where no settings UI exists.
     */
    public static Runnable onToggleChanged;

    private static List<VfxModule> modules;
    private static final Map<String, VfxModule> byId = new HashMap<>();
    private static final Map<String, ValueBoolean> toggles = new HashMap<>();

    private VfxModules()
    {
    }

    /** All installed modules, discovered once from the {@code "vfx-module"} entrypoints. */
    public static List<VfxModule> all()
    {
        if (modules == null)
        {
            List<VfxModule> found = new ArrayList<>();

            for (var container : FabricLoader.getInstance().getEntrypointContainers(ENTRYPOINT, VfxModule.class))
            {
                try
                {
                    VfxModule module = container.getEntrypoint();
                    found.add(module);
                    byId.put(module.id(), module);
                }
                catch (Throwable t)
                {
                    System.err.println("[VFX] Failed to load a vfx-module entrypoint: " + t);
                }
            }

            modules = found;
        }

        return modules;
    }

    /** Settings row / config key for a module's toggle. */
    public static String configKey(VfxModule module)
    {
        return "module_" + module.id();
    }

    /**
     * Bind a module's enable toggle — called by the settings builder as the VFX category is created.
     * A change callback dispatches {@link VfxModule#onEnabled()} / {@link VfxModule#onDisabled()} and
     * fires {@link #onToggleChanged} so the UI (form picker) can refresh.
     */
    public static void bindToggle(String moduleId, ValueBoolean toggle)
    {
        toggles.put(moduleId, toggle);

        toggle.postCallback((value, flag) ->
        {
            VfxModule module = byId.get(moduleId);

            if (module != null)
            {
                if (Boolean.TRUE.equals(toggle.get()))
                {
                    module.onEnabled();
                }
                else
                {
                    module.onDisabled();
                }
            }

            if (onToggleChanged != null)
            {
                onToggleChanged.run();
            }
        });
    }

    /**
     * Whether a module is enabled. Defaults to {@code true} when no toggle is bound yet (e.g. a feature
     * queried before the settings module has loaded), so a module is never silently dark at startup.
     */
    public static boolean isEnabled(String moduleId)
    {
        ValueBoolean toggle = toggles.get(moduleId);

        return toggle == null || Boolean.TRUE.equals(toggle.get());
    }
}
