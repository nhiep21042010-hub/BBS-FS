#version 150

/* The no-pack backend: one fullscreen pass per lamp over the finished frame. All the light MATH
   lives in the shared model (shaders/include/vfxlights_model.glsl — one source for this file, the
   pack patch and the beams); this file is the FALLBACK ADAPTER: depth-reconstructed normals, the
   uniform-per-light plumbing, the albedo-floor compose against an already tone-mapped frame. */

uniform sampler2D DiffuseSampler;
uniform sampler2D ShadowColor;
uniform sampler2D DepthSampler;
uniform sampler2D ShadowAtlas;

uniform mat4 InvViewProj;
uniform vec3 CameraPos;

/* One light per draw. Minecraft's core-shader uniforms have no comfortable array support, and a
   fullscreen pass per light keeps the shader trivial and the CPU side honest. The cost is a draw call
   each; scissoring each light to its screen bounds is the obvious optimisation once this is proven. */
uniform vec3 LightPos;
uniform vec3 LightColor;
/* x = intensity, y = range, z = source radius, w = type (0 point, 1 spot, 2 area, 3 ambient) */
uniform vec4 LightParams;
/* xyz = direction, w = cos(outer half-angle) */
uniform vec4 LightDir;
/* x = cos(inner half-angle) [spot] or barn softness [area], y = width, z = height (tube: thickness),
   w = area shape (+10 when two-sided) */
uniform vec4 LightShape;
/* xyz = emitter up axis, w = spread 0..1 */
uniform vec4 LightUp;
/* barn door cut fractions: x top, y bottom, z left, w right */
uniform vec4 LightBarn;

/* Shadow map placement. x = first tile index (-1 = this light has no map), y = tiles across the atlas
   (NEGATIVE flags a wide spot whose map is the six-face cube), z = near plane, w = the softness DIAL
   (0 = crisp decree, 1 = physical) — the model multiplies it by the emitter's own size. */
uniform vec4 ShadowSlot;
/* x = dispersion strength, y = pattern scale, z = animation phase, w = water-entry distance */
uniform vec4 Dispersion;
/* x = translucency (SSS), y = photometric profile id, z = water surface Y, w = rim dial */
uniform vec4 LightExtra;
uniform vec4 LightCel;
uniform vec4 LightAffect;
/* x = contour blur (0 = hard inked line, 1 = wide feathered), y = rim strip width, z = group-mask
   flag (1 = this lamp lights only the approved replay categories). The width rides LightAffect.w. */
uniform vec4 LightContour;
/* x = interior (in-geometry) contour strength, y = contour target (0 = world and models,
   1 = models only, 2 = world only), z = the character mask exists at all, w = blend mode
   (0 = add, 1 = screen, 2 = overlay — the delta against DiffuseSampler's pre-light frame). */
uniform vec4 LightOutlineX;
/* Falloff law for point/spot: 0 = range fill, 1 = physical 1/d² windowed (the Blender look). */
uniform float LightFalloff;
/* ★TEMP diagnostic: -Dvfxlights.shadow.gatedebug paints the actor-map gates (see main). */
uniform float GateDebug;
/* ★TEMP diagnostic: -Dvfxlights.light.debug paints the raw diffuse contribution (see main). */
uniform float LightDebug;
/* Physical rim dial: grazing Fresnel scale in the specular (1 = physics, 0 = off, 2 = hot). */
uniform float SpecRim;
uniform float WaterDebug;
uniform sampler2D CharMask;
uniform sampler2D GroupMaskTex;
/* ★First-person hand (vanilla): the LIVE main depth, copied aside at composite time — it includes
   the hand (the DepthSampler snapshot cannot: it is taken pre-hand, and MC clears depth before
   drawing it). Where the live depth is NEARER than the reconstructed surface, the hand covers the
   pixel and the lamp's light belongs to the world behind, not to the hand. The GL LEQUAL mask on
   the caller stayed paper-correct yet let the hand through in practice — this compare runs on
   data the shader can see (HandDebug paints it). */
uniform sampler2D HandDepth;
uniform float HandMask;
/* ★TEMP diagnostic (-Dvfxlights.hand.debug): R = hand-covered, dark G = cleared world, B = other. */
uniform float HandDebug;

/* Spot and area lights occupy a single tile and need the matrix they were rendered with. */
uniform mat4 LightViewProj;
/* Actor-fitted shadow maps (Blender-look), one narrow frustum per actor slot: x = tile
   (-1 = the slot carries no map), y = near, z = far, w = the frustum half-angle tangent. */
uniform vec4 ActorSlot0;
uniform vec4 ActorSlot1;
uniform vec4 ActorSlot2;
uniform vec4 ActorSlot3;
uniform mat4 ActorViewProj0;
uniform mat4 ActorViewProj1;
uniform mat4 ActorViewProj2;
uniform mat4 ActorViewProj3;
/* The shadow atlas' two global dials, stamped into the model at the top of main(): x = side of one
   tile in texels (Shadow quality — every texel-sized offset in the shadow stack is measured against
   it), y = filter step 0..3 (tap counts and kernel width). zw reserved. */
