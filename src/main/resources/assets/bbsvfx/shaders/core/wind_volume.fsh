#version 150

/* Screen-space VOLUMETRIC ambient wind — silky noise streaks flowing through the zone (true 3D fractal
 * density, not billboards). Per pixel: reconstruct the view ray, clip it to the zone cylinder (radius
 * uReach, height slab [0, uHeight] in the form's local frame), march that segment sampling a 3D fbm whose
 * domain is STRETCHED along the wind direction (elongated wisps) and ADVECTED with time (the flow), and
 * front-to-back composite. Occluded correctly by scene depth; drawn post-pack (Iris-safe). Same deferred
 * infra as SmokeVolume/DomeVolume. Everything lives in the form's local frame via the form matrix — no
 * world origin, no heightmap: the terrain occludes the wisps naturally through the depth buffer. */

uniform sampler2D Sampler0;   /* scene colour (blitted frame) */
uniform sampler2D Sampler1;   /* scene depth (world, snapshotted at LAST) */
uniform sampler2D Sampler2;   /* first-person HAND depth (hand pass cleared depth; background = 1.0) */

uniform mat4  uInvProj;
uniform vec3  uOrigin;        /* zone base centre, VIEW space */
uniform vec3  uUp;            /* form up axis, VIEW space (normalized) */
uniform vec3  uTanU;
uniform vec3  uTanV;
uniform float uReach;         /* zone radius (blocks) */
uniform float uHeight;        /* wind layer thickness above the base (blocks) */
uniform float uDensity;       /* opacity per block of wisp */
uniform float uDust;          /* ground-hugging brown dust amount (0 = off) */
uniform float uMode;          /* 0 = directional slab, 1 = vortex funnel */
uniform float uCoreR;         /* vortex: funnel radius at the ground */
uniform float uFlare;         /* vortex: how much the radius grows toward the top */
uniform float uScale;         /* fractal noise frequency */
uniform float uSpeed;         /* flow speed, blocks per second */
uniform vec2  uWind;          /* wind direction in the local (u,v) ground basis, normalized */
uniform float uTime;
uniform vec3  uColor;

const int STEPS = 32;
const int STEPS_VORTEX = 72;

in vec2 vUv;

out vec4 fragColor;

float hash13(vec3 p)
{
    p = fract(p * 0.1031);
    p += dot(p, p.zyx + 31.32);

    return fract((p.x + p.y) * p.z);
}

float vnoise3(vec3 x)
{
    vec3 i = floor(x), f = fract(x);

    f = f * f * (3.0 - 2.0 * f);

    float n000 = hash13(i + vec3(0.0, 0.0, 0.0));
    float n100 = hash13(i + vec3(1.0, 0.0, 0.0));
    float n010 = hash13(i + vec3(0.0, 1.0, 0.0));
    float n110 = hash13(i + vec3(1.0, 1.0, 0.0));
    float n001 = hash13(i + vec3(0.0, 0.0, 1.0));
    float n101 = hash13(i + vec3(1.0, 0.0, 1.0));
    float n011 = hash13(i + vec3(0.0, 1.0, 1.0));
    float n111 = hash13(i + vec3(1.0, 1.0, 1.0));

    return mix(mix(mix(n000, n100, f.x), mix(n010, n110, f.x), f.y),
               mix(mix(n001, n101, f.x), mix(n011, n111, f.x), f.y), f.z);
}

float fbm3d(vec3 p)
{
    float s = 0.0, a = 0.5;

    for (int i = 0; i < 4; i++)
    {
        s += a * vnoise3(p);
        p = p * 2.02 + vec3(11.0, 17.0, 23.0);
        a *= 0.5;
    }

    return s;
}

vec3 viewPos(vec2 uv, float d)
{
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 v = uInvProj * ndc;

    return v.xyz / v.w;
}

/* VORTEX (tornado) density: a funnel whose radius flares with height. The wisps live in a SHELL around the
 * funnel wall (that's what reads as a condensation funnel — a solid cylinder looks like a pillar, not a
 * tornado), and the noise is sampled in CYLINDRICAL coords with the angle advanced by time — faster near the
 * axis (Rankine-like) — so the bands spiral up and around instead of sliding sideways. */
