// VFX LIGHTS — film lighting fed into the pack's own surface shading.
// SHARED across every patched pack (Complementary, IterationRP, Eclipse, …); PackPatcher serves this
// exact file for each pack's `#include "/lib/vfxlights_lights.glsl"`, splicing the shared light model
// (shaders/include/vfxlights_model.glsl — ONE source for the pack, the fallback and the beams) over
// the //@VFX_MODEL@ marker below. This file is only the PACK-SIDE ADAPTER: the SSBO layout, the
// samplers, and the loop that gathers slots into the model's structs.
//
// Two portability rules keep it universal: (1) normals arrive in WORLD space — a view-space pack
// rotates at the call site, not here; (2) texture2D is remapped to texture() on GLSL 130+ (inside
// the model) so the same source compiles on a #version 120 pack (Complementary) and a #version 400+
// deferred pack (IterationRP/Eclipse) alike.
// Light positions arrive CAMERA-RELATIVE, i.e. already in playerPos space.
#extension GL_ARB_shader_storage_buffer_object : enable

layout(std430, binding = 5) readonly buffer VfxLightData {
    vec4 vfxCount;
    vec4 vfxAtlas;
    vec4 vfxL[];
};

// Screen-tile cluster grid (binding 8 — 5 is our lights, 6/7 are IRLite's): header = (gridX,
// gridY, active), then one vec4 per tile with the lamp bitmask as three 24-bit float chunks
// (GLSL 120 has no uints — decoded like the group filter's bits). CPU-built each frame from the
// exact packed list above, so bit i of a tile IS lamp i: the loop tests the bit before fetching
// a single slot. active == 0 runs the full loop, bit-identical to no cluster at all.
layout(std430, binding = 8) readonly buffer VfxClusterData {
    vec4 vfxClusterHeader;
    vec4 vfxCluster[];
};

uniform sampler2D vfxShadowAtlas;
uniform sampler2D vfxShadowColor;
uniform sampler2D vfxCharMask;
uniform sampler2D vfxGroupTex;

//@VFX_MODEL@

#ifdef VFX_OUTLINE_DEBUG
/* ★TEMP: per-pixel MAX of the raw second difference the inner pairs compute — painted by
   vfxlights_run, so "no signal at all" (black) and "signal exists but the window kills it"
   (bright) separate. GLSL globals are per-fragment, so this accumulates within the pixel.
   vfxInnerDbg2/3 = the centre tap and one neighbour tap themselves (scaled by the painter):
   the body showing a huge d while the CENTRE reads sane incriminates the neighbour fetches
   (offset/scale/texture), not the threshold window. */
float vfxInnerDbg = 0.0;
float vfxInnerDbg2 = 0.0;
float vfxInnerDbg3 = 0.0;
#endif

/* Interior (in-geometry) edge pair score: the SAME two-sided second difference the exterior
   stencil computes, but absolute and windowed — small |d| is a crease or an overlap INSIDE the
   silhouette, huge |d| is the silhouette itself (owned by the one-sided exterior score, and the
   far side of it must never ink: that would step the line OFF the object). The call sites already
   fetched the tap depths, so the interior mask costs no extra texture reads.
   (The contour blend modes live in the shared model — vfxInkBlendDelta — so the fallback gets the
   same code.) */
