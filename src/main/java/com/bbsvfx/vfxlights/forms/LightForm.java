package com.bbsvfx.vfxlights.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.base.BaseKeyframeFactoryValue;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.core.ValueString;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.ui.ValueStringKeys;
import mchorse.bbs_mod.utils.colors.Color;
import com.bbsvfx.vfxlights.forms.values.LightAir;
import com.bbsvfx.vfxlights.forms.values.LightFactories;
import com.bbsvfx.vfxlights.forms.values.LightStyle;
import com.bbsvfx.vfxlights.forms.values.LightToon;

/**
 * Shared base of every light in this addon: what colour it is, how bright, how far it reaches and what
 * it is allowed to touch. Concrete lights add their own shape on top.
 *
 * <p><b>Why a base class at all.</b> Every one of these values is read by the same code downstream — the
 * light registry, the shading backends, the export — so they have to mean the same thing in every light.
 * Keeping them here is what lets the registry treat an area light and a point light uniformly and only
 * branch where the geometry genuinely differs.</p>
 *
 * <p><b>Colour temperature.</b> A cinematographer reasons in kelvin, not in RGB: tungsten is 3200K, daylight
 * 5600K, and a scene reads as lit rather than tinted when its sources sit on the blackbody curve. So
 * {@link #temperature} is offered as an alternative authoring path — when {@link #useTemperature} is on,
 * it produces the colour and {@link #color} only tints it. The conversion lives in
 * {@link #effectiveColor()} so that every backend gets the same answer.</p>
 */
public abstract class LightForm extends Form
{
    /** Emitted colour. With {@link #useTemperature} on this multiplies the blackbody colour instead. */
    public final ValueColor color = new ValueColor("color", new Color(1F, 1F, 1F, 1F));
    /**
     * Brightness multiplier: 1 = unit brightness at one block, by the attenuation's normalisation.
     * Default 1.5 — with the capped, one-block-normalised falloff, intensity 1 already reads as a
     * unit-bright lamp; the old 4 blew every lit surface to pure white ("интенсивность очень сильная").
     */
    public final ValueFloat intensity = new ValueFloat("intensity", 1.5F, 0F, 50F);
    /** Distance in blocks at which the light is considered finished, so it can be culled. */
    public final ValueFloat range = new ValueFloat("range", 10F, 0.1F, 256F);

    /**
     * Physical falloff (the Blender look): inverse-square 1/d² with a soft window that reaches zero
     * at {@link #range}, instead of the default even pool that fills the range and cuts at its edge.
     * Physical gives the wrapping gradient on faces a cine lamp has; it wants a generous range
     * (≥10 blocks) so the window stays invisible. Default OFF so existing films keep their look.
     * Area lights are always physical (analytic form factors) and ignore this switch.
     */
    public final ValueBoolean falloffPhysical = new ValueBoolean("falloff_physical", false);

    /** Author the colour in kelvin instead of RGB. */
    public final ValueBoolean useTemperature = new ValueBoolean("use_temperature", false);
    /** Blackbody temperature in kelvin: 1900 candle, 3200 tungsten, 5600 daylight, 8000+ shade. */
    public final ValueFloat temperature = new ValueFloat("temperature", 5600F, 1000F, 15000F);

    /** Whether this light casts shadows at all. Off is both cheaper and a legitimate look (fill light). */
    public final ValueBoolean shadows = new ValueBoolean("shadows", true);

    /**
     * Shadow softness as a multiplier over the PHYSICAL penumbra (from the source's size): 0 = crisp
     * by decree, 1 = what the emitter's size dictates (the default — a panel softens like a panel
     * out of the box), above — exaggerated. Softness is not a style knob here; it is how much of
     * the physics to admit.
     */
    public final ValueFloat shadowSoftness = new ValueFloat("shadow_softness", 1F, 0F, 2F);

    /**
     * ★What this light does to the AIR — beam, haze, dust and its size, prism and its scale, bounce —
     * as ONE property, and therefore ONE track on the film timeline.
     *
     * <p><b>Why grouped.</b> The film builds a keyframe channel per visible property, so thirty-odd
     * dials meant thirty-odd numeric tracks: functional, unreadable. These seven are set as a mood
     * ("this shot is foggy"), never one at a time, and each keyframe still interpolates them
     * individually — see {@link com.bbsvfx.vfxlights.forms.values.LightStructFactory}.</p>
     */
    public final BaseKeyframeFactoryValue<LightAir> air = LightFactories.air();

