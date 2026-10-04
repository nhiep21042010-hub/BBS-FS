#version 150

uniform sampler2D BeamSampler;
uniform sampler2D DepthSampler;
uniform sampler2D PostDepth;

/* x/y = one half-res texel in uv; HandMask = 1 in vanilla (first-person hand test). */
uniform vec2 BeamTexel;
uniform float HandMask;

in vec2 texCoord;

out vec4 fragColor;

/*
 * Depth-aware bilateral upsample of the half-res beam buffer (rgb = glow, a = marched scene
 * depth01, 0 = no beam here — the buffer is 0-cleared and every beam ADDs its depth in). The
 * neighbourhood gather is what kills the march's static dither grain — averaged over 3x3 taps it
 * falls below notice, and being STATIC it cannot crawl under a pack's TAA the way the old
 * per-frame re-randomised grain did. The depth weights keep beam glow from bleeding across
 * silhouettes (a fence post in front of a far beam stays clean).
 */
void main()
{
    float fullDepth = texture(DepthSampler, texCoord).r;
    vec4 center = texture(BeamSampler, texCoord);
    vec3 sum = vec3(0.0);
    float wsum = 0.0;

    for (int y = -1; y <= 1; y++)
    {
        for (int x = -1; x <= 1; x++)
        {
            vec4 s = texture(BeamSampler, texCoord + vec2(float(x), float(y)) * BeamTexel);
            float dd = s.a - fullDepth;
            /* Depth01 is nonlinear: near edges differ by ~1e-4, a wall-to-sky cut by ~1e-2.
               The constant sits between the two — same-surface taps pass, cross-silhouette taps die. */
            float dw = 1.0 / (1.0 + dd * dd * 25000.0);
            float sw = (x == 0 && y == 0) ? 1.0 : ((x == 0 || y == 0) ? 0.5 : 0.25);
            float w = dw * sw;

            sum += s.rgb * w;
            wsum += w;
        }
    }

    vec3 glow = sum / max(wsum, 0.00001);

    /* Vanilla first-person hand: live depth NEARER than the marched scene end means the hand (or
       anything rendered after LAST) hides that stretch of air — the glow was gathered up to
       geometry BEHIND it and must not draw over it. Packs manage their own hand. center.a > 0
       gates no-beam pixels (alpha is 0-cleared and ADDs the depth per beam; where two beams stack
       on a pixel the sum can over-reject — rare, and the alternative GL_MIN blend never reached
       the rasterizer). */
    if (HandMask > 0.5 && center.a > 0.0005 && texture(PostDepth, texCoord).r < center.a - 0.0005)
    {
        glow = vec3(0.0);
    }

    /* Dither the tail before the 8-bit target quantizes it: at the falloff edge the glow is a few
       code values over black, and a smooth radial gradient turns into concentric rings (the
       "фантомные круги" report). ±0.5 LSB of static interleaved-gradient noise trades the bands
       for grain the eye cannot see, and is invisible on 16F targets too. */
    float ign = fract(52.9829189 * fract(dot(gl_FragCoord.xy, vec2(0.06711056, 0.00583715))));

    glow += (ign - 0.5) / 255.0;

    fragColor = vec4(glow, 1.0);
}