float vfxInteriorPair(float zA, float zB, float zC)
{
    /* The crease step in WORLD depth units — it is what it is at any distance, so the detection
       threshold must be world-anchored too. The old relative form (d / zC, thresholds 0.015..0.045)
       asked a crease at zC metres to be 0.015·zC deep — 3 cm at 2 m, 12 cm at 8 m, 24 cm at 16 m —
       and a model's few-centimetre folds quietly vanished a few blocks out ("подходишь — рисуется,
       отходишь — пропадает"). The floor still creeps up with distance: reconstruction noise grows
       with zC, and a fixed centimetre threshold would ink noise at 30 blocks. The upper window
       keeps its relative form — a silhouette IS a gap comparable to the distance.
       lo = 0.012 + zC·0.004, ramp ×3: fires folds of 2.0/4.4/7.6/14 cm at 2/8/16/32 m (the old
       window asked 3/12/24/48 cm) — BBS cuboid seams are shallow view-Z steps of 1–5 cm. */
    float d = abs(zA + zB - 2.0 * zC);
    float lo = 0.012 + zC * 0.004;
    float hi = 0.12 * zC;

#ifdef VFX_OUTLINE_DEBUG
    vfxInnerDbg = max(vfxInnerDbg, d);
    vfxInnerDbg2 = zC;
    vfxInnerDbg3 = zA;
#endif

    /* Sparse gbuffer holes: the pack's entity path leaves 1-px holes in the actor's depth
       (alpha discards / translucent texels), and a hole tap reads the BACKGROUND metres away —
       d then saturates the silhouette suppressor on EVERY pixel of the body, which is exactly
       how the inner contour died in packs while exterior (it WANTS huge gaps) happily fired on
       the same taps. A pair whose taps are not BOTH within the fold window of the centre is
       not an interior fold at all — hole or silhouette — so it scores zero outright. Legitimate
       folds have both taps within hi of the centre by design, and the graduated suppressor
       below keeps its old role for the d that remains in-window. */
    if (abs(zA - zC) > hi || abs(zB - zC) > hi)
    {
        return 0.0;
    }

    return smoothstep(lo, lo * 3.0, d) * (1.0 - smoothstep(hi, hi * 2.5, d));
}

