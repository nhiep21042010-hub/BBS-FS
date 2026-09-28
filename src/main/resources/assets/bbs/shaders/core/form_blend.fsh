#version 150

uniform sampler2D Sampler0; // the form, rendered in isolation (rgb = colour, a = coverage)
uniform sampler2D Sampler1; // a copy of the scene colour behind it

uniform int BlendMode;
uniform float Factor;

in vec2 texCoord;
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
    vec4 form = texture(Sampler0, texCoord);
    vec3 scene = texture(Sampler1, texCoord).rgb;

    vec3 blended = applyBlend(BlendMode, form.rgb, scene);

    /* Coverage (the form's alpha) times the strength controls how much of the blend shows; uncovered
     * pixels pass the scene through unchanged. */
    float amount = clamp(form.a * Factor, 0.0, 1.0);

    fragColor = vec4(mix(scene, blended, amount), 1.0);
}
