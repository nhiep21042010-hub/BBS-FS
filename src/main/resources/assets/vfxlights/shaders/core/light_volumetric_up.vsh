#version 150

in vec3 Position;

out vec2 texCoord;

/* Clip-space quad, same contract as the volumetric march it upsamples. */
void main()
{
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
