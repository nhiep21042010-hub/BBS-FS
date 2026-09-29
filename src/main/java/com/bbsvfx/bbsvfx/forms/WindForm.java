package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;
import org.joml.Vector3f;

/**
 * A zonal WIND form: scans a territory around the actor (read-only, reusing the explosion's crown scan)
 * and drives ambient wind over it — foliage sway plus visible wind (streaks, lifted leaves, dust). Unlike
 * the explosion's wind, which is an impulse (a blast front that passes and decays), this is a sustained
 * environmental field a director keyframes to bring a shot alive.
 *
 * <p><b>Workflow.</b> Pick the form, set {@link #scanRadius}, press Scan (sets {@link #scanned}); a
 * direction handle then appears — the vector from the actor to {@link #point()} is where the wind blows —
 * and the rest is sliders. The direction point reuses the Destruction Box gizmo pattern.</p>
 *
 * <p><b>Field type.</b> {@link #windType} selects the flow: 0 = directional (a uniform push along the
 * direction), later 1 = storm (strong directional) and 2 = tornado (a vortex that uproots). Phase 0 wires
 * only the directional visible wind; sway, vortex and destruction come in later phases.</p>
 *
 * <p>Everything is a closed form of {@link #progress} (× {@link #duration} = motion time) so scrubbing is
 * exact, with {@link #opacity} an independent master fade — the same timeline model as {@code BeamForm}.</p>
 */
public class WindForm extends Form
{
    /** Master timeline 0..1 (keyframed on the replay track) — drives the wind flow / advection. */
    public final ValueFloat progress = new ValueFloat("progress", 1F, 0F, 1F);
    /** Independent master fade 0..1 for the visible wind. */
    public final ValueFloat opacity = new ValueFloat("opacity", 1F, 0F, 1F);
    /** Seconds the effect spans; maps {@link #progress} to motion time. */
    public final ValueFloat duration = new ValueFloat("duration", 8F, 0.1F, 600F);

    /** Territory radius the wind reaches, in blocks (the read-only scan range). */
    public final ValueFloat scanRadius = new ValueFloat("scan_radius", 48F, 1F, 512F);
    /** Set true by the Scan button — gates the direction handle and the wind FX. */
    public final ValueBoolean scanned = new ValueBoolean("scanned", false);

    /** Flow field: 0 = directional, 1 = storm, 2 = tornado (only 0 is wired in phase 0). */
    public final ValueInt windType = new ValueInt("wind_type", 0, 0, 2);
    /** Wind acceleration, blocks per second squared. */
    public final ValueFloat strength = new ValueFloat("strength", 6F, 0F, Float.POSITIVE_INFINITY);

    /** Direction target (local offset from the actor); wind blows along {@code point() - origin}. */
    public final ValueFloat pointX = new ValueFloat("point_x", 8F);
    public final ValueFloat pointY = new ValueFloat("point_y", 0F);
    public final ValueFloat pointZ = new ValueFloat("point_z", 0F);

    /** Visible-wind densities, 0..1 each (streaking motion lines, lifted leaves, drifting dust). */
    public final ValueFloat streaks = new ValueFloat("streaks", 1F, 0F, 1F);
    public final ValueFloat leaves = new ValueFloat("leaves", 1F, 0F, 1F);
    public final ValueFloat dust = new ValueFloat("dust", 1F, 0F, 1F);

    /** Colour of the visible wind streaks (the dust keeps its own earthy brown). */
    public final ValueColor color = new ValueColor("color", new Color(0.85F, 0.88F, 0.93F, 1F));

