#version 150

in vec3 Position;
in vec4 Color;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out float viewDepth;

void main()
{
    vec4 viewPos = ModelViewMat * vec4(Position, 1.0);

    gl_Position = ProjMat * viewPos;
    vertexColor = Color;
    /* Depth along the view axis, in BLOCKS — matches the dominant-axis depth the cube lookups use
     * and the linearized depth of the spot lookup. */
    viewDepth = -viewPos.z;
}
