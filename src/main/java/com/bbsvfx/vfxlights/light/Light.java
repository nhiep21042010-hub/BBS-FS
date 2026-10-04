package com.bbsvfx.vfxlights.light;

/**
 * One light source for one frame, flattened into plain fields.
 *
 * <p><b>Why a mutable struct and not a record.</b> An explosion submits hundreds of these per frame, every
 * frame, and they all die at the end of it. Allocating that many short-lived objects is exactly the churn
 * that shows up as stutter on the small heaps our testers run, so {@link LightRegistry} keeps a pool and
 * hands out instances to fill in. Nothing outside the registry should hold on to one past the frame.</p>
 *
 * <p><b>Position is absolute world space.</b> Every backend and the export need one unambiguous frame of
 * reference, and camera-relative coordinates silently break the moment a second camera exists (film camera
 * versus player camera). Whoever fills a light in is responsible for that conversion.</p>
 */
public class Light
{
    public enum Type
    {
        POINT,
        SPOT,
        AREA,
        AMBIENT
    }

    /** Emitter geometry for {@link Type#AREA}, mirroring {@code AreaLightForm.Shape}. */
    public enum AreaShape
    {
        RECT,
        DISC,
        TUBE,
        SPHERE
    }

    /**
     * Identity of whatever produced this light, used to recognise the same lamp across frames and to
     * discard duplicates within one. A form instance for authored lights; anything stable for submitted
     * ones. Null means "anonymous" — never deduplicated.
     */
    public Object key;

    public Type type = Type.POINT;

    /* Absolute world position. */
    public double x;
    public double y;
    public double z;

    /* Orientation. Direction is where the light points (spot cone axis, area surface normal); up
     * completes the frame, which a rectangle needs and a point light ignores. Kept as vectors rather
     * than a matrix because that is what shading actually consumes. */
    public float dirX;
    public float dirY = -1F;
    public float dirZ;
    public float upX;
    public float upY;
    public float upZ = 1F;

    /* Emission. Colour has temperature already folded in. */
    public float r = 1F;
    public float g = 1F;
    public float b = 1F;
    public float intensity = 1F;
    public float range = 12F;

    /**
     * Radius of the emitting sphere for point and spot lights; drives penumbra and specular size.
     * Read it through {@link #effectiveSourceRadius()} when handing it to shading.
     */
    public float sourceRadius;

    /**
     * Distance falloff law for point and spot lights: 0 = range fill (even pool, normalised to one
     * block, cuts at {@link #range}), 1 = physical inverse-square windowed to zero at the range —
     * the Blender look, which wants a generous range so the window stays invisible. Area lights are
     * always physical (analytic form factors) and ignore this.
     */
    public int falloffMode;

    /* Spot cone half-angles, cosines rather than degrees — shading compares against a dot product, and
     * converting once here beats doing it per pixel. */
    public float cosOuter = 0.7F;
    public float cosInner = 0.85F;
    /** Elliptical cone: 1 = round, >1 stretches the cone along the light's right/up axis respectively. */
    public float coneScaleX = 1F;
    public float coneScaleY = 1F;

    /* Area emitter. */
    public AreaShape areaShape = AreaShape.RECT;
    public float width = 1F;
    public float height = 1F;
    public float thickness = 0.1F;
    public boolean twoSided;
    public float spread = 1F;
    public float barnTop;
    public float barnBottom;
    public float barnLeft;
    public float barnRight;
    public float barnSoftness;

    /* Ambient fill. */
    public boolean hemisphere;
    public boolean boxVolume;
    public float sizeX = 8F;
    public float sizeY = 4F;
    public float sizeZ = 8F;
    public float edgeFalloff = 0.35F;
    public float groundR = 0.25F;
    public float groundG = 0.22F;
    public float groundB = 0.18F;
    public float occlusion = 1F;

    public boolean shadows = true;
    /** Penumbra multiplier over the source-size physics; 0 = crisp. */
    public float shadowSoft;
    public boolean affectBlocks = true;
    public boolean affectEntities = true;

