#version 150

in vec3 Position;

out vec2 texCoord;

/* The quad is submitted directly in clip space, so there is nothing to transform — this stage exists
   only to hand the fragment stage its UV. Keeping the matrices out of it means the pass cannot be
   thrown off by whatever view state the world render left behind. */
void main()
{
    gl_Position = vec4(Position.xy, 0.0, 1.0);
    texCoord = Position.xy * 0.5 + 0.5;
}
