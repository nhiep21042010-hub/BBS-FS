#version 150

uniform sampler2D DepthSampler;

/* x, y = light position in screen UV, z = light depth01, w = aspect (width / height) */
uniform vec4 FlareScreen;
/* x = strength, y = style (0 star, 1 anamorphic, 2 clean), z = animation phase, w unused */
uniform vec4 FlareParams;
uniform vec3 FlareColor;

in vec2 texCoord;

out vec4 fragColor;

/*
 * The LENS flare: not light in the world but the camera's answer to it — the vocabulary of Optical
 * Flares, rebuilt procedurally. Glow core, streaks (star blades or one anamorphic slash), the ghost
 * train of iris polygons marching down the optical axis through frame centre, a chromatic halo ring,
 * needle spikes and soft orbs. Behaviours are the ones that sell it: the flare dies when the source
 * hides behind geometry (depth-buffer occlusion), FLASHES as it leaves frame, and never quite holds
 * still (flicker off the shared phase clock).
 */

float hash1(float n)
{
    return fract(sin(n) * 43758.5453);
}

/* The same hue-wheel spectrum as the rest of the addon: 0 = red, 1 = violet. */
vec3 spectrum(float t)
{
    float h = t * 0.78;

    return clamp(abs(fract(h + vec3(0.0, 0.6667, 0.3333)) * 6.0 - 3.0) - 1.0, 0.0, 1.0);
}

/* Soft hexagon mask — the shape of an iris ghost. 1 inside, 0 outside. */
float hexMask(vec2 p, float radius, float soft)
{
    p = abs(p);

    float d = max(p.x * 0.866025 + p.y * 0.5, p.y);

    return smoothstep(radius, radius - soft, d);
}