    /** ★How surfaces answer this light: rim and its width, grazing sheen, translucency, contour. */
    public final BaseKeyframeFactoryValue<LightStyle> style = LightFactories.style();

    /** ★The two-tone anime ramp: on/off, terminator softness, and the shadow side's tint and level. */
    public final BaseKeyframeFactoryValue<LightToon> toon = LightFactories.toon();

    /**
     * Flame-like flicker: a smooth random walk dimming the brightness, ~3 Hz on the world clock (so
     * it pauses with the game and each lamp walks its own phase). 0 = steady, 1 = full dips.
     */
    public final ValueFloat flicker = new ValueFloat("flicker", 0F, 0F, 1F);

    /** Flicker tempo multiplier: 1 = base walk (~3 Hz), higher = faster strobe. */
    public final ValueFloat flickerSpeed = new ValueFloat("flicker_speed", 1F, 0.05F, 20F);

    /** Photometric profile: 0 none, 1 downlight, 2 batwing, 3 ring. Ordinal — append only. */
    public final mchorse.bbs_mod.settings.values.numeric.ValueInt iesProfile =
        new mchorse.bbs_mod.settings.values.numeric.ValueInt("ies_profile", 0, 0, 3);

    /**
     * Lens flare: the CAMERA's answer to this lamp — glow, streaks, the ghost train down the optical
     * axis, occlusion-aware and flashing at the frame edge. Zero = off.
     */
    public final ValueFloat flare = new ValueFloat("flare", 0F, 0F, 1F);
    /** Flare style: 0 star, 1 anamorphic, 2 clean, 3 JJ, 4 sun, 5 searchlight, 6 tactical,
     * 7 vintage, 8 bokeh. Ordinal-serialised — append only. */
    public final mchorse.bbs_mod.settings.values.numeric.ValueInt flareStyle =
        new mchorse.bbs_mod.settings.values.numeric.ValueInt("flare_style", 0, 0, 8);

    /* Two independent switches rather than one "only" flag: a fill light that lifts the actor without
     * touching the set, and a practical that lights the set without doubling up on the actor, are both
     * ordinary requests — and neither is expressible with a single exclusive toggle. */
    /** Light the world geometry. */
    public final ValueBoolean affectBlocks = new ValueBoolean("affect_blocks", true);
    /** Light actors, entities and items. */
    public final ValueBoolean affectEntities = new ValueBoolean("affect_entities", true);

    /**
     * Replay categories this light is allowed to touch (the BBS "groups"). Empty = everything,
     * model blocks included; with any category set, the lamp lights only the actors whose replay
     * sits in one of them — a key for the hero group that leaves background extras alone.
     */
    public final ValueStringKeys groups = new ValueStringKeys("groups");

    /** Master switch for the category filter: the selection can stay set while filtering is off. */
    public final ValueBoolean groupFilter = new ValueBoolean("group_filter", false);

    /**
     * This lamp's own identity, minted once and SERIALISED with the form. BBS's {@code getId()}
     * answers the value key ("form" on every lamp) and its {@code getFormId()} is type-and-name
     * based, so neither singles one lamp out — yet BBS recreates the form instance on edits and
     * the form editor previews a detached copy. A random id riding the form's data survives both,
     * which is what the editor live-refresh and the registry's ghost pruning match by. Invisible:
     * identity, not a dial — no keyframe track.
     */
    public final ValueString vfxId = new ValueString("vfx_id", "");

    /**
     * Id of the built-in look this lamp was stamped from, empty when it was built by hand. Authoring
     * state, not shading state: nothing downstream reads it, the picker does — which is exactly why it
     * lives on the FORM. Held in the UI, the selector went on showing the look of the previously edited
     * lamp, since a widget outlives the form it was pointed at.
     */
    public final ValueString preset = new ValueString("preset", "");

    /** Whether a dial has been moved since the look was stamped — the "*" the picker shows. */
    public final ValueBoolean presetTweaked = new ValueBoolean("preset_tweaked", false);