vec2 densityVortex(float u, float v, float h)
{
    float r = sqrt(u * u + v * v);
    float edge = smoothstep(uReach, uReach * 0.7, r);

    if (edge <= 0.001)
    {
        return vec2(0.0);
    }

    /* Funnel radius: narrow at the ground, flaring toward the top. The exponent keeps the lower half a
     * near-column (a tornado is a column that opens up high), instead of a searchlight cone. */
    float t = clamp(h / uHeight, 0.0, 1.0);
    float rf = uCoreR * (1.0 + uFlare * pow(t, 1.7));

    /* WALL: a smooth GAUSSIAN shell instead of a smoothstep band. The funnel is hollow — a filled cone
     * reads as a pillar of fog, and hard band edges alias into speckle at these step sizes. */
    float d = (r - rf) / max(rf * 0.28, 0.6);
    float band = exp(-d * d * 2.2);

    /* Swirl: angular advance ∝ time, faster near the axis (Rankine); the height term corkscrews it. */
    float ang = atan(v, u);
    float phi = ang + uTime * uSpeed * (uCoreR / max(r, 2.0)) * 0.5 + h * 0.10;

    /* ★EXPLICIT helical bands — this is what actually reads as ROTATION. Pure fbm at these march step
     * sizes just looks like static; the sine gives large, coherent stripes that visibly spiral upward. */
    float helix = 0.5 + 0.5 * sin(phi * 3.0 - h * 0.32);

    /* LOW-frequency noise only, for organic breakup. High frequencies = per-pixel speckle. */
    vec3 nc = vec3(phi * 0.55, h * 0.07 - uTime * uSpeed * 0.05, r * 0.05) * uScale;
    float n = smoothstep(0.28, 0.86, fbm3d(nc));

    /* Fade the very top into the sky and glue the foot to the ground. */
    float hf = smoothstep(uHeight, uHeight * 0.62, h) * smoothstep(-0.6, 0.6, h);

    float wisp = band * edge * hf * (0.25 + 0.75 * helix) * (0.45 + 0.75 * n);

    /* Debris skirt: dust kicked up around the funnel FOOT. ★It must be a RING with real structure, not a
     * filled disc — a uniform ground-hugging slab is marched lengthways by near-horizontal rays, saturates,
     * and paints a flat solid circle on the terrain (which is exactly what it used to do). */
    float dust = 0.0;

    if (uDust > 0.0)
    {
        /* Low skirt, tapering upward. */
        float dh = smoothstep(uHeight * 0.18, -0.3, h);

        /* Ring hugging the funnel base, falling off BOTH inward and outward. */
        float rBase = max(uCoreR, 1.0);
        float dr = (r - rBase * 1.25) / (rBase * 1.10);
        float ring = exp(-dr * dr);

        /* Spiral structure + livelier noise so it reads as swirling debris, not painted ground. */
        float dHelix = 0.40 + 0.60 * sin(phi * 2.2 - h * 0.5 + r * 0.12);
        vec3 dc = vec3(phi * 1.1, h * 0.35 - uTime * uSpeed * 0.12, r * 0.13) * uScale
            + vec3(5.1, 9.3, 2.7);
        float dn = smoothstep(0.35, 0.78, fbm3d(dc));

        dust = ring * dh * dHelix * dn * edge;
    }

    return vec2(wisp, dust);
}

/* Wisp density at a zone-local point. The noise domain is compressed ALONG the wind (~8× longer features =
 * streaks) and advected by uTime·uSpeed; a second, faster and finer layer adds gust parallax so the flow
 * does not read as one rigid sheet. */