void main()
{
    vec2 lightUv = FlareScreen.xy;
    float aspect = FlareScreen.w;

    /* Occlusion: a 4x4 depth kernel around the source. Partially hidden = partially flared, fully
       hidden = nothing — the single behaviour that makes a flare feel IN the scene, not pasted on. */
    float visibility = 0.0;

    for (int ky = 0; ky < 4; ky++)
    {
        for (int kx = 0; kx < 4; kx++)
        {
            vec2 offset = (vec2(float(kx), float(ky)) - 1.5) * 0.006;
            float stored = texture(DepthSampler, clamp(lightUv + offset, 0.001, 0.999)).r;

            visibility += stored >= FlareScreen.z - 0.0004 ? 1.0 : 0.0;
        }
    }

    visibility /= 16.0;

    float strength = FlareParams.x * visibility;

    if (strength < 0.002)
    {
        discard;
    }

    float phase = FlareParams.z;

    /* Flicker: two incommensurate sines — alive, never looping visibly. */
    strength *= 0.94 + 0.06 * sin(phase * 9.7) * sin(phase * 13.3 + 1.7);

    /* Edge flash: the Optical Flares signature — the flare BLOOMS as the source approaches the frame
       edge, then dies past it. */
    vec2 fromCentre = abs(lightUv - 0.5) * 2.0;
    float edge = max(fromCentre.x, fromCentre.y);

    strength *= (1.0 + smoothstep(0.55, 1.0, edge) * 1.3) * (1.0 - smoothstep(1.0, 1.35, edge));

    /* Aspect-corrected space so circles are circles. */
    vec2 p = (texCoord - lightUv) * vec2(aspect, 1.0);
    float r = length(p);
    float ang = atan(p.y, p.x);
    int style = int(FlareParams.y + 0.5);

    /*
     * The style table — each preset is a recipe over the same element set, the way an Optical Flares
     * preset is a stack of the same twelve objects. Append styles at the end only (ordinal-saved).
     *
     * 0 Star        — six blades and needle spikes, the all-round practical.
     * 1 Anamorphic  — the single wide blue slash.
     * 2 Clean       — core and halo only, for when the shot needs restraint.
     * 3 JJ          — triple dirty anamorphic lines, cool cast, muted core.
     * 4 Sun         — massive warm glow with dense fine rays.
     * 5 Searchlight — hot core, needles, prominent orbs and ring.
     * 6 Tactical    — small cold core, four hard blades, sparse ghosts.
     * 7 Vintage     — soft warm core, big double hoops, heavy washed ghost train, a hint of bokeh.
     * 8 Bokeh       — the defocused lens: a field of rimmed discs around a soft core.
     */
    float glowGain = 1.0;
    float bladeGain = 0.0;
    float bladeFreq = 3.0;
    float needleGain = 0.0;
    float anaGain = 0.0;
    float anaLines = 1.0;
    float rayGain = 0.0;
    float ringGain = 0.14;
    float ringRadius = 0.21;
    float hoopGain = 0.0;
    float ghostGain = 1.0;
    float orbGain = 0.0;
    float bokehGain = 0.0;
    vec3 cast = vec3(1.0);

    if (style == 0)
    {
        bladeGain = 0.55;
        needleGain = 0.3;
        orbGain = 1.0;
    }
    else if (style == 1)
    {
        anaGain = 1.15;
        orbGain = 1.0;
    }
    else if (style == 2)
    {
        ghostGain = 0.35;
    }
    else if (style == 3)
    {
        glowGain = 0.75;
        anaGain = 1.25;
        anaLines = 3.0;
        ghostGain = 0.5;
        ringGain = 0.08;
        cast = vec3(0.82, 0.92, 1.12);
    }
    else if (style == 4)
    {
        glowGain = 1.65;
        rayGain = 0.55;
        ringGain = 0.1;
        ringRadius = 0.27;
        ghostGain = 0.8;
        orbGain = 0.6;
        cast = vec3(1.12, 1.0, 0.82);
    }
    else if (style == 5)
    {
        glowGain = 1.35;
        needleGain = 0.5;
        ringGain = 0.2;
        ghostGain = 0.9;
        orbGain = 1.5;
    }
    else if (style == 6)
    {
        glowGain = 0.85;
        bladeGain = 0.35;
        bladeFreq = 2.0;
        needleGain = 0.12;
        ghostGain = 0.4;
        ringGain = 0.07;
        cast = vec3(0.85, 0.96, 1.1);
    }
    else if (style == 7)
    {
        glowGain = 0.8;
        ringGain = 0.2;
        ringRadius = 0.17;
        hoopGain = 0.13;
        ghostGain = 1.35;
        bokehGain = 0.4;
        cast = vec3(1.06, 1.0, 0.88);
    }
    else
    {
        glowGain = 0.7;
        ringGain = 0.05;
        ghostGain = 0.3;
        bokehGain = 1.0;
    }

    /* Normalised tint: the lamp's HUE at full brightness — the flare's energy lives in strength. */
    vec3 tint = FlareColor / max(max(FlareColor.r, max(FlareColor.g, FlareColor.b)), 0.001);

    tint *= cast;

    vec3 color = vec3(0.0);

    /* 1. Glow: a hot core inside a wide soft halo. */
    color += tint * (exp(-r * 26.0) * 1.5 + exp(-r * 5.0) * 0.16) * glowGain;

    /* 2. Star blades and needle spikes. */
    if (bladeGain > 0.0)
    {
        color += tint * pow(abs(cos(ang * bladeFreq + phase * 0.04)), 24.0) * exp(-r * 3.4) * bladeGain;
    }

    if (needleGain > 0.0)
    {
        color += tint * pow(abs(cos(ang * 17.0 + 1.3)), 60.0) * exp(-r * 9.0) * needleGain;
    }

    /* 3. Anamorphic slashes — one clean, or the JJ stack of three at drifting heights. */
    if (anaGain > 0.0)
    {
        for (int i = 0; i < 3; i++)
        {
            if (float(i) >= anaLines)
            {
                break;
            }

            float fi = float(i);
            float yOff = fi == 0.0 ? 0.0 : (hash1(fi * 11.3) - 0.5) * 0.14;
            float gain = fi == 0.0 ? 1.0 : 0.4;
            float slash = exp(-abs(p.y - yOff) * (200.0 / (1.0 + r * 1.5))) * exp(-abs(p.x) * 1.5);

            color += mix(tint, vec3(0.45, 0.65, 1.0), 0.72) * slash * anaGain * gain;
        }
    }

    /* 4. Dense fine rays — the sun's signature: two incommensurate spike families. */
    if (rayGain > 0.0)
    {
        float rays = pow(abs(cos(ang * 12.0 + 0.7)), 30.0) * 0.6
            + pow(abs(cos(ang * 29.0 + 2.1)), 50.0) * 0.5;

        color += tint * rays * exp(-r * 4.0) * rayGain;
    }

    /* 5. Halo ring: thin, chromatic across its width — red leaning in, violet out. */
    float ring = exp(-abs(r - ringRadius) * 110.0);

    color += spectrum(clamp((r - ringRadius) * 30.0 + 0.5, 0.0, 1.0)) * ring * ringGain;

    /* 6. Vintage hoops: two broad soft chromatic circles further out. */
    if (hoopGain > 0.0)
    {
        color += spectrum(clamp((r - 0.42) * 12.0 + 0.5, 0.0, 1.0)) * exp(-abs(r - 0.42) * 35.0) * hoopGain;
        color += spectrum(clamp((r - 0.62) * 12.0 + 0.5, 0.0, 1.0)) * exp(-abs(r - 0.62) * 45.0) * hoopGain * 0.6;
    }

    /* 7. The ghost train: iris polygons strung along the axis from the light THROUGH frame centre —
       the march every real lens performs. */
    vec2 axis = (vec2(0.5) - lightUv) * vec2(aspect, 1.0);

    for (int i = 0; i < 10; i++)
    {
        float fi = float(i);
        float along = mix(0.3, 2.05, fi / 9.0) + hash1(fi * 7.1) * 0.1;
        vec2 ghostPos = axis * along;
        float size = mix(0.014, 0.07, hash1(fi * 3.7)) * (0.55 + 0.75 * abs(along - 1.0));
        vec2 q = p - ghostPos;
        float body = hexMask(q, size, size * 0.55);
        float fringe = hexMask(q, size * 1.12, size * 0.35) - body;
        vec3 ghostColor = mix(tint, spectrum(hash1(fi * 5.3) * 0.9), 0.5);

        color += ghostGain * (ghostColor * body * (0.045 + 0.1 * hash1(fi * 9.4))
            + spectrum(hash1(fi * 2.9)) * max(fringe, 0.0) * 0.05);
    }

    /* 8. Bokeh: the defocused lens — flat discs with a BRIGHT RIM (spherical aberration's donut),
       chromatic at the edge, scattered around the source and drifting almost imperceptibly. */
    if (bokehGain > 0.0)
    {
        for (int i = 0; i < 12; i++)
        {
            float fi = float(i) + 40.0;
            float scatter = hash1(fi * 3.3) * 6.28318;
            float reach = mix(0.06, 0.55, pow(hash1(fi * 7.7), 1.4));
            vec2 bokehPos = vec2(cos(scatter), sin(scatter)) * reach
                + vec2(sin(phase * 0.11 + fi), cos(phase * 0.09 + fi * 1.7)) * 0.015;
            float bokehSize = mix(0.02, 0.085, hash1(fi * 5.1));
            float d = length(p - bokehPos);
            float disc = smoothstep(bokehSize, bokehSize * 0.9, d);
            float inner = smoothstep(bokehSize * 0.82, bokehSize * 0.72, d);
            float rim = max(disc - inner, 0.0);
            float weight = 0.5 + 0.5 * hash1(fi * 2.2);

            color += (tint * disc * 0.026
                + mix(tint, spectrum(hash1(fi * 9.1)), 0.45) * rim * 0.05) * bokehGain * weight;
        }
    }

    /* 9. Lens orbs: soft bubbles drifting off-axis. */
    if (orbGain > 0.0)
    {
        for (int i = 0; i < 4; i++)
        {
            float fi = float(i) + 20.0;
            vec2 orbPos = axis * mix(0.5, 1.6, hash1(fi * 3.1))
                + vec2(hash1(fi * 5.7) - 0.5, hash1(fi * 8.3) - 0.5) * 0.22;
            float orbSize = mix(0.02, 0.05, hash1(fi * 4.9));
            float orb = smoothstep(orbSize, orbSize * 0.35, length(p - orbPos));

            color += tint * orb * 0.035 * orbGain;
        }
    }

    vec3 outColor = color * strength;

    /* NaN/Inf guard — a bad pixel must not black the whole additive frame (see the beam/surface). */
    if (any(isnan(outColor)) || any(isinf(outColor)))
    {
        outColor = vec3(0.0);
    }

    fragColor = vec4(outColor, 1.0);
}