    /** Volumetric (in-air) strength; 0 = no beam. */
    public float volumetric;
    /** Ambient haze the light glows into (0 = off) — soft luminous fog around the lamp. */
    public float haze;
    /** Dust motes sparkling inside the beam (0 = off); billboarded by the dust pass. */
    public float dust;
    /** Mote size multiplier over the base orb radius (1 = a few cm). */
    public float dustSize = 1F;
    /** The model block this lamp's form lives in, if any — a block break kills exactly these. */
    public net.minecraft.util.math.BlockPos sourceBlock;
    /** The collecting form's id — stable across BBS recreating the form on edits, which the
     * identity-keyed persist map is not. Used by the editor live-refresh fallback. */
    public String formId;

    /** Spectral dispersion strength (0 = plain light) and the caustic pattern's scale. */
    public float dispersion;
    public float dispersionScale = 1F;

    /** One virtual bounce off the surface the light's axis strikes; 0 = off. */
    public float bounce;

    /**
     * Distance along the beam at which it enters WATER, or -1 when it never does. Filled per frame by
     * the water pass; shading beyond this distance absorbs red-first and gains the caustic net.
     */
    public float waterDist = -1F;
    /** World Y of the water surface the beam entered (valid when {@link #waterDist} >= 0). */
    public float waterY;
    /** True when this light is a virtual water-surface reflection of another lamp. */
    public boolean reflected;

    /** Lens flare strength (0 = off) and style (0 star, 1 anamorphic, 2 clean). */
    public float flare;
    public int flareStyle;

    /** Translucency through thin occluders (0 = off) and the photometric profile id. */
    public float translucency;
    public int iesProfile;

    /** Rim / edge-light dial: scales the grazing-Fresnel reflection of this light (0 = pure physics). */
    public float rim;
    /** Width of the rim strip across the silhouette (0 = hairline, 1 = wide strip). */
    public float rimWidth = 0.35F;

    /**
     * Physical rim dial: scales the grazing Fresnel term inside the specular itself (the
     * always-on rim a bare lamp gives, independent of the stylised {@link #rim} strip).
     * 1 = physics, 0 = flat 4% reflector at grazing, 2 = exaggerated.
     */
    public float specRim = 1F;

    /** Outline: hardens the rim into a thin step contour line (0 = soft rim, unchanged). */
    public float outline;
    /** Contour line radius in screen pixels, and its softness (0 = hard ink, 1 = wide feather). */
    public float outlineWidth = 2F;
    /** Interior contour strength: edge-detect lines inside the geometry, not just the silhouette. */
    public float outlineInner;
    /** What the contour draws on: 0 = world and models, 1 = models only, 2 = world only. */
    public int outlineTarget;
    /** Contour blend mode: 0 = add, 1 = screen, 2 = overlay. */
    public int outlineBlend;
    public float outlineBlur = 0.3F;

    /** Cel / toon shading: hard two-tone terminator on this lamp's diffuse, with an auto cool shadow. */
    public boolean cel;
    public float celSoftness = 0.03F;
    public float celShadowShift = 0.6F;
    public float celShadowLevel = 0.22F;

    /** Flame-like flicker depth (0 = steady); already folded into {@link #intensity} at collection. */
    public float flicker;
    /** Flicker tempo multiplier (1 = ~3 Hz base walk). */
    public float flickerSpeed = 1F;

    /**
     * Replay categories this lamp is allowed to touch (the BBS "groups"). Empty = everything,
     * model blocks included; otherwise only actors of these categories are lit.
     */
    public final java.util.Set<String> groups = new java.util.HashSet<>();
    /** Master switch for the category filter: the selection can stay set while filtering is off. */
    public boolean groupFilter;

    /* Filled in by the shadow pass: which atlas tile holds this light's map (-1 = none), and for a spot
     * or area light the matrix it was rendered with. A point light needs no matrix — the shader works
     * out its cube face arithmetically. */
    public int shadowTile = -1;
    public final org.joml.Matrix4f shadowMatrix = new org.joml.Matrix4f();

    /** How many actor-fitted maps one lamp can carry: one narrow frustum per tracked actor. */
    public static final int ACTOR_SLOTS = 4;

