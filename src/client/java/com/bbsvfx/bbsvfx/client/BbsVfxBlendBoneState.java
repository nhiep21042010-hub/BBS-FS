package com.bbsvfx.bbsvfx.client;

import java.util.Collections;
import java.util.Set;

/**
 * Active set of bones to HIDE (collapse to zero scale) during a model render, used to isolate bones for
 * the per-bone blend: in the in-world pass the blended bones are hidden (so the rest render normally), and
 * in each offscreen blend pass everything EXCEPT that group's bones is hidden (so only the group renders,
 * to be composited with its mode). Read by {@code ModelFormRendererPoseMixin.getPose}.
 *
 * <p>Box-model caveat: hiding a bone via {@code scale=0} cleanly removes its cube only when vertices are
 * rigidly weighted to one bone (typical BBS box models); smooth-skinned meshes distort at the seams.</p>
 */
public final class BbsVfxBlendBoneState
{
    public static boolean active;
    public static Set<String> hide = Collections.emptySet();

    private BbsVfxBlendBoneState()
    {}

    public static void begin(Set<String> hide)
    {
        BbsVfxBlendBoneState.active = true;
        BbsVfxBlendBoneState.hide = hide;
    }

    public static void end()
    {
        BbsVfxBlendBoneState.active = false;
        BbsVfxBlendBoneState.hide = Collections.emptySet();
    }
}
