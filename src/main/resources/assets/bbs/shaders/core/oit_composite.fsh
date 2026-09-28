#version 150

/* Weighted-Blended OIT resolve: reconstruct the average translucent colour and blend it over the scene
 * by the revealage. The draw blend func is (ONE_MINUS_SRC_ALPHA, SRC_ALPHA), so with src.a = reveal the
 * result is  scene * reveal + accumColour * (1 - reveal). */

uniform sampler2D Sampler0;   /* accum  (RGBA16F) */
uniform sampler2D Sampler1;   /* reveal (R16F)    */

in vec2 vUv;

out vec4 fragColor;

void main()
{
    float reveal = texture(Sampler1, vUv).r;

    /* Fully opaque background here (nothing accumulated) → contribute nothing. */
    if (reveal > 0.9999)
    {
        discard;
    }

    vec4 accum = texture(Sampler0, vUv);
    vec3 avg = accum.rgb / max(accum.a, 1e-5);

    fragColor = vec4(avg, reveal);
}
