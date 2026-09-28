#version 150

uniform sampler2D Sampler0;
uniform sampler2D Sampler1; // silhouette coverage (alpha = actor coverage)

uniform float Silhouette;   // negative-space / silhouette amount (0..1)
uniform vec3 SilColor;      // silhouette fill
uniform vec3 BgColor;       // negative-space background

uniform float SilStrokes;       // rough directional strokes on the silhouette (0..1)
uniform float SilStrokeAngle;   // stroke direction (degrees)
uniform float SilStrokeLength;  // directional streak length (uv)
uniform float SilStrokeScale;   // stroke frequency across the direction
uniform float SilStrokeRough;   // stroke roughness

uniform float InkBurst;     // rough ink burst amount (0..1)
uniform vec3 InkColor;      // ink colour
uniform float InkRadius;    // outer reach (screen uv)
uniform float InkInner;     // clear core radius
uniform float InkSpikes;    // spike count / angular frequency
uniform float InkRough;     // edge roughness / gap amount
uniform float InkSeed;

uniform float Shockwave;        // expanding ring amount (0..1)
uniform float ShockwaveProgress;// clip progress 0..1 (drives the radius)
uniform float ShockwaveRadius;  // max radius (screen uv)
uniform float ShockwaveWidth;   // ring thickness
uniform vec3 ShockwaveColor;

uniform float FlashStar;        // contact flash star amount (0..1)
uniform float FlashStarSize;    // ray length
uniform float FlashStarWidth;   // ray half-width
uniform float FlashStarGlow;    // central glow radius (fraction of size)
uniform float FlashStarRotation;// degrees
uniform vec3 FlashStarColor;

uniform float Invert;
uniform float Flash;
uniform float Grayscale;
uniform float Threshold;
uniform float ThresholdLevel;
uniform float ThresholdSoft;
uniform float Chroma;
uniform vec3 FlashColor;
uniform vec3 DarkColor;
uniform vec3 LightColor;
uniform vec2 Focus;

uniform float ZoomBlur;
uniform float BlurMode; // 0 = zoom (radial), 1 = horizontal, 2 = vertical
uniform float ZoomLines;
uniform float LinesCount;
uniform float LinesThickness;
uniform float LinesInner;
uniform float LinesMode; // 0 = zoom (radial), 1 = vertical, 2 = horizontal
uniform float LinesSeed;
uniform vec3 LinesColor;

uniform float Shapes;
uniform float ShapesCount;
uniform float ShapesSize;
uniform float ShapesSpread;
uniform float CenterStar;
uniform float CenterCircle;
uniform vec3 ShapesColor;

uniform float Aspect;

in vec2 texCoord;

out vec4 fragColor;

const vec3 LUMA = vec3(0.299, 0.587, 0.114);
const float TAU = 6.2831853;
const int BLUR_SAMPLES = 8;
const int SHAPES_MAX = 24;

float hash(float n)
{
    return fract(sin(n * 12.9898) * 43758.5453);
}

