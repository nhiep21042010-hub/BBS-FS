package com.bbsvfx.bbsvfx.forms;

import mchorse.bbs_mod.settings.values.numeric.ValueBoolean;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.settings.values.numeric.ValueInt;

/**
 * An {@code bbsvfx:explosion} form: a {@link DestructionBoxForm} whose blocks are auto-scanned from a
 * SPHERE of the real world around an epicenter (instead of a wand box), pre-configured to blast outward
 * from that epicenter. Everything else — the PhysX bake, the GPU/CPU render paths, the persistent bake
 * cache — is inherited unchanged, so scrubbing {@link #destruction} plays the baked collapse exactly as
 * a Destruction Box does.
 *
 * <p>Phase 0 skeleton: the auto-scan (via {@code DestructionCapture.captureExplosion}) fills the blocks
 * and sets {@link #point} to the epicenter; the constructor picks blast-friendly defaults (physics on,
 * an epicenter impulse, a little tumble). The wind / fire / foliage-bend subsystems layer on later.</p>
 */
public class ExplosionForm extends DestructionBoxForm
{
    /** Radius (blocks) of the world sphere the scan pulls in around the epicenter. Set by the scan. */
    public final ValueFloat scanRadius = new ValueFloat("scan_radius", 8F, 1F, 128F);

    /**
     * Only scan blocks at or below the epicenter (a dome, not a full ball) — so a ground blast scoops a
     * crater instead of also lifting a hemisphere of earth from under itself. Default off (full sphere).
     */
    public final ValueBoolean scanHemisphere = new ValueBoolean("scan_hemisphere", false);

    /* --- Global wind (Ф2a): ONE field every subsystem samples. ------------------------------------
     * The blast wind is an expanding FRONT: it leaves the epicenter at windFrontSpeed and, as it
     * passes a piece of debris, shoves it radially with windStrength, decaying over windDecay seconds
     * — the far field visibly reacts LATER than the core, which is what sells the scale. On top, an
     * optional constant ambient wind (yaw + strength) drifts everything sideways all along; later the
     * same field drives smoke, fire and the foliage bend. */

    /** Radial shove (blocks/s²) as the front passes. 0 = no blast wind. */
    public final ValueFloat windStrength = new ValueFloat("wind_strength", 10F, 0F, Float.POSITIVE_INFINITY);

    /** How fast the front expands (blocks/s). */
    public final ValueFloat windFrontSpeed = new ValueFloat("wind_front_speed", 25F, 1F, Float.POSITIVE_INFINITY);

    /** How long the shove lasts after the front passes (seconds, exponential decay). */
    public final ValueFloat windDecay = new ValueFloat("wind_decay", 0.8F, 0.05F, 10F);

    /**
     * SUSTAINED BLAST WIND: after the front's punch the explosion keeps DRIVING air outward for this
     * many seconds — trees hold a bowed lean, dust/smoke/leaves stream away from the epicenter, wind
     * streaks keep racing — then it lets go and the negative phase pulls back. 0 = punch only.
     */
    public final ValueFloat windHold = new ValueFloat("wind_hold", 3.5F, 0F, 15F);

    /** Constant ambient wind: direction (degrees, world yaw) and strength (blocks/s²). 0 = calm. */
    public final ValueFloat windAmbientYaw = new ValueFloat("wind_ambient_yaw", 0F);
    public final ValueFloat windAmbientStrength = new ValueFloat("wind_ambient", 0F, 0F, Float.POSITIVE_INFINITY);

    /**
     * NEGATIVE PHASE (suction): after the pressure front passes, real blasts briefly REVERSE the wind
     * — the rarefaction behind the front pulls smoke/dust back toward the epicenter before letting go.
     * 1 = the pull-back peaks at the outward wind speed; 0 = off. Read by the visual subsystems
     * (smoke, dust, haze, leaves) — the baked debris keeps its committed ballistics.
     */
    public final ValueFloat windSuction = new ValueFloat("wind_suction", 0.6F, 0F, 2F);

