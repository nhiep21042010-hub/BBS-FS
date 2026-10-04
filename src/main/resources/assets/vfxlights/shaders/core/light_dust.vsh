#version 150

in vec3 Position;
in vec2 UV0;

out vec2 texCoord;

/* NDC billboard quad emitted CPU-side per dust mote; UV0 carries the quad-local 0..1 for the
   radial falloff (Position is NDC — deriving the local uv from it only works for fullscreen quads). */
void main()
{
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = UV0;
}
