// VFX LIGHTS — THE light model. One source of truth, three consumers:
//
//   1. light_composite.fsh   (no-pack fallback)  — pulls this in with #moj_import <vfxlights:...>;
//   2. vfxlights_lights.glsl (pack patch)        — PackPatcher splices this file over //@VFX_MODEL@;
//   3. light_volumetric.fsh  (beams)             — imports the leaf functions for its march.
//
// Before this file existed the model lived TWICE — light_composite.fsh and the pack patch were
// independent ~1.2k-line implementations of the same math, and every feature had to land in both
// or the backends silently disagreed (they did: cel on sphere/tube area lamps worked only in the
// fallback, translucency on area lamps only in the pack, the tube penumbra differed 2x).
//
// PORTABILITY RULES — this file must compile everywhere at once:
//   * from #version 120 (Complementary) through #version 400+ deferred packs and the vanilla 150 core
//     shaders, so: no array initializers, no textureSize/texelFetch, no uints, texture2D remapped
//     below;
//   * NO uniform or SSBO access — every function takes its data as parameters. Each backend gathers
//     its own slots (uniforms vs vfxL[]) into VfxLightDesc/VfxSurface and calls in; that adapter is
//     the ONLY per-backend code;
//   * every global symbol carries the vfx prefix — this text lands inside other people's packs.
//
// MERGE DECISIONS (2026-07-28), where the two implementations disagreed:
//   * slope-scaled shadow bias: pack had it, fallback did not -> slopeMax parameter (pack 6.0,
//     fallback 1.0 = the old flat bias, its depth-derived normals are too noisy to steer bias);
//   * far plane guard max(range, near+0.15): fallback had it, pack did not -> unified in;
//   * cel celShape/celReach on sphere/tube area lamps: pack left them 0 (lamp went dark in cel
//     mode) -> unified to the fallback's dot(n,L)/factor for every area shape;
//   * cel'd area diffuse missed the range fade in the pack -> celReach now uses the FADED factor;
//   * translucency (SSS) on area lamps: pack only -> unified in (fallback gains it);
//   * shadow gate: unified to max(factor, spill*disp) — the fallback's ndl-gate skipped the shadow
//     for spill-only caustics;
//   * area rim direction: representative point (fallback) over emitter centre (pack) — the rim
//     follows the surface of the emitter you actually see reflected;
//   * tube shadow penumbra: thickness*0.5 (pack) over thickness (old fallback Java) — the radius,
//     consistent with rect/disc using half-extents;
//   * contour ink carries the vec3 shade (fallback) rather than its max channel (pack): ink through
//     stained glass is tinted like every other delivery;
//   * specular gain stays per-backend (pack 0.8 with real materials, fallback 0.6 against its fixed
//     0.42 gloss + partial albedo tint) -> VfxSurface.specGain.

#if __VERSION__ >= 130
#define texture2D texture
#endif

/* Shadow atlas tile side, in texels. NOT const, and the ONE exception to "every function takes its
   data as parameters": the Shadow quality setting moves it at runtime (512..4096), and it is read by
   nine leaf functions three call levels down — threading it through every signature would put a
   parameter on the whole shadow stack for a number that is the same for every lamp in the frame.
   So each backend stamps it ONCE at the top of its entry point (light_composite.fsh and
   light_volumetric.fsh from the AtlasTile uniform, vfxlights_lights.glsl from vfxAtlas.y) before
   anything here runs. The literal below is the default, and what a backend that forgets to stamp
   would silently get — which is why VfxLightsClient parses this line at startup and logs an ERROR if
   it ever drifts from ShadowAtlas.DEFAULT_TILE_SIZE.

   Everything derived from it is written in TEXELS (radiusUv = n / VFX_TILE), so raising the quality
   tightens the filters by exactly the same factor the map sharpened by. The biases are in WORLD
   units and deliberately do not scale — acne is a property of the depth range, not of the density. */
float VFX_TILE = 1024.0;

/* Shadow filtering: 0 fast, 1 balanced (what the look was tuned against), 2 high, 3 ultra. Stamped
   the same way and for the same reason as VFX_TILE. It buys the OTHER half of shadow quality —
   resolution decides how much detail the map HOLDS, this decides how cleanly the edge is read out of
   it, and it costs frame time rather than memory. Higher steps sample a DENSER grid over a TIGHTER
   kernel, so they are sharper and smoother at once; the tuned 1.2-texel kernel is step 1's. */
float VFX_FILTER = 1.0;

/* Must match ShadowAtlas.NEAR on the Java side — verified at startup the same way. */
const float VFX_NEAR = 0.05;

/* Global brightness. Was 3.0 (tuned loud against CR's blocklight); dropped after "интенсивность
   очень сильная" on every shader. The per-lamp intensity dial rides on top. Applied by the BACKENDS
   at compose time (ink excluded — a drawn line is not radiometry).
   ONE shared baseline for every pack, the IRLite way: the light is injected pre-exposure with the
   pack's own conventions, so each pack treats it like its native lights (torches also read
   differently pack to pack — accepted). A per-pack calibration ladder (measured constants per
   patch) was tried and abandoned by the author's call — per-pack exposure/tonemap variance is the
   pack's character, not a bug. A patch MAY still #define VFX_GAIN before the include if one ever
   proves unusable out of range. */
#ifndef VFX_GAIN
#define VFX_GAIN 1.0
#endif

/* ------------------------------------------------------------------------------------------------
 * Shadow atlas plumbing
 * ------------------------------------------------------------------------------------------------ */

/* Where a tile sits in the atlas, in texture coordinates. */
vec4 vfxTileRect(float tile, float across)
{
    float size = 1.0 / across;

    return vec4(mod(tile, across) * size, floor(tile / across) * size, size, size);
}

/* Raw depth stored at a tile position. Taps clamp inside the tile so a wide filter near the edge
   cannot read the neighbouring light's map. */
float vfxStored(sampler2D atlas, vec4 rect, vec2 uv)
{
    vec2 cl = clamp(uv, vec2(1.0 / VFX_TILE), vec2(1.0 - 1.0 / VFX_TILE));

    return texture2D(atlas, rect.xy + cl * rect.zw).r;
}

/* Bilinear read of the same NEAREST atlas for the STATISTICAL taps (moments, contact): a NEAREST
   tap grid identical on every pixel beats against the texel grid into barcode bands and sawteeth
   at grazing, and the per-pixel jitter that broke the beat up traded the bands for speckle on the
   skin. Four texel reads lerped by hand (GLSL 120 has no textureLod gather) — the filtered values
   smooth the beat without any grain, and the taps stay clamped inside the tile like vfxStored.
   The hard compare path keeps plain vfxStored: interpolating depth at the binary compare would
   shift the shadow edge. */
float vfxStoredSmooth(sampler2D atlas, vec4 rect, vec2 uv)
{
    float tc = 1.0 / VFX_TILE;
    vec2 t = clamp(uv * VFX_TILE - 0.5, vec2(0.0), vec2(VFX_TILE - 1.0));
    vec2 b0 = floor(t);
    vec2 b1 = min(b0 + 1.0, vec2(VFX_TILE - 1.0));
    vec2 f = t - b0;

    float z00 = texture2D(atlas, rect.xy + vec2(b0.x + 0.5, b0.y + 0.5) * tc * rect.zw).r;
    float z10 = texture2D(atlas, rect.xy + vec2(b1.x + 0.5, b0.y + 0.5) * tc * rect.zw).r;
    float z01 = texture2D(atlas, rect.xy + vec2(b0.x + 0.5, b1.y + 0.5) * tc * rect.zw).r;
    float z11 = texture2D(atlas, rect.xy + vec2(b1.x + 0.5, b1.y + 0.5) * tc * rect.zw).r;

    return mix(mix(z00, z10, f.x), mix(z01, z11, f.x), f.y);
}

/* Perspective depth01 back to linear distance along the projection axis. */
float vfxLinearDepth(float d01, float near, float far)
{
    float a = (far + near) / (far - near);
    float b = 2.0 * far * near / (far - near);

    return b / max(a + 1.0 - 2.0 * d01, 0.0001);
}

/* One filtered comparison: read the 2x2 texel neighbourhood, compare EACH against the reference,
   then blend the binary RESULTS bilinearly. Order matters — blending depths before comparing
   averages two unrelated surfaces into a depth that exists nowhere, while blending comparisons
   gives the sub-texel edge position. This is what turns a staircase edge into a straight one. */
float vfxTap(sampler2D atlas, vec4 rect, vec2 uv, float ref)
{
    vec2 t = uv * VFX_TILE - 0.5;
    vec2 f = fract(t);
    vec2 base = (floor(t) + 0.5) / VFX_TILE;
    float s00 = ref <= vfxStored(atlas, rect, base) ? 1.0 : 0.0;
    float s10 = ref <= vfxStored(atlas, rect, base + vec2(1.0 / VFX_TILE, 0.0)) ? 1.0 : 0.0;
    float s01 = ref <= vfxStored(atlas, rect, base + vec2(0.0, 1.0 / VFX_TILE)) ? 1.0 : 0.0;
    float s11 = ref <= vfxStored(atlas, rect, base + vec2(1.0 / VFX_TILE)) ? 1.0 : 0.0;

    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
}

/* Same bilinear compare but with a SOFT depth ramp instead of the binary step: a shadow term
   that is continuous in depth cannot flicker from sub-millimetre pose motion — idle breathing
   slides each tap's response along the ramp instead of clicking it lit/shadowed (measured:
   the body shimmer was the binary step crossing at mm density, in vanilla too). The band is
   sub-millimetre, so silhouette edges keep their read. */
float vfxTapSoft(sampler2D atlas, vec4 rect, vec2 uv, float ref, float band)
{
    vec2 t = uv * VFX_TILE - 0.5;
    vec2 f = fract(t);
    vec2 base = (floor(t) + 0.5) / VFX_TILE;
    float s00 = smoothstep(ref - band, ref, vfxStored(atlas, rect, base));
    float s10 = smoothstep(ref - band, ref, vfxStored(atlas, rect, base + vec2(1.0 / VFX_TILE, 0.0)));
    float s01 = smoothstep(ref - band, ref, vfxStored(atlas, rect, base + vec2(0.0, 1.0 / VFX_TILE)));
    float s11 = smoothstep(ref - band, ref, vfxStored(atlas, rect, base + vec2(1.0 / VFX_TILE)));

    return mix(mix(s00, s10, f.x), mix(s01, s11, f.x), f.y);
}

/* The COLOURED part of the shadow, softened: the stained tint averaged over a golden-angle disc so
   its edge FEATHERS in rather than snapping from white to full colour in one texel. Kept SEPARATE
   from the geometric shadow, which stays crisp — only the colour boundary is soft, like real
   diffuse light through a pane. The disc average also dissolves the last artefacts (8-bit
   staircases, gone with RGBA16, and the one-texel silhouette rim) since a lone "no glass" texel is
   outvoted by its tinted neighbours. */