    /* --- Fire & smoke (Ф3): visual burning, FIXED cost regardless of the scan size. -----------------
     * The wind front IGNITES the nearest fireCount blocks as it passes their rest sites (the crater
     * area burns — flying debris doesn't carry flames yet); each site gets a flickering flame that
     * burns for fireDuration and a stream of smoke puffs that rise, grow, darken and drift on the SAME
     * global wind as the debris; the epicenter feeds one big smoke column. Everything is a
     * deterministic closed form of the scrub time — reversible, frame-exact. */

    /** How many blocks nearest the epicenter catch fire. 0 = no fire. */
    public final ValueInt fireCount = new ValueInt("fire_count", 250, 0, 5000);

    /** Burn time of each ignited site (seconds); smoke lives on a while after. */
    public final ValueFloat fireDuration = new ValueFloat("fire_duration", 6F, 0.5F, 60F);

    /** Flame billboard scale. */
    public final ValueFloat fireSize = new ValueFloat("fire_size", 2.5F, 0.1F, 10F);

    /** Smoke amount/size multiplier. 0 = flames only. */
    public final ValueFloat smokeScale = new ValueFloat("smoke_scale", 1F, 0F, 5F);

    /** The ground-hugging dust wave that rides the blast front and engulfs the area. 0 = off. */
    public final ValueFloat dustScale = new ValueFloat("dust_scale", 1F, 0F, 5F);

    /** The hero MUSHROOM cloud (the Fate reference): a roiling fireball that rises, cools from
     *  white-orange into dark smoke and spreads into a cap over a dust stem. 0 = off. */
    public final ValueFloat mushroomScale = new ValueFloat("mushroom_scale", 1F, 0F, 5F);

    /* --- Hero cloud SHAPE + big spark fountain (Ф3 follow-up). -------------------------------------
     * The hero cloud can be the classic mushroom (cap over a dust stem) or a plain SPHERE — a single
     * roiling fireball that stays at the epicentre (cinematic fuel-air / gas-explosion look). The big
     * sparks are a separate, configurable fountain on top of the built-in ember spray: bright heads
     * with motion-stretched trails, arcing under their own gravity. */

    /** Hero cloud shape: 0 = mushroom, 1 = sphere (fireball). */
    public final ValueInt shape = new ValueInt("shape", 0, 0, 1);

    /** Sphere radius (blocks) once fully expanded. */
    public final ValueFloat sphereRadius = new ValueFloat("sphere_radius", 6F, 1F, 64F);

    /** How fast the sphere grows to its full radius (higher = faster). */
    public final ValueFloat sphereExpand = new ValueFloat("sphere_expand", 1.5F, 0.1F, 10F);

    /** Sphere fire amount: 1 = burning fireball, 0 = a pure smoke ball. */
    public final ValueFloat sphereHeat = new ValueFloat("sphere_heat", 1F, 0F, 1F);

    /** Independent visibility/size multiplier for the Sphere shape. Does NOT share a slider with the Mushroom. */
    public final ValueFloat sphereScale = new ValueFloat("sphere_scale", 1F, 0F, 5F);

    /** Big fountain sparks thrown at the detonation. 0 = off. */
    public final ValueInt sparkCount = new ValueInt("spark_count", 150, 0, 500);

    /** Spark streak length scale. */
    public final ValueFloat sparkSize = new ValueFloat("spark_size", 2.0F, 0.1F, 5F);

    /** Launch speed (blocks/s). */
    public final ValueFloat sparkSpeed = new ValueFloat("spark_speed", 30F, 0F, 100F);

    /** Gravity pulling the arcs down (blocks/s²). */
    public final ValueFloat sparkGravity = new ValueFloat("spark_gravity", 18F, 0F, 100F);

    /** Spark lifetime (seconds) — long, so the arcs stay visible. */
    public final ValueFloat sparkLife = new ValueFloat("spark_life", 12.0F, 0.1F, 30F);

    /** Fountain cone: 0 = a tight vertical jet, 1 = a wide upward hemisphere. */
    public final ValueFloat sparkSpread = new ValueFloat("spark_spread", 0.7F, 0F, 1F);