float hash2(vec2 p)
{
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// Value noise (smooth) for the rough ink edges.
float vnoise(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);

    float a = hash2(i);
    float b = hash2(i + vec2(1.0, 0.0));
    float c = hash2(i + vec2(0.0, 1.0));
    float d = hash2(i + vec2(1.0, 1.0));

    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Fractal (multi-octave) noise — gives jagged, self-similar edges instead of soft blobs.
float fbm(vec2 p)
{
    float v = 0.0;
    float a = 0.5;

    for (int i = 0; i < 4; i++)
    {
        v += a * vnoise(p);
        p = p * 2.03 + 1.7;
        a *= 0.5;
    }

    return v;
}

// Rough, hand-drawn radial ink burst from the focus. The look is FLAT ink (no gradients) with a JAGGED
// fractal outline and crisp radial dashes breaking the body into brush strokes — edges are anti-aliased to
// ~1px via fwidth only (wide smoothsteps were what made it read soft/smooth).
float inkBurstAt(vec2 uv)
{
    if (InkBurst <= 0.001)
    {
        return 0.0;
    }

    vec2 d = (uv - Focus) * vec2(Aspect, 1.0);
    float r = length(d);
    float ang = atan(d.y, d.x) / TAU + 0.5;          // 0..1 around the focus

    // Jagged outer boundary per angle (fractal) -> spiky silhouette, sharp not blobby.
    float reach = mix(InkInner + 0.02, InkRadius, fbm(vec2(ang * InkSpikes, InkSeed * 3.7)));
    reach += InkRough * 0.25 * InkRadius * (fbm(vec2(ang * InkSpikes * 3.0 + 7.0, InkSeed)) - 0.5);

    float aa = fwidth(r) + 1e-4;
    float inside = 1.0 - smoothstep(reach, reach + aa, r);            // hard (aa-thin) edge
    float core = 1.0 - smoothstep(InkInner, InkInner + aa, r);        // solid core

    // Radial dashes that break the body into strokes — crisp threshold (only fwidth-AA), more gaps as
    // Rough rises. Kept out of the inner core so the centre stays solid.
    float dashN = fbm(vec2(ang * InkSpikes * 1.5 + 31.0, r * 6.0 + InkSeed));
    float dw = fwidth(dashN) + 1e-4;
    float dash = smoothstep(0.42 - dw, 0.42 + dw, dashN + (0.5 - InkRough * 0.5));
    float body = inside * mix(1.0, dash, smoothstep(InkInner, reach, r));

    return clamp(max(core, body), 0.0, 1.0);
}

// Silhouette coverage with optional rough directional brush strokes: streak the actor coverage along the
// stroke direction and break it with a directional streaky noise (low frequency along the stroke, high
// across), so the actor reads as bold charcoal strokes rather than a flat fill.
float silCoverage(vec2 uv)
{
    float cov = texture(Sampler1, uv).a;

    if (SilStrokes <= 0.001)
    {
        return cov;
    }

    float a = radians(SilStrokeAngle);
    vec2 dir = vec2(cos(a), sin(a));

    /* Streak the coverage backward along the direction, then keep ONLY the part that extends BEYOND the
     * silhouette (streak - cov). The interior stays solid; the strokes are an edge/outward effect. */
    float streak = 0.0;

    for (int i = 1; i <= 6; i++)
    {
        float t = float(i) / 6.0 * SilStrokeLength;
        streak = max(streak, texture(Sampler1, uv - dir * t).a);
    }

    float outside = max(streak - cov, 0.0);

    /* Directional streaky noise -> stroke bands aligned to the direction; breaks the outward streak into
     * separate brush strokes coming off the edge. */
    vec2 lr = vec2(dot(uv, dir), dot(uv, vec2(-dir.y, dir.x)));
    float n = fbm(vec2(lr.x * 6.0, lr.y * SilStrokeScale));
    float strokeMask = smoothstep(0.55 - 0.5 * SilStrokeRough, 0.55 + 0.5 * SilStrokeRough, n);

    float strokes = outside * strokeMask;

    /* Solid interior + brushy directional strokes off the edge. */
    return max(cov, strokes * SilStrokes);
}

// One tapered needle (a star point), screen-space anti-aliased via fwidth so the edge stays crisp at
// any zoom instead of going jagged.
float needle(vec2 q, float len, float w)
{
    q = abs(q);

    if (q.x > len)
    {
        return 0.0;
    }

    float wAt = w * (1.0 - q.x / len);
    float aa = fwidth(q.y) + 1e-5;

    return 1.0 - smoothstep(wAt - aa, wAt + aa, q.y);
}

// 4-point star (sparkle) = two perpendicular needles.
float starCoverage(vec2 q, float sz)
{
    return max(needle(q, sz, sz * 0.22), needle(vec2(q.y, q.x), sz, sz * 0.22));
}

// Ring (outline circle), anti-aliased via fwidth.
float ringCoverage(vec2 q, float sz)
{
    float stroke = sz * 0.14;
    float d = abs(length(q) - sz) - stroke;
    float aa = fwidth(length(q)) + 1e-5;

    return 1.0 - smoothstep(-aa, aa, d);
}

// Scene colour at uv, with chromatic aberration (R/B split radially from the focus).
vec3 sceneColor(vec2 uv, float caOff)
{
    vec2 dir = uv - Focus;
    vec3 c;
    c.r = texture(Sampler0, uv + dir * caOff).r;
    c.g = texture(Sampler0, uv).g;
    c.b = texture(Sampler0, uv - dir * caOff).b;
    return c;
}

// Concentration / speed lines coverage at uv. Mode: 0 = zoom (radial from focus), 1 = vertical,
// 2 = horizontal. The inner radius keeps a clear band around the focus (negative space).
float linesAt(vec2 uv)
{
    if (ZoomLines <= 0.001 || LinesCount < 1.0)
    {
        return 0.0;
    }

    float coord;
    float dist;

    if (LinesMode < 0.5)
    {
        vec2 fd = (uv - Focus) * vec2(Aspect, 1.0);
        dist = length(fd) * 2.0;
        coord = atan(fd.y, fd.x) / TAU + 0.5;
    }
    else if (LinesMode < 1.5)
    {
        coord = uv.x;
        dist = abs(uv.x - Focus.x) * 2.0;
    }
    else
    {
        coord = uv.y;
        dist = abs(uv.y - Focus.y) * 2.0;
    }

    float x = coord * LinesCount;
    float idx = floor(x);
    float f = fract(x) - 0.5;
    float r = hash(idx + LinesSeed * 131.0);

    // Crisp line of half-width hw centred in each sector, screen-space anti-aliased via fwidth so it
    // stays clean at any zoom / distance from the focus.
    float hw = clamp(LinesThickness, 0.02, 1.0) * 0.5 * (0.4 + r);
    float aa = fwidth(x) + 1e-5;
    float line = 1.0 - smoothstep(hw - aa, hw + aa, abs(f));

    float inner = clamp(LinesInner, 0.0, 1.0) * (0.7 + 0.6 * r);
    float radial = smoothstep(inner, inner + 0.25, dist);

    return line * radial;
}

// Scattered sparkles coverage at uv — a mix of 4-point stars and ring (outline) circles.
float shapesAt(vec2 uv)
{
    if (Shapes <= 0.001 || ShapesCount < 1.0)
    {
        return 0.0;
    }

    float cover = 0.0;

    for (int i = 0; i < SHAPES_MAX; i++)
    {
        if (float(i) >= ShapesCount)
        {
            break;
        }

        float fi = float(i);
        vec2 off = (vec2(hash(fi * 2.0 + 1.0), hash(fi * 2.0 + 7.0)) - 0.5) * ShapesSpread;
        vec2 p = Focus + off;
        float sz = ShapesSize * (0.5 + hash(fi * 3.0 + 3.0));
        vec2 q = (uv - p) * vec2(Aspect, 1.0);

        float c = (hash(fi * 5.0 + 2.0) < 0.5) ? starCoverage(q, sz) : ringCoverage(q, sz);

        cover = max(cover, c);
    }

    return cover;
}

// Shockwave ring from the focus: radius = ShockwaveRadius * ShockwaveProgress (manual progress slider);
// the ring thins a little as it expands. Opacity is the Shockwave amount. Anti-aliased via fwidth.
float shockwaveAt(vec2 uv)
{
    if (Shockwave <= 0.001)
    {
        return 0.0;
    }

    vec2 fd = (uv - Focus) * vec2(Aspect, 1.0);
    float r = length(fd);
    float rad = ShockwaveRadius * ShockwaveProgress;
    float stroke = ShockwaveWidth * 0.5 * (1.0 - 0.6 * ShockwaveProgress);
    float d = abs(r - rad) - stroke;
    float aa = fwidth(r) + 1e-5;
    float ring = 1.0 - smoothstep(-aa, aa, d);

    return ring;
}

// Contact flash star at the focus: two long perpendicular tapered needles (a 4-point cross-star) plus a
// soft central glow. Rotatable; coverage AA'd by the needle()/fwidth path.
float flashStarAt(vec2 uv)
{
    if (FlashStar <= 0.001)
    {
        return 0.0;
    }

    vec2 q = (uv - Focus) * vec2(Aspect, 1.0);

    float a = radians(FlashStarRotation);
    float ca = cos(a);
    float sa = sin(a);
    vec2 qr = vec2(q.x * ca - q.y * sa, q.x * sa + q.y * ca);

    float rays = max(needle(qr, FlashStarSize, FlashStarWidth), needle(vec2(qr.y, qr.x), FlashStarSize, FlashStarWidth));

    /* Soft central glow (a bright hot spot at the contact point). */
    float glowR = max(FlashStarSize * FlashStarGlow, 1e-4);
    float glow = (FlashStarGlow > 0.001) ? (1.0 - smoothstep(0.0, glowR, length(q))) : 0.0;

    return max(rays, glow);
}

// A single star and/or ring circle placed exactly at the focus (deliberate centre piece).
float centerShapeAt(vec2 uv)
{
    vec2 q = (uv - Focus) * vec2(Aspect, 1.0);
    float cover = 0.0;

    if (CenterStar > 0.001)
    {
        cover = max(cover, starCoverage(q, ShapesSize) * CenterStar);
    }

    if (CenterCircle > 0.001)
    {
        cover = max(cover, ringCoverage(q, ShapesSize) * CenterCircle);
    }

    return cover;
}

// Base image (scene + lines + shapes) composited BEFORE the zoom blur, so the blur smears everything.
vec3 composite(vec2 uv, float caOff)
{
    vec3 c = sceneColor(uv, caOff);

    // Rough ink burst from the focus, drawn BEHIND the silhouette (over the negative-space background).
    float ink = inkBurstAt(uv) * clamp(InkBurst, 0.0, 1.0);

    // Silhouette / negative space: replace the scene with a flat background and the actor coverage filled
    // flat, BEFORE the lines/shapes/blur so they all draw over the redrawn frame. coverage = Sampler1.a.
    if (Silhouette > 0.001)
    {
        float cov = silCoverage(uv);
        vec3 bg = mix(BgColor, InkColor, ink);       // ink sits on the background, the subject covers it
        c = mix(c, mix(bg, SilColor, cov), clamp(Silhouette, 0.0, 1.0));
    }
    else
    {
        c = mix(c, InkColor, ink);                    // no silhouette: ink over the scene
    }

    c = mix(c, LinesColor, clamp(linesAt(uv) * ZoomLines, 0.0, 1.0));
    c = mix(c, ShapesColor, clamp(shapesAt(uv) * Shapes, 0.0, 1.0));
    c = mix(c, ShapesColor, clamp(centerShapeAt(uv), 0.0, 1.0));
    c = mix(c, ShockwaveColor, clamp(shockwaveAt(uv) * Shockwave, 0.0, 1.0));
    c = mix(c, FlashStarColor, clamp(flashStarAt(uv) * FlashStar, 0.0, 1.0));
    return c;
}

void main()
{
    float caOff = clamp(Chroma, 0.0, 1.0) * 0.05;
    float zb = clamp(ZoomBlur, 0.0, 1.0);

    // Directional / zoom blur: average the composited base over taps so it blurs the scene AND the
    // lines/shapes. Mode 0 = zoom (pure radial toward the focus — no tangential swirl), 1 = horizontal,
    // 2 = vertical. With no blur it collapses to a single tap.
    vec3 col = vec3(0.0);
    float alpha = 0.0;
    int n = 0;

    for (int i = 0; i < BLUR_SAMPLES; i++)
    {
        if (i > 0 && zb < 0.001)
        {
            break;
        }

        float s = float(i) / float(BLUR_SAMPLES - 1);
        vec2 uv;

        if (BlurMode < 0.5)
        {
            uv = mix(texCoord, Focus, s * zb * 0.5);            // zoom (radial)
        }
        else if (BlurMode < 1.5)
        {
            uv = texCoord + vec2((s - 0.5) * zb * 0.3, 0.0);    // horizontal
        }
        else
        {
            uv = texCoord + vec2(0.0, (s - 0.5) * zb * 0.3);    // vertical
        }

        col += composite(uv, caOff);
        alpha += texture(Sampler0, uv).a;
        n++;
    }

    col /= float(n);
    alpha /= float(n);

    // Grayscale (luma desaturate).
    float l = dot(col, LUMA);
    col = mix(col, vec3(l), clamp(Grayscale, 0.0, 1.0));

    // Priority 2 - threshold duotone: below the level -> dark colour, above -> light colour.
    float t = smoothstep(ThresholdLevel - ThresholdSoft, ThresholdLevel + ThresholdSoft, dot(col, LUMA));
    col = mix(col, mix(DarkColor, LightColor, t), clamp(Threshold, 0.0, 1.0));

    // Invert, then flash.
    col = mix(col, vec3(1.0) - col, clamp(Invert, 0.0, 1.0));
    col = mix(col, FlashColor, clamp(Flash, 0.0, 1.0));

    fragColor = vec4(col, alpha);
}
