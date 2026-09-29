package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.core.ValueColor;
import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;
import mchorse.bbs_mod.utils.colors.Color;

/**
 * An expanding energy DOME (AKIRA / Fate "Magical Splash Flare" climax): a translucent energy
 * hemisphere that bursts out of the origin and swells radially, levelling and swallowing everything in
 * its path. A bright fresnel RIM glows at the silhouette, the surface churns with turbulence, and a
 * blazing shockwave RING races out along the ground at the wavefront. This is the payoff of the
 * beam → white-out → dome sequence.
 *
 * <p>Like {@link BeamForm} everything is a <b>closed form of {@link #progress}</b> ({@code simTime =
 * progress * duration}) so scrubbing is exact and cheap. Rendered EMISSIVE in the form pass (through
 * BBS's provider) so shaderpacks light + bloom it natively, consistent with and without a pack. See
 * {@code DomeFormRenderer}.</p>
 */
public class DomeForm extends Form
{
    /** Master timeline 0..1 (keyframed on the replay properties track) — drives the expansion. */
    public final ValueFloat progress = new ValueFloat("progress", 1F, 0F, 1F);
    /** Independent master fade 0..1 (keyframe 0→1→0 for burst / hold / dissipate). */
    public final ValueFloat opacity = new ValueFloat("opacity", 1F, 0F, 1F);
    /** Seconds the effect spans; maps {@link #progress} to the motion time. */
    public final ValueFloat duration = new ValueFloat("duration", 3F, 0.1F, 120F);

    /** Final dome radius in blocks (how far the wavefront reaches). */
    public final ValueFloat maxRadius = new ValueFloat("max_radius", 16F, 0.5F, Float.POSITIVE_INFINITY);
    /** Progress span (AFTER impact) over which the dome swells to full (a fast burst, then holds). */
    public final ValueFloat expandAt = new ValueFloat("expand_at", 0.4F, 0.01F, 1F);
    /** Progress at which the beam FIRES: white-out + the blocks are launched up to hang in the air. */
    public final ValueFloat impactAt = new ValueFloat("impact_at", 0.25F, 0F, 1F);
    /** The LULL after the fire (beam gone, blocks hanging) before the dome manifests, in progress. */
    public final ValueFloat lull = new ValueFloat("lull", 0.12F, 0F, 1F);

    /* ---- Beam "tail": the strike from the sky that leads into the dome (rendered by reusing the beam) ---- */

    /** Enable the beam lead-in that descends and detonates into the dome. */
    public final ValueBoolean beamTail = new ValueBoolean("beam_tail", true);
    public final ValueFloat beamHeight = new ValueFloat("beam_height", 30F, 0.1F, Float.POSITIVE_INFINITY);
    public final ValueFloat beamRadius = new ValueFloat("beam_radius", 0.7F, 0.01F, Float.POSITIVE_INFINITY);
    /** Progress span after impact over which the beam fades out as the dome takes over. */
    public final ValueFloat beamFade = new ValueFloat("beam_fade", 0.12F, 0.01F, 1F);
    /** Vertical squash: 1 = perfect hemisphere, &lt;1 = flatter dome, &gt;1 = taller. */
    public final ValueFloat heightScale = new ValueFloat("height_scale", 0.9F, 0.05F, 4F);

    /** Body (interior) colour and the bright fresnel rim colour. */
    public final ValueColor color = new ValueColor("color", new Color(0.60F, 0.32F, 1F, 1F));
    public final ValueColor rimColor = new ValueColor("rim_color", new Color(0.82F, 0.92F, 1F, 1F));

    /** Fresnel sharpness of the rim (higher = thinner, brighter edge). */
    public final ValueFloat rimPower = new ValueFloat("rim_power", 3.5F, 0.2F, 12F);
    /** Optical density (Beer–Lambert absorption strength) of the shell: 0 = barely-there tint, only the
     *  rim reads; higher = the dome darkens/tints the world behind it more strongly (tinted glass). */
    public final ValueFloat fill = new ValueFloat("fill", 0.3F, 0F, 1F);
    /** Surface turbulence: radial displacement as a fraction of the radius (the energy churns). */
    public final ValueFloat turbulence = new ValueFloat("turbulence", 0.06F, 0F, 0.5F);
    /** Turbulence flow speed. */
    public final ValueFloat swirlSpeed = new ValueFloat("swirl_speed", 0.6F, 0F, Float.POSITIVE_INFINITY);

    /** Mesh resolution (azimuth segments / elevation rings). */
    public final ValueInt segments = new ValueInt("segments", 48, 6, 160);
    public final ValueInt ringsRes = new ValueInt("rings_res", 20, 3, 80);

    /** Electric arcs crawling over the dome surface (0 = off). */
    public final ValueInt lightning = new ValueInt("lightning", 7, 0, 64);

    /** Dust/debris haze kicked up along the expanding wavefront (0 = off). */
    public final ValueInt dust = new ValueInt("dust", 160, 0, 2000);

    /** White-out flash intensity at impact (0 = off) — the blinding burst as the beam lands. */
    public final ValueFloat flash = new ValueFloat("flash", 0.7F, 0F, 1F);
    /** Progress window over which the white-out fades. */
    public final ValueFloat flashFade = new ValueFloat("flash_fade", 0.12F, 0.01F, 1F);
    /** Built-in mini-explosion (shockwave ring + sparks) at the moment of impact. */
    public final ValueBoolean impactBurst = new ValueBoolean("impact_burst", true);

