#version 150

uniform sampler2D Sampler0;   // baked colour text
uniform sampler2D Sampler1;   // copy of the scene framebuffer (destination)

uniform vec2 ScreenSize;
uniform int BlendMode;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

vec3 applyBlend(int mode, vec3 s, vec3 d)
{
    if (mode == 6) return mix(2.0 * s * d, 1.0 - 2.0 * (1.0 - s) * (1.0 - d), step(0.5, d)); // overlay
    if (mode == 7)                                                                          // soft light
    {
        vec3 g = mix(((16.0 * d - 12.0) * d + 4.0) * d, sqrt(d), step(0.25, d));
        return mix(d - (1.0 - 2.0 * s) * d * (1.0 - d), d + (2.0 * s - 1.0) * (g - d), step(0.5, s));
    }
    if (mode == 8) return min(vec3(1.0), d / max(vec3(1.0 / 256.0), 1.0 - s));              // color dodge
    if (mode == 9) return abs(s - d);                                                       // difference
    if (mode == 10) return s + d - 2.0 * s * d;                                             // exclusion

    return s;
}

void main()
{
    vec4 color = texture(Sampler0, texCoord0) * vertexColor;

    if (color.a < 0.01)
    {
        discard;
    }

    vec3 dst = texture(Sampler1, gl_FragCoord.xy / ScreenSize).rgb;

    fragColor = vec4(applyBlend(BlendMode, color.rgb, dst), color.a);
}
