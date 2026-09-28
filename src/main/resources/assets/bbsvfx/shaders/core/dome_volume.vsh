#version 150

/* Fullscreen pass: Position already arrives in NDC (-1..1), UV0 in 0..1. No matrices. */
in vec3 Position;
in vec2 UV0;

out vec2 vUv;

void main()
{
    gl_Position = vec4(Position, 1.0);
    vUv = UV0;
}
