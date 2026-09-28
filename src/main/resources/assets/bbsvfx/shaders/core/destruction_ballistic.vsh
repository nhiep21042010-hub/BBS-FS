#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

/* Unit cube geometry (36 verts, positions in 0..1, per-face 0..1 UVs). */
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

/* Per-INSTANCE ballistic attributes (divisor 1, baked once per block). */
in vec3 IOrig;    /* rest corner (form-local block coords) */
in vec3 IVel;     /* launch velocity, blocks/s */
in vec4 ISpin;    /* xyz = unit tumble axis, w = angular speed rad/s */
in vec2 IMisc;    /* x = landing time (s), y = unused */
in vec4 IUvRect;  /* particle sprite in the block atlas: xy = min, zw = size */
in vec4 ITint;    /* biome tint (or white) */

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat3 NormalMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

/* Scrub time (destruction × bake duration, seconds) and gravity (blocks/s², positive = down). */
uniform float uTime;
uniform float uGravity;

/* Restitution of the landing bounce (0 = dead stop — reads as debris GLUED to the ground). */
uniform float uBounce;

/* Global wind: the blast front (radial shove from uEpicenter arriving at d/uWindFront, decaying over
 * uWindDecay) + constant ambient drift (uWindAmb, blocks/s²). Zeroes = calm. */
uniform vec3 uEpicenter;
uniform float uWindStr;
uniform float uWindFront;
uniform float uWindDecay;
uniform vec2 uWindAmb;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

/* Rodrigues rotation of v around a unit axis by angle. */
vec3 rotateAxis(vec3 v, vec3 axis, float angle)
{
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

/* Wind displacement at flight time t: double integral of the front's decaying radial shove
 * (S·τ·(tw − τ·(1−e^(−tw/τ)))) plus the constant ambient drift. Closed form — scrub-exact. */
vec3 windDisp(float t)
{
    vec3 ro = IOrig + vec3(0.5) - uEpicenter;
    float d = length(ro);
    float tPass = uWindFront > 1.0e-4 ? d / uWindFront : 0.0;
    float tw = max(t - tPass, 0.0);
    float tau = max(uWindDecay, 0.05);
    float wmag = uWindStr * tau * (tw - tau * (1.0 - exp(-tw / tau)));
    vec3 disp = (d > 1.0e-4 ? ro / d : vec3(0.0, 1.0, 0.0)) * wmag;

    disp.xz += 0.5 * uWindAmb * t * t;

    return disp;
}

void main()
{
    /* Closed-form flight — scrubbing any t is exact. Piecewise: parabola to the landing time, then TWO
     * damped bounces + a decelerating slide-out. IMisc.y = per-block hash that jitters restitution and
     * friction so the field doesn't move in visible lockstep (the "not physical" tell vs tier A). */
    float t1 = IMisc.x;
    float h = IMisc.y;
    float te = min(uTime, t1);
    vec3 flight = IVel * te + vec3(0.0, -0.5 * uGravity * te * te, 0.0);
    float angle = ISpin.w * te;

    if (uTime > t1 && t1 < 1.0e8 && uGravity > 1.0e-5)
    {
        float e = uBounce * (0.5 + h);       /* restitution: 0.5x..1.5x of the form value */
        float fr = 0.25 + 0.35 * h;          /* horizontal fraction kept through an impact */
        float vyl = min(IVel.y - uGravity * t1, 0.0);

        /* Bounce 1. */
        float v1 = -e * vyl;
        float t2 = 2.0 * v1 / uGravity;
        float tb = min(uTime - t1, t2);
        vec2 velH = IVel.xz * fr;

        flight = IVel * t1 + vec3(0.0, -0.5 * uGravity * t1 * t1, 0.0)
            + vec3(velH.x * tb, v1 * tb - 0.5 * uGravity * tb * tb, velH.y * tb);
        angle = ISpin.w * (t1 + 0.6 * tb);

        if (uTime > t1 + t2)
        {
            /* Bounce 2 (damped again). */
            float v2 = e * v1;
            float t3 = 2.0 * v2 / uGravity;
            float tc = min(uTime - t1 - t2, t3);

            velH *= fr;
            flight += vec3(velH.x * tc, v2 * tc - 0.5 * uGravity * tc * tc, velH.y * tc);
            angle += ISpin.w * 0.35 * tc;

            if (uTime > t1 + t2 + t3)
            {
                /* Slide-out: decelerates to a stop over ~0.6s (smooth: zero velocity at the end). */
                float ts = min(uTime - t1 - t2 - t3, 0.6);
                float slide = ts - ts * ts * 0.8333;

                velH *= fr;
                flight += vec3(velH.x * slide, 0.0, velH.y * slide);
                angle += ISpin.w * 0.2 * slide;
            }
        }
    }

    /* Wind rides the FLIGHT only (te caps at the landing time — grounded debris stops drifting). */
    flight += windDisp(te);

    vec3 centered = Position - 0.5;
    vec3 nrm = Normal;

    if (angle != 0.0)
    {
        centered = rotateAxis(centered, ISpin.xyz, angle);
        nrm = rotateAxis(Normal, ISpin.xyz, angle);
    }

    vec3 pos = IOrig + 0.5 + flight + centered;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vec3 fogPos = (ModelViewMat * vec4(pos, 1.0)).xyz;
    vertexDistance = (FogShape == 0) ? length(fogPos) : max(length(fogPos.xz), abs(fogPos.y));
    vec3 fixNormal = normalize(NormalMat * nrm);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, fixNormal, Color * ITint);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = IUvRect.xy + UV0 * IUvRect.zw;
    normal = ProjMat * ModelViewMat * vec4(nrm, 0.0);
}