    /* Filled in by the shadow pass: the ACTOR-FITTED maps — up to ACTOR_SLOTS second, narrow
     * frustums, each hugging one actor near the lamp (a slot's tile is -1 when it carries no
     * map). Millimetre texels where the main map's are centimetres, so the second skin layer's
     * shadow survives on the body (the Blender look). The shader samples every present map and
     * keeps the darkest. actorNear/actorFar are each frustum's projection planes. */
    public final int[] actorTile = { -1, -1, -1, -1 };
    public final float[] actorNear = new float[ACTOR_SLOTS];
    public final float[] actorFar = new float[ACTOR_SLOTS];
    /** Tangent of each actor frustum's half-angle — the shader sizes its bias in texels with it. */
    public final float[] actorTan = new float[ACTOR_SLOTS];
    public final org.joml.Matrix4f[] actorMatrix =
        { new org.joml.Matrix4f(), new org.joml.Matrix4f(), new org.joml.Matrix4f(),
            new org.joml.Matrix4f() };

    /**
     * Shared animation clock for the dispersion caustics and volumetric noise, in seconds. Computed
     * Java-side and shipped to every backend as data, so the pack path needs no time uniform of its
     * own (declaring one in the patched pack risks colliding with the pack's declarations).
     *
     * <p>Fed from WORLD time by the client at frame start — not the wall clock. The project rule is
     * closed-form effects from sim time: scrub the film back and forth and the caustic pattern is the
     * same, offline export (which does not run in real time) matches the preview, and pausing the
     * game freezes the pattern — exactly like the flicker walk already did. This class lives in the
     * common source set, so the client hands the number in rather than being imported here.</p>
     */
    private static float effectSeconds;

    /**
     * Wrap of the CAUSTIC phase clock, seconds: 1000π/3 (~17.5 min) — a whole number of 2π cycles
     * under every multiplier the caustic field applies to the phase (0.21/0.15/0.18/0.12 → 70π,
     * 50π, 60π, 40π, and their ×2.6 twins in the water paths — all even multiples of π). The
     * pattern is therefore CONTINUOUS across the wrap, where the raw 83-minute tick modulo snapped
     * it to a new one, and the sin() arguments stay under ~570 rad, inside float32's precise range.
     */
    private static final float CAUSTIC_WRAP = (float) (1000.0 * Math.PI / 3.0);

    /** Called once per frame by the client with the world-time clock (seconds, wrapped for float). */
    public static void updateEffectClock(float seconds)
    {
        effectSeconds = seconds;
    }

    /**
     * The caustic phase: the effect clock wrapped at the caustic-aligned period (see
     * {@link #CAUSTIC_WRAP}). Fed to every vfxCausticField consumer — pack SSBO, fallback
     * composite, volumetric dispersion/water.
     */
    public static float dispersionPhase()
    {
        return effectSeconds % CAUSTIC_WRAP;
    }

    /**
     * The RAW effect clock (legacy 100 000-tick wrap, ~83 min) for consumers with no period to
     * align to — the volumetric haze/smoke fBm drift, the dust motes, the flare twinkle. Fractal
     * noise is not periodic, so no wrap can be seamless for it; keeping the legacy interval keeps
     * their behaviour bit-identical.
     */
    public static float effectClock()
    {
        return effectSeconds;
    }

    /**
     * The source radius shading is allowed to use: never more than half the light's own reach.
     *
     * <p><b>Why cap it at all.</b> A bulb wider than the pool it lights is not a lamp, it is a
     * contradiction — and it reads as one: the near-field knee flattens the hotspot and the penumbra
     * swallows the shadow, so the dial starts to feel like a second, worse range control. Half the
     * reach is the same bound IRLights puts on its own bulb size, and it only bites where the numbers
     * were already nonsense: a 0.25 bulb stays untouched until the range drops below half a block.</p>
     *
     * <p>Applied at the JAVA boundary rather than in the shading model because all three backends
     * (pack SSBO, fallback composite, volumetric) are fed from here, and lights submitted from effect
     * code never pass through a form to be validated.</p>
     */
    public static float capSourceRadius(float sourceRadius, float range)
    {
        return Math.min(sourceRadius, range * 0.5F);
    }

