package com.bbsvfx.bbsvfx.mixin.client;

import net.minecraft.client.texture.AbstractTexture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import com.bbsvfx.bbsvfx.client.SmearRenderState;
import com.bbsvfx.bbsvfx.client.BbsVfxAtlasDissolve;

/**
 * Smear-frame dissolve for BLOCK forms: while a dissolving smear copy is drawn, hand back a holed copy of
 * the vanilla BLOCK ATLAS so the block's EntityCutout layer discards the holes (BBS renders blocks as
 * entities). This is the vanilla counterpart to {@code TextureManagerDissolveMixin} (which only catches
 * BBS's own {@code getTexture(Link)} path, used by model / billboard / label — not the block atlas).
 */
@Mixin(TextureManager.class)
public abstract class TextureManagerVanillaDissolveMixin
{
    @Inject(method = "getTexture(Lnet/minecraft/util/Identifier;)Lnet/minecraft/client/texture/AbstractTexture;", at = @At("RETURN"), cancellable = true)
    private void bbsvfx$dissolveBlockAtlas(Identifier id, CallbackInfoReturnable<AbstractTexture> cir)
    {
        if (!SmearRenderState.active || SmearRenderState.dissolve <= 0F
            || !PlayerScreenHandler.BLOCK_ATLAS_TEXTURE.equals(id))
        {
            return;
        }

        AbstractTexture original = cir.getReturnValue();
        AbstractTexture holed = BbsVfxAtlasDissolve.get(original, SmearRenderState.dissolve);

        if (holed != original)
        {
            cir.setReturnValue(holed);
        }
    }
}