    /** The blazing shockwave ring racing along the ground at the wavefront. */
    public final ValueBoolean baseRing = new ValueBoolean("base_ring", true);
    /** Ring band width in blocks. */
    public final ValueFloat ringWidth = new ValueFloat("ring_width", 1.6F, 0.05F, Float.POSITIVE_INFINITY);

    /* ---- Ground cracks: glowing magma fissures spreading from the epicentre (screen-space, CracksVolume) ---- */

    /** Master toggle for the ground cracks. */
    public final ValueBoolean cracks = new ValueBoolean("cracks", false);
    /** Magma glow colour of the cracks. */
    public final ValueColor crackColor = new ValueColor("crack_color", new Color(1F, 0.38F, 0.08F, 1F));
    /** How far the cracks spread, as a fraction of the current dome radius. */
    public final ValueFloat crackReach = new ValueFloat("crack_reach", 1F, 0.05F, 4F);
    /** Crack pattern frequency (higher = finer, denser fissures). */
    public final ValueFloat crackScale = new ValueFloat("crack_scale", 0.35F, 0.02F, 2F);
    /** Magma emission strength. */
    public final ValueFloat crackGlow = new ValueFloat("crack_glow", 1.6F, 0F, 8F);
    /** How deep the fissures sink into the ground (blocks) — the volumetric look you see down the crack. */
    public final ValueFloat crackDepth = new ValueFloat("crack_depth", 6F, 1F, 32F);

    /* ---- Smoke: volumetric fractal haze rising from the cracked crater (screen-space, SmokeVolume) ---- */

    /** Master toggle for the rising smoke. */
    public final ValueBoolean smoke = new ValueBoolean("smoke", false);
    /** Smoke colour (cool grey). */
    public final ValueColor smokeColor = new ValueColor("smoke_color", new Color(0.12F, 0.11F, 0.12F, 1F));
    /** How high the smoke climbs (blocks). */
    public final ValueFloat smokeHeight = new ValueFloat("smoke_height", 12F, 1F, 96F);
    /** Smoke opacity per block of depth. */
    public final ValueFloat smokeDensity = new ValueFloat("smoke_density", 0.5F, 0F, 4F);
    /** Fractal noise frequency (higher = finer wisps). */
    public final ValueFloat smokeScale = new ValueFloat("smoke_scale", 0.18F, 0.02F, 1F);
    /** Upward rise speed. */
    public final ValueFloat smokeRise = new ValueFloat("smoke_rise", 0.6F, 0F, 4F);

    /* ---- Destruction: the wavefront LEVELS the world radially (see DomeFormRenderer / DestructionCapture) ---- */

    /** Master toggle for the world-levelling behaviour. */
    public final ValueBoolean destruction = new ValueBoolean("destruction", false);
    /** How many blocks past the wavefront a block takes to blast apart and vaporise (short = crisp razing). */
    public final ValueFloat clearSpan = new ValueFloat("clear_span", 3.5F, 0.5F, 32F);
    /** The razed blocks (cut from the world once, rendered as they're swept away by the passing front). */
    public final DestructionBlockList blocks = new DestructionBlockList("blocks");

    public DomeForm()
    {
        super();

        this.add(this.progress);
        this.add(this.opacity);
        this.add(this.duration);
        this.add(this.maxRadius);
        this.add(this.expandAt);
        this.add(this.impactAt);
        this.add(this.lull);
        this.add(this.beamTail);
        this.add(this.beamHeight);
        this.add(this.beamRadius);
        this.add(this.beamFade);
        this.add(this.heightScale);
        this.add(this.color);
        this.add(this.rimColor);
        this.add(this.rimPower);
        this.add(this.fill);
        this.add(this.turbulence);
        this.add(this.swirlSpeed);
        this.add(this.segments);
        this.add(this.ringsRes);
        this.add(this.lightning);
        this.add(this.dust);
        this.add(this.flash);
        this.add(this.flashFade);
        this.add(this.impactBurst);
        this.add(this.baseRing);
        this.add(this.ringWidth);
        this.add(this.cracks);
        this.add(this.crackColor);
        this.add(this.crackReach);
        this.add(this.crackScale);
        this.add(this.crackGlow);
        this.add(this.crackDepth);
        this.add(this.smoke);
        this.add(this.smokeColor);
        this.add(this.smokeHeight);
        this.add(this.smokeDensity);
        this.add(this.smokeScale);
        this.add(this.smokeRise);
        this.add(this.destruction);
        this.add(this.clearSpan);
        this.add(this.blocks);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "dome";
    }

    @Override
    public String getFormId()
    {
        return "dome";
    }

    /** Motion time in seconds: {@code progress * duration}. */
    public float simTime()
    {
        return this.progress.get() * this.duration.get();
    }

    /** Progress at which the dome begins expanding: fire + lull when the beam tail leads in, else 0.
     *  (Blocks are launched at {@link #impactAt}, hang through the lull, then the dome eats them.) */
    public float domeStart()
    {
        return this.beamTail.get() ? this.impactAt.get() + this.lull.get() : 0F;
    }

    /** Current dome radius: 0 until {@link #domeStart()}, then a fast cubic ease-out burst to
     *  {@link #maxRadius} over {@link #expandAt}. */
    public float currentRadius()
    {
        float start = this.domeStart();
        float p = this.progress.get();

        if (p <= start)
        {
            return 0F;
        }

        float e = Math.min(1F, (p - start) / Math.max(0.001F, this.expandAt.get()));
        float ease = 1F - (1F - e) * (1F - e) * (1F - e);

        return this.maxRadius.get() * ease;
    }
}