    /** @see #capSourceRadius(float, float) */
    public float effectiveSourceRadius()
    {
        return capSourceRadius(this.sourceRadius, this.range);
    }

    /** Reset to defaults so a pooled instance never leaks the previous light's settings. */
    public Light reset()
    {
        this.key = null;
        this.formId = null;
        this.type = Type.POINT;
        this.x = this.y = this.z = 0D;
        this.dirX = 0F;
        this.dirY = -1F;
        this.dirZ = 0F;
        this.upX = 0F;
        this.upY = 0F;
        this.upZ = 1F;
        this.r = this.g = this.b = 1F;
        this.intensity = 1F;
        this.range = 12F;
        this.sourceRadius = 0F;
        this.falloffMode = 0;
        this.cosOuter = 0.7F;
        this.cosInner = 0.85F;
        this.coneScaleX = 1F;
        this.coneScaleY = 1F;
        this.areaShape = AreaShape.RECT;
        this.width = 1F;
        this.height = 1F;
        this.thickness = 0.1F;
        this.twoSided = false;
        this.spread = 1F;
        this.barnTop = this.barnBottom = this.barnLeft = this.barnRight = 0F;
        this.barnSoftness = 0F;
        this.hemisphere = false;
        this.boxVolume = false;
        this.sizeX = 8F;
        this.sizeY = 4F;
        this.sizeZ = 8F;
        this.edgeFalloff = 0.35F;
        this.groundR = 0.25F;
        this.groundG = 0.22F;
        this.groundB = 0.18F;
        this.occlusion = 1F;
        this.shadows = true;
        this.shadowSoft = 0F;
        this.affectBlocks = true;
        this.affectEntities = true;
        this.volumetric = 0F;
        this.haze = 0F;
        this.dust = 0F;
        this.dustSize = 1F;
        this.sourceBlock = null;
        this.dispersion = 0F;
        this.dispersionScale = 1F;
        this.bounce = 0F;
        this.waterDist = -1F;
        this.waterY = 0F;
        this.reflected = false;
        this.flare = 0F;
        this.flareStyle = 0;
        this.translucency = 0F;
        this.iesProfile = 0;
        this.rim = 0F;
        this.rimWidth = 0.35F;
        this.specRim = 1F;
        this.outline = 0F;
        this.outlineWidth = 2F;
        this.outlineBlur = 0.3F;
        this.cel = false;
        this.celSoftness = 0.03F;
        this.celShadowShift = 0.6F;
        this.celShadowLevel = 0.22F;
        this.flicker = 0F;
        this.flickerSpeed = 1F;
        this.groups.clear();
        this.groupFilter = false;
        this.shadowTile = -1;
        java.util.Arrays.fill(this.actorTile, -1);

        return this;
    }