// diffuse + a modest highlight, both in the pack's linear working space.
// n = the shading normal (normal-mapped); geoN = the flat geometric normal. The shadow lookup MUST use
// geoN: offsetting the sample point along a per-texel mapped normal jitters the lookup texel by texel,
// and the shadow edge dissolves into pixel noise — measured, that was the whole "пиксельные" complaint.
//
// ★NORMALS ARRIVE IN WORLD SPACE. Complementary's DoLighting hands them in VIEW space, so its patch
// rotates at the call site (mat3(gbufferModelViewInverse) * normalM); the deferred packs already store
// world-space gbuffer normals and pass them straight in. Keeping the rotation OUT of here is what lets
// one file serve every pack — and it was the view-space dot with world light vectors that once made
// every pool breathe and swing with the camera.
// shadowPos: the fragment position used for the SHADOW-MAP lookup, which may differ from playerPos.
// Our shadow atlas is baked in ABSOLUTE world; the lookup distance must land in exactly the space the
// SSBO light positions were uploaded in. On a deferred pack whose fragment position needed a bob/eye
// correction for the LIGHTING (playerPos), that same correction can push the shadow reference off the
// baked depth by a constant the lighting tolerates but the shadow does not — so the caller passes the
// shadow its own, bake-consistent position. Packs where the two agree just pass the same vector twice.
// edgeMask2 / edgeMask4: the screen-space contour-edge RAW scores (sum of the four one-sided pair
// terms, 0..4) at stencil radii 2 px and 4 px, computed ONCE per pixel by the CALL SITE from whatever
// depth it can read — 0 on packs where no depth sampler is reachable, which disables the outline ink.
// Two radii because the mask is shared by every lamp while the width dial is per-light: each lamp
// blends between them by its own outline width ([15].z) and opens the score window by its blur
// ([15].w). Both stencils are strictly ONE-SIDED (a pair scores only when the centre pixel is NEARER
// than both its taps — no abs, no two-sided branch): a background pixel beside the silhouette is
// always farther than its pair and scores zero, so the ink can never step off the actor onto the
// set, diagonals included. The per-light blur only reshapes that one-sided score.
// inkOnly: skip everything that DELIVERS light (shadow, water, diffuse, cel, caustics, sss, spec,
// rim) and keep only what the contour ink needs — the entry for a program that must add the outline
// on top of light another program already shaded (Complementary's deferred1; its gbuffers hook
// delivers the light itself). outDiffuse stays 0.
void vfxlights_run(vec3 playerPos, vec3 shadowPos, vec3 nWorld, vec3 geoNWorld, float smoothness, float edgeMask2, float edgeMask4, float edgeMaskIn, vec3 vfxDest, bool inkOnly, out vec3 outDiffuse, out vec3 outSpec)
{
    outDiffuse = vec3(0.0);
    outSpec = vec3(0.0);

#ifdef VFX_OUTLINE_DEBUG
    float dbgInner = 0.0;
#endif

    /* The shadow atlas' tile side (Shadow quality) and the filter step. Stamped BEFORE anything in
     * the model runs — every texel-sized offset in the shadow stack reads them. vfxAtlas.y is 0 only
     * against an SSBO an older build filled, where the model's own default is the right answer. */
    VFX_TILE = vfxAtlas.y > 0.5 ? vfxAtlas.y : 1024.0;
    VFX_FILTER = vfxAtlas.z;

    vec3 inkAcc = vec3(0.0);
    vec3 n = normalize(nWorld);
    vec3 geoN = normalize(geoNWorld);
    vec3 viewDir = normalize(-playerPos);
    int count = int(vfxCount.x);

    // Cluster lookup, once per pixel: this fragment's tile index. Only when the grid is live AND
    // the framebuffer size is known (vfxCount.yz) — otherwise the full loop below runs unchanged.
    float clusterActive = vfxCount.y > 0.5 ? vfxClusterHeader.z : 0.0;
    int clusterTile = 0;

    if (clusterActive > 0.5)
    {
        vec2 clusterUv = gl_FragCoord.xy / vfxCount.yz;
        int cx = min(int(clusterUv.x * vfxClusterHeader.x), int(vfxClusterHeader.x) - 1);
        int cy = min(int(clusterUv.y * vfxClusterHeader.y), int(vfxClusterHeader.y) - 1);

        clusterTile = cy * int(vfxClusterHeader.x) + cx;
    }

    // Cel applies to CHARACTERS only. vfxCount.yz = framebuffer size (GLSL 120 has no textureSize), so
    // gl_FragCoord -> screen UV. Just "is a character drawn here" - NOT a depth compare against the
    // scene: that compare flipped across the whole model under the pack's TAA (sub-pixel jitter on
    // slanted surfaces exceeds any bias) and the actor flickered. 0.9999 sits far from the jitter.
    float celMask = 0.0;
    // Replay category of the actor covering this pixel (0 = none or a block): the per-lamp group
    // filter's pixel half, sampled the same way as the character mask.
    int vfxCat = 0;

    if (vfxCount.y > 0.5)
    {
        vec2 vfxSuv = gl_FragCoord.xy / vfxCount.yz;

        celMask = texture2D(vfxCharMask, vfxSuv).r < 0.9999 ? 1.0 : 0.0;
        vfxCat = int(texture2D(vfxGroupTex, vfxSuv).r * 255.0 + 0.5);
    }

    // The surface is the same for every lamp; the loop only refills the light. slopeMax 2.0: the
    // slope-scaled bias still fights acne on sloped surfaces, but the 6.0 cap peter-panned every
    // thin occluder at grazing light — fences and grass lost contact with their own shadows by
    // ~15 cm (constant offset, packs only; the vanilla fallback keeps 1.0). specGain 0.8 against
    // real material smoothness.
    VfxSurface S;

    S.pos = playerPos;
    S.shadowPos = shadowPos;
    S.n = n;
    S.geoN = geoN;
    S.rimN = n;
    S.actN = n;
    S.viewDir = viewDir;
    S.smoothness = smoothness;
    S.specGain = 0.8;
    S.slopeMax = 2.0;

    for (int i = 0; i < count; i++)
    {
        int b = i * 38;

        // The tile's verdict on this lamp, before any slot is fetched: bit i of this pixel's
        // tile, as a 24-bit float chunk (i/24 selects x/y/z, i%24 the bit). The CPU rasterised
        // the influence spheres conservatively, so a 0 here is a lamp this pixel provably never
        // sees — skipping it is not a quality trade, it is the same light for less bandwidth.
        if (clusterActive > 0.5)
        {
            int cw = i / 24;
            vec4 cbits = vfxCluster[clusterTile];
            float cword = cw == 0 ? cbits.x : (cw == 1 ? cbits.y : cbits.z);

            if (mod(floor(cword / exp2(float(i - cw * 24))), 2.0) < 0.5)
            {
                continue;
            }
        }

        // Everything the reject needs is two slots: read [b] and [b+2] FIRST and cut out-of-range
        // lamps before the other six vec4s are fetched — in a many-lamp scene most iterations end
        // here, and each ended one used to cost the full 7-slot read before its dist check.
        vec4 dPos = vfxL[b];
        vec4 dDir = vfxL[b + 2];

        float type = dPos.w;
        vec3 toLight = dPos.xyz - playerPos;
        float dist = length(toLight);
        float range = dDir.w;

        if (type <= 2.5 && dist > range) continue;

        // "Light blocks / entities" toggles ([7].w: bit 1 blocks, bit 2 entities). Skip this lamp on a
        // surface it must not touch — characters via celMask, world otherwise.
        float vfxAffect = vfxL[b + 7].w;

        if (!(celMask > 0.5 ? vfxAffect > 1.5 : ((vfxAffect > 0.5 && vfxAffect < 1.5) || vfxAffect > 2.5)))
        {
            continue;
        }

        // Group filter ([36].z): the lamp lights only pixels whose actor's replay category its
        // bitmask allows. 16777215 (all 24 bits) = no filter. 24 bits, not 32: the SSBO carries
        // floats, exact integers stop at 2^24, and GLSL 120 packs have no uints to decode into —
        // so bit k is read as mod(floor(bits / 2^k), 2), all exact under 2^24. Skipping the whole
        // lamp (not just scaling shade) also keeps the cel cool-shadow tone and translucency from
        // leaking onto disallowed pixels; the contour ink pass is filtered the same way.
        // Blocks (vfxCat < 1) are ALWAYS lit — otherwise the selected actor's shadow has no lit
        // ground to read on; only disallowed-category ACTORS are gated.
        float vfxGroupBits = vfxL[b + 36].z;

        if (vfxGroupBits < 16777215.0 && vfxCat >= 1
            && mod(floor(vfxGroupBits / exp2(float(vfxCat - 1))), 2.0) < 0.5)
        {
            continue;
        }

        vec4 dCol = vfxL[b + 1];
        vec4 dUp = vfxL[b + 3];
        vec4 dCone = vfxL[b + 4];
        vec4 dBarn = vfxL[b + 5];
        vec4 dMisc = vfxL[b + 6];

        if (type > 2.5)
        {
            // Ambient fill: sphere zone or hemisphere; the box zone stays with the no-pack backend for
            // now. Pure light delivery — the ink pass has no ambient contour, so it skips these lamps.
            // Stays OUTSIDE the shared model: what data each backend can proxy occlusion from differs
            // (the fallback reads the rendered frame's albedo; here only the geometric normal exists).
            if (inkOnly) continue;

            float edge = dBarn.w;
            float fall = 1.0 - smoothstep(range * (1.0 - edge), range, dist);
            vec3 fill = dCol.rgb;

            if (dMisc.y > 1.5) fill = mix(dBarn.rgb, dCol.rgb, n.y * 0.5 + 0.5);

            // Occlusion dial ([15].y): openness from the geometric normal, so down-facing surfaces
            // (undersides, cavity floors) keep the least fill. An approximation, same in spirit as
            // the fallback's albedo-darkness proxy.
            float occlusion = vfxL[b + 15].y;
            float crevice = smoothstep(0.0, 0.35, geoN.y * 0.5 + 0.5);

            outDiffuse += fill * dCol.a * fall * 0.25 * mix(1.0, crevice, occlusion);

            continue;
        }

        // Everything below is the shared model — this block only TRANSLATES the SSBO slots.
        VfxLightDesc Ld;

        Ld.pos = dPos.xyz;
        Ld.type = type;
        Ld.color = dCol.rgb;
        Ld.intensity = dCol.a;
        Ld.dir = dDir.xyz;
        Ld.range = range;
        Ld.up = dUp.xyz;
        Ld.srcRadius = dUp.w;
        Ld.cosOuter = dCone.x;
        Ld.cosInner = dCone.y;
        Ld.shapeWH = dCone.zw;
        Ld.shapeId = mod(dMisc.y, 10.0);
        Ld.twoSided = dMisc.y >= 10.0;
        Ld.spread = dMisc.x;
        Ld.thickness = dMisc.w;
        Ld.barn = dBarn;
        Ld.barnSoft = dMisc.z;
        Ld.firstTile = vfxL[b + 7].x;
        // Point and area read the six-face cube; a WIDE spot ([7].y flag) is cube-mapped too — a
        // single projection cannot cover a 120°+ cone.
        Ld.cube = type < 0.5 || type > 1.5 || vfxL[b + 7].y > 0.5;
        Ld.across = vfxAtlas.x;
        Ld.near = VFX_NEAR;
        Ld.shadowMatrix = mat4(vfxL[b + 8], vfxL[b + 9], vfxL[b + 10], vfxL[b + 11]);
        Ld.softDial = vfxL[b + 7].z;
        Ld.disp = vfxL[b + 12].x;
        Ld.dispScale = vfxL[b + 12].y;
        Ld.dispPhase = vfxL[b + 12].z;
        Ld.waterDist = vfxL[b + 12].w;
        Ld.waterY = vfxL[b + 13].z;
        Ld.sss = vfxL[b + 13].x;
        Ld.iesProfile = vfxL[b + 13].y;
        Ld.rimKnob = vfxL[b + 13].w;
        Ld.rimWidth = vfxL[b + 36].x;
        Ld.falloffMode = vfxL[b + 36].y;
        Ld.specRim = vfxL[b + 36].w;
        // The actor-fitted slots: five vec4 each — params (tile, near, far, half-angle tangent)
        // then the four matrix columns. A slot with tile < 0 carries no map and is skipped.
        Ld.actorTile0 = vfxL[b + 16].x;
        Ld.actorNear0 = vfxL[b + 16].y;
        Ld.actorFar0 = vfxL[b + 16].z;
        Ld.actorTan0 = vfxL[b + 16].w;
        Ld.actorMatrix0 = mat4(vfxL[b + 17], vfxL[b + 18], vfxL[b + 19], vfxL[b + 20]);
        Ld.actorTile1 = vfxL[b + 21].x;
        Ld.actorNear1 = vfxL[b + 21].y;
        Ld.actorFar1 = vfxL[b + 21].z;
        Ld.actorTan1 = vfxL[b + 21].w;
        Ld.actorMatrix1 = mat4(vfxL[b + 22], vfxL[b + 23], vfxL[b + 24], vfxL[b + 25]);
        Ld.actorTile2 = vfxL[b + 26].x;
        Ld.actorNear2 = vfxL[b + 26].y;
        Ld.actorFar2 = vfxL[b + 26].z;
        Ld.actorTan2 = vfxL[b + 26].w;
        Ld.actorMatrix2 = mat4(vfxL[b + 27], vfxL[b + 28], vfxL[b + 29], vfxL[b + 30]);
        Ld.actorTile3 = vfxL[b + 31].x;
        Ld.actorNear3 = vfxL[b + 31].y;
        Ld.actorFar3 = vfxL[b + 31].z;
        Ld.actorTan3 = vfxL[b + 31].w;
        Ld.actorMatrix3 = mat4(vfxL[b + 32], vfxL[b + 33], vfxL[b + 34], vfxL[b + 35]);
        Ld.cel = vfxL[b + 14];

        VfxShadeOut R;

        vfxShadeLight(vfxShadowAtlas, vfxShadowColor, Ld, S, inkOnly,
            Ld.cel.x > 0.5 && celMask > 0.5, R);

        // ★Dev probe (vfxCount.w = 1, from the VFXLIGHTS_PROBE env): paint the GATES instead of the
        // light, so a screenshot NAMES the term that killed a pool - R shadow visibility, G range
        // headroom (0 at the range edge), B delivered reach (atten x cone / area form factor).
        // Unpainted = past range or lamp skipped. The fallback's probe adds a magenta floor; clean
        // channels here mean the PACK path drew this pixel.
        if (vfxCount.w > 0.5)
        {
            outDiffuse = vec3(max(R.shade.r, max(R.shade.g, R.shade.b)),
                clamp(1.0 - dist / range, 0.0, 1.0), clamp(R.celReach, 0.0, 1.0));
            outSpec = vec3(0.0);

            continue;
        }

        outDiffuse += R.diffuse;
        outSpec += R.spec;

        // Contour ink: the model's gates (R.inkBase = shade x Fresnel limb x backlight x reach —
        // vec3, so the ink through stained glass is tinted like every other delivery) times this
        // pack's own screen-space edge score. Width ([15].z, px) blends the two call-site radii
        // (2 -> edgeMask2, 4 -> edgeMask4, clamped); blur ([15].w) opens the score window — 0 is
        // the hard 0.35..1.0 line, 1 a wide soft ramp. Rides outSpec — the one channel every patch
        // adds AFTER the albedo multiply; a contour must not be tinted by the surface it borders.
        // [37].x = interior (in-geometry crease) strength, gated by R.inkInner — shade x reach,
        // WITHOUT the facing gates: a crease line is drawn whichever way its surface turns.
        // [37].y = target: 0 = world and models, 1 = models only (character mask), 2 = world only.
        // [37].z = blend mode: 0 = add, 1 = screen, 2 = overlay — applied as the exact DELTA
        // against vfxDest (the call site's colour for this pixel), so additive compositing still
        // lands the mode's result.
        float outline = vfxL[b + 15].x;
        float outlineInner = vfxL[b + 37].x;
        float outlineTarget = vfxL[b + 37].y;
        float outlineBlend = vfxL[b + 37].z;
        bool vfxTargetOk = outlineTarget < 0.5
            || (outlineTarget < 1.5 ? celMask > 0.5 : celMask < 0.5);

        if (outline > 0.001 && max(edgeMask2, edgeMask4) > 0.001 && vfxTargetOk)
        {
            float edge = mix(edgeMask2, edgeMask4, clamp((vfxL[b + 15].z - 2.0) * 0.5, 0.0, 1.0));

            edge = smoothstep(mix(0.35, 0.05, vfxL[b + 15].w), mix(1.0, 4.5, vfxL[b + 15].w), edge);

            vec3 inkL = dCol.rgb * R.inkBase * (edge * outline * 1.5);

            inkAcc += outlineBlend < 0.5 ? inkL : vfxInkBlendDelta(vfxDest, inkL, outlineBlend);
        }

        if (outlineInner > 0.001 && edgeMaskIn > 0.001 && vfxTargetOk)
        {
            float edgeIn = smoothstep(mix(0.35, 0.05, vfxL[b + 15].w), mix(1.0, 4.5, vfxL[b + 15].w), edgeMaskIn);
            vec3 inkL = dCol.rgb * R.inkInner * (edgeIn * outlineInner * 1.5);

            inkAcc += outlineBlend < 0.5 ? inkL : vfxInkBlendDelta(vfxDest, inkL, outlineBlend);
        }

#ifdef VFX_OUTLINE_DEBUG
        dbgInner = max(dbgInner, step(0.001, outlineInner));
#endif
    }

    // Gain applies to DELIVERED light only — a drawn contour line is not radiometry. The ink rides
    // outSpec — the one channel every patch adds AFTER the albedo multiply: the pre-albedo move
    // (IRLite's way) died on dark albedos at the Eclipse/photon call sites (their vfxDiffuse is
    // albedo-multiplied on the spot), bloomed on IterationRP, and never reached Complementary's
    // ink-only entry at all (it reads outSpec). The ink is CLAMPED at 16.0: the 1.0 cap it
    // replaces FROZE the intensity dial (the default ink already sums past it), and the dial is
    // unbounded on the UI side — 16 keeps the response linear across any sane drag while staying
    // far below runaway HDR (vfxGuard still catches poison; a thin line at 16 is bright, not
    // broken — packs with aggressive bloom will glow it past ~3, that is the accepted trade of
    // giving the dial real headroom). Then the NaN/Inf insurance (see vfxGuard): one poisoned
    // fragment spreads through a pack's temporal accumulation (IterationRP) and flashes whole
    // regions black.
    outDiffuse = vfxGuard(outDiffuse * VFX_GAIN);
    outSpec = vfxGuard(outSpec * VFX_GAIN + min(inkAcc, vec3(16.0)));

#ifdef VFX_OUTLINE_DEBUG
    /* ★TEMP diagnostic (-Dvfxlights.outline.debug=1, defined by PackPatcher): paints the hook's
       outline INPUTS instead of shading. Diffuse: R = depth probe (1.0 near → 0.0 far/sky — an
       actor as dark as the wall behind it is not in the gbuffer), G = the RAW second difference
       (10 cm → full): folds should be ISOLATED lines, not whole-body fill, B = the CENTER tap
       zC (3 m ≈ 0.1 of scale) — sane centre + whole-body d incriminates the NEIGHBOUR fetches.
       Spec: R = the interior edge mask ×4 (so a weak score still reads), G = ONE NEIGHBOUR tap
       z1 (same scale as diffuse.B — compare directly), B = any lamp at this pixel reporting
       outlineInner > 0 in the SSBO. */
    outDiffuse = vec3(clamp(1.0 - length(playerPos) / 30.0, 0.0, 1.0),
        clamp(vfxInnerDbg * 10.0, 0.0, 1.0), clamp(vfxInnerDbg2 * 0.1, 0.0, 1.0));
    outSpec = vec3(clamp(edgeMaskIn * 4.0, 0.0, 1.0), clamp(vfxInnerDbg3 * 0.1, 0.0, 1.0), dbgInner);
#endif

#ifdef VFX_ACTOR_GATE_DEBUG
    /* ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug): paints the gates vfxActorShadow stashed
       into the model global (it keeps its float contract — the vec3 return narrowed into float
       math on deferred1.fsh, C7623). R = faceW (edge-on-to-light gate), G = feather (cone
       edge), B = reach; white = no actor map spoke for the pixel. */
    outDiffuse = vec3(0.0);
    outSpec = vfxActorGateDbg;
#endif
}