    public LightForm()
    {
        super();

        this.add(this.color);
        this.add(this.intensity);
        this.add(this.range);
        this.add(this.falloffPhysical);
        this.add(this.useTemperature);
        this.add(this.temperature);
        this.add(this.shadows);
        this.add(this.shadowSoftness);
        this.add(this.air);
        this.add(this.style);
        this.add(this.toon);
        this.add(this.flicker);
        this.add(this.flickerSpeed);
        this.add(this.iesProfile);
        this.add(this.flare);
        this.add(this.flareStyle);
        this.add(this.affectBlocks);
        this.add(this.affectEntities);
        this.add(this.groups);
        this.add(this.groupFilter);
        this.add(this.vfxId);
        this.add(this.preset);
        this.add(this.presetTweaked);

        /* Invisible = no keyframe track. These describe how the lamp was AUTHORED (or WHICH lamp it
         * is); animating them would mean animating the editor, and every visible property costs a
         * row in the film's property list (FormUtils.collectPropertyPaths). */
        this.vfxId.invisible();
        this.preset.invisible();
        this.presetTweaked.invisible();

        /* "Enabled" OFF on the form must kill the lamp NOW: an invisible form simply stops being
         * collected, and without this push the light kept burning on its persist grace (or held
         * alive indefinitely while an editor session renewed every grace via touchAll). */
        this.visible.postCallback((value, flag) ->
        {
            if (!this.visible.get())
            {
                com.bbsvfx.vfxlights.light.LightRegistry.clearByFormId(this.ensureVfxId());
            }
        });
    }

    /**
     * The lamp's serialised identity, minted ON FIRST NEED. Minting lazily at collection left the
     * film's canonical form instance with an EMPTY id whenever the render path worked on copies
     * (the editor's stub renders): the registry light carried one minted id, the replay's form
     * another — the disabled-replay sweep matched nothing ("свет в фильме не выключается сразу").
     * Minting here, on the canonical instance, means every later copy carries the id with the data.
     */
    public String ensureVfxId()
    {
        if (this.vfxId.get().isEmpty())
        {
            this.vfxId.set(java.util.UUID.randomUUID().toString());
        }

        return this.vfxId.get();
    }

    /**
     * The colour this light actually emits, kelvin already folded in. Backends must use this rather than
     * reading {@link #color} directly, or temperature would silently do nothing in half of them.
     */
    public Color effectiveColor()
    {
        Color tint = this.color.get();

        if (!this.useTemperature.get())
        {
            return tint;
        }

        Color kelvin = blackbody(this.temperature.get());

        return new Color(kelvin.r * tint.r, kelvin.g * tint.g, kelvin.b * tint.b, tint.a);
    }

    /**
     * Approximate blackbody colour, normalised so that the brightest channel is 1 — the temperature
     * should change the HUE of the light, not how bright it is; brightness is {@link #intensity}'s job.
     *
     * <p>Uses Tanner Helland's piecewise fit, the same approximation real-time renderers use: accurate
     * enough over 1000..15000K that the classic tungsten-vs-daylight contrast reads correctly, and it
     * costs a couple of logarithms instead of a spectral integral.</p>
     */
    public static Color blackbody(float kelvin)
    {
        float t = Math.max(1000F, Math.min(15000F, kelvin)) / 100F;
        float r;
        float g;
        float b;

        if (t <= 66F)
        {
            r = 1F;
            g = clamp01((float) (0.39008157876901960784D * Math.log(t) - 0.63184144378862745098D));
            b = t <= 19F ? 0F : clamp01((float) (0.54320678911019607843D * Math.log(t - 10F) - 1.19625408914D));
        }
        else
        {
            r = clamp01((float) (1.29293618606274509804D * Math.pow(t - 60F, -0.1332047592D)));
            g = clamp01((float) (1.12989086089529411765D * Math.pow(t - 60F, -0.0755148492D)));
            b = 1F;
        }

        float max = Math.max(r, Math.max(g, b));

        if (max > 0F)
        {
            r /= max;
            g /= max;
            b /= max;
        }

        return new Color(r, g, b, 1F);
    }

    private static float clamp01(float v)
    {
        return v < 0F ? 0F : (v > 1F ? 1F : v);
    }
}
