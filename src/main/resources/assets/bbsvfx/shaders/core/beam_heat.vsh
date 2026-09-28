#version 150

in vec3 Position;
in vec4 Color;
in vec2 UV0;

/* Captured world projection (RenderSystem's matrices at post time belong to the GUI). Positions
 * arrive already baked into view space. */
uniform mat4 uProj;

out vec4 vColor;
out vec2 vUv;

void main()
{
    gl_Position = uProj * vec4(Position, 1.0);
    vColor = Color;
    vUv = UV0;
}
