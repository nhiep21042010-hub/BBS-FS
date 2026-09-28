#version 150

/* Screen-space VOLUMETRIC smoke rising from the cracked crater (true 3D fractal noise, not a flat sprite).
 * Per pixel: reconstruct the view ray, clip it to a vertical cylinder (radius = crack reach, up to a
 * height) over the epicentre, march that segment sampling a 3D fbm density that scrolls upward over time,
 * and front-to-back composite the smoke. Occluded correctly by the scene depth (terrain/blocks in front
 * cut the march). Warm-lit at the base (glow from the lava), cool grey higher up. Composited post-pack. */

uniform sampler2D Sampler0;   /* scene colour (blitted frame) */
uniform sampler2D Sampler1;   /* scene depth */

uniform mat4  uInvProj;
uniform vec3  uOrigin;        /* crater epicentre ON the ground surface, VIEW space */
uniform vec3  uUp;            /* ground up axis, VIEW space (normalized) */
uniform vec3  uTanU;
uniform vec3  uTanV;
uniform float uReach;         /* smoke disc radius (blocks) — matches the crack reach */
uniform float uHeight;        /* how high the smoke climbs (blocks) */
uniform float uDensity;       /* opacity per block of smoke */
uniform float uScale;         /* fractal noise frequency */
uniform float uRise;          /* upward scroll speed */
uniform float uCrackScale;    /* crack pattern frequency (must match the cracks so smoke aligns) */
uniform float uCrackReach;    /* crack spread radius (blocks) */
uniform float uTime;
uniform vec3  uColor;         /* smoke colour (cool grey) */

const int STEPS = 40;

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

/* --- 2D noise for the crack source field (must mirror cracks_volume's ridgedField so smoke aligns to
 *     the visible fissures) --- */

float hash2(vec2 p)
{
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);

    return fract(p.x * p.y);
}

float vnoise2(vec2 p)
{
    vec2 i = floor(p), f = fract(p);

    f = f * f * (3.0 - 2.0 * f);

    float a = hash2(i);
    float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0));
    float d = hash2(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm2(vec2 p, int oct)
{
    float s = 0.0, a = 0.5;

    for (int i = 0; i < 6; i++)
    {
        if (i >= oct) break;

        s += a * vnoise2(p);
        p = p * 2.03 + 7.1;
        a *= 0.5;
    }

    return s;
}

/* Crack openness at a ground coord (widened with height so the columns billow/merge as they rise). */
float crackSource(float u, float v, float heightFrac)
{
    float rr = sqrt(u * u + v * v);
    float hole = pow(clamp(1.0 - rr / uCrackReach, 0.0, 1.0), 0.8);

    if (hole <= 0.0)
    {
        return 0.0;
    }

    /* broaden the source upward: lower frequency higher up → columns fatten and merge */
    float widen = 1.0 + heightFrac * 1.8;
    vec2 c = vec2(u, v) * (uCrackScale / widen) + vec2(0.0, uTime * 0.05);

    vec2 w = vec2(fbm2(c * 0.5 + 11.3, 3), fbm2(c * 0.5 + 37.7, 3)) - 0.5;
    c += w * 1.6;

    float field = abs(fbm2(c, 6) * 2.0 - 1.0);

    /* open wider than the visible crack line so smoke has a mouth to rise from, wider still with height */
    float width = hole * (0.9 + heightFrac * 0.6);

    return smoothstep(width, width * 0.4, field) * smoothstep(0.0, 0.12, hole);
}

/* Smoke density at a ground-local point (u,v,h): wispy 3D fbm, RISING FROM THE CRACK LINES (gated by the
 * crack source field), faded by height. */
float density(float u, float v, float h)
{
    if (h < 0.0 || h > uHeight)
    {
        return 0.0;
    }

    float heightFrac = h / uHeight;

    /* Smoke only issues from the fissures (widening/merging as it climbs). */
    float src = crackSource(u, v, heightFrac);

    if (src <= 0.001)
    {
        return 0.0;
    }

    float hf = smoothstep(uHeight, uHeight * 0.15, h) * smoothstep(0.0, uHeight * 0.04, h);

    vec3 nc = vec3(u, v, h) * uScale + vec3(0.0, 0.0, -uTime * uRise);
    float d = fbm3d(nc);

    d = smoothstep(0.4, 0.85, d);

    return d * src * hf;
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    vec3 p = viewPos(vUv, depth);
    float sceneDist = (depth >= 1.0) ? 1e9 : length(p);
    vec3 dir = normalize(p);

    /* Work in the ground-local frame (cylinder is axis-aligned along h). Camera is at the view origin. */
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

    float stepLen = (tExit - tEnter) / float(STEPS);
    float dither = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453 + uTime);
    float t = tEnter + stepLen * dither;

    vec3 accum = vec3(0.0);
    float alpha = 0.0;

    for (int i = 0; i < STEPS; i++)
    {
        vec3 sp = dir * t;
        vec3 rel = sp - uOrigin;
        float lu = dot(rel, uTanU);
        float lv = dot(rel, uTanV);
        float lh = dot(rel, uUp);

        float dens = density(lu, lv, lh);

        if (dens > 0.001)
        {
            float da = clamp(dens * uDensity * stepLen, 0.0, 1.0);

            /* Warm at the base (lava glow), cool grey higher. */
            float warm = smoothstep(uHeight * 0.35, 0.0, lh);
            vec3 col = mix(uColor, vec3(1.0, 0.45, 0.12), warm * 0.6);

            accum += (1.0 - alpha) * col * da;
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
