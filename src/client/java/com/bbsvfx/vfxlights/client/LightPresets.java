package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.values.LightAir;
import com.bbsvfx.vfxlights.forms.values.LightStyle;
import com.bbsvfx.vfxlights.forms.values.LightToon;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Built-in looks: a one-click starting point that stamps the SHARED dials (shape stays per-lamp).
 * The gaffer's answer to a wall of sliders — pick an intent, adjust from there.
 *
 * <p><b>Identified by id, not by ordinal.</b> The lamp remembers which look it was stamped from
 * ({@link LightForm#preset}), so the picker still tells the truth after a reload and after switching
 * lamps. An id survives reordering and inserting a look in the middle; the ordinal the old
 * {@code UICirculate} stored did not.</p>
 */
public final class LightPresets
{
    /** One look: the id stored on the form and the name shown in the picker. */
    public static final class Preset
    {
        public final String id;
        public final IKey label;

        private Preset(String id, IKey label)
        {
            this.id = id;
            this.label = label;
        }
    }

    private static final List<Preset> PRESETS = new ArrayList<>();

    static
    {
        PRESETS.add(new Preset("soft_key", LightKeys.PRESET_SOFT_KEY));
        PRESETS.add(new Preset("hard_key", LightKeys.PRESET_HARD_KEY));
        PRESETS.add(new Preset("rim", LightKeys.PRESET_RIM));
        PRESETS.add(new Preset("noir", LightKeys.PRESET_NOIR));
        PRESETS.add(new Preset("anime", LightKeys.PRESET_ANIME));
        PRESETS.add(new Preset("foggy", LightKeys.PRESET_FOGGY));
        PRESETS.add(new Preset("blender_rim", LightKeys.PRESET_BLENDER_RIM));
    }

    private LightPresets()
    {}

    public static List<Preset> all()
    {
        return Collections.unmodifiableList(PRESETS);
    }

    /** Names as they read RIGHT NOW — resolved per call, so a language switch is picked up. */
    public static List<String> labels()
    {
        List<String> labels = new ArrayList<>();

        for (Preset preset : PRESETS)
        {
            labels.add(preset.label.get());
        }

        return labels;
    }

    public static Preset byId(String id)
    {
        for (Preset preset : PRESETS)
        {
            if (preset.id.equals(id))
            {
                return preset;
            }
        }

        return null;
    }

    public static Preset byLabel(String label)
    {
        for (Preset preset : PRESETS)
        {
            if (preset.label.get().equals(label))
            {
                return preset;
            }
        }

        return null;
    }

    /**
     * Stamp a look onto the lamp.
     *
     * <p>Every dial ANY look touches is stamped by EVERY look, so picking one never leaves leftovers
     * from the previous one (Noir's haze used to survive a switch to Soft key).</p>
     */
    public static void apply(LightForm form, String id)
    {
        Preset preset = byId(id);

        if (form == null || preset == null)
        {
            return;
        }

        /* The grouped dials are edited in place, so the notification the film and the undo stack
         * listen for is raised around the whole stamp rather than per dial. */
        LightAir air = form.air.getOriginalValue();
        LightStyle style = form.style.getOriginalValue();
        LightToon toon = form.toon.getOriginalValue();

        form.air.preNotify();
        form.style.preNotify();
        form.toon.preNotify();

        /* Neutral ground every look starts from; the branches below override what their look needs. */
        form.shadows.set(true);
        form.useTemperature.set(true);
        form.falloffPhysical.set(false);
        air.haze = 0F;
        toon.enabled = false;
        toon.softness = 0.03F;
        toon.shadowTint = 0.6F;
        toon.shadowLevel = 0.22F;

        /* Blender rim touches the source radius too, so every look restamps it (0.25 = the form
         * default). Area and ambient have no radius to stamp. */
        if (form instanceof PointLightForm point)
        {
            point.sourceRadius.set(0.25F);
        }
        else if (form instanceof SpotLightForm spot)
        {
            spot.sourceRadius.set(0.25F);
        }

        switch (preset.id)
        {
            case "soft_key" ->
            {
                /* Warm, broad, forgiving. */
                form.intensity.set(5F);
                form.shadowSoftness.set(1.0F);
                form.temperature.set(5200F);
                style.rim = 0F;
            }
            case "hard_key" ->
            {
                /* Crisp neutral daylight. */
                form.intensity.set(6F);
                form.shadowSoftness.set(0F);
                form.temperature.set(5600F);
                style.rim = 0F;
            }
            case "rim" ->
            {
                /* Cool edge, little fill. */
                form.intensity.set(3F);
                form.shadowSoftness.set(0.3F);
                form.temperature.set(6500F);
                style.rim = 1.4F;
            }
            case "noir" ->
            {
                /* Punchy, cool, a breath of haze. */
                form.intensity.set(7F);
                form.shadowSoftness.set(0F);
                form.temperature.set(6800F);
                style.rim = 0.3F;
                air.haze = 0.6F;
            }
            case "anime" ->
            {
                /* Cel two-tone, warm key, cool shadow, strong rim. */
                form.intensity.set(5F);
                form.shadowSoftness.set(0.05F);
                form.temperature.set(4800F);
                toon.enabled = true;
                toon.softness = 0.03F;
                toon.shadowTint = 0.65F;
                toon.shadowLevel = 0.22F;
                style.rim = 1.2F;
            }
            case "foggy" ->
            {
                /* Warm bulb glowing into mist. */
                form.intensity.set(4F);
                form.shadowSoftness.set(0.5F);
                form.temperature.set(3200F);
                air.haze = 1.5F;
                style.rim = 0F;
            }
            case "blender_rim" ->
            {
                /* The Cycles bare-point look — physical 1/d², a small soft source (0.25, stamped in
                 * the baseline), a cool edge. Intensity runs high because inverse-square falls fast.
                 * The lamp belongs BEHIND the subject — presets never move forms. */
                form.intensity.set(8F);
                form.shadowSoftness.set(1F);
                form.temperature.set(5600F);
                form.falloffPhysical.set(true);
                style.rim = 0.6F;
            }
            /* Unreachable: the id came from the table above. Bailing out here would leave the
             * pre-notifications above unmatched, so the branch simply stamps nothing extra. */
            default -> {}
        }

        form.air.postNotify();
        form.style.postNotify();
        form.toon.postNotify();

        form.preset.set(preset.id);
        form.presetTweaked.set(false);
    }
}
