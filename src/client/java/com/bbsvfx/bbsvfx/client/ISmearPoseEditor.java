package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.ui.framework.elements.UIElement;

/**
 * Exposes the per-bone smear / lines widgets built onto {@code UIPoseEditor} as two separate blocks so
 * each channel's keyframe factory can re-add just its own block after it rebuilds the bone editor
 * layout (it {@code removeAll()}s and re-lays-out, dropping anything the editor added on its own):
 * the {@code smear_frames} factory shows {@link #bbsvfx$smearBlock()}, the {@code motion_lines} factory
 * shows {@link #bbsvfx$linesBlock()}, and the live pose editor shows neither. Lives in the client source
 * set because it references a client-only UI type.
 */
public interface ISmearPoseEditor
{
    UIElement bbsvfx$smearBlock();

    UIElement bbsvfx$linesBlock();
}
