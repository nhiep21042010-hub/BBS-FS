#version 150

uniform sampler2D DepthSampler;
uniform sampler2D ShadowAtlas;
uniform sampler2D ShadowColor;

uniform mat4 InvViewProj;
uniform vec3 CameraPos;
uniform float GameTime;

uniform vec3 LightPos;
uniform vec3 LightColor;
/* x = volumetric strength, y = range, z = source radius, w = type (0 point, 1 spot, 2 area-sphere) */
uniform vec4 LightParams;
/* xyz = direction, w = cos(outer half-angle) */
uniform vec4 LightDir;
/* x = cos(inner half-angle) [spot] or two-sided flag [area], y = emitter width (sphere diameter),
   z = haze strength, w = cone scale X [spot] or spread [area] */
uniform vec4 LightShape;
/* Shadow: x = first tile (-1 none), y = tiles across, z = near, w unused */
uniform vec4 ShadowSlot;
/* x = dispersion strength, y = pattern scale, z = animation phase, w unused */
uniform vec4 Dispersion;
/* xyz = light's up axis (completes the caustic frame), w = cone scale Y [spot] */
uniform vec4 LightUp;
/* x = dust dial (unused HERE — motes are billboarded by DustPass), y = photometric profile id,
   z = water surface Y, w = rim dial */
uniform vec4 LightExtra;
uniform mat4 LightViewProj;
/* Falloff law for point/spot: 0 = soft range fill, 1 = physical 1/d² windowed (matches surfaces). */
uniform float LightFalloff;
/* Actor-fitted shadow maps (Blender-look), one narrow frustum per actor slot: x = tile
   (-1 = the slot carries no map), y = near, z = far, w unused. */
uniform vec4 ActorSlot0;
uniform vec4 ActorSlot1;
uniform vec4 ActorSlot2;
uniform vec4 ActorSlot3;
uniform mat4 ActorViewProj0;
uniform mat4 ActorViewProj1;
uniform mat4 ActorViewProj2;
uniform mat4 ActorViewProj3;
/* x = side of one atlas tile in texels (Shadow quality), y = filter step 0..3; stamped in main(). */
uniform vec4 AtlasInfo;
/* The quality preset's volumetric leg: x = samples per marched block, y = step floor,
   z = step cap (and dispersion cap), w = the bright-beam cap. */
uniform vec4 VolQuality;

in vec2 texCoord;

out vec4 fragColor;

/* The shared model (vfxTileRect / vfxCubeFace / vfxLinearDepth / vfxStored / vfxCausticField /
   vfxSpectrum / vfxIes / vfxConeCos …) — the beams sample the SAME atlas, caustic field and
   photometrics as the surfaces, from the same single source. */
#moj_import <vfxlights:vfxlights_model.glsl>

/*
 * The light's presence in the AIR: a raymarch along the view ray through the lamp's volume, shadowed
 * by the same atlas the surfaces use.
 *
 * This is the piece film lighting is sold by — the cone of a projector in dust, the halo of a
 * practical, a beam CUT INTO SLICES by whatever stands in it. The shadow taps are what turn a generic
 * fog cone into God rays: one occluder in the beam and the marched samples behind it go dark, exactly
 * like the real thing.
 *
 * Kept deliberately modest per sample — one shadow tap, no filtering — because 64 half-res steps
 * amortise the roughness, and the bilateral upsample pushes what remains below notice.
 */