vec3 vfxTint(sampler2D glassMap, vec4 rect, vec2 uv, float recvDist, float far, float radiusUv)
{
    vec3 sum = vec3(0.0);
    /* Same disc, denser at a higher filter step — the tint's edge is an AVERAGE, so more taps only
       make the feather smoother. Radius is unchanged (the spiral normalises by the live count). */
    int tintN = VFX_FILTER >= 2.5 ? 24 : (VFX_FILTER >= 1.5 ? 16 : (VFX_FILTER >= 0.5 ? 10 : 6));

    for (int i = 0; i < 24; i++)
    {
        if (i >= tintN)
        {
            break;
        }

        float fi = float(i);
        vec2 off = vec2(cos(fi * 2.39996), sin(fi * 2.39996))
            * sqrt((fi + 0.5) / float(tintN)) * radiusUv;
        vec2 cl = clamp(uv + off, vec2(1.0 / VFX_TILE), vec2(1.0 - 1.0 / VFX_TILE));
        vec4 g = texture2D(glassMap, rect.xy + cl * rect.zw);

        sum += recvDist > g.a * far + 0.05 ? g.rgb : vec3(1.0);
    }

    return sum / float(tintN);
}

/*
 * Which face of a cube a direction points at, and where on that face it lands.
 *
 * A point light needs six views to see all around itself. The usual answer is a cube-map array, but
 * that is an ARB/GL4 feature and Minecraft asks for a 3.2 core context — this codebase has had GL33
 * calls abort the JVM outright. So the six views live as six ordinary tiles in a 2D atlas, and this
 * function does by hand what a cube sampler would have done: pick the face by the dominant axis,
 * divide the other two by it. Face order matches the renderer: +X, -X, +Y, -Y, +Z, -Z.
 *
 * Depth comes back along the DOMINANT AXIS, not euclidean distance — that is what the projection
 * wrote. Using length() instead produces a six-pointed star of self-shadowing.
 */
float vfxCubeFace(vec3 dir, out vec2 uv, out float axisDepth)
{
    vec3 ad = abs(dir);
    float face;
    vec2 st;

    if (ad.x >= ad.y && ad.x >= ad.z)
    {
        axisDepth = ad.x;
        face = dir.x > 0.0 ? 0.0 : 1.0;
        st = dir.x > 0.0 ? vec2(-dir.z, -dir.y) : vec2(dir.z, -dir.y);
    }
    else if (ad.y >= ad.z)
    {
        axisDepth = ad.y;
        face = dir.y > 0.0 ? 2.0 : 3.0;
        st = dir.y > 0.0 ? vec2(dir.x, dir.z) : vec2(dir.x, -dir.z);
    }
    else
    {
        axisDepth = ad.z;
        face = dir.z > 0.0 ? 4.0 : 5.0;
        st = dir.z > 0.0 ? vec2(dir.x, -dir.y) : vec2(-dir.x, -dir.y);
    }

    // 0.48544 = 0.5 / 1.03: the faces are RENDERED with a 1.03 half-tangent (ShadowMapper) so their
    // content overlaps past the seams; the lookup shrinks by the same factor. Seam rays land on
    // interior texels of either face instead of the empty half-texel sliver at the edge.
    uv = st / axisDepth * 0.48544 + 0.5;

    return face;
}

/*
 * Shadow factor: PCSS against the atlas, penumbra from blocker geometry, softness from the source
 * size. Returns a COLOUR — black in shadow, white in the open, and behind a stained pane the pane's
 * tint.
 *
 * The penumbra is not a style knob. It follows from geometry — source size times (receiver distance
 * minus blocker distance) over blocker distance — which means a shadow is CRISP AND DARK where the
 * object meets the ground and widens as it falls away.
 *
 * geoN must be the flat GEOMETRIC normal: offsetting the sample point along a per-texel mapped
 * normal jitters the lookup texel by texel, and the shadow edge dissolves into pixel noise.
 *
 * slopeMax caps the slope-scaled bias: at grazing angles one shadow texel spans a long run of
 * depth, so the offsets widen by ~1/cos as the surface turns edge-on — worst where a pack's
 * geometric normal is octahedral-quantised (IterationRP). Head-on the factor is 1 and the base bias
 * stays SMALL, so the shadow never detaches from the object's base. slopeMax 1.0 disables the
 * scaling entirely (the fallback: its depth-reconstructed normals are too noisy to steer bias).
 */
vec3 vfxShadow(sampler2D atlas, sampler2D glassMap,
    vec3 pos, vec3 geoN, float dist, vec3 lightPos,
    float type, bool cube, float firstTile, float across, float near, float far,
    mat4 shadowMatrix, float cosOuter, float softness, float slopeMax)
{
    if (firstTile < 0.0)
    {
        return vec3(1.0);
    }

    vec3 lv = normalize(lightPos - pos);
    float slope = clamp(1.0 / max(dot(geoN, lv), 0.15), 1.0, slopeMax);

    /* Offset the receiver along its geometric normal only — IRLite's normal-offset bias: the
       surface is lifted out of its own thin overlay shell (the second skin layer is ~1.5 cm of
       coplanar geometry that otherwise acne-shadows the body at some poses), while blocks keep
       their tight contact. Cheaper and steadier than a large depth bias.
       FLAT on purpose: the earlier normalize(geoN + lv) * slope form added a lateral, light-parallel
       component that peter-panned every thin occluder at grazing light — fences and grass lost
       contact with their own shadows by decimetres (packs only; the vanilla fallback never scaled
       it). The slope scaling now lives only in the depth bias below. */
    vec3 sp = pos + geoN * (0.012 + dist * 0.0025);

    vec4 rect;
    vec2 uv;
    float reference;
    float recvDist;
    /* Blocks-to-UV at unit distance: tan of the half field of view the map was rendered with. */
    float tanHalf;

    vec3 dir = sp - lightPos;

    if (cube)
    {
        float axisDepth;
        float face = vfxCubeFace(dir, uv, axisDepth);

        rect = vfxTileRect(firstTile + face, across);
        recvDist = axisDepth;
        tanHalf = 1.03;

        float a = (far + near) / (far - near);

        reference = (a - (2.0 * far * near / (far - near)) / axisDepth) * 0.5 + 0.5;
    }
    else
    {
        vec4 clip = shadowMatrix * vec4(sp, 1.0);

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

        reference = ndc.z * 0.5 + 0.5;
        rect = vfxTileRect(firstTile, across);
        recvDist = vfxLinearDepth(reference, near, far);

        float cosO = clamp(cosOuter, 0.05, 0.999);

        tanHalf = sqrt(1.0 - cosO * cosO) / cosO;
    }

    /* Bias in WORLD units, converted through the local depth derivative. A constant depth-space bias
       is millimetres near the lamp and half a metre a few blocks out (perspective depth is that
       nonlinear) — measured in game as a cube's shadow bending and detaching on its far side, worst
       along the cube-face seams where the depth axis switches. */
    float biasB = 2.0 * far * near / (far - near);

    reference -= (0.015 + recvDist * 0.003) * slope * 0.5 * biasB / max(recvDist * recvDist, 0.01);

    /* Geometric shadow (monochrome) stays CRISP or PCSS-soft; the glass TINT is feathered separately
       over a wider disc so the colour boundary eases in. vis = how lit, tint = what colour — one is
       sharp, one is soft, and multiplying them keeps the occluder shadow's edge exactly where it was. */
    float radiusUv = 1.2 / VFX_TILE;
    float vis;

    /* Rotate every tap pattern per pixel (interleaved gradient noise) — vfxActorShadow's own trick,
       for the same reason: a tap pattern IDENTICAL on every pixel beats against the shadow texel grid
       and whatever regularity survives the bias comes out as a coherent PATTERN — nested arcs of
       self-shadowing across a floor. Rotating decorrelates the two grids, so the residue lands as
       fine per-pixel grain instead, which the eye reads as texture rather than as lines. This does
       NOT replace enough bias/depth precision; it removes the STRUCTURE from what is left over. */
    float ang = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))))
        * 6.2831853;
    float ca = cos(ang);
    float sa = sin(ang);

    if (softness > 0.003)
    {
        /* SOFT, opt-in per lamp: a golden-angle blocker search sets the penumbra RADIUS (depth only),
           then the PCF fills it. The old 5-tap MIN search banded; an AVERAGE is smooth.
           This search alone stays UNROTATED, deliberately: it decides a WIDTH, and a per-pixel
           rotation would jitter that width — noise in the shadow's SHAPE, not in its sampling. The
           averaging already keeps it smooth. Rotation belongs on the taps that read visibility. */
        float blocksToUv = 1.0 / max(2.0 * tanHalf * recvDist, 0.05);
        float searchUv = clamp(softness * blocksToUv, 1.5 / VFX_TILE, 14.0 / VFX_TILE);
        float blockerSum = 0.0;
        float blockerCount = 0.0;

        /* Tap counts ride the filter dial. Constant loop BOUNDS with a dynamic break, not a dynamic
           bound: a #version 120 pack is the floor this file compiles to, and a constant-bounded loop
           is the form every driver unrolls the same way. The spiral's normalisation uses the live
           count, so the disc keeps its RADIUS at every step — only its density changes. */
        int searchN = VFX_FILTER >= 2.5 ? 24 : (VFX_FILTER >= 1.5 ? 16 : (VFX_FILTER >= 0.5 ? 8 : 4));

        for (int i = 0; i < 24; i++)
        {
            if (i >= searchN)
            {
                break;
            }

            float fi = float(i);
            vec2 off = vec2(cos(fi * 2.39996), sin(fi * 2.39996))
                * sqrt((fi + 0.5) / float(searchN)) * searchUv;
            float sd = vfxStored(atlas, rect, uv + off);

            if (sd < reference)
            {
                blockerSum += sd;
                blockerCount += 1.0;
            }
        }

        if (blockerCount > 0.5)
        {
            /* Penumbra the physical way: source size scaled by receiver-past-blocker separation —
               crisp at contact, widening with distance. */
            float blockerDist = vfxLinearDepth(blockerSum / blockerCount, near, far);
            float penumbra = softness * (recvDist - blockerDist) / max(blockerDist, 0.1);

            radiusUv = clamp(penumbra * blocksToUv, 1.2 / VFX_TILE, 12.0 / VFX_TILE);
        }

        /* Classify before paying the PCF fill: open ground (no blocker anywhere in the search
           footprint, centre included) is the majority of any lit scene, and a fully-blocked
           footprint cannot come out soft-lit — the kernel radius never exceeds the search's.
           Both answers are as conservative as the map itself: anything the spiral could have
           missed is thinner than one shadow texel and the map does not hold it either way. */
        if (blockerCount < 0.5 && vfxTap(atlas, rect, uv, reference) > 0.999)
        {
            vis = 1.0;
        }
        else if (blockerCount >= float(searchN) - 0.5 && vfxTap(atlas, rect, uv, reference) < 0.001)
        {
            return vec3(0.0);
        }
        else
        {
            float sum = 0.0;
            int pcfN = VFX_FILTER >= 2.5 ? 40 : (VFX_FILTER >= 1.5 ? 24 : (VFX_FILTER >= 0.5 ? 12 : 6));

            for (int i = 0; i < 40; i++)
            {
                if (i >= pcfN)
                {
                    break;
                }

                float fi = float(i);
                vec2 off = vec2(cos(fi * 2.39996 + ang), sin(fi * 2.39996 + ang))
                    * sqrt((fi + 0.5) / float(pcfN)) * radiusUv;

                sum += vfxTap(atlas, rect, uv + off, reference);
            }

            vis = sum / float(pcfN);
        }
    }
    else
    {
        /* CRISP by decree (the default). Three shapes, picked by the filter dial, and step 1 is
           deliberately the ORIGINAL code unchanged — the shipped look was signed off against it.

           Step 1: one 3x3 of bilinear compares at 1.2 texels, the grid ROTATED per pixel. An
           axis-aligned 3x3 is the worst case for the beat described above — its offsets are
           parallel to the texel grid at every pixel, so the pattern it leaves is perfectly coherent.

           Steps 2-3: a golden-angle disc of 24 / 48 compares over a kernel 30% NARROWER. Both
           halves matter. More taps at the same width would only be blurrier; a narrower width alone
           would bring back the aliasing those 1.2 texels were buying off. Denser AND tighter is
           what reads as a harder, cleaner edge — this is the dial for "the shadow is soft and I
           don't want it to be". A disc rather than a bigger grid because the grid's coherence is
           exactly what the rotation is fighting, and it gets worse the more rows it has.

           Step 0: the single centre compare, still bilinear. */
        if (VFX_FILTER >= 1.5)
        {
            int discN = VFX_FILTER >= 2.5 ? 48 : 24;
            float discR = radiusUv * 0.7;

            /* Classify on the step-1 grid first — its 1.2-texel footprint CONTAINS the disc's
               0.84, so a unanimous verdict there is the disc's verdict: all-lit skips the disc
               (open ground, most of any lit scene), all-blocked is exact black. The thresholds
               demand unanimity, so bilinear edge fractions still pay for the disc they need. */
            float grid = 0.0;

            for (int gy = 0; gy < 3; gy++)
            {
                for (int gx = 0; gx < 3; gx++)
                {
                    vec2 g = vec2(float(gx) - 1.0, float(gy) - 1.0);
                    vec2 off = vec2(g.x * ca - g.y * sa, g.x * sa + g.y * ca) * radiusUv;

                    grid += vfxTap(atlas, rect, uv + off, reference);
                }
            }

            if (grid > 8.999)
            {
                vis = 1.0;
            }
            else if (grid < 0.001)
            {
                return vec3(0.0);
            }
            else
            {
                float sum = 0.0;

                for (int i = 0; i < 48; i++)
                {
                    if (i >= discN)
                    {
                        break;
                    }

                    float fi = float(i);
                    vec2 off = vec2(cos(fi * 2.39996 + ang), sin(fi * 2.39996 + ang))
                        * sqrt((fi + 0.5) / float(discN)) * discR;

                    sum += vfxTap(atlas, rect, uv + off, reference);
                }

                vis = sum / float(discN);
            }
        }
        else if (VFX_FILTER >= 0.5)
        {
            float sum = 0.0;

            for (int gy = 0; gy < 3; gy++)
            {
                for (int gx = 0; gx < 3; gx++)
                {
                    vec2 g = vec2(float(gx) - 1.0, float(gy) - 1.0);
                    vec2 off = vec2(g.x * ca - g.y * sa, g.x * sa + g.y * ca) * radiusUv;

                    sum += vfxTap(atlas, rect, uv + off, reference);
                }
            }

            vis = sum / 9.0;
        }
        else
        {
            vis = vfxTap(atlas, rect, uv, reference);
        }
    }

    /* Feather the tint over a disc at least as wide as the shadow's, so the colour never snaps. */
    return vis * vfxTint(glassMap, rect, uv, recvDist, far, max(radiusUv, 6.0 / VFX_TILE));
}

