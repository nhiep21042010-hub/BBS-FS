#version 150

uniform sampler2D Sampler0;   // glyph atlas
uniform sampler2D Sampler1;   // copy of the scene framebuffer (destination)

uniform vec4 ColorModulator;
uniform vec2 ScreenSize;
uniform int BlendMode;

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

vec3 blendOverlay(vec3 s, vec3 d)
{
    return mix(2.0 * s * d, 1.0 - 2.0 * (1.0 - s) * (1.0 - d), step(0.5, d));
}

vec3 blendSoftLight(vec3 s, vec3 d)
{
    vec3 g = mix(((16.0 * d - 12.0) * d + 4.0) * d, sqrt(d), step(0.25, d));
    vec3 lo = d - (1.0 - 2.0 * s) * d * (1.0 - d);
    vec3 hi = d + (2.0 * s - 1.0) * (g - d);

    return mix(lo, hi, step(0.5, s));
}

vec3 blendColorDodge(vec3 s, vec3 d)
{
    return min(vec3(1.0), d / max(vec3(1.0 / 256.0), 1.0 - s));
}

vec3 blendDifference(vec3 s, vec3 d)
{
    return abs(s - d);
}

vec3 blendExclusion(vec3 s, vec3 d)
{
    return s + d - 2.0 * s * d;
}

void main()
{
    vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;

    if (color.a < 0.1)
    {
        discard;
    }

    vec2 uv = gl_FragCoord.xy / ScreenSize;
    vec3 dst = texture(Sampler1, uv).rgb;
    vec3 src = color.rgb;
    vec3 outc = src;

    if (BlendMode == 6)       outc = blendOverlay(src, dst);
    else if (BlendMode == 7)  outc = blendSoftLight(src, dst);
    else if (BlendMode == 8)  outc = blendColorDodge(src, dst);
    else if (BlendMode == 9)  outc = blendDifference(src, dst);
    else if (BlendMode == 10) outc = blendExclusion(src, dst);

    fragColor = vec4(outc, color.a);
}
