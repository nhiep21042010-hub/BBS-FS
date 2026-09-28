package com.bbsvfx.bbsvfx.client;

/**
 * Duck interface on {@code ModelFormRenderer} (via {@code ModelFormRendererLinesMixin}) letting the smear
 * render draw the motion lines AFTER all its echo copies — so the lines read on top of the copies and bind
 * the swept arc (per the smear-deform concept), instead of being covered by copies drawn later.
 *
 * <p>The line render matrix is snapshotted during the crisp pass (when the stack is correctly posed) and
 * replayed here, because by the time the copies finish the form's render stack has been popped back.</p>
 */
public interface IMotionLineDrawer
{
    /** Draw the motion lines snapshotted on the crisp pass; no-op if none were deferred. */
    void bbsvfx$drawDeferredLines();
}
