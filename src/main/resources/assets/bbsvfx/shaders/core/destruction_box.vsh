#version 150

#moj_import <light.glsl>
#moj_import <fog.glsl>

in vec3 Position;
in vec4 Color;
in vec2 UV0;
in ivec2 UV1;
in ivec2 UV2;
in vec3 Normal;

/* Per-block shatter attributes (baked once). */
in vec3 BlockOrig;
in vec3 BlockRand;
in vec3 SpinAxis;
in float SpinMag;

uniform sampler2D Sampler1;
uniform sampler2D Sampler2;

uniform mat4 ModelViewMat;
uniform mat3 NormalMat;
uniform mat4 ProjMat;
uniform int FogShape;

uniform vec3 Light0_Direction;
uniform vec3 Light1_Direction;

/* Shatter uniforms — mirror DestructionBoxForm's localProgress / displacedPosition / blockSpin. */
uniform float uDestruction;
uniform int uPointMode;
uniform float uStagger;
uniform int uInvert;
uniform vec3 uPoint;
uniform float uMaxDist;
uniform vec3 uCenter;
uniform vec3 uDir;
uniform float uDirStrength;
uniform float uRadialStrength;
uniform float uRandomAmount;
uniform float uRotationAmount;
uniform float uPointStrength;
uniform int uPointAway;

out float vertexDistance;
out vec4 vertexColor;
out vec4 lightMapColor;
out vec4 overlayColor;
out vec2 texCoord0;
out vec4 normal;

/* Rodrigues rotation of v around a unit axis by angle (matches Quaternionf.fromAxisAngleRad). */
vec3 rotateAxis(vec3 v, vec3 axis, float angle)
{
    float c = cos(angle);
    float s = sin(angle);
    return v * c + cross(axis, v) * s + axis * dot(axis, v) * (1.0 - c);
}

float shatterProgress()
{
    float d = uDestruction;

    if (uPointMode == 0 || uStagger <= 0.0)
    {
        return d;
    }

    float st = clamp(uStagger, 0.0, 0.999);
    float t = uMaxDist > 1e-6 ? distance(BlockOrig, uPoint) / uMaxDist : 0.0;
    float order = (uInvert != 0) ? (1.0 - t) : t;
    float u = clamp((d - order * st) / (1.0 - st), 0.0, 1.0);

    return u * u * u * (u * (u * 6.0 - 15.0) + 10.0);
}

vec3 shatterOffset(float progress)
{
    vec3 disp = vec3(0.0);

    if (progress <= 0.0)
    {
        return disp;
    }

    if (uPointMode != 0)
    {
        if (uPointStrength != 0.0)
        {
            vec3 pull = (uPoint - BlockOrig) * uPointStrength;
            if (uPointAway != 0) pull = -pull;
            disp += pull;
        }
    }
    else
    {
        if (uDirStrength != 0.0)
        {
            disp += uDir * uDirStrength;
        }

        if (uRadialStrength != 0.0)
        {
            vec3 radial = BlockOrig - uCenter;
            if (dot(radial, radial) > 1e-6) disp += normalize(radial) * uRadialStrength;
        }
    }

    if (uRandomAmount != 0.0)
    {
        disp += BlockRand * uRandomAmount;
    }

    return disp * progress;
}

void main()
{
    float progress = shatterProgress();
    vec3 disp = shatterOffset(progress);

    /* Position is the block's rest vertex (BlockOrig + model vertex); centre it for the tumble pivot. */
    vec3 centered = Position - BlockOrig - 0.5;
    vec3 nrm = Normal;

    if (uRotationAmount > 0.0 && progress > 0.0)
    {
        float angle = uRotationAmount * progress * SpinMag * 6.28318530718;
        centered = rotateAxis(centered, SpinAxis, angle);
        nrm = rotateAxis(Normal, SpinAxis, angle);
    }

    vec3 pos = BlockOrig + disp + 0.5 + centered;

    gl_Position = ProjMat * ModelViewMat * vec4(pos, 1.0);

    vec3 fogPos = (ModelViewMat * vec4(pos, 1.0)).xyz;
    vertexDistance = (FogShape == 0) ? length(fogPos) : max(length(fogPos.xz), abs(fogPos.y));
    vec3 fixNormal = normalize(NormalMat * nrm);
    vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, fixNormal, Color);
    lightMapColor = texelFetch(Sampler2, UV2 / 16, 0);
    overlayColor = texelFetch(Sampler1, UV1, 0);
    texCoord0 = UV0;
    normal = ProjMat * ModelViewMat * vec4(nrm, 0.0);
}
