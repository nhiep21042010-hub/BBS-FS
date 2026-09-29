package com.bbsvfx.bbsvfx.mixin.client;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import mchorse.bbs_mod.client.BBSShaders;
import net.minecraft.client.gl.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.SmearDissolveShader;
import com.bbsvfx.bbsvfx.client.BbsVfxImpactShader;

/**
 * Routes BBS model rendering through our {@code model_dissolve} program (a copy of BBS's model shader
 * plus a {@code Dissolve} uniform). Built alongside BBS's own shaders on {@code setup}, and returned in
 * place of the stock model program from {@code getModel} — with {@code Dissolve} at 0 it renders
 * identically, and the smear render raises it to dissolve trailing copies. Reliable, unlike overriding
 * the {@code model} shader file in place.
 *
 * <p>Also builds the impact-frame full-screen program ({@code impact}) here — the same shader-setup
 * point gives a valid GL context with resources loaded.</p>
 */
@Mixin(value = BBSShaders.class, remap = false)
public abstract class BBSShadersDissolveMixin
{
    @Inject(method = "setup", at = @At("TAIL"))
    private static void bbsvfx$buildDissolve(CallbackInfo ci)
    {
        SmearDissolveShader.build();
        BbsVfxImpactShader.build();
    }

    @ModifyReturnValue(method = "getModel", at = @At("RETURN"))
    private static ShaderProgram bbsvfx$useDissolve(ShaderProgram original)
    {
        ShaderProgram dissolve = SmearDissolveShader.get();

        return dissolve != null ? dissolve : original;
    }
}
