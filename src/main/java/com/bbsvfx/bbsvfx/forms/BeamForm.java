package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * An anime energy BEAM: a vertical column of light rising from the form origin (Excalibur-Morgan
 * style) — a bright core, a soft outer glow, wrapping double-helix ribbons, stacked halo rings that
 * rise and expand up the column, rising dash sparks, and a ground glow at the base.
 *
 * <p>Everything is a <b>closed form of {@link #progress}</b> so scrubbing is exact and cheap:
 * {@code simTime = progress * duration} drives all motion (ring rise, helix spin, dash flight, the
 * beam's build-up), and {@link #opacity} is an independent keyframed master fade. Rendered as additive
 * emissive geometry (no custom core shader) so it looks the same with and without a shaderpack — packs
 * bloom the bright bands themselves. See {@code BeamFormRenderer}.</p>
 */
public class BeamForm extends Form
{
    /** Master timeline 0..1 (keyframed on the replay properties track) — drives all motion. */
    public final ValueFloat progress = new ValueFloat("progress", 1F, 0F, 1F);
    /** Independent master fade 0..1 (keyframe 0→1→0 for charge / hold / dissipate). */
    public final ValueFloat opacity = new ValueFloat("opacity", 1F, 0F, 1F);
    /** Seconds the effect spans; maps {@link #progress} to the motion time. */
    public final ValueFloat duration = new ValueFloat("duration", 3F, 0.1F, 120F);

    public final ValueFloat height = new ValueFloat("height", 24F, 0.1F, Float.POSITIVE_INFINITY);
    public final ValueFloat radius = new ValueFloat("radius", 0.6F, 0.01F, Float.POSITIVE_INFINITY);
    /** 0 = erupt UP from the ground; 1 = strike DOWN from the sky (the leading tip descends to impact). */
    public final ValueInt direction = new ValueInt("direction", 1, 0, 1);
    /** Core colour (bright inner beam) and the rim/outer-glow colour. */
    public final ValueColor color = new ValueColor("color", new Color(1F, 0.96F, 0.78F, 1F));
    public final ValueColor rimColor = new ValueColor("rim_color", new Color(1F, 0.62F, 0.16F, 1F));

    /** Thin bright strands wavering inside the core (0 = off). */
    public final ValueInt strands = new ValueInt("strands", 7, 0, 64);

    /**
     * Edge softness 0..3: geometric blur — expanded faint shells on the helix tubes and wider fades on
     * rings/strands/dashes, so the in-world render reads as soft as the editor's filtered preview.
     */
    public final ValueFloat softness = new ValueFloat("softness", 1F, 0F, 3F);

    /** Double-helix ribbons wrapping the column. */
    public final ValueBoolean helix = new ValueBoolean("helix", true);
    public final ValueInt helixCount = new ValueInt("helix_count", 2, 0, 8);
    /** Helix radius as a multiple of {@link #radius}. */
    public final ValueFloat helixRadius = new ValueFloat("helix_radius", 1.9F, 0F, Float.POSITIVE_INFINITY);
    public final ValueFloat helixTurns = new ValueFloat("helix_turns", 4F, 0F, 64F);
    public final ValueFloat helixThickness = new ValueFloat("helix_thickness", 0.28F, 0.001F, Float.POSITIVE_INFINITY);
    /** Ribbon rotation, turns per second (sign = direction). */
    public final ValueFloat helixSpin = new ValueFloat("helix_spin", 0.6F, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);

    /** Stacked halo rings that rise up the column and expand outward. */
    public final ValueInt rings = new ValueInt("rings", 6, 0, 64);
    /** Max ring radius as a multiple of {@link #radius}. */
    public final ValueFloat ringMax = new ValueFloat("ring_max", 3.2F, 0F, Float.POSITIVE_INFINITY);
    /** Ring rise speed, blocks per second. */
    public final ValueFloat ringRise = new ValueFloat("ring_rise", 9F, 0F, Float.POSITIVE_INFINITY);
    public final ValueFloat ringThickness = new ValueFloat("ring_thickness", 0.3F, 0.001F, Float.POSITIVE_INFINITY);

    /** Rising dash sparks around the column. */
    public final ValueInt dashes = new ValueInt("dashes", 40, 0, 2000);
    /** Dash rise speed (column heights per second). */
    public final ValueFloat dashSpeed = new ValueFloat("dash_speed", 1.1F, 0F, Float.POSITIVE_INFINITY);

    /** Ground glow disc radius at the base (0 = off). */
    public final ValueFloat groundRadius = new ValueFloat("ground_radius", 4F, 0F, Float.POSITIVE_INFINITY);

    /* ---- Destruction: the beam devours terrain at its impact (see BeamFormRenderer / DestructionCapture) ---- */

    /** Master toggle for the terrain-devouring behaviour. */
    public final ValueBoolean destruction = new ValueBoolean("destruction", false);
    /** Devour disc radius at the impact, in blocks (the bowl this beam eats). */
    public final ValueFloat destructRadius = new ValueFloat("destruct_radius", 12F, 1F, 64F);
    /** Crater depth at the centre, in blocks (bowl shape, shallower toward the rim). */
    public final ValueFloat destructDepth = new ValueFloat("destruct_depth", 7F, 1F, 32F);
    /** Progress at which the beam lands and the devour begins (0..1). */
    public final ValueFloat impactAt = new ValueFloat("impact_at", 0.2F, 0F, 1F);
    /** The carved blocks (cut from the world once, rendered as rising/sucked-in devour debris). */
    public final DestructionBlockList blocks = new DestructionBlockList("blocks");

    public BeamForm()
    {
        super();

        this.add(this.progress);
        this.add(this.opacity);
        this.add(this.duration);
        this.add(this.height);
        this.add(this.radius);
        this.add(this.direction);
        this.add(this.color);
        this.add(this.rimColor);
        this.add(this.strands);
        this.add(this.softness);
        this.add(this.helix);
        this.add(this.helixCount);
        this.add(this.helixRadius);
        this.add(this.helixTurns);
        this.add(this.helixThickness);
        this.add(this.helixSpin);
        this.add(this.rings);
        this.add(this.ringMax);
        this.add(this.ringRise);
        this.add(this.ringThickness);
        this.add(this.dashes);
        this.add(this.dashSpeed);
        this.add(this.groundRadius);
        this.add(this.destruction);
        this.add(this.destructRadius);
        this.add(this.destructDepth);
        this.add(this.impactAt);
        this.add(this.blocks);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "beam";
    }

    /* Show "beam" everywhere instead of the raw type id ("bbsvfx:beam"); serialization uses the form
     * factory's type lookup, not this, so overriding the display id is safe. */
    @Override
    public String getFormId()
    {
        return "beam";
    }

    /** Motion time in seconds: {@code progress * duration}. */
    public float simTime()
    {
        return this.progress.get() * this.duration.get();
    }
}
