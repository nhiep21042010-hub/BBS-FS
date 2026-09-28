package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.forms.renderers.FormRenderingContext;

/**
 * Implemented by {@code ModelFormRenderer} (via {@code ModelFormRendererLinesMixin}) so the smear render
 * can trigger the arc motion-trail draw after the echo copies — the lines mixin already holds the bone
 * matrix cache, the texture-colour helper and the {@code Draw} setup, so the ribbon is drawn there.
 */
public interface IArcTrailDrawer
{
    void bbsvfx$drawArcTrail(FormRenderingContext context);
}
