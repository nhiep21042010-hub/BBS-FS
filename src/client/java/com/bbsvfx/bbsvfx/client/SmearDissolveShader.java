package com.bbsvfx.bbsvfx.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceFactory;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.util.Optional;

/**
 * Our own copy of BBS's model shader plus a {@code Dissolve} uniform, loaded as a uniquely-named
 * program ({@code model_dissolve}) so there is no resource collision with BBS's {@code model} shader —
 * overriding that one in-place was non-deterministic (it would win some launches, lose others, so the
 * dissolve kept "breaking"). {@code BBSShadersDissolveMixin} makes {@code BBSShaders.getModel()} return
 * this program, so all model rendering goes through it; with {@code Dissolve} at 0 it is identical to
 * the original, and the smear render raises it per copy.
 */
public final class SmearDissolveShader
{
    private static ShaderProgram program;

    private SmearDissolveShader()
    {}

    public static ShaderProgram get()
    {
        return program;
    }

    /** (Re)build the program. Call from BBS's shader setup (valid GL context, after resources load). */
    public static void build()
    {
        if (program != null)
        {
            program.close();
            program = null;
        }

        try
        {
            program = new ShaderProgram(bbsvfx$factory(), "model_dissolve", VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
    }

    /** Mirrors BBS's ProxyResourceFactory: core shader files live under the {@code bbs} namespace. */
    private static ResourceFactory bbsvfx$factory()
    {
        return (id) ->
        {
            ResourceFactory manager = MinecraftClient.getInstance().getResourceManager();

            if (id.getPath().contains("/core/"))
            {
                return manager.getResource(Identifier.of("bbs", id.getPath()));
            }

            return manager.getResource(id);
        };
    }
}