    /** Spark tint (the head burns toward white on its own). */
    public final ValueFloat sparkColorR = new ValueFloat("spark_color_r", 1F, 0F, 1F);
    public final ValueFloat sparkColorG = new ValueFloat("spark_color_g", 0.85F, 0F, 1F);
    public final ValueFloat sparkColorB = new ValueFloat("spark_color_b", 0.45F, 0F, 1F);

    /* --- World reaction: the world ITSELF answers the blast, out to the far field. ------------------
     * All deterministic closed forms of the scrub time sampling the same wind field + the real
     * terrain heightmap — no bake, no simulation, scrub-exact. */

    /** The GROUND WAVE: as the front races over each terrain column it kicks up a short dust puff —
     *  a visible reaction ring running across the actual landscape (plus skittering ground litter
     *  chased downwind). Scale, 0 = off. */
    public final ValueFloat groundWave = new ValueFloat("ground_wave", 1F, 0F, 5F);

    /** How far the REAL WORLD reacts (blocks): ground wave, haze, and the uncaptured-tree bursts all
     *  run out to this radius over the actual loaded terrain — independent of the capture. */
    public final ValueFloat worldReach = new ValueFloat("world_reach", 200F, 16F, 512F);

    /** LINGERING HAZE: after the dust settles the air stays murky for ~half a minute — big slow
     *  translucent billows drifting on the ambient wind, slowly pulled toward the column. 0 = off. */
    public final ValueFloat hazeScale = new ValueFloat("haze", 1F, 0F, 5F);

    /** Birds scattering off the trees at the detonation (needs captured foliage). 0 = off. */
    public final ValueInt birdCount = new ValueInt("bird_count", 10, 0, 40);

    /** WIND STREAKS: stretched dust wisps racing through the air — fast with the front, then in
     *  decaying gusts for ~15 s (a ground-hugging band + an airborne band). Makes the AIR visible. */
    public final ValueFloat windStreaks = new ValueFloat("wind_streaks", 1F, 0F, 5F);

    /* --- Environment bend extras (Ф2b follow-ups). ------------------------------------------------ */

    /** Turbulent GUSTS after the front: the forest keeps rocking with decaying noise for ~10 s instead
     *  of freezing the moment the main swing settles. Amount relative to the bend, 0 = off. */
    public final ValueFloat bendGusts = new ValueFloat("bend_gusts", 0.4F, 0F, 1F);

    /** LEAF SHED: a tree hit by the front sheds a burst of leaves ∝ its bend, tumbling downwind. */
    public final ValueFloat bendLeaves = new ValueFloat("bend_leaves", 1F, 0F, 3F);

    /* --- Environment bend (Ф2b): the world REACTS beyond the crater. -------------------------------
     * The capture also grabs the FOLIAGE (logs+leaves as trees, small plants as grass) in bendRadius
     * around the epicenter — the real blocks are cut (same undo) and re-rendered as proxy units: a
     * tree pivots at its root, grass shears at its base. When the wind front arrives, each unit kicks
     * into a damped oscillator (lean away, spring back, settle) — a closed form of the scrub time, so
     * it plays scrub-exact with zero simulation, out to the far field where nothing else moves. */

    /** How far the capture/rescan GRABS foliage around the epicenter (blocks). Bigger = more trees
     *  react, at a proxy-build/sway cost. The capture still auto-raises it to ~2.2x the blast. */
    public final ValueFloat bendScanRadius = new ValueFloat("bend_scan_radius", 120F, 0F, 320F);

    /** Sway falloff reach (blocks) — auto-set to the scan radius at capture/rescan; lower it to
     *  concentrate the reaction near the blast. 0 = off. */
    public final ValueFloat bendRadius = new ValueFloat("bend_radius", 96F, 0F, 320F);

    /** Peak tree lean (degrees) next to the blast; grass bends ~2.5x this. Falls off with distance. */
    public final ValueFloat bendAmount = new ValueFloat("bend_amount", 18F, 0F, 80F);

