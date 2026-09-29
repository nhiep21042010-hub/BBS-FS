package com.bbsvfx.bbsvfx.mixin;

import mchorse.bbs_mod.resources.Link;
import mchorse.bbs_mod.utils.factory.MapFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.Map;

/**
 * Migration shim for the mod-id rename {@code xavin} → {@code bbsvfx}. Scenes, films and replays
 * saved before the rename store this addon's form / camera-clip type ids as {@code xavin:*}
 * (e.g. {@code xavin:explosion}, {@code xavin:follow_curve}); after the rename only the
 * {@code bbsvfx:*} keys are registered, so those old saves would fail to resolve their types and
 * load with the forms/clips silently dropped.
 *
 * <p>Both the form factory ({@code FormArchitect}) and the clip factories deserialize through
 * {@link MapFactory#create(Link)} (via {@code IFactory.fromData}), so remapping the requested type
 * here — only when the exact key is NOT registered, i.e. a real {@code xavin:*} registration by
 * another mod would still win — makes every old save load transparently. Saving is unaffected:
 * {@code factoryInverse} holds only the new {@code bbsvfx:*} keys, so a re-saved scene/film gets
 * the new id.</p>
 *
 * <p>Chosen over registering the same classes under the old {@code xavin:*} keys as aliases because
 * alias keys would show up as duplicate entries in BBS's clip-picker UI ({@code UIClips} iterates
 * {@code getKeys()}) and would fight the canonical key in {@code factoryInverse}.</p>
 */
@Mixin(value = MapFactory.class, remap = false)
public abstract class LegacyNamespaceMixin
{
    @Shadow
    protected Map<Link, Class<?>> factory;

    @ModifyVariable(method = "create", at = @At("HEAD"), argsOnly = true)
    private Link bbsvfx$remapLegacyNamespace(Link type)
    {
        /* LEGACY KEY: "xavin" is the pre-rename mod id; keep this remap as long as old saves matter. */
        if (type != null && "xavin".equals(type.source) && !this.factory.containsKey(type))
        {
            Link remapped = new Link("bbsvfx", type.path);

            if (this.factory.containsKey(remapped))
            {
                return remapped;
            }
        }

        return type;
    }
}
