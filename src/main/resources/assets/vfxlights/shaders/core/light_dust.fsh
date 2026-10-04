#version 150

uniform sampler2D DepthSampler;
/* x,y = mote centre in screen uv, z = mote depth01, w = alpha (dial x twinkle) */
uniform vec4 OrbScreen;
uniform vec3 OrbColor;

in vec2 texCoord;

out vec4 fragColor;

/* One dust mote: a soft BLURRED sphere — no hard edge anywhere, the glow just melts out. Drawn
   additively, occluded by the same depth snapshot the beams honour. */
void main()
{
    vec2 p = texCoord * 2.0 - 1.0;
    float d = length(p);

    if (d >= 1.0)
    {
        discard;
    }

    float disc = smoothstep(1.0, 0.0, d);

    disc *= disc;

    /* Die where scene geometry sits in front of the mote (slack widens with depth: depth01 is
       nonlinear and its world precision collapses with distance). */
    float scene = texture(DepthSampler, OrbScreen.xy).r;
    float slack = 0.0015 + OrbScreen.z * 0.004;
    float vis = smoothstep(OrbScreen.z - slack, OrbScreen.z + slack, scene);

    /* Stamp the SCENE depth: the caller runs a LEQUAL test against the live buffer, which already
       holds the first-person hand — this rejects the mote exactly where the hand covers it. */
    gl_FragDepth = scene;

    fragColor = vec4(OrbColor * (disc * OrbScreen.w * vis), 1.0);
}