/* ★TEMP diagnostic stash: vfxActorShadow keeps its float contract (deferred1.fsh C7623 — a vec3
   return narrowed into float math), so the gates ride a global the caller paints where a vec3 is
   legal. 1.0 = "no map spoke for this pixel". Written unconditionally — one vec3 store, nothing
   reads it without a debug flag: packs paint it under VFX_ACTOR_GATE_DEBUG (PackPatcher define),
   the fallback under the GateDebug uniform (both from -Dvfxlights.shadow.gatedebug). */
vec3 vfxActorGateDbg = vec3(1.0);

/* One actor-fitted map (Blender-look): a SECOND, narrow frustum hugging one actor near the lamp,
   rendered at millimetre texel density (up to four slots per lamp, one per actor). Within its
   cone it is simply the better map — the second skin layer's shell reads, and its shadow survives
   on the body. Outside the cone (or behind the light) it answers "lit", and the darker-of-all-maps
   combine keeps the main map's word.

   Two-part answer, IterationRP's recipe for thin shells:
   1. HARD visibility with a ~2mm lift — silhouettes and the vanilla ~1.5 cm outer layer. Any bigger
      and the user's ~2mm fake layers (BodyPart inflates) are eaten whole; any smaller and grazing
      angles acne.
   2. SOFT contact accumulation with NO bias — a spiral of taps converting "something hovers just
      above the receiver" into graduated darkening (their SSS trick: max(recv−stored−ε,0), then
      exp(−Σ·density)). Sub-bias 1-2mm layers show as soft contact shadow instead of vanishing.

   Depth16 at this window quantises at ~0.1-0.5mm, so the hard lift is acne-proof and the 1mm
   epsilon covers two quanta of self-depth noise. Returns a plain visibility (no glass tint —
   nothing renders glass into this map). */
