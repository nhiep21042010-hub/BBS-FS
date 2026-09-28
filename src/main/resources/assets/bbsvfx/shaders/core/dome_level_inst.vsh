#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

/* Unit cube geometry (36 verts, positions in 0..1, per-face 0..1 UVs, per-face shade in Color). */
in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

/* Per-INSTANCE attributes (divisor 1, baked once per block). */
in vec3 IOrig;    /* rest corner (form-local block coords) */
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

/* Dome levelling: the expanding front sweeps the world; a block SETTLES + DISSOLVES as it passes. All
 * closed-form of uFront (the current wavefront radius) — zero per-frame CPU geometry, scales to 100k+. */
uniform float uFront;
uniform vec3 uCenter;
uniform float uClearSpan;
uniform float uLaunch;   /* 0 before the beam fires, ramps as chunks are launched up and drift/tumble */

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

vec3 hash3(vec3 p)
{
    p = vec3(dot(p, vec3(127.1, 311.7, 74.7)), dot(p, vec3(269.5, 183.3, 246.1)), dot(p, vec3(113.5, 271.9, 124.6)));
    return fract(sin(p) * 43758.5453);
}

vec3 rotateAxis(vec3 v, vec3 axis, float angle)
{
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

void main()
{
    float d = length(IOrig.xz - uCenter.xz);
    float ep = uFront - d;
    float pf = clamp(ep / max(0.5, uClearSpan), 0.0, 1.0);   /* how far the dome front has passed */
    float sp = pf * pf * (3.0 - 2.0 * pf);

    vec3 rnd = hash3(IOrig + 0.37);   /* per-block randoms 0..1 */

    /* CLUSTERS: group neighbouring blocks so CHUNKS fly together, each with its OWN launch + tumble
     * (varied by rotation). The whole cluster rotates rigidly about its centre. */
    float cs = 3.0;
    vec3 cid = floor(IOrig / cs);
    vec3 cctr = (cid + 0.5) * cs;
    vec3 crnd = hash3(cid + 0.7);       /* per-CLUSTER randoms */

    float lt = uLaunch;
    float riseE = 1.0 - (1.0 - lt) * (1.0 - lt);   /* fast up, then eases — a small launch, not a freeze */

    /* Gentle launch: fly UP a bit + slight outward drift (they keep moving, never a static hang). */
    float upH = 1.5 + crnd.y * 3.0;
    vec2 outDir = normalize(vec2(crnd.x - 0.5, crnd.z - 0.5) + vec2(0.001));
    float outD = (0.5 + crnd.x) * lt;
    vec3 launch = vec3(outDir.x * outD, upH * riseE, outDir.y * outD);

    /* Varied cluster tumble (rate + direction differ per cluster). */
    vec3 axis = normalize(crnd * 2.0 - 1.0 + vec3(0.001, 1.0, 0.001));
    float ang = lt * (crnd.z * 2.0 - 1.0) * 4.0;

    vec3 relC = rotateAxis((IOrig + 0.5) - cctr, axis, ang);
    vec3 blockCentre = cctr + launch + relC;
    vec3 centered = rotateAxis(Position - 0.5, axis, ang);
    vec3 nrm = rotateAxis(Normal, axis, ang);

    vec3 disp = vec3(0.0);

    if (pf > 0.0)
    {
        /* Consumed by the dome front: drift UP into the light + dissolve (shrink to nothing). */
        disp.x += (rnd.x - 0.5) * 0.6 * sp;
        disp.z += (rnd.z - 0.5) * 0.6 * sp;
        disp.y += sp * (1.0 + rnd.y * 2.0);

        centered *= (1.0 - sp);
    }

    vec3 pos = blockCentre + disp + centered;

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
