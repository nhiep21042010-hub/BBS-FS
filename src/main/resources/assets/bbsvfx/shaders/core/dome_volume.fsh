#version 150

/* Screen-space raymarched energy dome (railgun/nuke technique). The dome is a translucent hemisphere
 * VOLUME reconstructed per pixel from the scene depth — NOT geometry — so it composites correctly against
 * clouds, water and terrain by DEPTH with zero sorting/OIT. It TINTS (absorbs) the world seen through it
 * (Beer-Lambert per shell wall) and adds a glowing silhouette rim. Everything is done in VIEW space, so
 * only the inverse projection is needed (no camera/view matrix). */

uniform sampler2D Sampler0;   /* scene colour (blitted frame) */
uniform sampler2D Sampler1;   /* scene depth  (captured world depth) */

uniform mat4  uInvProj;       /* inverse of the captured world projection (NDC -> view) */
uniform vec3  uOrigin;        /* dome centre, VIEW space */
uniform vec3  uUp;            /* dome up axis, VIEW space (normalized) */
uniform float uRadius;
uniform float uHeightScale;   /* reserved (ellipsoid), v1 treats the dome as a sphere clipped to up>=0 */
uniform float uDensity;       /* Beer-Lambert absorption strength of a shell wall */
uniform float uOpacity;
uniform float uRimPow;
uniform float uEmit;          /* overall brightness multiplier (1 = tinting dome; >1 = emissive flash) */
uniform float uTime;
uniform vec3  uTint;          /* body/absorption colour (what the world behind is tinted toward) */
uniform vec3  uRim;           /* additive silhouette-rim glow colour */

in vec2 vUv;

out vec4 fragColor;

/* Reconstruct a VIEW-space position from screen uv + window-space depth [0,1]. */
vec3 viewPos(vec2 uv, float d)
{
    vec4 ndc = vec4(uv * 2.0 - 1.0, d * 2.0 - 1.0, 1.0);
    vec4 vp = uInvProj * ndc;

    return vp.xyz / vp.w;
}

void main()
{
    vec3 scene = texture(Sampler0, vUv).rgb;
    float depth = texture(Sampler1, vUv).r;

    /* Camera is at the origin in view space; the ray to this pixel points at the reconstructed scene
     * point, and sceneDist is how far the solid/translucent world is (occluder for the dome). */
    vec3 sp = viewPos(vUv, depth);
    float sceneDist = length(sp);
    vec3 dir = sp / max(sceneDist, 1e-4);

    /* Ray-sphere intersection (camera at origin → oc = -centre). */
    vec3 oc = -uOrigin;
    float b = dot(dir, oc);
    float c = dot(oc, oc) - uRadius * uRadius;
    float disc = b * b - c;

    if (disc < 0.0)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    float sq = sqrt(disc);
    float ts[2];
    ts[0] = -b - sq;   /* near wall */
    ts[1] = -b + sq;   /* far wall  */

    vec3 domeCol = vec3(0.0);
    float domeA = 0.0;

    /* Two shell crossings, near -> far, front-to-back over-composite, occluded by the scene depth. */
    for (int i = 0; i < 2; i++)
    {
        float t = ts[i];

        if (t <= 0.0 || t >= sceneDist)
        {
            continue;   /* behind the camera, or behind the solid world */
        }

        vec3 p = dir * t;
        vec3 rel = p - uOrigin;
        float up = dot(rel, uUp);

        if (up < 0.0)
        {
            continue;   /* below the base plane → not part of the hemisphere */
        }

        /* Grazing factor from the surface normal: silhouette (grazing) → long chord through the wall →
         * dense + bright rim; face-on → thin. This is the fresnel look, straight from the geometry. */
        vec3 n = normalize(rel);
        float ndotd = abs(dot(n, dir));
        float graze = 1.0 - clamp(ndotd, 0.0, 1.0);

        float od = uDensity / max(0.16, ndotd);
        float a = clamp(uOpacity * (1.0 - exp(-od)), 0.0, 1.0);
        float rim = pow(graze, uRimPow);

        vec3 col = (uTint + uRim * rim) * uEmit;

        domeCol += (1.0 - domeA) * col * a;
        domeA += (1.0 - domeA) * a;
    }

    vec3 outc = scene * (1.0 - domeA) + domeCol;

    fragColor = vec4(outc, 1.0);
}
