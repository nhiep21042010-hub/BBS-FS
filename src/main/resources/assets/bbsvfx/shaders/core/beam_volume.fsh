#version 150

/* Screen-space raymarched energy BEAM column (railgun/nuke technique, library-free). The view ray is
 * clipped to a cylinder around the beam axis and marched through an EMISSIVE energy field: a bright warm
 * core with a soft radial falloff to an orange rim, wrapped by helix ribbons, wavered by turbulence, and
 * faded at the ends. Purely additive (order-independent) and depth-occluded — terrain/clouds/water in
 * front cut the march, so no sorting grime and no binary depth-prepass. Composited post-pack (Iris-safe). */

uniform sampler2D Sampler0;   /* scene colour */
uniform sampler2D Sampler1;   /* scene depth */

uniform mat4  uInvProj;
uniform vec3  uBase;          /* column base, VIEW space */
uniform vec3  uAxis;          /* column axis (growth dir), VIEW space (normalized) */
uniform vec3  uTanU;          /* radial basis U, VIEW space (normalized) */
uniform vec3  uTanV;          /* radial basis V, VIEW space (normalized) */
uniform float uLen;           /* column length (blocks) */
uniform float uRadius;        /* core radius (blocks) */
uniform float uSoftness;      /* radial falloff softness */
uniform float uOpacity;
uniform float uEmit;          /* brightness */

uniform int   uHelixOn;
uniform int   uHelixCount;
uniform float uHelixRadius;
uniform float uHelixTurns;
uniform float uHelixThickness;
uniform float uHelixSpin;

uniform float uTime;
uniform vec3  uColor;         /* hot core colour */
uniform vec3  uRim;           /* cooler rim / helix colour */

const int STEPS = 48;
const float PI = 3.14159265;

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

vec3 viewPos(vec2 uv, float d)
{
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 v = uInvProj * ndc;

    return v.xyz / v.w;
}

float angDiff(float a, float b)
{
    float d = mod(a - b + PI, 2.0 * PI) - PI;

    return d;
}

/* Emissive energy at a point given by (h axial, radial vector components ru,rv). Returns premultiplied
 * colour to add. */
vec3 emission(float h, float ru, float rv)
{
    if (h < 0.0 || h > uLen)
    {
        return vec3(0.0);
    }

    float rd = sqrt(ru * ru + rv * rv);
    float phi = atan(rv, ru);

    /* Turbulence: waver the column so the core isn't a rigid cylinder. */
    float turb = (vnoise(vec2(h * 0.5, phi * 1.5 + uTime * 0.6)) - 0.5) * uRadius * 0.6;
    float rr = max(0.0, rd + turb);

    /* Soft warm core → orange rim. */
    float core = exp(-(rr * rr) / max(1e-3, uRadius * uRadius * uSoftness));
    vec3 col = mix(uRim, uColor, clamp(core, 0.0, 1.0)) * core;

    /* Helix ribbons wrapping the column. */
    if (uHelixOn == 1 && uHelixCount > 0)
    {
        float hel = 0.0;

        for (int k = 0; k < 8; k++)
        {
            if (k >= uHelixCount)
            {
                break;
            }

            float theta = (h / max(0.01, uLen)) * uHelixTurns * 2.0 * PI + uTime * uHelixSpin
                + float(k) * 2.0 * PI / float(uHelixCount);
            float dphi = angDiff(phi, theta);
            float dist = sqrt((rd - uHelixRadius) * (rd - uHelixRadius) + (dphi * uHelixRadius) * (dphi * uHelixRadius));

            hel += smoothstep(uHelixThickness, 0.0, dist);
        }

        col += uRim * hel;
    }

    /* Fade the far end so the tip isn't a hard disc; keep the near/base bright. */
    float fade = smoothstep(uLen, uLen * 0.82, h);

    return col * fade;
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    vec3 p = viewPos(vUv, depth);
    float sceneDist = (depth >= 1.0) ? 1e9 : length(p);
    vec3 dir = normalize(p);

    /* Ray in the beam-local frame; the column runs along uAxis. Camera at the view origin. */
    vec3 oc = -uBase;
    float ou = dot(oc, uTanU), ov = dot(oc, uTanV), oh = dot(oc, uAxis);
    float du = dot(dir, uTanU), dv = dot(dir, uTanV), dh = dot(dir, uAxis);

    float reach = uRadius + uHelixRadius + uHelixThickness + 0.5;

    /* Ray vs infinite cylinder (radius reach) around the axis. */
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

    /* Clip to the axial slab h ∈ [0, len]. */
    float th0 = (0.0 - oh) / dh;
    float th1 = (uLen - oh) / dh;

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
    float dither = hash(gl_FragCoord.xy + fract(uTime) * 13.0);
    float t = tEnter + stepLen * dither;

    vec3 accum = vec3(0.0);

    for (int i = 0; i < STEPS; i++)
    {
        vec3 sp = dir * t;
        vec3 rel = sp - uBase;
        float h = dot(rel, uAxis);
        float ru = dot(rel, uTanU);
        float rv = dot(rel, uTanV);

        accum += emission(h, ru, rv) * stepLen;

        t += stepLen;
    }

    vec3 outc = scene + accum * uEmit * uOpacity;

    fragColor = vec4(outc, 1.0);
}