float vfxActorShadow(sampler2D atlas,
    vec3 pos, vec3 geoN, vec3 lightPos,
    float tile, float across, float near, float far, float tanHalf, mat4 matrix, float slopeMax)
{
    vec3 toLight = lightPos - pos;
    float distLight = length(toLight);
    vec3 lv = toLight / max(distLight, 0.0001);
    float facing = clamp(dot(geoN, lv), 0.0, 1.0);

    /* Texel size in world units at the receiver, and the depth run one texel spans on a tilted
       surface: zero face-on, exploding toward grazing. The run is CAPPED: the fallback's
       depth-reconstructed normals are too noisy to steer it far (the main map's own rule), but
       a close lamp throws the body's sides oblique enough that a flat cap of 1 acnes — 2 both
       sides of it. Packs get 6 again, decoupled from the main map's 2.0: at this map's mm
       density the peter-pan a big cap buys is sub-visible, but its absence is NOT — the 2.0
       cap left every facing∈[0.16,0.48] flank underbiased, and elongated far-light shadows
       degraded into acne lines there. */
    float texWorld = 2.0 * distLight * tanHalf / VFX_TILE;
    float span = min(sqrt(max(1.0 - facing * facing, 0.0)) / max(facing, 0.1),
        slopeMax <= 1.0 ? 2.0 : max(slopeMax, 6.0));

    vec3 sp = pos + normalize(geoN + lv) * (0.0006 + texWorld * 0.3 * span);

    vec4 clip = matrix * vec4(sp, 1.0);

    if (clip.w <= 0.0)
    {
        return 1.0;
    }

    vec3 ndc = clip.xyz / clip.w;
    vec2 uv = ndc.xy * 0.5 + 0.5;

    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
    {
        return 1.0;
    }

    float reference = ndc.z * 0.5 + 0.5;
    vec4 rect = vfxTileRect(tile, across);

    /* The Chebyshev receiver rides the same texel-sized world-units lift the compare used to:
       with the moments' variance floor, an unbiased receiver sits mdiff-noise above its own
       mean and self-shadows. */
    float recvDist = vfxLinearDepth(reference, near, far);

    recvDist -= (0.0006 + texWorld * span);

    /* HARD, binary-free: Chebyshev over a 5x5 depth neighborhood — EVSM-on-the-fly
       (IRLite's MSM lesson). Every depth tap feeds a mean/variance pair instead of a
       lit/shadowed click, so pose motion, the TAA jitter walk and idle breathing SLIDE the
       answer continuously — a binary step at this density clicked per frame, which was the
       body shimmer. NO per-pixel rotation or hash jitter on the grid: dither left a STATIC
       speckle field on the body, and packs whose TAA mis-reprojects entities (IterationRP
       0.8.x) shimmered it per frame. The fixed grid's beat against the NEAREST atlas is
       smoothed at the source instead — every tap here (and the contact spiral below) reads
       through vfxStoredSmooth, a manual bilinear of four texels, so the beat dies in the
       filter rather than in noise. 5x5 over the SAME footprint as the old 3x3 (half-step
       offsets) — twice the tap density, not a wider blur. */
    /* Moment footprint adapts to the surface's own run AND to the blocker gap (the PCSS half of
       the main map's look): face-on in contact it stays the sharp 1.0 texels; on grazing flanks
       one shadow texel spans many screen pixels, and a fixed grid staircases into bands — the
       window widens with span; and where the occluder floats off the receiver the edge grows a
       soft gradient with the gap, like the main map's penumbra, instead of a hard staircase.
       The centre tap costs one read; the cap keeps flat contact crisp. Base 1.5 -> 1.0 and the
       gap growth 300 -> 100 per metre (2026-08-09, the "края не чёткие" report): the quality
       preset helped but never cured the softness because it lived in the FILTER, not the texel
       density — the 5x5 over 1.5 texels was a box blur, and +1 texel of window per 3.3 mm of
       gap inflated it before the real penumbra even started. IterationRP's own sun shadow
       (Sunlight_Shadow.glsl) reads crisp for the same reason inverted: BINARY step taps over a
       sub-texel kernel near contact, the window opening only with measured blocker distance. */
    float vfxCenterZ = vfxLinearDepth(vfxStoredSmooth(atlas, rect, uv), near, far);
    float vfxPenumbra = clamp((recvDist - vfxCenterZ) * 100.0, 0.0, 4.0);
    float radiusUv = (1.0 + span * 0.75 + vfxPenumbra) / VFX_TILE;
    float contactRadius = (2.0 + vfxPenumbra * 0.5) / VFX_TILE;
    float mean = 0.0;
    float m2 = 0.0;

    for (int gy = 0; gy < 5; gy++)
    {
        for (int gx = 0; gx < 5; gx++)
        {
            vec2 off = vec2(float(gx) - 2.0, float(gy) - 2.0) * (radiusUv * 0.5);
            float z = vfxLinearDepth(vfxStoredSmooth(atlas, rect, uv + off), near, far);

            mean += z;
            m2 += z * z;
        }
    }

    mean /= 25.0;
    m2 /= 25.0;

    /* Variance floor: sub-texel depth staircases read as zero-variance and would poke the
       receiver through (VSM light leak); (0.3mm)² swallows them, and the sharpen remap below
       hands the shell edge its read back. */
    float variance = max(m2 - mean * mean, 1e-7);
    float mdiff = max(recvDist - mean, 0.0);
    float vis = recvDist <= mean ? 1.0 : clamp(variance / (variance + mdiff * mdiff), 0.0, 1.0);

    /* Sharpen — a smooth CONTRAST CURVE, not the old linear cutoff: (vis−0.15)/0.85 left a long
       washed gradient (the "края не чёткие" complaint), and raising its floor to 0.2 was already
       measured as a hard knee that made the texel staircase read as zigzag stripes. smoothstep
       keeps the midpoint where the linear remap had it (0.5 stays 0.5) but compresses both tails
       — the visible 20-80% edge roughly halves — with zero-slope ends, so there is no knee for
       the staircase to catch on. */
    vis = smoothstep(0.2, 0.8, vis);

    /* SOFT contact accumulation: sixteen taps on a golden spiral (2-texel radius, tightened with
       the moments kernel — the halo was the second soft-edge contributor). Each tap weighs how
       CLOSE the nearest surface above the receiver is — a bump window peaking at ~1-2mm of gap
       (the thin shell on the body) and reaching zero past ~4mm. This is a CONTACT shadow, NOT a
       second shadow map: blockers blocks away from the receiver are the hard path's job — letting
       them in here painted wavy iso-contours ("полосы") and sharp clipped wedges around every
       silhouette on the walls and floor. The ramp from zero keeps the surface itself untouched. */
    float eps = 0.0008 + texWorld * 0.5 * span;
    float occ = 0.0;

    for (int i = 0; i < 16; i++)
    {
        float fi = float(i);
        vec2 off = vec2(cos(fi * 2.39996), sin(fi * 2.39996))
            * sqrt((fi + 0.5) / 16.0) * contactRadius;
        float storedLin = vfxLinearDepth(vfxStoredSmooth(atlas, rect, uv + off), near, far);
        float gap = recvDist - storedLin - eps;

        occ += clamp(gap * 1250.0, 0.0, 1.0) * clamp(1.0 - gap * 250.0, 0.0, 1.0);
    }

    /* occ is a SUM over the taps — the 16-tap spiral doubles it, so the weight halves: 0.045
       keeps the contact shadow's depth exactly where the 8-tap 0.09 left it. */
    float soft = exp(-occ * 0.045);

    /* Feather the cone's edge: outside it this map has no opinion, and a hard UV cutoff carves a
       visible elliptical boundary into the shadow (the "weird shadow" blob). Fade the combine in
       over the outer QUARTER — the 12% band was crossed by a fast-swinging limb in one or two
       frames, and the handoff between this map's mm detail and the main map's word popped
       per-frame (the gatedebug cyan flicker). The wider band halves the per-frame step of the
       handoff; the crispness lives in vis/soft and is untouched. */
    vec2 toEdge = min(uv, 1.0 - uv);
    float feather = smoothstep(0.0, 0.25, min(toEdge.x, toEdge.y));

    /* And stay silent on surfaces edge-on to the light: there the depth run outgrows any sane
       bias. The shell shadows this map exists for live on light-FACING surfaces. */
    float faceW = smoothstep(0.08, 0.3, facing);

    /* The second layer works up to the lamp's RANGE, like the main map (user call: the old reach
       window died ~1.6 blocks past the actor — "отодвинул свет на 2 блока, тени пропали"). The
       far plane already carries the range; this fade only veils its edge. The veil now starts
       earlier (0.70 of far instead of 0.85) but still closes exactly AT far — receivers past the
       plane read as darkness (the 2026-07-31 wall rectangle), so the handoff must complete before
       it; the gentler slope keeps the 2 cm distQ steps of the snapped fit window from flipping
       the band's pixels frame to frame. */
    float reach = 1.0 - smoothstep(far * 0.7, far, distLight);

    /* ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug): stash which GATE holds this pixel —
       R = faceW (the edge-on-to-light gate), G = feather (cone edge), B = reach. Painted by
       vfxlights_run (packs) or light_composite (fallback): a channel that PULSES on a rotating
       limb is the gate flipping the layer on and off — the fast-animation flicker hunt. */
    vfxActorGateDbg = vec3(faceW, feather, reach);

    return mix(1.0, min(vis, soft), feather * faceW * reach);
}

/* How far the light travelled INSIDE the occluder before reaching this fragment — the shadow map
   already knows: receiver depth minus stored blocker depth. Returns -1 when the lamp has no map. */
float vfxThickness(sampler2D atlas,
    vec3 pos, vec3 lightPos, float type, bool cube, float firstTile, float across,
    float near, float far, mat4 shadowMatrix)
{
    if (firstTile < 0.0)
    {
        return -1.0;
    }

    vec4 rect;
    vec2 uv;
    float recvDist;

    if (cube)
    {
        float axisDepth;
        float face = vfxCubeFace(pos - lightPos, uv, axisDepth);

        rect = vfxTileRect(firstTile + face, across);
        recvDist = axisDepth;
    }
    else
    {
        vec4 clip = shadowMatrix * vec4(pos, 1.0);

        if (clip.w <= 0.0)
        {
            return -1.0;
        }

        vec3 ndc = clip.xyz / clip.w;

        uv = ndc.xy * 0.5 + 0.5;

        if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
        {
            return -1.0;
        }

        recvDist = vfxLinearDepth(ndc.z * 0.5 + 0.5, near, far);
        rect = vfxTileRect(firstTile, across);
    }

    float stored = vfxStored(atlas, rect, uv);

    return max(recvDist - vfxLinearDepth(stored, near, far) - 0.1, 0.0);
}

/* ------------------------------------------------------------------------------------------------
 * Area emitters — analytic irradiance
 * ------------------------------------------------------------------------------------------------ */

/*
 * Exact diffuse irradiance from a quad source — the polygon form-factor integral (the same
 * mathematics LTC reduces to for the diffuse lobe): clip the quad against the receiver's horizon,
 * then sum the edge integrals. What it buys over any point approximation is everything an area
 * light is FOR — no singularity up close, a wide source wrapping around surfaces, the emitter's
 * actual shape present in the falloff. Returns the form factor, 1 = the source fills the hemisphere.
 *
 * Written with ternary chains, not array initializers — this text must compile on #version 120.
 */
float vfxQuad(vec3 p, vec3 n, vec3 c0, vec3 c1, vec3 c2, vec3 c3)
{
    vec3 q0 = c0 - p; vec3 q1 = c1 - p; vec3 q2 = c2 - p; vec3 q3 = c3 - p;
    vec3 poly[5];
    int cnt = 0;

    /* Clip against the horizon plane dot(v, n) = 0 — a quad crossing it becomes up to a pentagon. */
    for (int i = 0; i < 4; i++)
    {
        vec3 a = i == 0 ? q0 : (i == 1 ? q1 : (i == 2 ? q2 : q3));
        vec3 b = i == 0 ? q1 : (i == 1 ? q2 : (i == 2 ? q3 : q0));
        float da = dot(a, n);
        float db = dot(b, n);

        if (da >= 0.0 && cnt < 5) { poly[cnt] = a; cnt++; }
        if (da * db < 0.0 && cnt < 5) { poly[cnt] = mix(a, b, da / (da - db)); cnt++; }
    }

    if (cnt < 3)
    {
        return 0.0;
    }

    float acc = 0.0;

    for (int i = 0; i < cnt; i++)
    {
        vec3 a = normalize(poly[i]);
        vec3 b = normalize(poly[(i + 1) % cnt]);
        float ct = clamp(dot(a, b), -0.9999, 0.9999);
        vec3 cr = cross(a, b);
        float cl = length(cr);

        if (cl > 0.00001)
        {
            acc += acos(ct) * dot(cr / cl, n);
        }
    }

    /* SIGNED, not abs: the winding carries which side of the emitter the receiver is on. abs()
       folded the back side onto the front and, worse, printed a V-notch to zero exactly on the
       emitter's plane — a thin dark line across every surface the infinite plane crossed. The
       caller takes max(.,0) for a one-sided panel (smoothly to zero at the plane, no notch) or
       abs for two-sided. */
    return acc * 0.15915494;
}

/* Irradiance from a glowing sphere: solid-angle falloff with a soft horizon, so a fireball sitting
   on the ground still wraps light around it instead of cutting off at the terminator. */
float vfxSphere(vec3 toC, float radius, vec3 n)
{
    float d = max(length(toC), radius + 0.01);
    float s2 = clamp(radius * radius / (d * d), 0.0, 0.9999);
    float s = sqrt(s2);
    float c = dot(n, toC / d);

    return s2 * clamp((c + s) / (1.0 + s), 0.0, 1.0);
}

/* ------------------------------------------------------------------------------------------------
 * Dispersion, water, photometrics
 * ------------------------------------------------------------------------------------------------ */

/* The caustic FIELD: a network of thin bright fold lines with soft washes between them — the
   anatomy of real caustics (light concentrated by the folds of glass or water), not the amorphous
   blobs of a value noise. The fold lines are the iso-contours of two drifting warped waves: thin,
   curved, branching, and they merge and split as the phase drifts — caustics breathe like that. */
float vfxCausticField(vec2 uv, float ph)
{
    /* The field is EXACTLY periodic in ph with 200π/3 s: every drift multiplier below completes a
       whole number of 2π cycles over it (0.21→14π, 0.15→10π, 0.18→12π, 0.12→8π). Reducing here
       keeps the pattern continuous across the Java clock's wrap and the sin() arguments deep
       inside float32's precise range, whatever phase a caller hands in (water passes ph × 2.6). */
    ph = mod(ph, 209.43951);

    float a = sin(uv.x * 1.7 + sin(uv.y * 1.3 + ph * 0.21) * 1.8 + ph * 0.15);
    float b = sin(uv.y * 2.1 + sin(uv.x * 1.1 - ph * 0.18) * 1.7 - ph * 0.12);
    /* Ridges: tight and BRIGHT — a caustic is a concentration of light, it overshoots the pool. */
    float folds = pow(max(1.0 - abs(a + b) * 0.55, 0.0), 6.0);
    /* Washes: the broad soft spill between the folds. */
    float wash = 0.5 + 0.5 * sin(a * 1.9 + b * 1.3);

    return folds * 1.6 + wash * 0.22;
}

