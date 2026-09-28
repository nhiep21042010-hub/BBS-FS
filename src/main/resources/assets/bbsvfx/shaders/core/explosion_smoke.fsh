#version 150

/* The explosion's HERO MUSHROOM as a screen-space VOLUME — a true 3D fractal-noise cloud instead of a swarm
 * of billboards. Per pixel: reconstruct the view ray, clip it to the mushroom's bounding cylinder, march that
 * segment sampling a signed-distance mushroom (a squashed cap sphere unioned with a flaring stem, both
 * displaced by 3D fbm so the silhouette is cauliflower rather than geometric) and composite front-to-back.
 *
 * ★The FIRE lives INSIDE this same field as an emissive term driven by uHeat, so the hot core cools into the
 * smoke continuously — the billboard version cut the flames off at a heat threshold while they were still at
 * ~19% size and blinked them out in one frame. Here there is nothing to pop: the emission simply decays to
 * zero inside a cloud that is already there.
 *
 * Deferred like the other volumes: queued during the form pass, depth snapshotted at LAST, marched and
 * composited after the shaderpack, so packs never alpha-test it into "black cores". */

uniform sampler2D Sampler0;   /* scene colour (blitted frame) */
uniform sampler2D Sampler1;   /* scene depth (world, snapshotted at LAST) */
uniform sampler2D Sampler2;   /* first-person HAND depth (background = 1.0) */

uniform mat4  uInvProj;
uniform vec3  uOrigin;        /* epicentre, VIEW space */
uniform vec3  uUp;            /* form up axis, VIEW space (normalized) */
uniform vec3  uTanU;
uniform vec3  uTanV;

uniform float uCapY;          /* cap centre height above the epicentre */
uniform float uCapR;          /* cap radius */
uniform float uStemR;         /* stem radius */
uniform float uDensity;       /* opacity per marched block */
uniform float uScale;         /* fractal frequency */
uniform float uRise;          /* how fast the noise domain drifts upward (the boil) */
uniform float uHeat;          /* 0..1 emissive hot core (fire) */
uniform float uBallR;         /* blast fireball radius (0 = off) */
uniform float uBallHeat;      /* blast fireball emissive amount */
uniform float uEmit;          /* emissive gain */
uniform float uFade;          /* master fade */
uniform float uTime;
uniform vec3  uColor;         /* smoke tint */
uniform vec2  uDrift;         /* horizontal drift of the column (wind) */
uniform float uShape;         /* 0 = mushroom (cap + stem), 1 = sphere (fireball) */
uniform float uSphereRadius;  /* sphere shape: ball radius */
uniform float uSphereHeat;    /* sphere shape: emissive fire amount (0 = smoke only) */

const int STEPS = 56;

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
        p = p * 2.03 + vec3(11.0, 17.0, 23.0);
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

/* The mushroom's ANALYTIC signed distance (no fbm — the noise is the expensive part). Used for the cheap
 * self-shading probe: re-evaluating this a few blocks toward the light costs almost nothing, while a real
 * secondary march would double the sample count. */
float bodySdf(float u, float v, float h)
{
    float r = sqrt(u * u + v * v);
    float dyC = (h - uCapY) / 0.72;
    float dCap = sqrt(r * r + dyC * dyC) - uCapR;
    float f = clamp(h / max(uCapY, 0.001), 0.0, 1.0);
    float rs = uStemR * (1.0 + 0.9 * (1.0 - f));

    return min(dCap, max(r - rs, h - uCapY));
}

