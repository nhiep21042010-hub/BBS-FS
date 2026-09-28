#version 150
#extension GL_ARB_explicit_attrib_location : require

/* Weighted-Blended OIT accumulation pass (McGuire & Bavoil 2013). Writes to TWO targets:
 *   0 (accum, RGBA16F): sum of premultiplied colour * weight, and weight in .a
 *   1 (reveal, R16F):   product of (1 - alpha)  (via a multiplicative blend on this target)
 * so translucent layers combine correctly in ANY order — no back-to-front sorting. */

uniform sampler2D Sampler0;
uniform vec4 ColorModulator;

in vec4 vColor;
in vec2 vUv;

layout(location = 0) out vec4 accum;
layout(location = 1) out vec4 reveal;

void main()
{
    vec4 c = texture(Sampler0, vUv) * vColor * ColorModulator;

    if (c.a < 0.001)
    {
        discard;
    }

    /* Depth-based weight: nearer fragments dominate (McGuire's tuned curve, clamped). */
    float z = gl_FragCoord.z;
    float w = c.a * clamp(0.03 / (1e-5 + pow(z / 200.0, 4.0)), 1e-2, 3e3);

    accum = vec4(c.rgb * c.a, c.a) * w;
    reveal = vec4(c.a, 0.0, 0.0, 0.0);
}