uniform vec4 AtlasInfo;

in vec2 texCoord;

out vec4 fragColor;

#moj_import <vfxlights:vfxlights_model.glsl>

/* World position of any screen point, reconstructed from its depth. */
vec3 worldPositionAt(vec2 uv, float depth)
{
    vec4 ndc = vec4(uv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 world = InvViewProj * ndc;

    return world.xyz / world.w + CameraPos;
}

vec3 worldPosition(float depth)
{
    return worldPositionAt(texCoord, depth);
}

/* Normal from depth, EDGE-AWARE. The naive cross(dFdx, dFdy) straddles the 2x2 quad, so at every
   silhouette or facet edge one derivative crosses a depth discontinuity and the normal spikes or
   flips — harmless for broad diffuse, ruinous for a grazing rim, which then outlines every cuboid
   edge and, where the normal flips away from the light, cuts black seams (the fallback "рябь").
   This picks, per axis, the neighbour that does NOT cross the discontinuity, keeping the derivative
   on one surface. Four extra depth taps; the rim is worthless without them. */
vec3 reconstructNormal(vec3 p0, float d0, float scale, out float seam)
{
    vec2 texel = scale / vec2(textureSize(DepthSampler, 0));
    float dr = texture(DepthSampler, texCoord + vec2(texel.x, 0.0)).r;
    float dl = texture(DepthSampler, texCoord - vec2(texel.x, 0.0)).r;
    float du = texture(DepthSampler, texCoord + vec2(0.0, texel.y)).r;
    float dd = texture(DepthSampler, texCoord - vec2(0.0, texel.y)).r;

    vec3 pr = worldPositionAt(texCoord + vec2(texel.x, 0.0), dr);
    vec3 pl = worldPositionAt(texCoord - vec2(texel.x, 0.0), dl);
    vec3 pu = worldPositionAt(texCoord + vec2(0.0, texel.y), du);
    vec3 pd = worldPositionAt(texCoord - vec2(0.0, texel.y), dd);

    /* Hybrid derivative. On a flat face the two neighbour depth-differences are nearly EQUAL, and
       picking the smaller (edge-aware) flip-flops on depth-buffer quantisation — the reconstructed
       normal jitters, and the grazing rim turned that jitter into speckle on plain surfaces. So use
       the CENTRAL difference there (both neighbours, quantisation cancels). Only when one side is far
       larger — a real silhouette or facet STEP — fall back to the single non-crossing neighbour, the
       edge-aware behaviour that keeps silhouettes clean. */
    float dxr = abs(dr - d0);
    float dxl = abs(d0 - dl);
    float dyu = abs(du - d0);
    float dyd = abs(d0 - dd);

    vec3 dpdx = max(dxr, dxl) > 3.0 * min(dxr, dxl) + 1e-6
        ? (dxr < dxl ? (pr - p0) : (p0 - pl)) : (pr - pl) * 0.5;
    vec3 dpdy = max(dyu, dyd) > 3.0 * min(dyu, dyd) + 1e-6
        ? (dyu < dyd ? (pu - p0) : (p0 - pd)) : (pu - pd) * 0.5;

    /* SEAM strength: the smaller-per-axis WORLD step, min'd across axes. Tiny on a face or a clean
       edge (one axis steps, the other stays on the surface); large only where BOTH axes cross — a
       model's dense cuboid seams, where the reconstructed normal is garbage and the diffuse craters to
       black. Under the pack's TAA those black cracks dance frame to frame (the "дрожание" in fallback);
       the caller blends the normal to face the camera there, a stable moderate shade instead. */
    float wx = min(length(pr - p0), length(pl - p0));
    float wy = min(length(pu - p0), length(pd - p0));

    seam = smoothstep(0.02, 0.06, min(wx, wy));

    return normalize(cross(dpdx, dpdy));
}

/* Screen-space contour edge (IRLite-style): one-sided second difference of depth over an
   outlineWidth px stencil — four opposite tap pairs, diagonals shortened by 0.707 so the radius
   stays round. Fires where this pixel sits NEARER than both neighbours of a pair: the near rim of
   a silhouette. z is the RADIAL distance to the camera (there are no projection near/far uniforms
   here); over a few px its curvature error is orders of magnitude below the 0.05 threshold.
   ONE-SIDED on purpose, and that is what keeps the ink INSIDE the actor: a background pixel beside
   the silhouette is always FARTHER than its pair, its second difference goes negative and the pair
   scores zero — the mask can only ever light up on the object side of an edge, diagonals included.
   blur opens the final window (blur 0 = the old hard 0.35..1.0 line, blur 1 = a wide soft ramp);
   it only reshapes the one-sided score, so a blurred contour still cannot step off the object.
   NOTE: one fullscreen pass per light means this is recomputed for every lamp that dials outline
   up — the price of the pass-per-light design, accepted (8 depth taps). */
const vec2 CONTOUR_TAPS[8] = vec2[8](
    vec2(1.0, 0.0), vec2(-1.0, 0.0), vec2(0.0, 1.0), vec2(0.0, -1.0),
    vec2(0.707, 0.707), vec2(-0.707, -0.707), vec2(0.707, -0.707), vec2(-0.707, 0.707)
);

float contourEdge(vec3 pos, float radius, float blur)
{
    vec2 px = radius / vec2(textureSize(DepthSampler, 0));
    float zC = length(pos - CameraPos);
    float score = 0.0;

    for (int p = 0; p < 4; p++)
    {
        vec2 uvA = texCoord + CONTOUR_TAPS[2 * p] * px;
        vec2 uvB = texCoord + CONTOUR_TAPS[2 * p + 1] * px;
        float zA = length(worldPositionAt(uvA, texture(DepthSampler, uvA).r) - CameraPos);
        float zB = length(worldPositionAt(uvB, texture(DepthSampler, uvB).r) - CameraPos);

        score += smoothstep(0.05, 0.10, (zA + zB - 2.0 * zC) / zC);
    }

    return smoothstep(mix(0.35, 0.05, blur), mix(1.0, 4.5, blur), score);
}

/* Interior (in-geometry) edge: the SAME stencil, but the pair term is absolute and windowed —
   small |d| is a crease or an overlap INSIDE the silhouette, huge |d| is the silhouette itself
   (owned by the one-sided score above; its far side must never ink, or the line steps off the
   object). This is what draws detail lines across a model's own geometry.
   The crease step is measured in WORLD depth units (see vfxInteriorPair in the shared include —
   same formula, lo = 0.012 + zC·0.004): the old relative threshold (d/zC) demanded 0.015·zC of
   crease depth, so a few-centimetre fold died beyond a few blocks ("подходишь — есть, отходишь —
   пропадает"). The floor creeps with distance only to stay above the reconstruction noise. */
float contourEdgeInner(vec3 pos, float radius, float blur)
{
    vec2 px = radius / vec2(textureSize(DepthSampler, 0));
    float zC = length(pos - CameraPos);
    float lo = 0.012 + zC * 0.004;
    float hi = 0.12 * zC;
    float score = 0.0;

    for (int p = 0; p < 4; p++)
    {
        vec2 uvA = texCoord + CONTOUR_TAPS[2 * p] * px;
        vec2 uvB = texCoord + CONTOUR_TAPS[2 * p + 1] * px;
        float zA = length(worldPositionAt(uvA, texture(DepthSampler, uvA).r) - CameraPos);
        float zB = length(worldPositionAt(uvB, texture(DepthSampler, uvB).r) - CameraPos);
        float d = abs(zA + zB - 2.0 * zC);

        score += smoothstep(lo, lo * 3.0, d) * (1.0 - smoothstep(hi, hi * 2.5, d));
    }

    return smoothstep(mix(0.35, 0.05, blur), mix(1.0, 4.5, blur), score);
}

void main()
{
    /* Before anything reads the atlas — the shared model's tile side and filter are runtime values. */
    VFX_TILE = AtlasInfo.x > 0.5 ? AtlasInfo.x : 1024.0;
    VFX_FILTER = AtlasInfo.y;

    float depth = texture(DepthSampler, texCoord).r;

    /* Sky: nothing there to light. */
    if (depth >= 1.0)
    {
        discard;
    }

    /* First-person hand: reject pixels the hand covers (see the HandDepth comment above). The
       measured rule (values debug view): after MC's pre-hand depth clear the LIVE buffer is
       exactly 1.0 across world AND actors, and only post-clear content (the hand) sits below —
       so anything < 0.9999 IS the hand region. A live-vs-snapshot compare is NOT equivalent:
       the snapshot can itself hold near geometry (the first-person body renders into it), which
       left the arm unmasked ("просвечивает"). */
    float liveDepth = HandMask > 0.5 ? texture(HandDepth, texCoord).r : 1.0;

    if (HandDebug > 0.5)
    {
        /* Values view: R = nearness of the LIVE depth, G = nearness of the SNAPSHOT (both ×6,
           clipped), B = the mask's discard decision. */
        fragColor = vec4(clamp((1.0 - liveDepth) * 6.0, 0.0, 1.0),
            clamp((1.0 - depth) * 6.0, 0.0, 1.0),
            HandMask > 0.5 && liveDepth < 0.9999 ? 1.0 : 0.0, 1.0);

        return;
    }

    if (HandMask > 0.5 && liveDepth < 0.9999)
    {
        discard;
    }

    /* Stamp the fragment at the WORLD-snapshot depth so, when the caller enables a LEQUAL depth test
       against the LIVE buffer (vanilla hand mask), the first-person hand — nearer than the world it
       hides — rejects our light. Depth-mask is off, so this only drives the test, never the buffer.
       Robust whether or not MC cleared the world depth for the hand. */
    gl_FragDepth = depth;

    vec3 pos = worldPosition(depth);

    /* Character mask: just "is a character drawn here", NOT a depth comparison against the scene.
       Comparing to the scene depth flipped across the whole model under the pack's TAA (sub-pixel
       jitter on slanted surfaces moves the depth more than any sane bias) — the actor MERZALO,
       flickered. The 0.9999 threshold sits far from that jitter, so the mask is rock-steady. A
       character behind a wall marks the wall, but portraits never occlude. */
    float charDepth = texture(CharMask, texCoord).r;
    float celMask = charDepth < 0.9999 ? 1.0 : 0.0;

    /* "Light blocks / entities" toggles: bit 1 = blocks (world), bit 2 = entities (characters). Skip
       the whole lamp on a surface it is not allowed to touch. */
    float affect = LightAffect.x;
    bool entOn = affect > 1.5;
    bool blocksOn = (affect > 0.5 && affect < 1.5) || affect > 2.5;

    if (!(celMask > 0.5 ? entOn : blocksOn))
    {
        discard;
    }

    /* No G-buffer without a shaderpack — the normal comes from depth, but EDGE-AWARE (see
       reconstructNormal): the naive derivative version spiked at every facet edge and the rim turned
       it into an outline over the whole subject with black seams where the normal flipped. */
    float seam;
    vec3 normal = reconstructNormal(pos, depth, 1.0, seam);
    /* A wider (2px) stencil for the RIM only: the grazing rim is hypersensitive to normal noise, and
       the last specks sat on genuine model corners where both 1px axes step onto different faces.
       Sampling further out averages past those corners — the rim reads a touch softer, the specks go.
       Diffuse and specular keep the sharp 1px normal. */
    float rimSeam;
    vec3 rimNormal = reconstructNormal(pos, depth, 2.0, rimSeam);

    /* Reconstruction gives an unsigned normal; flip it to face the viewer. */
    vec3 toEye = normalize(CameraPos - pos);

    if (dot(normal, toEye) < 0.0)
    {
        normal = -normal;
    }

    if (dot(rimNormal, toEye) < 0.0)
    {
        rimNormal = -rimNormal;
    }

    /* AXIS-SNAP: Minecraft geometry — the world AND an actor's cuboids — is axis-aligned, so a normal
       CLOSE to an axis IS that axis. Snapping it there kills the reconstruction noise the pack's TAA
       injects into the actor's depth (the fallback flicker and dither): a face reads one rock-steady
       normal instead of a per-frame, per-pixel wobble. A genuinely slanted surface (a posed limb) sits
       far from every axis and keeps its reconstructed normal. This is the diffuse's stability; the rim
       keeps the smooth reconstructed normal so its grazing edge stays continuous. */
    vec3 an = abs(normal);
    float mx = max(an.x, max(an.y, an.z));

    if (mx > 0.9)
    {
        normal = an.x == mx ? vec3(sign(normal.x), 0.0, 0.0)
            : (an.y == mx ? vec3(0.0, sign(normal.y), 0.0) : vec3(0.0, 0.0, sign(normal.z)));
    }

    /* At a seam the reconstructed normal is garbage; steer it toward the camera, a stable moderate
       shade rather than a black crack that dances under TAA. Faces (seam ~ 0) are untouched. */
    normal = normalize(mix(normal, toEye, seam * 0.7));
    rimNormal = normalize(mix(rimNormal, toEye, seam * 0.7));

    /* An even wider (3px) stencil for the ACTOR-fitted shadow sampling, with the diffuse's axis
       snap: the fitted map's bias/eps/contact weights all key off the surface facing, and on
       slanted skin (posed limbs) the 1-2px normals staircased per row — the "линии". Cuboid faces
       snap rock-steady, limbs read smooth. */
    float actSeam;
    vec3 actorNormal = reconstructNormal(pos, depth, 3.0, actSeam);

    if (dot(actorNormal, toEye) < 0.0)
    {
        actorNormal = -actorNormal;
    }

    vec3 aan = abs(actorNormal);
    float amx = max(aan.x, max(aan.y, aan.z));

    if (amx > 0.9)
    {
        actorNormal = aan.x == amx ? vec3(sign(actorNormal.x), 0.0, 0.0)
            : (aan.y == amx ? vec3(0.0, sign(actorNormal.y), 0.0) : vec3(0.0, 0.0, sign(actorNormal.z)));
    }

    actorNormal = normalize(mix(actorNormal, toEye, actSeam * 0.7));

    vec3 albedo = texture(DiffuseSampler, texCoord).rgb;

    float intensity = LightParams.x;
    float range = LightParams.y;
    float type = LightParams.w;

    vec3 contribution = vec3(0.0);
    /* Dev probe channels (LightAffect.y): R shadow, G range headroom, B angular reach. -1 = unset. */
    vec3 probe = vec3(-1.0);
    /* The highlight, kept separate: specular is NOT albedo-modulated the way diffuse is. */
    vec3 specular = vec3(0.0);
    /* Contour ink accumulator (outline dial, LightAffect.z): additive lines of lamp colour along
       silhouettes, NOT albedo-modulated — a contour is drawn, not shaded. The edge mask is only
       derived when this pass's lamp wants it (8 depth taps otherwise wasted); the radius is the
       lamp's own width dial (LightAffect.w, whole px) and the softness its blur (LightContour.x). */
    vec3 ink = vec3(0.0);
    float contour = LightAffect.z > 0.001
        ? contourEdge(pos, max(round(LightAffect.w), 1.0), clamp(LightContour.x, 0.0, 1.0)) : 0.0;
    /* Interior contour (LightOutlineX.x) — the in-geometry crease/overlap lines. */
    float contourIn = LightOutlineX.x > 0.001
        ? contourEdgeInner(pos, max(round(LightAffect.w), 1.0), clamp(LightContour.x, 0.0, 1.0)) : 0.0;
    vec3 viewDir = normalize(CameraPos - pos);

    if (type > 2.5)
    {
        /*
         * Ambient fill: light from everywhere, no shadow of its own. This is the other half of how a
         * scene is lit — the key light shapes, the ambient decides how deep the blacks go.
         *
         * Stays OUTSIDE the shared model (the pack's ambient differs by design: no box zone there,
         * and its occlusion proxies from the geometric normal where this one reads the frame).
         *
         * ambient mode in LightShape.w: 0 = sphere zone, 1 = box zone, 2 = hemisphere.
         */
        vec3 delta = pos - LightPos;
        float mode = LightShape.w;
        float edge = clamp(LightShape.x, 0.0, 1.0);
        float falloff;

        if (mode > 0.5 && mode < 1.5)
        {
            /* Box volume, oriented by the form's transform — a corridor of fill is a rotated box. */
            vec3 axisZ = normalize(LightDir.xyz);
            vec3 axisY = LightUp.xyz - axisZ * dot(LightUp.xyz, axisZ);

            axisY = length(axisY) < 0.0001 ? vec3(0.0, 0.0, 1.0) : normalize(axisY);

            vec3 axisX = cross(axisZ, axisY);
            vec3 local = abs(vec3(dot(delta, axisX), dot(delta, axisY), dot(delta, axisZ)))
                / max(vec3(LightShape.y, LightShape.z, LightUp.w), vec3(0.01));
            float edgeDist = max(local.x, max(local.y, local.z));

            falloff = 1.0 - smoothstep(max(1.0 - edge, 0.0), 1.0, edgeDist);
        }
        else
        {
            /* Sphere zone (and the hemisphere mode's bound): radius is the shared range field.
               The soft edge is not cosmetic — a hard boundary crawls across the floor as a visible
               line the moment anything moves. */
            float dist = length(delta);

            falloff = 1.0 - smoothstep(range * (1.0 - edge), range, dist);
        }

        vec3 fill = LightColor;

        if (mode > 1.5)
        {
            /* Hemisphere: the light's colour arrives from above, the ground bounce from below,
               blended by the surface normal — the classic outdoor fill. */
            fill = mix(LightBarn.xyz, LightColor, normal.y * 0.5 + 0.5);
        }

        contribution = fill * intensity * falloff;

        /* Occlusion dial (LightBarn.w). There is no G-buffer in this fallback, so real AO is
           impossible — cheap approximation over the already-rendered frame instead: surfaces that
           are already DARK (crevices, undersides) keep the least fill, lit surfaces are untouched.
           ★The catch: an absolute-luma proxy cannot tell "dark because crevice" from "dark because
           NIGHT" — a cave at luma 0.02 reads as one giant crevice (crevice ≈ 0.006) and the fill
           died to ~1% of itself exactly where it exists for ("свет не светит, а красит"). The
           proxy only has meaning against a LIT frame, so the occlusion's effect fades in with the
           frame's own brightness: near-black scene → no occlusion, the fill lands at full strength;
           a lit scene keeps the crevice read untouched. */
        float crevice = smoothstep(0.0, 0.35, dot(albedo, vec3(0.333)));
        float litScene = smoothstep(0.01, 0.06, dot(albedo, vec3(0.333)));

        contribution *= mix(1.0, mix(1.0, crevice, LightBarn.w), litScene);
    }
    else
    {
        vec3 toLight = LightPos - pos;
        float dist = length(toLight);

        if (dist > range)
        {
            discard;
        }

        /* Everything below is the shared model — this block only TRANSLATES the uniforms. */
        VfxLightDesc Ld;

        Ld.pos = LightPos;
        Ld.type = type;
        Ld.color = LightColor;
        Ld.intensity = intensity;
        Ld.dir = LightDir.xyz;
        Ld.range = range;
        Ld.up = LightUp.xyz;
        Ld.srcRadius = LightParams.z;
        Ld.cosOuter = LightDir.w;
        Ld.cosInner = LightShape.x;
        Ld.shapeWH = LightShape.yz;
        Ld.shapeId = mod(LightShape.w, 10.0);
        Ld.twoSided = LightShape.w >= 10.0;
        Ld.spread = LightUp.w;
        Ld.thickness = LightShape.z;
        Ld.barn = LightBarn;
        Ld.barnSoft = LightShape.x;
        Ld.firstTile = ShadowSlot.x;
        /* Point AND area read the six-face cube; only a NARROW spot has a single-cone map — a wide
           one (negative ShadowSlot.y) is cube-mapped too, or the cone outruns the projection. */
        Ld.cube = type < 0.5 || type > 1.5 || ShadowSlot.y < 0.0;
        Ld.across = abs(ShadowSlot.y);
        Ld.near = ShadowSlot.z;
        Ld.shadowMatrix = LightViewProj;
        Ld.softDial = ShadowSlot.w;
        Ld.disp = Dispersion.x;
        Ld.dispScale = Dispersion.y;
        Ld.dispPhase = Dispersion.z;
        Ld.waterDist = Dispersion.w;
        Ld.waterY = LightExtra.z;
        Ld.sss = LightExtra.x;
        Ld.iesProfile = LightExtra.y;
        Ld.rimKnob = LightExtra.w;
        Ld.rimWidth = LightContour.y;
        Ld.falloffMode = LightFalloff;
        Ld.specRim = SpecRim;
        Ld.actorTile0 = ActorSlot0.x;
        Ld.actorNear0 = ActorSlot0.y;
        Ld.actorFar0 = ActorSlot0.z;
        Ld.actorTan0 = ActorSlot0.w;
        Ld.actorMatrix0 = ActorViewProj0;
        Ld.actorTile1 = ActorSlot1.x;
        Ld.actorNear1 = ActorSlot1.y;
        Ld.actorFar1 = ActorSlot1.z;
        Ld.actorTan1 = ActorSlot1.w;
        Ld.actorMatrix1 = ActorViewProj1;
        Ld.actorTile2 = ActorSlot2.x;
        Ld.actorNear2 = ActorSlot2.y;
        Ld.actorFar2 = ActorSlot2.z;
        Ld.actorTan2 = ActorSlot2.w;
        Ld.actorMatrix2 = ActorViewProj2;
        Ld.actorTile3 = ActorSlot3.x;
        Ld.actorNear3 = ActorSlot3.y;
        Ld.actorFar3 = ActorSlot3.z;
        Ld.actorTan3 = ActorSlot3.w;
        Ld.actorMatrix3 = ActorViewProj3;
        Ld.cel = LightCel;

        /* No materials without a pack: a fixed moderate gloss, spec gain calibrated against the
           partial albedo tint below. slopeMax 1.0 = flat bias — the depth-reconstructed normals
           are too noisy to steer the slope-scaled kind. */
        VfxSurface S;

        S.pos = pos;
        S.shadowPos = pos;
        S.n = normal;
        S.geoN = normal;
        S.rimN = rimNormal;
        S.actN = actorNormal;
        S.viewDir = viewDir;
        S.smoothness = 0.42;
        S.specGain = 0.6;
        S.slopeMax = 1.0;

        VfxShadeOut R;

        vfxShadeLight(ShadowAtlas, ShadowColor, Ld, S, false,
            LightCel.x > 0.5 && celMask > 0.5, R);

        contribution = R.diffuse;
        specular = R.spec;

        /* ★TEMP diagnostic (-Dvfxlights.water.debug): paint R.shade — shadow map x water factor —
           localises the water "circle": in shade vs in the diffuse/specular terms. */
        if (WaterDebug > 0.5)
        {
            fragColor = vec4(R.shade, 1.0);

            return;
        }

        /* Broad night tail (fallback-only, POINT lamps only): the fill law (1−d/range)⁴ dies to
           black within a few blocks while a real lamp lifts the room — the vanilla sea-lantern
           feel. Kept WEAK and quadratic after the v4 overshoot ("углы пропали"): the mid-field
           gets a gentle lift, corners stay near-black like vanilla, and the tight night gate
           keeps twilight from inheriting a half-strength tail. The tail rides the lamp's own
           colour and shadow (a wall still blocks the lift). Spots are excluded — the tail would
           bleed past the cone; area lamps are physical by design. */
        if (type < 0.5)
        {
            float night = 1.0 - smoothstep(0.05, 0.15, dot(albedo, vec3(0.2126, 0.7152, 0.0722)));
            float tail = 1.0 - dist / range;

            contribution += LightColor * intensity * R.shade * (tail * tail * 0.06) * night;
        }

        /* PROBE: the area branch shows (shade, range headroom, form factor); point/spot shows
           (ndl, atten x cone, shade) — R = ndl is the prime suspect when a cut goes black where
           the light otherwise reaches: it means the reconstructed normal is wrong. */
        float shadeMax = max(R.shade.r, max(R.shade.g, R.shade.b));

        probe = type > 1.5
            ? vec3(shadeMax, clamp(1.0 - dist / range, 0.0, 1.0), clamp(R.factor, 0.0, 1.0))
            : vec3(clamp(R.ndl, 0.0, 1.0), clamp(R.atten, 0.0, 1.0), shadeMax);

        /* Contour ink: the model's gates (R.inkBase = shade x Fresnel limb x backlight x reach)
           times this pass's own screen-space edge score at the lamp's radius/blur. Light-gated by
           shade: a contour shows only where this lamp's light actually lands — nothing glows in
           the dark.
           Target (LightOutlineX.y): 0 = world and models, 1 = models only (the character mask must
           cover the pixel — LightOutlineX.z says the mask exists at all), 2 = world only.
           Interior lines run on R.inkInner: shade x reach, no facing gates — a crease line is
           drawn whichever way its surface turns. */
        float inkTarget = LightOutlineX.y;
        bool inkTargetOk = true;

        if (inkTarget > 0.5)
        {
            float charHere = LightOutlineX.z > 0.5 && texture(CharMask, texCoord).r <= depth + 0.0002 ? 1.0 : 0.0;

            inkTargetOk = inkTarget < 1.5 ? charHere > 0.5 : charHere < 0.5;
        }

        if (LightAffect.z > 0.001 && contour > 0.001 && inkTargetOk)
        {
            vec3 inkL = LightColor * R.inkBase * (contour * LightAffect.z * 1.5);

            ink += LightOutlineX.w < 0.5 ? inkL
                : vfxInkBlendDelta(texture(DiffuseSampler, texCoord).rgb, inkL, LightOutlineX.w);
        }

        if (LightOutlineX.x > 0.001 && contourIn > 0.001 && inkTargetOk)
        {
            vec3 inkL = LightColor * R.inkInner * (contourIn * LightOutlineX.x * 1.5);

            ink += LightOutlineX.w < 0.5 ? inkL
                : vfxInkBlendDelta(texture(DiffuseSampler, texCoord).rgb, inkL, LightOutlineX.w);
        }
    }

    /* Group filter (LightContour.z): this lamp lights blocks AND actors of its replay categories —
     * other actors stay dark but still CAST its shadows onto the blocks (otherwise there is no lit
     * ground for the shadow to read on). CharMask tells "an actor is here", GroupMaskTex tells "an
     * APPROVED actor is here"; both are cleared to 1.0, so unmarked pixels fail their compare. */
    if (LightContour.z > 0.5)
    {
        /* Without a character mask there are no actors to gate: everything is a block, all allowed. */
        float actorHere = LightContour.w > 0.5 && texture(CharMask, texCoord).r <= depth + 0.0002 ? 1.0 : 0.0;
        float selectedHere = texture(GroupMaskTex, texCoord).r <= depth + 0.0002 ? 1.0 : 0.0;
        float allowed = actorHere < 0.5 ? 1.0 : selectedHere;

        contribution *= allowed;
        specular *= allowed;
        ink *= allowed;
    }

    /* ★Dev probe (LightAffect.y = 1, from the VFXLIGHTS_PROBE env): paint the gates instead of the
       light. The MAGENTA floor is the fallback path's signature — if probe pixels carry it while a
       patched pack is active, BOTH backends are shading the same frame (double-light). */
    if (LightAffect.y > 0.5)
    {
        fragColor = vec4(max(probe, vec3(0.25, 0.0, 0.25)) * (probe.x < 0.0 ? 0.0 : 0.9), 1.0);

        return;
    }

    /* ★TEMP diagnostic (-Dvfxlights.light.debug, LightDebug uniform): paint the RAW diffuse
       contribution — before litBase, before the shoulder. A strong pool here with a tinted
       result on screen localises the complaint to the composite math (litBase), a weak/black
       pool to the light computation itself. */
    if (LightDebug > 0.5)
    {
        fragColor = vec4(contribution, 1.0);

        return;
    }

    /* Albedo is unknown — the frame is already tone-mapped and lit. Pure modulation (albedo × light)
       keeps lit surfaces honest, but in a dark scene the frame is ~black and the lamp multiplies
       into nothing. The old answer was a PER-CHANNEL floor, max(albedo, 0.3): in the dark it
       flattened every texel to one grey, and even in daylight it raised each texel's low channels
       to 0.3 — a saturated red block went pink-grey under the lamp. Both read as the lamp PAINTING
       the surface instead of lighting it ("красит не светит").
       ★v11 CARRIER — back to the exact v4 carrier the user benchmarked as crisp ("в v4 не было
       ни мыла, ни радужки"): pure hue direction (albedo / own luma), NO spatial terms at all.
       Every spatial divisor tried since (v5 0.75px cross, v6–v9 3px ring at any blend weight,
       v9 1.25px dark-zone blur) reads as halo mud or мыло at this scene's texel scale (ring ≈
       texel size); the per-texel ratio carries hue per texel exactly and leaves the luma field
       to the contribution gradient — smooth, noise-free, sharp. Two changes vs v4: floor 0.035
       instead of 0.02 (gain ×8.6, not ×15 — the v4 speckle halves) and the deband grain below. */
    float texLuma = max(dot(albedo, vec3(0.2126, 0.7152, 0.0722)), 1e-4);
    /* ★v11 — back to the exact v4 carrier the user benchmarked as crisp: pure hue direction
       (albedo / own luma), NO spatial terms at all. Every spatial divisor (3px ring, any blend
       weight) reads as halo mud at this scene's texel scale (ring ≈ texel size), and any blur
       reads as мыло; the per-texel ratio carries hue per texel exactly and leaves the luma
       field to the contribution gradient — smooth, noise-free, sharp. The only change vs v4:
       floor 0.035 instead of 0.02 (gain ×8.6, not ×15 — the v4 speckle halves), and the deband
       grain stays on the deposit. */
    float texelGain = 0.3 / max(texLuma, 0.035);
    vec3 litBase = albedo * texelGain;

    /* ★Scotopic knead (the "перенасыщено" report): the hue-preserving lift keeps the full chroma
       ratio while raising luma up to ×8.6, and amplified colour reads stronger than the same hue
       at daylight exposure — night vision itself is low-chroma besides. Knead up to 25% of the
       chroma toward the texel's luma at maximum night gain, fading to zero by gain ×1, so the
       day path stays bit-exact. */
    float satKnead = clamp((texelGain - 1.0) / 7.6, 0.0, 1.0) * 0.25;

    litBase = mix(litBase, vec3(dot(litBase, vec3(0.2126, 0.7152, 0.0722))), satKnead);

    vec3 addTex = litBase * contribution * VFX_GAIN;

    /* v7: the deposit is purely MULTIPLICATIVE — litBase × contribution, the vanilla lightmap rule
       (surface hue = albedo × light colour; contribution already carries LightColor × intensity).
       The dom/outHue machinery is gone: dom saturates at 1 across the WHOLE night pool, not just
       the core, so the v6 exposure boost blew out the entire room (the 05.10 white-blob screenshot,
       "жёстко мылит") and the hue walk kept repainting surfaces no matter its weight. A hot core,
       when the user cranks intensity, is now the AgX shoulder's job alone — hue and texture survive
       until REAL overexposure. Specular keeps its partial albedo tint (a glint is the source's
       colour, but fully untinted floats like a sticker on dark surfaces). */
    vec3 lightSum = addTex + specular * mix(albedo, vec3(1.0), 0.6) * VFX_GAIN;

    /* Hot core without the clamp: the fallback has no tonemapper of its own, so the lamp's
       contribution gets the AgX-flavoured shoulder — the excess above white is compressed and
       washed toward white instead of clipping into a saturated blob. Per-lamp, pre-additive;
       ink is drawn, not shaded, and stays crisp. */
    vec3 outColor = vfxAgxShoulder(lightSum);

    /* ★DEBAND (the "радужка/постеризация" fix): the carrier's texture is reconstructed from a
       NEAR-BLACK 8-bit readback, so its per-channel quantization (±½ LSB) is amplified by the
       exposure lift (0.3/texLuma, up to ×8.6) into visible blotchy bands. No blur can remove
       them without killing the texture (the "мыло" tradeoff) — decorrelate instead: triangular
       IGN grain matched to the local amplified step turns the bands into imperceptible
       film-like noise. Amplitude tracks the pool: it dies to zero where the lamp does not
       shine, so dark/unlit pixels keep their original frame verbatim. Same IGN idiom as the
       volumetric pass. */
    float ignA = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));
    float ignB = fract(52.9829189 * fract(dot(gl_FragCoord.xy + 0.5, vec2(0.00583715, 0.06711056))));
    float debandStep = texelGain * VFX_GAIN * dot(contribution, vec3(0.333333)) * (0.5 / 255.0);
    /* LUMA grain, one value on all channels: the per-channel decorrelated version read as COLOURED
       speckle on dim pools (the "радужка и дизеринг" report) — the cure outshone the banding. */
    vec3 deband = vec3(ignA + ignB - 1.0);

    outColor += deband * debandStep + ink;

    /* ★NaN/Inf guard (vfxGuard): the depth-reconstructed normal is normalize(cross(...)); on a
       degenerate patch that is normalize(0) = NaN, and one NaN added over the frame reads as a fully
       BLACK screen once a lamp starts drawing — measured in third-person. A bad pixel costs its own
       light, not the frame. */
    fragColor = vec4(vfxGuard(outColor), 1.0);

    /* ★TEMP diagnostic (-Dvfxlights.shadow.gatedebug, GateDebug uniform): paint the actor-map
       gates the model stashed this pixel — R = faceW (edge-on-to-light gate), G = feather (cone
       edge), B = reach; white = no actor map spoke. Additive pass: one lamp reads cleanest. */
    if (GateDebug > 0.5)
    {
        fragColor = vec4(vfxActorGateDbg, 1.0);
    }
}
