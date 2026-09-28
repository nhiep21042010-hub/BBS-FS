#version 150

uniform sampler2D Sampler0;   // scene colour (copy)
uniform sampler2D Sampler1;   // scene depth
uniform sampler2D Sampler2;   // baked text texture (the slide)
uniform sampler2D Sampler3;   // first-person hand depth (hand pass clears depth; background = 1.0)

uniform mat4 InvCameraVP;     // inverse(projection): screen+depth -> view-space position
uniform mat4 ProjectorVP;     // projector clip from view-space position
uniform vec3 ProjForward;     // projector forward (view space) for the facing fade
uniform int BlendMode;
uniform float Fade;           // soft edge fade, 0..1
uniform vec4 Tint;

in vec2 texCoord0;

out vec4 fragColor;

vec3 applyBlend(int mode, vec3 s, vec3 d)
{
    if (mode == 1) return min(s + d, vec3(1.0));                                   // add
    if (mode == 2) return 1.0 - (1.0 - s) * (1.0 - d);                            // screen
    if (mode == 3) return s * d;                                                   // multiply
    if (mode == 4) return min(s, d);                                               // darken
    if (mode == 5) return max(s, d);                                               // lighten
    if (mode == 6) return mix(2.0 * s * d, 1.0 - 2.0 * (1.0 - s) * (1.0 - d), step(0.5, d)); // overlay
    if (mode == 7)                                                                 // soft light
    {
        vec3 g = mix(((16.0 * d - 12.0) * d + 4.0) * d, sqrt(d), step(0.25, d));
        return mix(d - (1.0 - 2.0 * s) * d * (1.0 - d), d + (2.0 * s - 1.0) * (g - d), step(0.5, s));
    }
    if (mode == 8) return min(vec3(1.0), d / max(vec3(1.0 / 256.0), 1.0 - s));     // color dodge
    if (mode == 9) return abs(s - d);                                              // difference
    if (mode == 10) return s + d - 2.0 * s * d;                                    // exclusion

    return s;                                                                      // normal
}

void main()
{
    vec3 scene = texture(Sampler0, texCoord0).rgb;
    float depth = texture(Sampler1, texCoord0).r;

    /* Sky / nothing — leave the scene untouched. */
    if (depth >= 1.0)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    /* Covered by the first-person hand (its depth pass sits closer than the world snapshot). When no
     * hand was drawn the two depths are identical and nothing is masked. */
    if (texture(Sampler3, texCoord0).r < depth - 0.0005)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    /* Reconstruct the view-space position of this pixel. */
    vec4 ndc = vec4(texCoord0 * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
    vec4 view = InvCameraVP * ndc;
    vec3 p = view.xyz / view.w;

    /* Into the projector's clip space. */
    vec4 pc = ProjectorVP * vec4(p, 1.0);

    if (pc.w <= 0.0)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    vec3 pn = pc.xyz / pc.w;

    if (pn.x < -1.0 || pn.x > 1.0 || pn.y < -1.0 || pn.y > 1.0 || pn.z < -1.0 || pn.z > 1.0)
    {
        fragColor = vec4(scene, 1.0);
        return;
    }

    /* Facing fade: surfaces facing away from the projector get nothing (kills grazing-angle streaks).
     * Orient the geometric normal toward the camera first so flat faces are consistent. */
    vec3 n = normalize(cross(dFdx(p), dFdy(p)));
    if (n.z < 0.0) n = -n;
    float facing = -dot(n, normalize(ProjForward));
    float facingFade = smoothstep(0.0, 0.35, facing);

    /* Soft edge of the projection spot. */
    float edge = max(abs(pn.x), abs(pn.y));
    float edgeFade = Fade > 0.0 ? smoothstep(1.0, 1.0 - Fade, edge) : 1.0;

    vec2 uv = pn.xy * 0.5 + 0.5;
    vec4 slide = texture(Sampler2, vec2(uv.x, 1.0 - uv.y)) * Tint;

    float a = slide.a * facingFade * edgeFade;
    vec3 blended = applyBlend(BlendMode, slide.rgb, scene);

    fragColor = vec4(mix(scene, blended, a), 1.0);
}
