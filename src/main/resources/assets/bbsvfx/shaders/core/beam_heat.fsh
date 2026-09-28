#version 150

/* Heat-haze (mirage) refraction: re-samples the already-rendered scene with noise-displaced UVs.
 * The noise itself is NEVER shown — only the lens-like distortion it produces (displacement map).
 * Occlusion is a manual depth test against the world depth captured at WorldRenderEvents.LAST
 * (this pass runs after a shaderpack's composite, where the framebuffer has no world depth). */

uniform sampler2D Sampler0;
uniform sampler2D Sampler1;

uniform vec2 ScreenSize;
uniform float uTime;
uniform float uStrength;

in vec4 vColor;
in vec2 vUv;

out vec4 fragColor;

void main()
{
    vec2 suv = gl_FragCoord.xy / ScreenSize;

    /* Manual occlusion: terrain in front of the haze plane hides it. */
    if (texture(Sampler1, suv).r < gl_FragCoord.z - 0.00002)
    {
        discard;
    }

    float x = vUv.x;    /* -1..1 across the haze column */
    float y = vUv.y;    /* blocks along the beam */
    float t = uTime;

    /* BIG slow waves flowing along the beam (low frequency — high octaves read as static noise). */
    float ph = sin(y * 0.5 + t * 1.1 + sin(y * 1.1 - t * 0.7) * 0.9);
    float nx = sin(y * 0.7 - t * 1.5 + x * 0.8 + ph) * 0.70
             + sin(y * 1.6 + t * 2.1 - x * 0.6) * 0.30;
    float ny = cos(y * 0.9 + t * 1.3 - x * 0.7 + ph) * 0.65
             + cos(y * 2.1 - t * 1.8 + x * 0.5) * 0.35;

    /* Horizontal falloff: the lens melts into still air at the column edges. */
    float mask = vColor.a * pow(max(0.0, 1.0 - x * x), 1.5);

    vec2 duv = vec2(nx, ny * (ScreenSize.x / ScreenSize.y)) * (0.006 * uStrength * mask);

    fragColor = vec4(texture(Sampler0, clamp(suv + duv, vec2(0.002), vec2(0.998))).rgb, 1.0);
}