// Full lighting entry — identical to the pre-split vfxlights_apply; the three pack patches call this.
// vfxDest = the call site's COMPOSED pixel colour for this pixel (post-albedo — the blend modes'
// destination). A pre-albedo lighting accumulator is NOT fine: screen/overlay against it
// degenerates to additive exactly where the mode was chosen to matter (dark scenes).
void vfxlights_apply(vec3 playerPos, vec3 shadowPos, vec3 nWorld, vec3 geoNWorld, float smoothness, float edgeMask2, float edgeMask4, float edgeMaskIn, vec3 vfxDest, out vec3 outDiffuse, out vec3 outSpec)
{
    vfxlights_run(playerPos, shadowPos, nWorld, geoNWorld, smoothness, edgeMask2, edgeMask4, edgeMaskIn, vfxDest, false, outDiffuse, outSpec);
}

// Contour ink ONLY: same lamp loop, same reach/facing gates, but nothing that delivers light — for a
// program whose job is the outline while the light itself arrives through another hook. shadowPos,
// geoN and smoothness only feed the skipped terms, so the call passes none of them.
void vfxlights_ink(vec3 playerPos, vec3 nWorld, float edgeMask2, float edgeMask4, float edgeMaskIn, vec3 vfxDest, out vec3 outInk)
{
    vec3 vfxDrop;

    vfxlights_run(playerPos, playerPos, nWorld, nWorld, 0.0, edgeMask2, edgeMask4, edgeMaskIn, vfxDest, true, vfxDrop, outInk);
}