    /* ---- VORTEX (windType 2) ---- the funnel's shape. A tornado is a tall, narrow-footed cone that flares
     * toward the sky; the visible wisps live in a SHELL around that wall, so `coreRadius` is the wall radius
     * at the ground, not a filled volume. */
    /** Funnel radius at the ground, blocks. */
    public final ValueFloat coreRadius = new ValueFloat("core_radius", 7F, 0.5F, 128F);
    /** How much the funnel widens toward the top (0 = a straight column). */
    public final ValueFloat funnelFlare = new ValueFloat("funnel_flare", 1.0F, 0F, 8F);
    /** Funnel height, blocks — how far up the vortex reaches. */
    public final ValueFloat funnelHeight = new ValueFloat("funnel_height", 40F, 2F, 320F);
    /** Tangential swirl speed (how fast the vortex spins) — the flow field's rotation. */
    public final ValueFloat swirl = new ValueFloat("swirl", 12F, 0F, 120F);
    /** Updraft: how strongly the vortex lifts leaves/debris up the funnel. */
    public final ValueFloat updraft = new ValueFloat("updraft", 6F, 0F, 60F);
    /** Inward suction toward the axis. */
    public final ValueFloat suction = new ValueFloat("suction", 3F, 0F, 60F);

    /** Sway the world's foliage on the wind. The zone's real grass/leaves/trees are captured and cut on
     * scan and replaced by swaying proxies (restored on stop) — the same cut-and-replace the explosion
     * uses, so it is pack-lit and scrub-exact. 0 = off. */
    public final ValueBoolean sway = new ValueBoolean("sway", false);
    /** Peak sway angle in degrees. */
    public final ValueFloat swayAmount = new ValueFloat("sway_amount", 9F, 0F, 45F);

    public WindForm()
    {
        super();

        this.add(this.progress);
        this.add(this.opacity);
        this.add(this.duration);
        this.add(this.scanRadius);
        this.add(this.scanned);
        this.add(this.windType);
        this.add(this.strength);
        this.add(this.pointX);
        this.add(this.pointY);
        this.add(this.pointZ);
        this.add(this.streaks);
        this.add(this.leaves);
        this.add(this.dust);
        this.add(this.color);
        this.add(this.coreRadius);
        this.add(this.funnelFlare);
        this.add(this.funnelHeight);
        this.add(this.swirl);
        this.add(this.updraft);
        this.add(this.suction);
        this.add(this.sway);
        this.add(this.swayAmount);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "wind";
    }

    /* Show "wind" instead of the raw type id ("bbsvfx:wind"); serialization uses the form factory. */
    @Override
    public String getFormId()
    {
        return "wind";
    }

    /** True while the field is a vortex (tornado) rather than a directional push. */
    public boolean isVortex()
    {
        return this.windType.get() == 2;
    }

    /** True for the gusting storm field — a directional wind that surges and shifts instead of blowing flat. */
    public boolean isStorm()
    {
        return this.windType.get() == 1;
    }

    /**
     * Load a tuned starting point for the given field type. Deliberately a separate action rather than a
     * side effect of switching type — a director who has dialled the sliders in shouldn't lose that work
     * just by looking at another field.
     */
    public void applyPreset(int type)
    {
        this.windType.set(type);

        if (type == 2)
        {
            this.strength.set(6F);
            this.streaks.set(1F);
            this.leaves.set(1F);
            this.dust.set(1F);
            this.swayAmount.set(24F);
            this.coreRadius.set(7F);
            this.funnelFlare.set(1F);
            this.funnelHeight.set(40F);
            this.swirl.set(14F);
            this.updraft.set(8F);
            this.suction.set(4F);
        }
        else if (type == 1)
        {
            this.strength.set(11F);
            this.streaks.set(0.9F);
            this.leaves.set(0.75F);
            this.dust.set(0.6F);
            this.swayAmount.set(14F);
        }
        else
        {
            this.strength.set(6F);
            this.streaks.set(0.6F);
            this.leaves.set(0.5F);
            this.dust.set(0.3F);
            this.swayAmount.set(9F);
        }
    }

    /** Motion time in seconds: {@code progress * duration}. */
    public float simTime()
    {
        return this.progress.get() * this.duration.get();
    }

    /** Direction target as a local offset from the actor origin. */
    public Vector3f point()
    {
        return new Vector3f(this.pointX.get(), this.pointY.get(), this.pointZ.get());
    }
}