    /**
     * The shockwave FELLS a share of the trees: each tree rolls a hash against this chance scaled by
     * the distance falloff — most fall near the epicenter, a few at the edge, the rest just sway (для
     * живости). A felled tree topples away from the blast (with a per-tree delay and a landing
     * bounce) and stays down; grass in the hot zone stays flattened. 0 = everything springs back.
     */
    public final ValueFloat bendFell = new ValueFloat("bend_fell", 0.45F, 0F, 1F);

    /** The captured foliage blocks (form-local coords, same origin as {@link #blocks}). */
    public final DestructionBlockList foliage = new DestructionBlockList("foliage");

    public ExplosionForm()
    {
        super();

        this.add(this.scanRadius);
        this.add(this.scanHemisphere);
        this.add(this.windStrength);
        this.add(this.windFrontSpeed);
        this.add(this.windDecay);
        this.add(this.windHold);
        this.add(this.windAmbientYaw);
        this.add(this.windAmbientStrength);
        this.add(this.windSuction);
        this.add(this.fireCount);
        this.add(this.fireDuration);
        this.add(this.fireSize);
        this.add(this.smokeScale);
        this.add(this.dustScale);
        this.add(this.mushroomScale);
        this.add(this.shape);
        this.add(this.sphereRadius);
        this.add(this.sphereExpand);
        this.add(this.sphereHeat);
        this.add(this.sphereScale);
        this.add(this.sparkCount);
        this.add(this.sparkSize);
        this.add(this.sparkSpeed);
        this.add(this.sparkGravity);
        this.add(this.sparkLife);
        this.add(this.sparkSpread);
        this.add(this.sparkColorR);
        this.add(this.sparkColorG);
        this.add(this.sparkColorB);
        this.add(this.groundWave);
        this.add(this.worldReach);
        this.add(this.hazeScale);
        this.add(this.birdCount);
        this.add(this.windStreaks);
        this.add(this.bendScanRadius);
        this.add(this.bendRadius);
        this.add(this.bendAmount);
        this.add(this.bendFell);
        this.add(this.bendGusts);
        this.add(this.bendLeaves);
        this.add(this.foliage);

        /* Blast-friendly defaults: the physics bake drives everything, the outward push comes from the
         * epicenter impulse. The scan sets the epicenter to the BOTTOM-centre of the captured mass, so
         * every block is above it and gets ejected up-and-out (crater ejecta) instead of half the ball
         * punching straight down through the ground. The cone leans the blast UP (dirPitch 90° + a
         * partial cone) for a fountain rather than a flat radial burst. The scan overrides point +
         * explosion radius from the captured geometry. */
        this.physicsMode.set(true);
        this.radialStrength.set(0F);
        this.dirStrength.set(0F);
        this.dirPitch.set(90F);
        this.physExplosionStrength.set(30F);
        this.physExplosionCone.set(0.35F);
        this.physExplosionRadius.set(8F);
        this.randomAmount.set(1.5F);
        this.rotationAmount.set(1F);
        this.physWorld.set(true);
        this.physGround.set(true);

        /* Livelier debris: drives BOTH the PhysX restitution (tier A) and the ballistic landing
         * bounce (tier C) — the sim default 0.1 reads as debris GLUED to the ground. */
        this.physBounciness.set(0.25F);

        /* LOD: full PhysX for the blocks nearest the epicenter (the visible action — the CPU render
         * path is proven to ~27k), the far bulk flies on GPU-instanced analytic ballistics —
         * city-block scans (50-100k+) stay real-time. */
        this.physMaxBodies.set(20_000);
    }

    @Override
    protected String getDefaultDisplayName()
    {
        return "Explosion";
    }

    /** Ambient wind acceleration, X component (same yaw convention as the blast direction). */
    public float windAmbientX()
    {
        return (float) (Math.sin(Math.toRadians(this.windAmbientYaw.get())) * this.windAmbientStrength.get());
    }

    /** Ambient wind acceleration, Z component. */
    public float windAmbientZ()
    {
        return (float) (Math.cos(Math.toRadians(this.windAmbientYaw.get())) * this.windAmbientStrength.get());
    }
}