    /** Copy every field of {@code other} into this instance. */
    public Light copyFrom(Light other)
    {
        this.key = other.key;
        this.type = other.type;
        this.x = other.x;
        this.y = other.y;
        this.z = other.z;
        this.dirX = other.dirX;
        this.dirY = other.dirY;
        this.dirZ = other.dirZ;
        this.upX = other.upX;
        this.upY = other.upY;
        this.upZ = other.upZ;
        this.r = other.r;
        this.g = other.g;
        this.b = other.b;
        this.intensity = other.intensity;
        this.range = other.range;
        this.sourceRadius = other.sourceRadius;
        this.falloffMode = other.falloffMode;
        this.cosOuter = other.cosOuter;
        this.cosInner = other.cosInner;
        this.coneScaleX = other.coneScaleX;
        this.coneScaleY = other.coneScaleY;
        this.areaShape = other.areaShape;
        this.width = other.width;
        this.height = other.height;
        this.thickness = other.thickness;
        this.twoSided = other.twoSided;
        this.spread = other.spread;
        this.barnTop = other.barnTop;
        this.barnBottom = other.barnBottom;
        this.barnLeft = other.barnLeft;
        this.barnRight = other.barnRight;
        this.barnSoftness = other.barnSoftness;
        this.hemisphere = other.hemisphere;
        this.boxVolume = other.boxVolume;
        this.sizeX = other.sizeX;
        this.sizeY = other.sizeY;
        this.sizeZ = other.sizeZ;
        this.edgeFalloff = other.edgeFalloff;
        this.groundR = other.groundR;
        this.groundG = other.groundG;
        this.groundB = other.groundB;
        this.occlusion = other.occlusion;
        this.shadows = other.shadows;
        this.shadowSoft = other.shadowSoft;
        this.affectBlocks = other.affectBlocks;
        this.affectEntities = other.affectEntities;
        this.volumetric = other.volumetric;
        this.haze = other.haze;
        this.dust = other.dust;
        this.dustSize = other.dustSize;
        this.sourceBlock = other.sourceBlock;
        this.formId = other.formId;
        this.dispersion = other.dispersion;
        this.dispersionScale = other.dispersionScale;
        this.bounce = other.bounce;
        this.waterDist = other.waterDist;
        this.waterY = other.waterY;
        this.reflected = other.reflected;
        this.flare = other.flare;
        this.flareStyle = other.flareStyle;
        this.translucency = other.translucency;
        this.iesProfile = other.iesProfile;
        this.rim = other.rim;
        this.rimWidth = other.rimWidth;
        this.specRim = other.specRim;
        this.outline = other.outline;
        this.outlineWidth = other.outlineWidth;
        this.outlineBlur = other.outlineBlur;
        this.outlineInner = other.outlineInner;
        this.outlineTarget = other.outlineTarget;
        this.outlineBlend = other.outlineBlend;
        this.cel = other.cel;
        this.celSoftness = other.celSoftness;
        this.celShadowShift = other.celShadowShift;
        this.celShadowLevel = other.celShadowLevel;
        this.flicker = other.flicker;
        this.flickerSpeed = other.flickerSpeed;
        this.groups.clear();
        this.groups.addAll(other.groups);
        this.groupFilter = other.groupFilter;

        /* The shadow assignment (shadowTile/shadowMatrix, actorTile/actorNear/actorFar/actorMatrix)
         * is deliberately NOT copied: it is per-render state, carried across explicitly by the
         * passes that sample it (VolumetricPass) or re-applied by identity (ShadowMapper). */

        return this;
    }

    /** Set the cone from full angles in degrees, clamping the inner one below the outer. */
    public void setCone(float outerDegrees, float innerDegrees)
    {
        float outer = Math.max(1F, Math.min(179F, outerDegrees));
        float inner = Math.max(0F, Math.min(outer, innerDegrees));

        this.cosOuter = (float) Math.cos(Math.toRadians(outer * 0.5F));
        this.cosInner = (float) Math.cos(Math.toRadians(inner * 0.5F));
    }

    /** Normalise the direction, falling back to straight down if it was degenerate. */
    public void normaliseDirection()
    {
        float len = (float) Math.sqrt(this.dirX * this.dirX + this.dirY * this.dirY + this.dirZ * this.dirZ);

        if (len < 1.0E-6F)
        {
            this.dirX = 0F;
            this.dirY = -1F;
            this.dirZ = 0F;

            return;
        }

        this.dirX /= len;
        this.dirY /= len;
        this.dirZ /= len;
    }

    /**
     * Rough screen-independent measure of how much this light matters, used to decide what to drop when
     * there are more lights than a backend can take. Intensity over distance squared is the same falloff
     * the light itself obeys, so the ranking degrades in the order a viewer would notice least.
     */
    public float importance(double camX, double camY, double camZ)
    {
        if (this.type == Type.AMBIENT)
        {
            /* Ambient has no falloff and changes the whole look of a shot — never the first to go. */
            return Float.MAX_VALUE;
        }

        double dx = this.x - camX;
        double dy = this.y - camY;
        double dz = this.z - camZ;
        double distSq = dx * dx + dy * dy + dz * dz;

        if (distSq > (double) this.range * this.range)
        {
            return 0F;
        }

        return (float) (this.intensity / (1D + distSq));
    }
}