/* Saturated spectral colour for wavelength t: 0 = red, 1 = violet, the prism's order. A hue wheel,
   not a cos palette — the cos version muddies every colour between its three peaks. White must
   EMERGE from overlap, so no lift. */
vec3 vfxSpectrum(float t)
{
    float h = t * 0.78;

    return clamp(abs(fract(h + vec3(0.0, 0.6667, 0.3333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

/* The physically-shaped trick: one caustic intensity field, sampled once PER WAVELENGTH with a
   refraction-dependent shift — red bent one way, violet the other, exactly what a prism does to a
   caustic. Where the shifted fields agree they sum to a white core; where the field has an edge the
   wavelengths separate into an ORDERED rainbow fringe. */
vec3 vfxCaustic(vec3 fragPos, vec3 lightPos, vec3 dir, vec3 up, float scale, float phase)
{
    vec3 nd = normalize(fragPos - lightPos);
    vec3 f = normalize(dir);
    vec3 u = up - f * dot(up, f);

    u = length(u) < 0.0001 ? (abs(f.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)) : normalize(u);

    vec3 r = cross(f, u);
    /* Anisotropic angular coords: tighter across, stretched along — the flame-like streaks of the refs. */
    vec2 cuv = vec2(dot(nd, r) * 7.0, dot(nd, u) * 2.6) * scale;
    vec3 sum = vec3(0.0);

    for (int i = 0; i < 8; i++)
    {
        float t = float(i) / 7.0;
        /* The spectral shift, WIDE: the references show whole rainbows fanned across a patch, not a
           thin fringe on its edge. Each fold line fans into a parallel rainbow ribbon. */
        vec2 uvw = cuv + vec2(0.9, 0.32) * ((t - 0.5) * 1.1);

        sum += vfxSpectrum(t) * vfxCausticField(uvw, phase);
    }

    sum *= 0.37;

    /* Pastel, the reference way: every hue present, each lifted toward white. Mixing toward the
       BRIGHTEST channel keeps the hue readable while milking the colour — white cores untouched. */
    float peak = max(sum.r, max(sum.g, sum.b));

    return mix(sum, vec3(peak), 0.22);
}

/* Water's answer to the light past its surface (wd = beam's water-entry distance, -1 = never,
   -2 = the reflected share off the surface): Beer-Lambert absorption — red first, teal then blue
   with submerged path — and the pool floor's dancing caustic net, fading with depth. */
vec3 vfxWater(vec3 fragPos, float dist, vec3 lightPos, vec3 dir, vec3 up, float wd, float ph, float waterY)
{
    if (wd < -2.5)
    {
        /* ★TEMP bisect sentinel (-Dvfxlights.water.off passes wd = -3 from the fallback): the whole
           water treatment off — absorb, net, all of it. */
        return vec3(1.0);
    }

    if (wd < -1.5)
    {
        /* The REFLECTED share (a mirrored virtual light): the dancing net without absorption —
           this light bounced OFF the surface, it never travelled through the water. */
        vec3 nd0 = fragPos - lightPos;
        vec3 f0 = normalize(dir);
        vec3 u0 = up - f0 * dot(up, f0);

        u0 = length(u0) < 0.0001 ? (abs(f0.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)) : normalize(u0);

        /* Planar projection, NOT angular: the angular map has a pole straight under the source —
           a flat disc of frozen net there, and any lamp motion swings the pole's sampling wildly
           (the "ровный круг под источником, мерцает при драге" report). World-planar arc length
           has no singularity and keeps the cells wave-sized at every distance. */
        vec2 cuv0 = vec2(dot(nd0, cross(f0, u0)), dot(nd0, u0)) * 1.1;
        float net0 = vfxCausticField(cuv0, ph * 2.6);
        float dist0 = length(nd0);

        /* Near-field fade (see the submerged branch). */
        float near0 = smoothstep(0.4, 2.2, dist0);

        return vec3(clamp(1.0 + 0.9 * exp(-dist0 * 0.04) * near0 * (net0 - 0.55), 0.0, 1.25));
    }

    if (wd < 0.0 || dist <= wd)
    {
        return vec3(1.0);
    }

    /* ONLY below the surface: waterDist alone is radial — a dry ceiling farther from the lamp than
       the pool got netted (measured: white scratches over the stained glass scene). */
    if (fragPos.y > waterY + 0.1)
    {
        return vec3(1.0);
    }

    /* Absorption keys off the VERTICAL depth below the surface, not the slant path (dist − wd):
       the slant version put the least-absorbed (brightest) point straight under the lamp and
       fell off radially with the slant — a perfect disc around the sub-lamp point, the "ровный
       круг" report. A flat pool floor is one depth, so the floor now reads uniform, as water
       should. The flat clarity factor (0.85, never-perfectly-clear water) rides the same entry
       fade as the net below, or its step would redraw the circle at the boundary sphere. */
    float depth = max(waterY - fragPos.y, 0.0);
    float entryA = smoothstep(0.0, 0.5, depth);
    vec3 absorb = exp(-depth * vec3(0.30, 0.12, 0.07)) * mix(1.0, 0.85, entryA);
    vec3 rel = fragPos - lightPos;
    vec3 f = normalize(dir);
    vec3 u = up - f * dot(up, f);

    u = length(u) < 0.0001 ? (abs(f.y) > 0.9 ? vec3(1.0, 0.0, 0.0) : vec3(0.0, 1.0, 0.0)) : normalize(u);

    vec3 r = cross(f, u);
    /* World-planar projection (see the reflected branch above): no pole disc, no drag flicker. */
    vec2 cuv = vec2(dot(rel, r), dot(rel, u)) * 1.1;
    float net = vfxCausticField(cuv, ph * 2.6);

    /* ★The net fades IN over the first half-block of submersion. The dist == wd sphere is where
       the treatment starts (dist <= wd returns 1.0 above), and a full-strength net THERE drew a
       perfect circle around the source on the water surface, its edge pumping as wd was
       re-estimated — the "ровный круг, мерцает" report, verified on the R.shade debug view (the
       sphere was literally visible). depth 0 at the ring = no net = no boundary. */
    float entry = entryA;
    float strength = 0.85 * exp(-depth * 0.06) * entry;

    /* Soft cap at +25%: the ridge overshoot was deliberate energy, but under the source the
       product (intensity x atten x ridge) ran deep past the AgX knee and the shoulder flattened
       the whole hotspot into a uniform disc. Capped, the ridges survive INSIDE the hotspot.
       ★Near-field fade: within ~2 blocks of the source the net's cells drop under a screen texel
       and boil frame to frame under any motion. */
    float near = smoothstep(0.4, 2.2, dist);

    return absorb * clamp(1.0 + strength * near * (net - 0.55), 0.0, 1.25);
}

/* Photometric profile: how a REAL fixture distributes intensity over angle — the shape IES files
   describe, offered as procedural archetypes. ca = cosine of the angle to the lamp's axis. */
float vfxIes(float profile, float ca)
{
    if (profile < 0.5)
    {
        return 1.0;
    }

    float theta = acos(clamp(ca, -1.0, 1.0));

    if (profile < 1.5)
    {
        return pow(clamp(ca, 0.0, 1.0), 2.5);                     /* downlight: hot centre */
    }

    if (profile < 2.5)
    {
        float s = sin(min(theta * 2.0, 3.14159));                 /* batwing: twin lobes */

        return clamp(s * s * 1.4, 0.0, 1.4);
    }

    return exp(-pow((theta - 0.5) / 0.22, 2.0)) * 1.3;            /* ring: a donut of light */
}

/* ------------------------------------------------------------------------------------------------
 * BRDF stack
 * ------------------------------------------------------------------------------------------------ */

/* Analytic split-sum environment BRDF (Karis mobile fit): (scale, bias) of the pre-integrated GGX
   directional albedo, for energy conservation without shipping a LUT. */
vec2 vfxEnvBRDF(float nov, float rough)
{
    vec4 c0 = vec4(-1.0, -0.0275, -0.572, 0.022);
    vec4 c1 = vec4(1.0, 0.0425, 1.04, -0.04);
    vec4 r = rough * c0 + c1;
    float a004 = min(r.x * r.x, exp2(-9.28 * nov)) * r.x + r.y;

    return vec2(-1.04, 1.04) * a004 + r.zw;
}

/* GGX microfacet specular with Schlick Fresnel — the physically-shaped highlight. The alpha is
   WIDENED by the source's angular size: a big soft source cannot give a pin-prick glint, which is
   exactly the difference between a bare bulb and a softbox in a subject's eye. Kulla-Conty energy
   compensation puts back the light single-scatter GGX loses to Smith shadowing at grazing angles —
   the very angles a rim lives at, so without it a physical rim comes out anaemic. */
float vfxGGX(vec3 n, vec3 viewDir, vec3 L, float dist, float srcRadius, float smoothness, float specRim)
{
    float rough = max(1.0 - clamp(smoothness, 0.0, 1.0), 0.07);
    float a = rough * rough;

    a = clamp(a + srcRadius / max(dist * 2.0, 0.5), a, 1.0);

    float a2 = a * a;
    vec3 h = normalize(L + viewDir);
    float noh = clamp(dot(n, h), 0.0, 1.0);
    float nol = clamp(dot(n, L), 0.0, 1.0);
    float nov = clamp(dot(n, viewDir), 0.0, 1.0);
    float voh = clamp(dot(viewDir, h), 0.0, 1.0);
    float den = noh * noh * (a2 - 1.0) + 1.0;
    float d = a2 / max(3.14159 * den * den, 0.0001);
    float k = a * 0.5;
    float vis = 0.25 / max((nol * (1.0 - k) + k) * (nov * (1.0 - k) + k), 0.001);
    /* specRim scales the grazing Fresnel addition alone: 1 = physics, 0 = a flat 4% reflector
       at grazing (the physical rim dies), 2 = exaggerated — the highlight's frontal part is
       untouched either way. */
    float fres = 0.04 + 0.96 * pow(1.0 - voh, 5.0) * specRim;
    /* Floor at 0.05, not near-zero: the analytic DFG bias dips tiny at grazing and 1/bias would
       explode the multiplier to ~x40 — the blown-white faces on aggressive tonemaps. Capped it is
       a modest x1.8 at most. */
    float energy = 1.0 + 0.04 * (1.0 / max(vfxEnvBRDF(nov, rough).y, 0.05) - 1.0);

    return d * vis * fres * nol * energy;
}

/* Contour light, not a tint: the Fresnel band (silhouette-only) gated THREE ways — the light must
   be BEHIND the subject relative to the camera (a frontal light has no rim), the rim lives at the
   terminator (a fully lit surface takes none — that is what stopped it reading as a wash over
   walls and floors), and the wrap cuts only the true back side. The caller multiplies it into the
   delivered radiance and colour-shadow, so it is occluded and coloured like real light. */
float vfxRim(vec3 n, vec3 viewDir, vec3 L, float smoothness, float rimKnob, float rimWidth)
{
    float nov = clamp(dot(n, viewDir), 0.0, 1.0);
    float ndl = dot(n, L);

    /* behind: 0 for frontal light, 1 for backlight, half for a 90° kicker (its far edge rims). */
    float behind = smoothstep(-0.3, 0.3, -dot(viewDir, L));
    float wrap = smoothstep(-0.3, -0.05, ndl);
    float term = 1.0 - smoothstep(0.1, 0.5, ndl);

    /* A crisp strip rather than a soft falloff: the band is stepped, and rimWidth sets how much
       of the silhouette it covers (0 = hairline, 1 = a wide strip). */
    float band = pow(1.0 - nov, mix(2.0, 6.0, clamp(smoothness, 0.0, 1.0)));
    float lo = 1.0 - rimWidth * 0.85;

    band = smoothstep(lo, min(lo + 0.12, 1.0), band);

    return rimKnob * band * behind * wrap * term;
}

/* The COOL shadow tone of cel shading: anime shades the dark side by jumping COLDER on the colour
   wheel, not just darker. Map the lit colour's luminance onto a blue ramp, blend toward it by the
   shift, then dim by the level. shift 0 = same hue dimmed, 1 = full cold; level scales brightness. */
vec3 vfxCoolShadow(vec3 lit, float shift, float level)
{
    float lum = dot(lit, vec3(0.3, 0.59, 0.11));
    vec3 cold = vec3(lum * 0.35, lum * 0.5, lum * 1.0 + 0.12);

    return mix(lit, cold, shift) * level;
}

/* Cosine of the ELLIPTICAL cone angle for a spot: perpendicular components scaled by the cone's
   right/up stretch, so the cone widens along the light's own axes. The caller shapes the falloff —
   surfaces ease out hard, air smoothsteps softer. */
float vfxConeCos(vec3 dirFromLight, vec3 forward, vec3 upHint, vec2 coneScale)
{
    vec3 f = normalize(forward);
    vec3 r = normalize(cross(f, upHint));
    vec3 u = cross(r, f);
    vec2 xy = vec2(dot(dirFromLight, r), dot(dirFromLight, u));

    xy /= vec2(max(coneScale.x, 0.01), max(coneScale.y, 0.01));

    return cos(atan(length(xy), dot(dirFromLight, f)));
}

/* NaN/Inf insurance, written without isnan()/isinf() so it also compiles on #version 120 packs:
   x != x is true only for NaN; abs > 1e30 catches Inf and any runaway value. A single poisoned
   fragment spreads through a pack's temporal accumulation and flashes whole regions black. */
vec3 vfxGuard(vec3 v)
{
    if (any(notEqual(v, v)) || any(greaterThan(abs(v), vec3(1e30))))
    {
        return vec3(0.0);
    }

    return v;
}

/* Contour blend modes. The ink is always ADDED to the frame, so a mode is applied as its exact
   DELTA against the pixel's current colour: out = dest + (blend(dest, ink) - dest). The ink
   arrives premultiplied (hue x strength); the modes need them split — overlay with a muted SOURCE
   darkens a dark destination (the "overlay делает контур чёрным" report), so the blend runs
   against the full-strength hue and the strength mixes the result in. The mode is computed
   against the LDR base d0 = clamp(dest, 0, 1), but the delta subtracts the REAL dest: past 1.0
   (HDR working colour in packs) clamping dest made overlay(dest≥1, ·) ≡ 1 and the delta died to
   a constant 0 — the "overlay делает inner почти невидимым, интенсивность не работает" report.
   strength is NOT capped at 1 either (it used to — the dial saturated before the ink cap did):
   mixing past 1.0 EXTRAPOLATES the blend, so the dial reads linearly into the high range, with
   the extrapolated result floored at 0 (a fully dark line is a legitimate answer, negative is
   not) and mixIn bounded at 8 as runaway insurance. LDR behaviour with strength ≤ 1 is
   bit-identical to the old code. Mode 0 (add) needs no delta — the caller just sums. */
vec3 vfxInkBlendDelta(vec3 dest, vec3 ink, float mode)
{
    vec3 d0 = clamp(dest, vec3(0.0), vec3(1.0));

    float strength = max(ink.r, max(ink.g, ink.b));

    if (strength <= 0.0)
    {
        return vec3(0.0);
    }

    float mixIn = min(strength, 8.0);
    vec3 hue = ink / max(strength, 0.0001);

    if (mode < 1.5)
    {
        /* screen against the full hue, mixed in by the line's strength */
        vec3 s = vec3(1.0) - (vec3(1.0) - d0) * (vec3(1.0) - hue);

        return (max(mix(d0, s, mixIn), vec3(0.0)) - dest);
    }

    /* overlay against the full hue, mixed in by the strength */
    vec3 lo = 2.0 * d0 * hue;
    vec3 hi = vec3(1.0) - 2.0 * (vec3(1.0) - d0) * (vec3(1.0) - hue);

    return max(mix(d0, mix(lo, hi, step(0.5, d0)), mixIn), vec3(0.0)) - dest;
}

/* AgX-flavoured highlight shoulder, for the FALLBACK only (patched packs run their own tonemap —
   that is the explicit decision recorded in the IterationRP patch). Two film moves on the excess
   above luma 1: compress it (a soft shoulder instead of a clip) and wash the hue toward white with
   it — a hot core reads as BRIGHT, not as a saturated blob glued to the ceiling. Below luma 1 the
   colour is untouched, so midtones keep the lamp's hue. Applied per lamp contribution, before the
   additive blend — never on the accumulated frame. */
vec3 vfxAgxShoulder(vec3 c)
{
    float luma = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float over = max(luma - 1.0, 0.0);
    float t = over / (1.0 + over);
    vec3 shoulder = c / (1.0 + over * 0.4);
    vec3 white = vec3(dot(shoulder, vec3(0.2126, 0.7152, 0.0722)));

    return mix(shoulder, white, t * 0.65);
}

/* ------------------------------------------------------------------------------------------------
 * Orchestration — one lamp against one surface, the whole delivery chain
 * ------------------------------------------------------------------------------------------------ */

/* Everything the model needs to know about a lamp, gathered by the backend from ITS storage
   (uniforms / SSBO slots). Positions are in the backend's shading space (world / camera-relative);
   the model only ever takes differences, except waterY which must share fragPos' space. */
struct VfxLightDesc
{
    vec3 pos;        float type;      /* 0 point, 1 spot, 2 area — ambient never reaches the model */
    vec3 color;      float intensity;
    vec3 dir;        float range;
    vec3 up;         float srcRadius;
    float cosOuter;  float cosInner;
    vec2 shapeWH;                     /* spot: elliptical cone scale X/Y; area: width/height */
    float shapeId;   bool twoSided;   /* area: 0 rect, 1 disc, 2 tube, 3 sphere */
    float spread;    float thickness; /* area: emission focus; tube girth */
    vec4 barn;       float barnSoft;
    float firstTile; bool cube;       /* shadow: first atlas tile (-1 = none), six-face flag */
    float across;    float near;      /* atlas tiles per row; projection near plane */
    mat4 shadowMatrix;                /* narrow spot only */
    float softDial;                   /* shadow softness dial (0 = crisp decree, 1 = physical) */
    float disp;      float dispScale; float dispPhase;
    float waterDist; float waterY;
    float sss;       float iesProfile;
    float rimKnob;   float rimWidth;
    float falloffMode;                /* point/spot: 0 = range fill, 1 = physical 1/d² windowed */
    float specRim;                    /* physical rim: grazing Fresnel scale in vfxGGX (1 = physics) */
    /* actor-fitted maps, one narrow frustum per slot (-1 = no map in the slot); scalar
       quadruplets, not arrays — GLSL 120 struct-array support is a portability risk in spliced
       packs */
    float actorTile0; float actorNear0; float actorFar0;
    float actorTan0;                  /* frustum half-angle tangent — texel size for the bias */
    mat4 actorMatrix0;
    float actorTile1; float actorNear1; float actorFar1;
    float actorTan1;
    mat4 actorMatrix1;
    float actorTile2; float actorNear2; float actorFar2;
    float actorTan2;
    mat4 actorMatrix2;
    float actorTile3; float actorNear3; float actorFar3;
    float actorTan3;
    mat4 actorMatrix3;
    vec4 cel;                         /* x toggle, y softness, z cool shift, w tone level */
};

/* The receiving surface plus the backend's calibration. */
struct VfxSurface
{
    vec3 pos;        /* shading position */
    vec3 shadowPos;  /* position for shadow lookups — bob-consistent with the baked maps */
    vec3 n;          /* shading normal (normal-mapped where available) */
    vec3 geoN;       /* flat geometric normal — the shadow nudge must not jitter per texel */
    vec3 rimN;       /* the normal the rim band reads (the fallback smooths it wider) */
    vec3 actN;       /* the normal the ACTOR-fitted shadow reads (fallback: 3px stencil, axis-snapped) */
    vec3 viewDir;    /* unit, surface -> eye */
    float smoothness;
    float specGain;  /* pack 0.8 (real materials), fallback 0.6 (fixed gloss + albedo tint) */
    float slopeMax;  /* shadow slope-bias ceiling: pack 6.0, fallback 1.0 */
};

struct VfxShadeOut
{
    vec3 diffuse;    /* delivered light: diffuse + cel + caustics + sss. Pre-gain, pre-albedo. */
    vec3 spec;       /* highlight + rim. Pre-gain, not albedo-modulated the way diffuse is. */
    vec3 shade;      /* shadow x water colour (probe, ink) */
    vec3 inkBase;    /* contour ink before the edge mask: shade x facing/reach gates (vec3 — ink
                        through stained glass is tinted like everything else) */
    vec3 inkInner;   /* interior contour before the edge mask: shade x reach, NO facing gates —
                        a crease line is drawn whichever way its surface turns */
    float factor;    /* geometric reach (probe B / area ink gate) */
    float ndl;       /* Lambert toward the light (probe R) */
    float atten;     /* attenuation x cone (probe G) */
    float celReach;  /* facing-independent reach (pack probe B) */
};

/*
 * One lamp's full delivery to one surface. The backends differ ONLY in where the data comes from
 * and what happens after: the pack accumulates over its SSBO loop, the fallback runs one lamp per
 * fullscreen pass and composes against the frame.
 *
 * inkOnly skips everything that DELIVERS light (water, diffuse, cel, caustics, sss, spec, rim) and
 * keeps only what the contour needs — the entry for a program that must add the outline on top of
 * light another program already shaded (Complementary's deferred1). The shadow tap still runs: a
 * contour shows only where the lamp's light actually lands.
 *
 * celOn is the caller's "this pixel is a character AND the lamp wants cel" — mask semantics differ
 * per backend, so the decision arrives ready-made.
 */
void vfxShadeLight(sampler2D atlas, sampler2D glassMap,
    VfxLightDesc Ld, VfxSurface S, bool inkOnly, bool celOn, out VfxShadeOut R)
{
    R.diffuse = vec3(0.0);
    R.spec = vec3(0.0);
    R.shade = vec3(1.0);
    R.inkBase = vec3(0.0);
    R.inkInner = vec3(0.0);
    R.factor = 0.0;
    R.ndl = 0.0;
    R.atten = 0.0;
    R.celReach = 0.0;

    vec3 toLight = Ld.pos - S.pos;
    float dist = length(toLight);

    if (dist > Ld.range)
    {
        return;
    }

    vec3 L = toLight / max(dist, 0.0001);
    float far = max(Ld.range, Ld.near + 0.15);
    float factor;
    float spill = 0.0;
    /* The specular's own frame: direction to the REPRESENTATIVE POINT of the emitter (its centre
       for point/spot, the point of its surface nearest the reflection ray for area shapes — that
       is what stretches a tube's glint into a stripe), plus its own attenuation. */
    vec3 specL = L;
    float specDist = dist;
    float specAtten = 0.0;
    float specSrc = Ld.srcRadius;
    /* Front-side mask for area specular AND rim: a one-sided panel reflects/rims nothing behind
       its plane. The diffuse form factor knows this from its winding, but specAtten does not, so
       without this the rim printed onto surfaces BEHIND the light. 1.0 for spot/point/two-sided. */
    float front = 1.0;
    /* Cel: the terminator SHAPE (N.L toward the light) and the light's facing-independent REACH,
       so the two-tone split can put cool where the surface faces away yet the light still lands. */
    float celShape = 0.0;
    float celReach = 0.0;

    if (Ld.type > 1.5)
    {
        /* AREA: analytic irradiance from the emitter's actual geometry. */
        vec3 eN = normalize(Ld.dir);
        vec3 eU = Ld.up - eN * dot(Ld.up, eN);

        eU = length(eU) < 0.0001 ? vec3(0.0, 0.0, 1.0) : normalize(eU);

        vec3 eR = cross(eN, eU);
        float w = Ld.shapeWH.x;
        float h = Ld.shapeWH.y;

        if (Ld.shapeId > 2.5)
        {
            /* Sphere: a fireball, a magic orb. */
            factor = vfxSphere(toLight, w * 0.5, S.n);
        }
        else if (Ld.shapeId > 1.5)
        {
            /* Tube: light from the closest point of the segment, sized by its thickness. The
               length contributes a mild widening — an honest first take, not the full line
               integral. */
            float along = clamp(dot(-toLight, eR), -w * 0.5, w * 0.5);
            vec3 closest = Ld.pos + eR * along;

            factor = vfxSphere(closest - S.pos, max(Ld.thickness * 0.5, 0.02), S.n);
            factor *= 1.0 + 0.5 * clamp(w / max(dist, 0.5), 0.0, 2.0);
        }
        else
        {
            /* Rect and disc: the quad integral; a disc is a square of equal area. */
            float sc = Ld.shapeId > 0.5 ? 0.886 : 1.0;
            vec3 ex = eR * (w * 0.5 * sc);
            vec3 ey = eU * ((Ld.shapeId > 0.5 ? w : h) * 0.5 * sc);

            factor = vfxQuad(S.pos, S.n, Ld.pos - ex - ey, Ld.pos + ex - ey,
                Ld.pos + ex + ey, Ld.pos - ex + ey);

            /* One-sidedness straight from the winding. The front/back edge is razor-thin near the
               lamp (steep form-factor gradient) and broad far away, so ANY fixed-width knee aliased
               into a jagged band near the source. fwidth() sets the feather to the screen-space
               slope — a clean ~1px edge at every distance, physical sharpness kept. */
            float aa = max(fwidth(factor), 1e-5);

            factor = Ld.twoSided ? abs(factor) : factor * smoothstep(-aa, aa, factor);

            if (!Ld.twoSided)
            {
                front = smoothstep(-0.05, 0.1, dot(S.pos - Ld.pos, eN));
            }

            vec3 dirFrag = normalize(S.pos - Ld.pos);
            float axial = abs(dot(dirFrag, eN));
            float spread = clamp(Ld.spread, 0.02, 1.0);

            /* Spread narrows the emission cone the way a honeycomb grid does. */
            if (spread < 0.999)
            {
                factor *= pow(axial, 1.0 / spread - 1.0);
            }

            float lz = max(axial, 0.05);
            float tX = dot(dirFrag, eR) / lz;
            float tY = dot(dirFrag, eU) / lz;
            float bs = Ld.barnSoft * 0.5 + 0.03;

            /* Barn doors, PER FLAP and only when the flap is actually closed. An OPEN door mapped
               to threshold 3 still cut the beam at tan ~3 (~71 deg off axis) — a hard jagged pool
               edge on default settings. Gating each cut by how closed its own door is means open
               doors do nothing; barnFade kills the tan blow-up right at the emitter's plane. */
            float barnFade = smoothstep(0.05, 0.3, axial);

            factor *= mix(1.0, smoothstep((1.0 - Ld.barn.w) * 3.0 + bs, (1.0 - Ld.barn.w) * 3.0 - bs, tX),
                smoothstep(0.02, 0.15, Ld.barn.w) * barnFade);
            factor *= mix(1.0, smoothstep((1.0 - Ld.barn.z) * 3.0 + bs, (1.0 - Ld.barn.z) * 3.0 - bs, -tX),
                smoothstep(0.02, 0.15, Ld.barn.z) * barnFade);
            factor *= mix(1.0, smoothstep((1.0 - Ld.barn.x) * 3.0 + bs, (1.0 - Ld.barn.x) * 3.0 - bs, tY),
                smoothstep(0.02, 0.15, Ld.barn.x) * barnFade);
            factor *= mix(1.0, smoothstep((1.0 - Ld.barn.y) * 3.0 + bs, (1.0 - Ld.barn.y) * 3.0 - bs, -tY),
                smoothstep(0.02, 0.15, Ld.barn.y) * barnFade);
        }

        /* The integral IS the physical falloff; the range only fades the tail out for culling. */
        factor *= 1.0 - smoothstep(Ld.range * 0.85, Ld.range, dist);
        /* Area caustics stay inside the pool — the emitter has no cone edge to spill past. */
        spill = factor;
        /* Every area shape, AFTER the fade: the pack used to leave sphere/tube at zero (a cel'd
           orb lit nothing) and gate the rect on the pre-fade factor (cel ignored the range edge). */
        celShape = max(dot(S.n, L), 0.0);
        celReach = factor;

        /* Representative point: where on the emitter the reflection ray actually looks — a tube's
           glint is a STRIPE, a panel's is a window. */
        vec3 rr = reflect(-S.viewDir, S.n);

        if (Ld.shapeId > 2.5)
        {
            /* Sphere: pull the centre toward the reflection ray, clamped to the surface. */
            vec3 centreToRay = dot(toLight, rr) * rr - toLight;

            specL = toLight + centreToRay * clamp((w * 0.5) / max(length(centreToRay), 0.001), 0.0, 1.0);
            specSrc = 0.08;
        }
        else if (Ld.shapeId > 1.5)
        {
            /* Tube: nearest point of the segment to the reflection ray (UE-style MRP). */
            vec3 a0 = toLight - eR * (w * 0.5);
            vec3 ad = eR * w;
            float tt = (dot(rr, a0) * dot(rr, ad) - dot(a0, ad))
                / max(dot(ad, ad) - dot(rr, ad) * dot(rr, ad), 0.0001);

            specL = a0 + ad * clamp(tt, 0.0, 1.0);
            specSrc = max(Ld.thickness * 0.5, 0.05);
        }
        else
        {
            /* Rect and disc, representative point CONTINUOUS. Intersecting the reflection ray with
               the emitter plane made the point JUMP from a panel edge to the centre as the ray
               swept through parallel (denom -> 0), and the specular jump printed a jagged line
               radiating from the lamp. Take the ray point nearest the panel centre (no division)
               and clamp THAT onto the rect: smooth everywhere. */
            float tt = max(dot(toLight, rr), 0.0);
            vec3 rel = rr * tt - toLight;
            float halfH = (Ld.shapeId > 0.5 ? w : h) * 0.5;

            specL = toLight + eR * clamp(dot(rel, eR), -w * 0.5, w * 0.5)
                + eU * clamp(dot(rel, eU), -halfH, halfH);
            specSrc = 0.08;
        }

        specDist = max(length(specL), 0.05);
        specL /= specDist;
        specAtten = 1.0 / (1.0 + specDist * specDist) * (1.0 - smoothstep(Ld.range * 0.85, Ld.range, dist));
    }
    else
    {
        /* POINT / SPOT — two falloff laws, picked per lamp.
           RANGE FILL (default), not inverse-square, normalised to one block. 1/dist^2 collapsed to
           a tiny bubble a couple blocks out — probe measured atten BLACK on every wall ("обрез
           пополам"). A film lamp fills its radius evenly and cuts at the edge; the SOURCE RADIUS
           softens the near field through the effective distance: a wide source flattens the centre
           into a soft pool instead of a singular hotspot. */
        float srcR = max(Ld.srcRadius, 0.05);
        float atten;

        if (Ld.falloffMode > 0.5)
        {
            /* PHYSICAL inverse-square (the Blender look): 1/d² with the near-field knee folded
               into the same srcR — unit-bright at one block, a percent at ten, a hot clamp-free
               core inside the source radius. The range does not CUT, it windows the tail to zero
               (quartic, UE-style): with a generous range the window leaves the curve untouched
               and only culls; a tight one visibly reshapes it, so physical wants range ≥ 10. */
            float rr = (dist * dist) / (Ld.range * Ld.range);
            float w = clamp(1.0 - rr * rr, 0.0, 1.0);

            atten = w * w / (dist * dist + srcR * srcR);
        }
        else
        {
            float softDist = sqrt(dist * dist + Ld.srcRadius * Ld.srcRadius);
            float fill = clamp(1.0 - softDist / Ld.range, 0.0, 1.0);

            atten = fill * fill;

            /* Near-field knee: a source BIGGER than the distance dilutes the pool — the hotspot
               flattens as the dial grows (1-block brightness drops right away), small radii keep
               full punch. */
            atten *= min((dist * dist + 1.0) / (dist * dist + srcR * srcR), 1.0);
        }

        /* Photometric profile shapes the throw before the cone does. */
        if (Ld.iesProfile > 0.5)
        {
            atten *= vfxIes(Ld.iesProfile, dot(-L, normalize(Ld.dir)));
        }

        float cone = 1.0;
        float coneWide = 1.0;

        if (Ld.type > 0.5)
        {
            float ca = vfxConeCos(normalize(-L), Ld.dir, Ld.up, Ld.shapeWH);
            float denom = max(Ld.cosInner - Ld.cosOuter, 0.0005);

            cone = clamp((ca - Ld.cosOuter) / denom, 0.0, 1.0);
            /* Ease OUT, not smoothstep: hold near full across most of inner->outer, drop only near
               the rim, so the bright pool is WIDE and a shadow reads across it. smoothstep left
               half the pool a dim ring. */
            cone = 1.0 - (1.0 - cone) * (1.0 - cone);
            /* The spill cone, wider and dimmer: the rainbow tongues dance AROUND the white shaft,
               not inside it — caustics escape past the pool's edge. */
            coneWide = clamp((ca - (Ld.cosOuter - 0.35)) / 0.35, 0.0, 1.0);
            coneWide = coneWide * coneWide * (3.0 - 2.0 * coneWide);
        }

        float ndl = max(dot(S.n, L), 0.0);

        factor = ndl * atten * cone;
        spill = ndl * atten * max(cone, coneWide * 0.6);
        specAtten = atten * cone;
        celShape = ndl;
        celReach = atten * cone;
        R.ndl = ndl;
        R.atten = atten * cone;
    }

    vec3 shade = vec3(1.0);

    /* The shadow tap runs in inkOnly mode too: the contour's light-gate reads it (a contour shows
       only where the lamp's light actually lands). */
    if (max(factor, spill * Ld.disp) > 0.0005)
    {
        /* Penumbra from the emitter's PHYSICAL size. A tube's width is the segment LENGTH, not its
           girth — sizing from it gave a wall-wide half-shadow; the size is HALF the thickness, the
           radius, consistent with rect/disc using half-extents. Floor 0.05: a zero source radius
           must not null the penumbra with the softness dial up. */
        float srcSize = Ld.type > 1.5
            ? (Ld.shapeId > 1.5 && Ld.shapeId < 2.5 ? Ld.thickness * 0.5
                : max(Ld.shapeWH.x, Ld.shapeWH.y) * 0.5)
            : Ld.srcRadius;

        srcSize = max(srcSize, 0.05);

        shade = vfxShadow(atlas, glassMap, S.shadowPos, S.geoN, dist, Ld.pos,
            Ld.type, Ld.cube, Ld.firstTile, Ld.across, Ld.near, far,
            Ld.shadowMatrix, Ld.cosOuter, srcSize * Ld.softDial, S.slopeMax);

        /* Actor-fitted maps, one slot per tracked actor: within its narrow cone each slot is
           simply the better map on its own target (mm texels) — the second skin layer's shadow
           survives on the body. Outside its cone a slot answers "lit", and keeping the DARKER
           of all maps preserves the main map's word everywhere else. */
        /* The actor-sampling normal (actN): the widest stencil of the three, axis-snapped —
           the fitted maps' bias/eps/contact weights all key off surface facing, and on
           slanted skin (posed limbs) the narrower normals staircased per row — the "линии". */
        if (Ld.actorTile0 >= 0.0)
        {
            shade = min(shade, vec3(vfxActorShadow(atlas, S.shadowPos, S.actN, Ld.pos,
                Ld.actorTile0, Ld.across, Ld.actorNear0, Ld.actorFar0, Ld.actorTan0,
                Ld.actorMatrix0, S.slopeMax)));
        }

        if (Ld.actorTile1 >= 0.0)
        {
            shade = min(shade, vec3(vfxActorShadow(atlas, S.shadowPos, S.actN, Ld.pos,
                Ld.actorTile1, Ld.across, Ld.actorNear1, Ld.actorFar1, Ld.actorTan1,
                Ld.actorMatrix1, S.slopeMax)));
        }

        if (Ld.actorTile2 >= 0.0)
        {
            shade = min(shade, vec3(vfxActorShadow(atlas, S.shadowPos, S.actN, Ld.pos,
                Ld.actorTile2, Ld.across, Ld.actorNear2, Ld.actorFar2, Ld.actorTan2,
                Ld.actorMatrix2, S.slopeMax)));
        }

        if (Ld.actorTile3 >= 0.0)
        {
            shade = min(shade, vec3(vfxActorShadow(atlas, S.shadowPos, S.actN, Ld.pos,
                Ld.actorTile3, Ld.across, Ld.actorNear3, Ld.actorFar3, Ld.actorTan3,
                Ld.actorMatrix3, S.slopeMax)));
        }

        /* Dissolve the shadow across the light's last stretch: the map's far plane IS the range,
           and whatever the receiver reads near it (cleared far depth, the uv clamp, a neighbour
           tile) is not a real occluder — on the high-contrast deferred packs (IterationRP/Eclipse)
           that read as shadows clipping mid-air a hair before the pool ends. Fading the shadow to
           "lit" as the falloff dies keeps the occluder's word where the light is strong and lets
           the edge breathe out with it; on the packs that already faded smoothly the last 20% of
           range holds ~4% of the light, so nothing visible changes there. */
        shade = mix(vec3(1.0), shade, 1.0 - smoothstep(Ld.range * 0.8, Ld.range, dist));
    }

    if (!inkOnly)
    {
        shade *= vfxWater(S.pos, dist, Ld.pos, Ld.dir, Ld.up, Ld.waterDist, Ld.dispPhase, Ld.waterY);
    }

    float shadeMax = max(shade.r, max(shade.g, shade.b));
    vec3 tint = Ld.color * Ld.intensity;

    R.shade = shade;
    R.factor = factor;
    R.celReach = celReach;

    if (Ld.type > 1.5)
    {
        R.ndl = celShape;
        R.atten = factor;
    }

    if (!inkOnly)
    {
        /* Cel / toon: hard TWO-TONE terminator instead of the smooth ramp. Warm lit tone (light
           colour x cast shadow, keeps glass tint) vs an auto-cool shadow tone; both the N.L
           terminator and the cast shadow are stepped hard. celReach carries the light's spatial
           pool so the cool fills the shadow SIDE without leaking outside the beam. */
        if (celOn)
        {
            if (celReach > 0.0005)
            {
                float term = smoothstep(0.5 - Ld.cel.y, 0.5 + Ld.cel.y, celShape);
                float castHard = smoothstep(0.5 - Ld.cel.y, 0.5 + Ld.cel.y, shadeMax);
                float litMask = term * castHard;

                R.diffuse += mix(vfxCoolShadow(tint, Ld.cel.z, Ld.cel.w), tint * shade, litMask) * celReach;
            }
        }
        else
        {
            R.diffuse += tint * factor * shade;
        }

        /* Dispersion caustics: shadowed (and stained) like the light, riding the SPILL factor with
           real caustic ENERGY — fold ridges overshoot the pool, bloom turns it into glow. */
        if (Ld.disp > 0.001 && spill * shadeMax > 0.0005)
        {
            R.diffuse += tint * (Ld.disp * 2.5 * spill) * shade
                * vfxCaustic(S.pos, Ld.pos, Ld.dir, Ld.up, Ld.dispScale, Ld.dispPhase);
        }

        /* Translucency: light through THIN occluders. Only for back-lit fragments; thickness from
           the shadow map keeps a stone wall dark and lets a leaf canopy glow. NOT multiplied by
           the shadow factor — transmission is precisely the light that comes THROUGH the blocker. */
        if (Ld.sss > 0.001 && dot(S.n, L) < 0.0 && specAtten > 0.0005)
        {
            float thick = vfxThickness(atlas, S.shadowPos, Ld.pos, Ld.type, Ld.cube,
                Ld.firstTile, Ld.across, Ld.near, far, Ld.shadowMatrix);

            if (thick >= 0.0)
            {
                float backNdl = clamp(-dot(S.n, L), 0.0, 1.0);
                float vdl = clamp(dot(S.viewDir, -L), 0.0, 1.0);
                float trans = exp(-thick * 2.8) * backNdl * (0.35 + 0.65 * vdl * vdl);

                R.diffuse += tint * specAtten * trans * Ld.sss;
            }
        }

        /* Highlight: GGX with Schlick Fresnel, aimed at the emitter's representative point.
           Shadow and stained glass tint it like everything else. */
        if (specAtten * shadeMax > 0.0005)
        {
            R.spec += tint * shade
                * vfxGGX(S.n, S.viewDir, specL, specDist, specSrc, S.smoothness, Ld.specRim)
                * specAtten * front * S.specGain;

            /* Rim / edge light: the grazing-Fresnel reflection of this lamp, riding the SAME
               delivered radiance and colour-shadow as everything else — occluded, stained and
               coloured like real light, never a floating overlay. Aims at the representative
               point: the rim follows the emitter surface you actually see reflected (for point
               and spot that IS the centre). */
            if (Ld.rimKnob > 0.001)
            {
                R.spec += tint * shade
                    * vfxRim(S.rimN, S.viewDir, specL, S.smoothness, Ld.rimKnob, Ld.rimWidth)
                    * specAtten * front;
            }
        }
    }

    /* Contour ink, IRLite LocalLightOutline semantics (ported from the iterationrp contour patch):
       the line lives on GRAZING surface — a Fresnel limb term (1−|n·v|)^2.2 — and only where this
       lamp's light actually lands (shade carries the shadow and the stained tint; the reach gate
       keeps it inside the pool). Back rim PLUS a modest front catch-light (0.3·ndl, IRLite's own
       FRONT_STRENGTH): the pure back-rim gate vanished under the frontal key light every film
       rig uses — the "в фильме не видны outline" report. The caller multiplies by its screen-space
       edge mask, the width/blur dials and the lamp colour. (IRLite's pre-albedo application was
       tried and rejected: it died on dark albedos at the Eclipse/photon call sites, bloomed on
       IterationRP, and never reached Complementary's ink-only entry.) */
    float inkFres = pow(clamp(1.0 - abs(dot(S.n, S.viewDir)), 0.0, 1.0), 2.2);
    float inkNdl = clamp(dot(S.n, L), 0.0, 1.0);
    float inkBack = (1.0 - inkNdl) + 0.3 * inkNdl;
    float inkReach = Ld.type > 1.5 ? factor : celReach;

    R.inkBase = shade * (inkFres * inkBack) * smoothstep(0.02, 0.10, inkReach);

    /* Interior contour (edge-detect lines INSIDE the geometry): light-driven like the silhouette
       ink — it shows only where this lamp's light actually lands — but without the facing gates:
       a crease line is drawn on surfaces turned any way, which is what makes it read as detail. */
    R.inkInner = shade * smoothstep(0.02, 0.10, inkReach);
}