/* .x = airborne wisp density (white), .y = ground-hugging dust density (brown). */
vec2 density(float u, float v, float h)
{
    if (h < 0.0 || h > uHeight)
    {
        return vec2(0.0);
    }

    if (uMode > 0.5)
    {
        return densityVortex(u, v, h);
    }

    float rr = sqrt(u * u + v * v);
    float edge = smoothstep(uReach, uReach * 0.7, rr);

    if (edge <= 0.001)
    {
        return vec2(0.0);
    }

    float along = u * uWind.x + v * uWind.y;
    float acrs = -u * uWind.y + v * uWind.x;

    /* WISPS: streaks, denser near the ground, fading toward the layer top. */
    float hf = smoothstep(uHeight, uHeight * 0.35, h) * smoothstep(-0.4, 0.6, h);

    vec3 nc = vec3((along - uTime * uSpeed) * 0.12, acrs * 0.85, h * 0.65) * uScale;
    float w1 = smoothstep(0.52, 0.80, fbm3d(nc));

    vec3 nc2 = vec3((along - uTime * uSpeed * 1.6) * 0.10, acrs * 1.10, h * 0.80) * (uScale * 1.9)
        + vec3(31.7, 11.3, 7.9);
    float w2 = smoothstep(0.58, 0.85, fbm3d(nc2));

    float wisp = (w1 + 0.55 * w2) * edge * hf;

    /* DUST: low brown haze skittering along the ground, coarser and slower than the wisps. */
    float dust = 0.0;

    if (uDust > 0.0)
    {
        float dh = smoothstep(uHeight * 0.5, -0.3, h);
        vec3 dc = vec3((along - uTime * uSpeed * 0.8) * 0.07, acrs * 0.5, h * 0.4) * uScale
            + vec3(5.1, 9.3, 2.7);
        /* Shape only — the uDust amount is applied in the accumulation so dust is INDEPENDENT of the
         * streak density (uDensity). */
        dust = smoothstep(0.40, 0.72, fbm3d(dc)) * dh * edge;
    }

    return vec2(wisp, dust);
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    vec3 p = viewPos(vUv, depth);
    float sceneDist = (depth >= 1.0) ? 1e9 : length(p);
    vec3 dir = normalize(p);

    /* The first-person hand is drawn after the world depth snapshot, so it isn't in Sampler1 — clip the
     * march at it too, or streaks (and the world seen through the hand) show through the hand. */
    float handDepth = texture(Sampler2, vUv).r;

    if (handDepth < 1.0)
    {
        sceneDist = min(sceneDist, length(viewPos(vUv, handDepth)));
    }

    /* Zone-local frame; camera at the view origin. */
    vec3 oc = -uOrigin;
    float ou = dot(oc, uTanU), ov = dot(oc, uTanV), oh = dot(oc, uUp);
    float du = dot(dir, uTanU), dv = dot(dir, uTanV), dh = dot(dir, uUp);

    /* Ray vs infinite cylinder (radius uReach) around the h axis. */
    float a = du * du + dv * dv;
    float b = 2.0 * (ou * du + ov * dv);
    float c = ou * ou + ov * ov - uReach * uReach;
    float disc = b * b - 4.0 * a * c;

    if (disc < 0.0 || a < 1e-6)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    float sq = sqrt(disc);
    float t0 = (-b - sq) / (2.0 * a);
    float t1 = (-b + sq) / (2.0 * a);

    /* Clip to the height slab h ∈ [0, uHeight]. */
    float th0 = (0.0 - oh) / dh;
    float th1 = (uHeight - oh) / dh;

    if (th0 > th1)
    {
        float tmp = th0; th0 = th1; th1 = tmp;
    }

    float tEnter = max(max(t0, th0), 0.0);
    float tExit = min(min(t1, th1), sceneDist);

    if (tExit <= tEnter)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    /* ★The vortex marches a MUCH longer segment (a tall funnel) than the directional slab, so 32 steps put
     * barely one sample across the funnel wall — that read as per-pixel static. Give the vortex a finer
     * march; the thin slab stays cheap. */
    int steps = uMode > 0.5 ? STEPS_VORTEX : STEPS;

    float stepLen = (tExit - tEnter) / float(steps);
    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453 + uTime);
    float t = tEnter + stepLen * dither;

    vec3 accum = vec3(0.0);
    float alpha = 0.0;

    for (int i = 0; i < STEPS_VORTEX; i++)
    {
        if (i >= steps)
        {
            break;
        }

        vec3 sp = dir * t;
        vec3 rel = sp - uOrigin;
        float lu = dot(rel, uTanU);
        float lv = dot(rel, uTanV);
        float lh = dot(rel, uUp);

        vec2 dens = density(lu, lv, lh);

        if (dens.x + dens.y > 0.001)
        {
            /* Wisps (uColor) scale with uDensity (streaks slider); dust (brown) scales with uDust — the two
             * sliders are fully independent. */
            float wa = clamp(dens.x * uDensity * stepLen, 0.0, 1.0);
            accum += (1.0 - alpha) * uColor * wa;
            alpha += (1.0 - alpha) * wa;

            float da = clamp(dens.y * uDust * 1.4 * stepLen, 0.0, 1.0);
            accum += (1.0 - alpha) * vec3(0.62, 0.54, 0.42) * da;
            alpha += (1.0 - alpha) * da;

            if (alpha > 0.985)
            {
                break;
            }
        }

        t += stepLen;
    }

    vec3 outc = scene * (1.0 - alpha) + accum;

    fragColor = vec4(outc, 1.0);
}
