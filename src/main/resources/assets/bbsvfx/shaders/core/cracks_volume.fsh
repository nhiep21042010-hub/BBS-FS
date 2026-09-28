#version 150

/* Screen-space magma cracks with real VOLUME (destroying-minecraft technique, library-free). Per pixel:
 * reconstruct the VIEW-space scene point from depth, keep only the epicentre's ground plane, then — where
 * the surface is a fissure — MARCH the view ray DOWN into the ground through the crack void, accumulating
 * molten glow (attenuated with depth, tone-mapped to the magma hue) until it hits a rock wall or the floor.
 * The fissure network is a DOMAIN-WARPED, two-scale ridged fbm (big veins + fine tributaries) with fwidth
 * anti-aliased edges and an ember-textured molten interior. Depth-gated, composited post-pack (Iris-safe). */

uniform sampler2D Sampler0;   /* scene colour (blitted frame) */
uniform sampler2D Sampler1;   /* scene depth */

uniform mat4  uInvProj;       /* inverse captured projection (NDC -> view) */
uniform vec3  uOrigin;        /* crack epicentre ON the ground surface, VIEW space */
uniform vec3  uUp;            /* ground up axis, VIEW space (normalized) */
uniform vec3  uTanU;          /* ground tangent U, VIEW space (normalized) — world-stable noise axis */
uniform vec3  uTanV;          /* ground tangent V, VIEW space (normalized) */
uniform float uReach;         /* current crack spread radius (blocks) */
uniform float uScale;         /* crack pattern frequency */
uniform float uThickness;     /* ground-plane gate half-thickness (blocks) */
uniform float uGlow;          /* magma emission strength */
uniform float uDepth;         /* how deep the fissures sink into the ground (blocks) */
uniform float uTime;
uniform vec3  uColor;         /* magma colour */

const int   MARCH_STEPS = 64;
const float MARCH_DS = 0.3;       /* world step along the view ray */

in vec2 vUv;

out vec4 fragColor;

float hash(vec2 p)
{
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);

    return fract(p.x * p.y);
}

float vnoise(vec2 p)
{
    vec2 i = floor(p), f = fract(p);

    f = f * f * (3.0 - 2.0 * f);

    float a = hash(i);
    float b = hash(i + vec2(1.0, 0.0));
    float c = hash(i + vec2(0.0, 1.0));
    float d = hash(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

float fbm6(vec2 p)
{
    float s = 0.0, a = 0.5;

    for (int i = 0; i < 6; i++)
    {
        s += a * vnoise(p);
        p = p * 2.03 + 7.1;
        a *= 0.5;
    }

    return s;
}

float fbm3(vec2 p)
{
    float s = 0.0, a = 0.5;

    for (int i = 0; i < 3; i++)
    {
        s += a * vnoise(p);
        p = p * 2.11 + 3.3;
        a *= 0.5;
    }

    return s;
}

/* Ridged crack field over a 2D ground coord: 0 ON a fissure line, →1 in solid rock. Domain-warped for
 * organic meander. Single clean scale — no high-frequency detail (it read as speckle noise over the lava). */
float ridgedField(vec2 c)
{
    vec2 w = vec2(fbm3(c * 0.5 + 11.3), fbm3(c * 0.5 + 37.7)) - 0.5;
    c += w * 1.6;

    return abs(fbm6(c) * 2.0 - 1.0);
}

vec3 viewPos(vec2 uv, float d)
{
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 v = uInvProj * ndc;

    return v.xyz / v.w;
}

/* Openness of the fissure void at (u,v,h) relative to the surface epicentre: >0 inside the crack, 0 in
 * rock. The slot NARROWS with depth (a tapering wedge). */
float crackField(float uu, float vv, float hh)
{
    float rr = sqrt(uu * uu + vv * vv);
    float hole = pow(clamp(1.0 - rr / uReach, 0.0, 1.0), 0.8);

    if (hole <= 0.0)
    {
        return 0.0;
    }

    float depthFrac = clamp(-hh / uDepth, 0.0, 1.0);
    float width = hole * (1.0 - 0.75 * depthFrac);

    float field = ridgedField(vec2(uu, vv) * uScale + vec2(0.0, uTime * 0.05));

    return smoothstep(width, width * 0.45, field) * smoothstep(0.0, 0.12, hole);
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    if (depth >= 1.0)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    vec3 p = viewPos(vUv, depth);
    vec3 dir = normalize(p);
    vec3 rel = p - uOrigin;
    float h = dot(rel, uUp);

    if (abs(h) > uThickness)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    float u = dot(rel, uTanU);
    float v = dot(rel, uTanV);
    float r = sqrt(u * u + v * v);

    if (r > uReach)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    float hole = pow(clamp(1.0 - r / uReach, 0.0, 1.0), 0.8);
    float fade = smoothstep(0.0, 0.12, hole);

    /* Surface fissure field, anti-aliased with screen-space derivatives (crisp at any distance). */
    vec2 sc = vec2(u, v) * uScale + vec2(0.0, uTime * 0.05);
    float field = ridgedField(sc);
    float aa = max(fwidth(field), 0.002);

    float surfOpen = smoothstep(hole + aa, hole * 0.45 - aa, field) * fade;
    float nearCrack = smoothstep(hole * 1.7 + aa, hole * 0.9 - aa, field) * fade;
    float charred = clamp(nearCrack - surfOpen, 0.0, 1.0);

    if (nearCrack < 0.01)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    /* March DOWN into the ground through the fissure void → volumetric molten glow, attenuated with depth
     * (dark bottom, no white blow-out) and tone-mapped to the magma hue. Ember texture on the walls. */
    vec3 glowCol = vec3(0.0);
    float trans = 1.0;
    float atten = 2.6 / max(1.0, uDepth);

    if (surfOpen > 0.02)
    {
        /* Dither the ray start by a per-pixel sub-step offset so the discrete march doesn't band into
         * visible stairsteps down the fissure walls — the banding becomes fine, near-invisible grain. */
        float dither = hash(gl_FragCoord.xy + fract(uTime) * 17.0);
        vec3 rp = rel + dir * MARCH_DS * dither;

        for (int i = 0; i < MARCH_STEPS; i++)
        {
            rp += dir * MARCH_DS;

            float hh = dot(rp, uUp);

            if (hh > 0.05 || hh < -uDepth)
            {
                break;
            }

            float uu = dot(rp, uTanU);
            float vv = dot(rp, uTanV);
            float open = crackField(uu, vv, hh);

            if (open < 0.02)
            {
                break;
            }

            /* Smooth magma emission — brighter in the wide part of the void, always the magma hue. */
            vec3 emis = uColor * (0.4 + 0.6 * open);

            glowCol += trans * emis * open * MARCH_DS;
            trans *= exp(-atten * MARCH_DS);

            if (trans < 0.03)
            {
                break;
            }
        }
    }

    float flick = 0.85 + 0.15 * sin(uTime * 6.0 + r * 0.7 + field * 6.0);
    vec3 molten = (vec3(1.0) - exp(-glowCol * uGlow)) * flick;

    /* Charred rock rim + faint scorch on the intact ground between fissures. */
    vec3 ground = scene * (1.0 - 0.8 * charred);
    ground *= 1.0 - 0.25 * hole * (1.0 - nearCrack);

    vec3 outc = ground * (1.0 - surfOpen) + molten;

    fragColor = vec4(outc, 1.0);
}