/* .x = smoke density, .y = emissive (fire) density, .z = shading multiplier. */
vec3 density(float u, float v, float h)
{
    /* SPHERE shape: no cap/stem at all — a single displaced fireball hovering at the epicentre, its
     * fire the same turbulent emissive term as the mushroom's core (driven by uSphereHeat). */
    if (uShape > 0.5)
    {
        float sr = max(uSphereRadius, 0.5);
        vec3 q = vec3(u - uDrift.x, h - (sr * 0.55 + 1.0), v - uDrift.y);
        float distS = length(q);

        /* Cauliflower displacement, the same noise family as the mushroom so the two shapes read as
         * one effect; the domain drifts DOWN so the ball appears to boil upward. */
        float sn = fbm3d((q + vec3(0.0, -uTime * uRise, 0.0)) * (0.09 * uScale));
        float sd = distS - (sr + (sn - 0.45) * sr * 0.55);
        float sdens = 1.0 - smoothstep(-sr * 0.45, 0.0, sd);

        if (sdens <= 0.001)
        {
            return vec3(0.0);
        }

        /* SELF-SHADING: probe the ball toward the light — buried samples read dark. */
        const vec3 SL = vec3(0.35, 0.87, 0.34);
        float sprobe = length(q + SL * sr * 0.6) - sr;
        float sburied = clamp(-sprobe / max(sr * 0.9, 0.001), 0.0, 1.0);
        float sshade = mix(1.0, 0.32, sburied);

        sdens *= smoothstep(-1.0, 1.2, h);

        /* Fire: a turbulent emissive core — ridged filaments clumped into licks, like the mushroom. */
        float score = 1.0 - smoothstep(0.0, sr * 1.15, distS);

        score *= score;

        vec3 sfc = (q + vec3(0.0, -uTime * uRise * 2.4, 0.0)) * (0.13 * uScale) + vec3(7.3, 2.1, 5.7);
        float sridged = 1.0 - abs(fbm3d(sfc) * 2.0 - 1.0);
        float sfn = smoothstep(0.34, 0.96, sridged);
        vec3 sfc2 = (q + vec3(0.0, -uTime * uRise * 1.3, 0.0)) * (0.06 * uScale) + vec3(2.7, 8.1, 4.3);
        float slick = smoothstep(0.35, 0.80, fbm3d(sfc2));

        float shot = score * uSphereHeat * sdens * sfn * (0.45 + 0.85 * slick) * 3.2;

        /* The burning core lights the smoke shell around it. */
        shot += uSphereHeat * (1.0 - smoothstep(0.0, sr * 2.1, distS)) * sdens * 0.30;

        return vec3(sdens * uFade, shot * uFade, sshade);
    }

    /* The column leans with the ambient wind, more the higher it goes. */
    float lean = h / max(uCapY, 0.001);

    u -= uDrift.x * lean;
    v -= uDrift.y * lean;

    float r = sqrt(u * u + v * v);

    /* Cauliflower displacement. The domain drifts DOWN over time so the cloud appears to boil upward. */
    vec3 nc = vec3(u, h - uTime * uRise, v) * (0.055 * uScale);
    float n = fbm3d(nc);
    float disp = (n - 0.45) * uCapR * 0.60;

    /* CAP: a vertically squashed sphere. */
    float dyC = (h - uCapY) / 0.72;
    float distCap = sqrt(r * r + dyC * dyC);
    float dCap = distCap - (uCapR + disp);

    /* STEM: a cylinder that flares toward the ground, cut off under the cap. */
    float f = clamp(h / max(uCapY, 0.001), 0.0, 1.0);
    float rs = uStemR * (1.0 + 0.9 * (1.0 - f));
    float dStem = max(r - (rs + disp * 0.5), h - uCapY);

    float d = min(dCap, dStem);

    /* Soft shell — a wide falloff keeps the edges wispy instead of solid. */
    float dens = 1.0 - smoothstep(-uCapR * 0.40, 0.0, d);

    if (dens <= 0.001)
    {
        return vec3(0.0);
    }

    /* SELF-SHADING: probe the analytic body a few blocks toward the light. Still deep inside → this sample
     * is buried under a lot of smoke and reads dark; near the surface → lit. Without this the mushroom is a
     * uniformly bright blob with no volume to it. */
    const vec3 L = vec3(0.35, 0.87, 0.34);
    float probe = bodySdf(u + L.x * uCapR * 0.6, v + L.z * uCapR * 0.6, h + L.y * uCapR * 0.6);
    float buried = clamp(-probe / max(uCapR * 0.9, 0.001), 0.0, 1.0);
    float shade = mix(1.0, 0.32, buried);

    /* Ground clip + top dissolve. */
    dens *= smoothstep(-1.0, 1.2, h);

    /* Hot core: emissive, concentrated at the cap centre and decaying with uHeat. Because it is a term of
     * the SAME field, the fire never leaves a hole when it dies — the smoke is already occupying it.
     *
     * ★It must be TURBULENT, not a smooth radial blob: a clean falloff reads as a lamp behind fog and the
     * fire visibly separates from the fractal cloud around it. A second, finer and faster-drifting noise
     * breaks the emission into tongues that follow the same boil as the smoke. */
    float core = 1.0 - smoothstep(0.0, uCapR * 1.20, distCap);

    core *= core;

    /* ★RIDGED turbulence, not plain fbm. `1 - |2n-1|` collapses the noise onto its mid-level crests, which
     * gives thin FILAMENTS — flame tongues. Plain fbm only ever made a soft glow, which is why the fire
     * "didn't read as fire": brightness alone doesn't say flame, structure does. */
    vec3 fc = vec3(u, h - uTime * uRise * 2.4, v) * (0.11 * uScale) + vec3(7.3, 2.1, 5.7);
    float ridged = 1.0 - abs(fbm3d(fc) * 2.0 - 1.0);
    float fn = smoothstep(0.34, 0.96, ridged);

    /* A second, coarser layer so the tongues clump into licks instead of even filigree. */
    vec3 fc2 = vec3(u, h - uTime * uRise * 1.3, v) * (0.05 * uScale) + vec3(2.7, 8.1, 4.3);
    float lick = smoothstep(0.35, 0.80, fbm3d(fc2));

    float hot = core * uHeat * dens * fn * (0.45 + 0.85 * lick) * 3.2;

    /* The burning core LIGHTS the smoke around it — a wide, dim emissive halo. Cheaper and more convincing
     * than a light source: the cloud glows from within while the fire is alive, and goes out with it. */
    hot += uHeat * (1.0 - smoothstep(0.0, uCapR * 2.2, distCap)) * dens * 0.30;

    /* BLAST FIREBALL: the opening beat, built from the SAME noise family so it belongs to the cloud instead
     * of being a sprite flipbook pasted in front of it. It carries its own density too, so the ball is a
     * real volume that the mushroom then grows out of. */
    if (uBallR > 0.01)
    {
        float dyB = h - 1.5;
        float db = sqrt(u * u + dyB * dyB + v * v);
        float bd = db - (uBallR + (n - 0.45) * uBallR * 0.55);
        float bdens = 1.0 - smoothstep(-uBallR * 0.55, 0.0, bd);

        if (bdens > 0.001)
        {
            /* ★Fade the ball's DENSITY out with its heat. Its radius used to stay full right up to the end
             * of the fireball window and then snap to zero in a single frame, so the whole contribution
             * blinked out — the mushroom has grown to nearly full size by then and takes over cleanly. */
            dens = max(dens, bdens * 0.85 * uBallHeat);
            hot += bdens * uBallHeat * smoothstep(0.22, 0.72, fn) * 2.0;
        }
    }

    /* ★The master fade applies to the FIRE as well. It used to scale only the smoke, and since the
     * accumulation was gated on smoke density, the flames were guillotined the moment the fade pushed the
     * smoke under the threshold — the fire vanished in one frame instead of dying on its own curve. */
    return vec3(dens * uFade, hot * uFade, shade);
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    vec3 p = viewPos(vUv, depth);
    float sceneDist = (depth >= 1.0) ? 1e9 : length(p);
    vec3 dir = normalize(p);

    float handDepth = texture(Sampler2, vUv).r;

    if (handDepth < 1.0)
    {
        sceneDist = min(sceneDist, length(viewPos(vUv, handDepth)));
    }

    /* Bounding cylinder around cap + stem, in the epicentre-local frame. For the SPHERE shape the ball
     * sits low at the epicentre, so bound it instead of the (absent) mushroom. */
    float reach = uShape > 0.5
        ? uSphereRadius * 1.9 + abs(uDrift.x) + abs(uDrift.y)
        : max(max(uCapR * 1.9, uStemR * 2.6), uBallR * 1.7) + abs(uDrift.x) + abs(uDrift.y);
    float top = uShape > 0.5
        ? uSphereRadius * 2.6 + 1.5
        : max(uCapY + uCapR * 1.9, uBallR * 1.8 + 1.5);

    vec3 oc = -uOrigin;
    float ou = dot(oc, uTanU), ov = dot(oc, uTanV), oh = dot(oc, uUp);
    float du = dot(dir, uTanU), dv = dot(dir, uTanV), dh = dot(dir, uUp);

    float a = du * du + dv * dv;
    float b = 2.0 * (ou * du + ov * dv);
    float c = ou * ou + ov * ov - reach * reach;
    float disc = b * b - 4.0 * a * c;

    if (disc < 0.0 || a < 1e-6)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    float sq = sqrt(disc);
    float t0 = (-b - sq) / (2.0 * a);
    float t1 = (-b + sq) / (2.0 * a);

    float th0 = (-1.5 - oh) / dh;
    float th1 = (top - oh) / dh;

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

    float stepLen = (tExit - tEnter) / float(STEPS);
    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453 + uTime);
    float t = tEnter + stepLen * dither;

    /* The fire cools from white-hot through orange to deep ember red as uHeat drops. A wide ramp is what
     * makes it read as FIRE rather than a coloured light. */
    float hk = clamp(uHeat, 0.0, 1.0);
    vec3 fireCol = hk > 0.5
        ? mix(vec3(1.0, 0.62, 0.18), vec3(1.0, 0.95, 0.80), (hk - 0.5) * 2.0)
        : mix(vec3(0.95, 0.16, 0.02), vec3(1.0, 0.62, 0.18), hk * 2.0);

    vec3 accum = vec3(0.0);
    float alpha = 0.0;

    for (int i = 0; i < STEPS; i++)
    {
        vec3 sp = dir * t;
        vec3 rel = sp - uOrigin;
        float lu = dot(rel, uTanU);
        float lv = dot(rel, uTanV);
        float lh = dot(rel, uUp);

        vec3 dens = density(lu, lv, lh);

        /* Emit when EITHER component is present: gating on smoke alone cut the fire off wherever the smoke
         * was thin or already faded. */
        if (dens.x > 0.001 || dens.y > 0.001)
        {
            float sa = clamp(dens.x * uDensity * stepLen, 0.0, 1.0);

            /* Smoke absorbs (shaded by how buried the sample is); the hot term EMITS on top of it.
             * ★The fire lives at the CORE, so by the time the ray reaches it the outer smoke has already
             * built up alpha and was swallowing nearly all of the glow — that is why the flames were barely
             * visible. Emission is therefore only PARTIALLY occluded: it still dims behind thick smoke, but
             * it punches through the way real flame seen through its own plume does. */
            accum += (1.0 - alpha) * uColor * dens.z * sa
                + (1.0 - alpha * 0.55) * fireCol * dens.y * uEmit * stepLen;
            alpha += (1.0 - alpha) * sa;

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
