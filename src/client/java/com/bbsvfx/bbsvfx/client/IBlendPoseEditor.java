package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * Exposes the per-bone blend widgets built onto {@code UIPoseEditor} ({@code UIPoseEditorBlendMixin}) as a
 * block the {@code blend} channel's keyframe factory re-adds after it rebuilds the bone-editor layout
 * (mirrors {@link ISmearPoseEditor}).
 */
public interface IBlendPoseEditor
{
    UIElement bbsvfx$blendBlock();

    /** Refresh the whole-model controls from the current pose (called after wiring the pose late). */
    void bbsvfx$syncBlend();
}
