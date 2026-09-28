package com.bbsvfx.bbsvfx.client;

import net.minecraft.client.gl.ShaderProgram;

/** Holds the custom {@code destruction_box} / {@code destruction_ballistic} core shaders (registered in {@link BbsVfxClient}). */
public final class DestructionShader
{
    public static ShaderProgram PROGRAM;

    /** Tier-C ballistic debris: instanced cubes, closed-form flight in the vertex shader. */
    public static ShaderProgram BALLISTIC;

    /** Dome world-levelling: static block geometry, radial-front settle+dissolve in the vertex shader. */
    public static ShaderProgram DOME;

    private DestructionShader()
    {
    }
}