vec3 worldPosition(float depth)
{
    vec4 ndc = vec4(texCoord * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = InvViewProj * ndc;

    return world.xyz / world.w + CameraPos;
}

/* One unfiltered shadow tap — cube face arithmetic for point/sphere, matrix for the spot. Returns a
   COLOUR: black in shadow, white in the open, the glass' tint behind a stained pane — God rays
   through a cathedral window carry the window with them. */
vec3 shadowAtMain(vec3 pos)
{
    float firstTile = ShadowSlot.x;

    if (firstTile < 0.0)
    {
        return vec3(1.0);
    }

    float near = ShadowSlot.z;
    float far = max(LightParams.y, near + 0.15);
    float across = abs(ShadowSlot.y);
    float type = LightParams.w;
    vec4 rect;
    vec2 uv;
    float recvLin;

    if (type > 0.5 && type < 1.5 && ShadowSlot.y > 0.0)
    {
        vec4 clip = LightViewProj * vec4(pos, 1.0);

        if (clip.w <= 0.0)
        {
            return vec3(1.0);
        }

        vec3 ndc = clip.xyz / clip.w;

        uv = ndc.xy * 0.5 + 0.5;

        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
        {
            return vec3(1.0);
        }

        recvLin = vfxLinearDepth(ndc.z * 0.5 + 0.5, near, far);
        rect = vfxTileRect(firstTile, across);
    }
    else
    {
        float axisDepth;
        float face = vfxCubeFace(pos - LightPos, uv, axisDepth);

        rect = vfxTileRect(firstTile + face, across);
        recvLin = axisDepth;
    }

    /* Compare in LINEAR units: a constant bias in depth01 grows quadratically with world distance,
       and a character standing far down the beam lands wholly inside the bias zone — never cutting
       the ray. 5 cm of slack is all the air needs. */
    float storedLin = vfxLinearDepth(vfxStored(ShadowAtlas, rect, uv), near, far);

    if (recvLin > storedLin + 0.05)
    {
        return vec3(0.0);
    }

    /* Behind a stained pane the AIR itself is tinted — the coloured shaft of a church window.
       Alpha is LINEAR depth over the range at 16-bit precision (see light_glass.fsh); compare in
       blocks with a hair of slack. */
    vec2 clamped = clamp(uv, vec2(1.0 / VFX_TILE), vec2(1.0 - 1.0 / VFX_TILE));
    vec4 glass = texture(ShadowColor, rect.xy + clamped * rect.zw);

    return recvLin > glass.a * far + 0.05 ? glass.rgb : vec3(1.0);
}

/* One actor slot's cut of the beam: where the slot's narrow cone covers the sample and its depth
   says "occluded", the incoming shadow goes dark (feathered at the cone edge); an empty slot or a
   miss keeps the incoming answer. */
vec3 shadowAtActor(vec3 pos, vec4 slot, mat4 viewProj, vec3 mainShadow)
{
    if (slot.x < 0.0)
    {
        return mainShadow;
    }

    vec4 clip = viewProj * vec4(pos, 1.0);

    if (clip.w <= 0.0)
    {
        return mainShadow;
    }

    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
    {
        return mainShadow;
    }

    /* Single tap with a centimetre of slack — the air needs no skin-layer finesse, just the cut. */
    float recvLin = vfxLinearDepth(ndc.z * 0.5 + 0.5, slot.y, slot.z);
    vec4 rect = vfxTileRect(slot.x, abs(ShadowSlot.y));
    float storedLin = vfxLinearDepth(vfxStored(ShadowAtlas, rect, uv), slot.y, slot.z);

    /* Feather the cone edge, same as the surface tap — a hard cutoff slices the beam. */
    vec2 toEdge = min(uv, 1.0 - uv);
    float feather = smoothstep(0.0, 0.12, min(toEdge.x, toEdge.y));

    if (recvLin > storedLin + 0.01)
    {
        return mix(mainShadow, vec3(0.0), feather);
    }

    return mainShadow;
}

/* The beam's full shadow answer: the main map, then every actor-fitted map where it covers. Without
   the second taps a beam passes clean through the actor standing in it — the very bug the actor
   maps exist to kill, and the surfaces already combine the same maps (each slot simply cuts what
   its own target occludes). */
vec3 shadowAt(vec3 pos)
{
    vec3 mainShadow = shadowAtMain(pos);

    mainShadow = shadowAtActor(pos, ActorSlot0, ActorViewProj0, mainShadow);
    mainShadow = shadowAtActor(pos, ActorSlot1, ActorViewProj1, mainShadow);
    mainShadow = shadowAtActor(pos, ActorSlot2, ActorViewProj2, mainShadow);
    mainShadow = shadowAtActor(pos, ActorSlot3, ActorViewProj3, mainShadow);

    return mainShadow;
}

/* The surface model's PHYSICAL law mirrored for the air: 1/d² with the quartic window to zero at
   the range (vfxlights_model.glsl, point/spot). The beam and the haze must fall off exactly like
   the pool the lamp leaves on surfaces — any softer and the air glows past the wall the light
   already died on. */
float physicalAir(float dist, float range)
{
    float srcR = max(LightParams.z, 0.05);
    float rr = (dist * dist) / (range * range);
    float w = clamp(1.0 - rr * rr, 0.0, 1.0);

    return w * w / (dist * dist + srcR * srcR);
}

/* How much this point of AIR is lit by the lamp. */
float airDensity(vec3 pos)
{
    float type = LightParams.w;
    vec3 toLight = LightPos - pos;
    float dist = length(toLight);
    float range = LightParams.y;

    if (dist > range)
    {
        return 0.0;
    }

    /* Softer falloff than surfaces get: air glow reads better with a longer tail. A PHYSICAL lamp
       (point/spot) swaps it for the surface law — the beam must agree with the pool. */
    float falloff = (1.0 - dist / range);

    falloff *= falloff;

    if (LightFalloff > 0.5 && type < 1.5)
    {
        falloff = physicalAir(dist, range);
    }

    /* The BEAM must obey the fixture's throw too, or a batwing lamp would still glow like a bare
       cone in haze. */
    if (LightExtra.y > 0.5)
    {
        falloff *= vfxIes(LightExtra.y, dot(normalize(pos - LightPos), normalize(LightDir.xyz)));
    }

    if (type > 0.5 && type < 1.5)
    {
        /* Spot: only inside the cone, soft toward its edge. */
        float ca = vfxConeCos(normalize(pos - LightPos), LightDir.xyz, LightUp.xyz,
            vec2(LightShape.w, LightUp.w));
        float denom = max(LightShape.x - LightDir.w, 0.0005);
        float cone = clamp((ca - LightDir.w) / denom, 0.0, 1.0);

        return falloff * cone * cone * (3.0 - 2.0 * cone);
    }

    if (type > 1.5)
    {
        /* Sphere emitter: a hot core the size of the ball, fading out. */
        float radius = max(LightShape.y * 0.5, 0.1);
        float core = clamp(1.0 - (dist - radius) / (range * 0.5), 0.0, 1.0);

        /* Panels are DIRECTIONAL, mirroring the surface shading: a one-sided emitter throws no beam
           behind itself, and spread narrows the lobe the way a honeycomb grid does. At the defaults
           (two-sided or spread 1) the factors collapse to 1 — pow(x, 0) — and the old radial glow is
           kept exactly; the pow is gated so spread 1 never evaluates pow(0, 0). */
        vec3 nd = normalize(pos - LightPos);
        vec3 f = normalize(LightDir.xyz);
        float axial = dot(nd, f);

        if (LightShape.x < 0.5)
        {
            falloff *= smoothstep(0.0, 0.25, axial);
        }

        float spread = clamp(LightShape.w, 0.02, 1.0);

        if (spread < 0.999)
        {
            falloff *= pow(abs(axial), 1.0 / spread - 1.0);
        }

        return falloff * core;
    }

    /* Point: near-bulb halo. The fill law squares once more for a tight halo; the physical law is
       already the steepest thing here — take it as it is. */
    return LightFalloff > 0.5 ? falloff : falloff * falloff;
}

/* Haze halo: the light glowing into ambient mist — a SOFT radial density that fills the space around
   the lamp in EVERY direction, not just the cone. The march in-scatters and phase-lights it like the
   beam, but it is wider and gentler, so it reads as a luminous fog around the source (a streetlamp in
   mist), not a shaft. Strength arrives in LightShape.z. */
float hazeHalo(vec3 pos)
{
    float dist = length(LightPos - pos);
    float range = LightParams.y;

    if (dist > range)
    {
        return 0.0;
    }

    /* Physical lamp (point/spot): the halo obeys the same windowed 1/d² as its pool. */
    if (LightFalloff > 0.5 && LightParams.w < 1.5)
    {
        return physicalAir(dist, range);
    }

    float f = 1.0 - dist / range;

    return f * f;
}

/* True 3D value noise (trilinear, Z included) and its fBm — the fractal, billowing structure that
   makes haze read as CLOUD, the AE-Fractal-Noise look, not a smooth glow. */
float vfxHash3(vec3 p)
{
    /* pcg3d — a true INTEGER-LATTICE hash. Two traps avoided here: fract(sin(big)) degenerates at
       world-coordinate magnitudes (concentric rings), and the small-argument Hoskins hash RAMPS
       linearly along integer axes (its near-equal coefficients make h sweep slowly cell-to-cell, so
       an occupancy gate selects whole SLABS — stripes). pcg scrambles the integer bits directly and
       decorrelates neighbours on every axis. */
    uvec3 v = uvec3(ivec3(floor(p)));

    v = v * 1664525u + 1013904223u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;
    v ^= v >> 16u;
    v.x += v.y * v.z;
    v.y += v.z * v.x;
    v.z += v.x * v.y;

    return vec3(v).x * (1.0 / 4294967296.0);
}

float vfxNoise3(vec3 p)
{
    vec3 i = floor(p);
    vec3 f = fract(p);

    f = f * f * (3.0 - 2.0 * f);

    float x00 = mix(vfxHash3(i), vfxHash3(i + vec3(1.0, 0.0, 0.0)), f.x);
    float x10 = mix(vfxHash3(i + vec3(0.0, 1.0, 0.0)), vfxHash3(i + vec3(1.0, 1.0, 0.0)), f.x);
    float x01 = mix(vfxHash3(i + vec3(0.0, 0.0, 1.0)), vfxHash3(i + vec3(1.0, 0.0, 1.0)), f.x);
    float x11 = mix(vfxHash3(i + vec3(0.0, 1.0, 1.0)), vfxHash3(i + vec3(1.0, 1.0, 1.0)), f.x);

    return mix(mix(x00, x10, f.y), mix(x01, x11, f.y), f.z);
}

/* fBm: octaves at doubling frequency, halving amplitude — the self-similar detail of fractal noise.
   THREE octaves, not five: the two finest were sub-centimetre detail no fog read can show, and the
   march pays for two fBm evaluations per step — this is the single biggest ALU line in the pass
   (the "20-30 fps for two lamps" profile). */
float vfxFbm(vec3 p)
{
    float v = 0.0;
    float a = 0.5;

    for (int i = 0; i < 3; i++)
    {
        v += a * vfxNoise3(p);
        p = p * 2.02 + vec3(17.3, 9.1, 23.7);
        a *= 0.5;
    }

    return v;
}

/*
 * Henyey-Greenstein phase, normalised to 1 at side-view. Real dust scatters FORWARD: a beam flares
 * when the camera faces the lamp (the backlight shot) and calms from behind. An isotropic beam glows
 * identically from every angle, and that sameness is exactly what reads as "flat" on film.
 */
float phase(float mu)
{
    float g = 0.45;
    float g2 = g * g;

    return min((1.0 - g2) / pow(1.0 + g2 - 2.0 * g * mu, 1.5) / (1.0 - g2), 3.5);
}

/*
 * Spectral tint of the AIR at a point in the beam: four wavelengths of the caustic field, fanned the
 * same way the surface version fans eight — four is where the volume stops caring (24 march steps
 * amortise the difference) and the cost stays honest. Returns a modulation around 1, so a beam with
 * dispersion keeps its body and gains rainbow shafts, rather than dimming to whatever the field says.
 */
vec3 dispersionTint(vec3 samplePos)
{
    vec3 nd = normalize(samplePos - LightPos);
    vec3 f = normalize(LightDir.xyz);

    /* A point light has an arbitrary axis; any stable frame will do — the caustic just needs one. */
    if (abs(LightParams.w) < 0.5)
    {
        f = abs(nd.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    }

    vec3 u = LightUp.xyz - f * dot(LightUp.xyz, f);

    u = length(u) < 0.0001 ? (abs(f.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)) : normalize(u);

    vec3 r = cross(f, u);
    vec2 cuv = vec2(dot(nd, r) * 7.0, dot(nd, u) * 2.6) * Dispersion.y;
    vec3 sum = vec3(0.0);

    for (int i = 0; i < 4; i++)
    {
        float t = float(i) / 3.0;
        vec2 uvw = cuv + vec2(0.9, 0.32) * ((t - 0.5) * 1.1);
        float field = vfxCausticField(uvw, Dispersion.z);

        /* Soft-knee the folds IN AIR: the surface wants ridges that overshoot, but in the march the
           same spikes multiply against a thin beam's density and print the dither grid onto it. */
        field = 0.25 + 0.75 * field / (0.6 + field);

        sum += vfxSpectrum(t) * field;
    }

    sum *= 0.85;

    float peak = max(sum.r, max(sum.g, sum.b));

    sum = mix(sum, vec3(peak), 0.22);

    return mix(vec3(1.0), sum * 1.5, Dispersion.x * 0.65);
}

/*
 * Water's answer to the AIR in the beam past its surface: rgb = Beer-Lambert absorption of the
 * submerged path (the shaft turns teal, then blue, then dies), a = a density multiplier carrying the
 * caustic net through the water column — the wavering bright veins of an underwater beam.
 */
vec4 waterAt(vec3 samplePos)
{
    float waterDist = Dispersion.w;

    if (waterDist > -1.5 && waterDist < 0.0)
    {
        return vec4(1.0);
    }

    float d = length(samplePos - LightPos);

    if (waterDist >= 0.0 && d <= waterDist)
    {
        return vec4(1.0);
    }

    vec3 nd = normalize(samplePos - LightPos);
    vec3 f = normalize(LightDir.xyz);

    if (abs(LightParams.w) < 0.5)
    {
        f = abs(nd.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0);
    }

    vec3 u = LightUp.xyz - f * dot(LightUp.xyz, f);

    u = length(u) < 0.0001 ? (abs(f.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)) : normalize(u);

    vec3 r = cross(f, u);
    /* World-ish scale: angular uv times distance = arc length, cells stay wave-sized. */
    vec2 cuv = vec2(dot(nd, r), dot(nd, u)) * clamp(d, 2.0, 24.0) * 1.1;
    float net = vfxCausticField(cuv, Dispersion.z * 2.6);

    /* The reflected share (waterDist = -2): the net rides the shaft, no absorption — this light
       bounced OFF the water, it never travelled through it. */
    if (waterDist < -1.5)
    {
        return vec4(1.0, 1.0, 1.0, max(1.0 + 0.7 * exp(-d * 0.04) * (net - 0.55), 0.0));
    }

    /* Only the SUBMERGED stretch of the shaft: above the surface the air is just air. */
    if (samplePos.y > LightExtra.z + 0.1)
    {
        return vec4(1.0);
    }

    float depth = d - waterDist;
    vec3 absorb = exp(-depth * vec3(0.30, 0.12, 0.07));
    float mul = max(1.0 + 0.7 * exp(-depth * 0.06) * (net - 0.55), 0.0);

    return vec4(absorb, mul);
}

void main()
{
    /* Before the march samples the atlas — the model's tile side and filter are runtime values. */
    VFX_TILE = AtlasInfo.x > 0.5 ? AtlasInfo.x : 1024.0;
    VFX_FILTER = AtlasInfo.y;

    /* The depth snapshot was blitted from the pack's JITTERED gbuffer depth: per frame the scene
       end walked within the pixel footprint and the marched volume oscillated with it — the
       beam/haze "дрожь" at distance. Take the FAR side of the footprint (max of four taps):
       phase-invariant, so the march end is stable. ±1 texel, not ±0.5: Eclipse's TAA runs an
       8-frame Halton at ±0.875 px, and half a texel simply did not cover the walk. The far side
       merely lengthens the march a hair — air glow bleeding slightly further is invisible next
       to the crawl it kills. Vanilla needs it not (no jitter), but pays the same four taps. */
    vec2 vfxHalfPx = 1.0 / vec2(textureSize(DepthSampler, 0));
    float depth = max(max(texture(DepthSampler, texCoord + vfxHalfPx).r,
                          texture(DepthSampler, texCoord - vfxHalfPx).r),
                      max(texture(DepthSampler, texCoord + vec2(vfxHalfPx.x, -vfxHalfPx.y)).r,
                          texture(DepthSampler, texCoord + vec2(-vfxHalfPx.x, vfxHalfPx.y)).r));
    /* The marched scene depth01 goes out in ALPHA (nearest of the stacked beams, MIN blend): the
       upsample reads it for its bilateral weights and for the vanilla hand mask — the gl_FragDepth
       stamp this replaces only worked while the march drew straight into the main buffer. */
    vec3 sceneEnd = worldPosition(depth);

    /* The ray direction comes from the pixel CENTRE at a fixed ndc depth — never from the
       reconstructed scene end: the gbuffer depth is TAA-jittered, so a direction taken from
       sceneEnd wobbles the whole marched volume with every jitter phase (the beam/haze
       "дрожь" and the follows-the-camera complaint). A fixed ndc.z is phase-invariant. */
    vec4 ndcDir = vec4(texCoord * 2.0 - 1.0, 1.0, 1.0);
    vec4 worldDir4 = InvViewProj * ndcDir;
    vec3 rayDir = normalize(worldDir4.xyz / worldDir4.w);
    float sceneDist = length(sceneEnd - CameraPos);


    /* March only the segment of the ray that can intersect the light's sphere of influence. */
    vec3 toCentre = LightPos - CameraPos;
    float along = dot(toCentre, rayDir);
    float perpSq = dot(toCentre, toCentre) - along * along;
    float radius = LightParams.y;
    float radiusSq = radius * radius;

    if (perpSq >= radiusSq)
    {
        discard;
    }

    float half_ = sqrt(radiusSq - perpSq);
    float start = max(along - half_, 0.05);
    float end = min(along + half_, sceneDist);

    if (end <= start)
    {
        discard;
    }

    /* Interleaved Gradient Noise on the start offset: discrete steps become smooth fog without
       shells. STATIC per pixel — the march renders at half resolution and the bilateral upsample
       averages this grain away; the per-frame golden-ratio re-randomisation this replaced read as
       living noise ("шум") in vanilla and, fighting the pack's TAA, as crawl ("дрожь"). Dispersive
       beams take more steps — their spectral children are THIN, and a thin cone crossed by few
       samples turns the dither into a visible pixel grid (measured on the fan). */
    float dither = fract(52.9829189 * fract(0.06711056 * gl_FragCoord.x + 0.00583715 * gl_FragCoord.y));

    /* ADAPTIVE steps, the density the quality preset asks for (VolQuality.x samples per marched
       block; Medium = 4, the value the tuned look lived at): a 4-block beam pays 16 steps instead
       of 64, a 16-block one lands exactly on the old fixed count, and long beams cap at the old
       maxima so worst-case cost and their look are unchanged. Dispersion keeps a raised floor —
       its spectral children are THIN and under-sampling grids the dither (measured). Bright beams
       take the raised cap ("выкрути на полную катушку"): past luma 1 the coverage variance of the
       smoke fBm scales with brightness and the grain turns visible.

       The samples sit on a WORLD-ANCHORED LATTICE along the ray — a fixed spacing hung off the
       light's projected centre — never spread evenly between start and end. The even spread
       re-laid out EVERY sample whenever ceil(segLen * q) ticked ±1 under a moving camera, and the
       thin caustic/dispersion ridges, re-sampled at shifted positions, read as flicker. On the
       lattice a camera move slides the whole grid smoothly and only adds or drops a sample at the
       segment ENDS. The spacing itself comes from a discrete doubling ladder around the natural
       density, so the cap/floor clamps cannot make it breathe with the camera either: it shifts at
       isolated span thresholds, once. The haze fBm shares this march and inherits the stability. */
    float beamLuma = dot(LightColor, vec3(0.299, 0.587, 0.114));
    float segLen = end - start;
    float maxSteps = Dispersion.x > 0.001 ? VolQuality.z : (beamLuma > 1.0 ? VolQuality.w : VolQuality.z);
    float minSteps = Dispersion.x > 0.001 ? VolQuality.y + 8.0 : VolQuality.y;
    float ideal = segLen * VolQuality.x;
    /* Ladder rung: 0 at natural density, >0 coarsens by doublings toward the cap, <0 refines by
       halvings toward the floor — count = ideal * 2^-ladder always lands in (cap/2, cap] or
       [floor, 2*floor), and between rungs nothing moves. */
    float ladder = max(ceil(log2(ideal / maxSteps)), min(0.0, floor(log2(ideal / minSteps))));
    float stepLen = exp2(ladder) / VolQuality.x;
    /* Lattice indices covering [start, end], hung off the light's projection on the ray: smooth
       under camera motion, so sample i keeps its world neighbourhood instead of re-layouting. The
       count is NOT floored to minSteps here — the ladder already refined the spacing for short
       spans, and stretching extra samples past `end` would bleed the beam through the occluder
       that cut the segment short. */
    float first = ceil((start - along) / stepLen - 0.0001);
    float last = floor((end - along) / stepLen + 0.0001);
    int steps = int(clamp(last - first + 1.0, 1.0, maxSteps));
    float stepsF = float(steps);
    float travelled = along + (first + dither) * stepLen;
    vec3 accum = vec3(0.0);
    bool spotRim = Dispersion.x > 0.001 && LightParams.w > 0.5 && LightParams.w < 1.5;
    vec3 shadowTint = vec3(1.0);

    for (int i = 0; i < steps; i++)
    {
        vec3 samplePos = CameraPos + rayDir * travelled;

        /* Haze = a soft radial halo CARVED by 3D fractal noise, so it billows like cloud instead of
           glowing evenly (the AE Fractal Noise look the refs asked for). The fBm is remapped hard —
           dark gaps, bright wisps — and the brightness that the gaps eat is put back, so the average
           stays. Slow drift on three axes makes it roll, not fizz. */
        float hazeD = hazeHalo(samplePos) * LightShape.z;

        if (hazeD > 0.0)
        {
            vec3 np = samplePos * 0.45 + vec3(GameTime * 0.5, GameTime * 0.18, GameTime * 0.32);
            float fn = clamp(vfxFbm(np) * 1.7 - 0.45, 0.0, 1.0);

            hazeD *= fn * 2.2;
        }

        /* Beam (cone-gated) and haze share the march; each carries its own strength so the beam stays
           exactly as before and the fractal haze adds on top. The beam's shape is kept on its own so
           the dust motes below can gate on it without a second airDensity() call. */
        float beamShape = airDensity(samplePos);
        float density = beamShape * LightParams.x + hazeD;
        vec3 tint = vec3(1.0);

        if (density > 0.001)
        {
            /* The shadow tap is the march's priciest sample — take it every OTHER step and hold
               it between (IRLite's stride cache): beams are smooth at half-res and the bilateral
               upsample irons the alternation out; the rim/dispersion/water tints below stay
               per-step, they are cheap. */
            if (mod(float(i), 2.0) < 0.5)
            {
                shadowTint = shadowAt(samplePos);
            }

            tint = shadowTint;

            /* Cathedral shaft: a beam through stained glass is coloured, and a coloured tint (channels
               below 1) multiplies the shaft DIMMER than a clear one — the god-ray washed out next to a
               shaderpack's. Lift the density where the beam is tinted so the colour reads as a bright
               shaft, the light a window concentrates rather than loses. Clear beams (tint ~ white) are
               untouched. */
            float tinted = 1.0 - min(tint.r, min(tint.g, tint.b));

            density *= 1.0 + tinted * 1.6;

            /* Smoky beam: fractal carving with a mild along-axis stretch — rolling texture without
               comb-striping the shaft (a strong stretch read as literal stripes down the beam). */
            vec3 smokeAxis = dot(LightDir.xyz, LightDir.xyz) > 0.0001 ? normalize(LightDir.xyz) : vec3(0.0, -1.0, 0.0);
            float axAlong = dot(samplePos - LightPos, smokeAxis);
            vec3 axAcross = (samplePos - LightPos) - smokeAxis * axAlong;
            vec3 bnp = axAcross * 1.1 + smokeAxis * (axAlong * 0.5 - GameTime * 0.55)
                + vec3(GameTime * 0.13, GameTime * 0.07, GameTime * 0.1);
            float bfn = clamp(vfxFbm(bnp) * 1.8 - 0.5, 0.0, 1.0);

            density *= 0.35 + 1.8 * bfn;

            /* Dust motes are NOT here: a march step and a mote lattice resonate into shells/rings no
               matter the hash. They live in DustPass as real billboards (light_dust.fsh). */

            /* Photons travel lamp -> sample; the eye receives along -rayDir. mu = 1 means the camera
               is looking straight into the light through this sample — the flare of a backlight. */
            float mu = dot(normalize(samplePos - LightPos), -rayDir);

            density *= 0.55 + 0.45 * phase(mu);

            /* Dispersion rim: toward the cone's edge the beam splits into an ordered spectrum —
               violet leaning in, red bleeding out, the way a wide lens flares. Core stays white. */
            if (spotRim)
            {
                float ca = vfxConeCos(normalize(samplePos - LightPos), LightDir.xyz, LightUp.xyz,
                    vec2(LightShape.w, LightUp.w));
                float denom = max(LightShape.x - LightDir.w, 0.0005);
                float edge = 1.0 - clamp((ca - LightDir.w) / denom, 0.0, 1.0);
                float rim = smoothstep(0.35, 0.95, edge);

                tint *= mix(vec3(1.0), vfxSpectrum(0.85 - 0.6 * edge), Dispersion.x * rim);
            }

            /* And through the body of the beam, the caustic shafts — the gobo projected into haze. */
            if (Dispersion.x > 0.001)
            {
                tint *= dispersionTint(samplePos);
            }

            /* Underwater: the shaft absorbs red-first and carries the caustic net through the column. */
            vec4 water = waterAt(samplePos);

            tint *= water.rgb;
            density *= water.a;
        }

        accum += density * tint;
        travelled += stepLen;
    }

    /* Normalised by step count, scaled by path length so a long trip through the beam glows more. */
    vec3 glow = accum / stepsF * clamp((end - start) / radius, 0.0, 1.0);

    /* Strength is already inside the density (beam x LightParams.x + haze x LightShape.z).
       When rim light is dialed up, the LDR surface composite can saturate to white and swallow an
       additive beam. Lift the air glow slightly with the rim dial so it stays readable. */
    vec3 outColor = LightColor * 0.35 * glow * (1.0 + LightExtra.w * 0.5);

    /* ★NaN/Inf guard (vfxGuard): a NaN from the march added over the frame reads as a fully BLACK
       screen once a beam draws — measured in third-person. A bad pixel costs its own glow only.
       Alpha = the marched scene depth01, ADDed onto the 0-cleared buffer: single-beam pixels carry
       the exact depth the upsample's bilateral and hand mask read; no-beam pixels stay 0. */
    fragColor = vec4(vfxGuard(outColor), depth);
}
