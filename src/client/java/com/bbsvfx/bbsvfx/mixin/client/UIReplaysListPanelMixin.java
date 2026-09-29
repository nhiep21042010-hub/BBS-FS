package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplayList;
import mchorse.bbs_mod.ui.film.replays.UIReplaysListPanel;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIIcon;
import mchorse.bbs_mod.ui.framework.elements.input.text.UITextbox;
import mchorse.bbs_mod.ui.utils.icons.Icons;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.DestructionCapture;
import com.bbsvfx.bbsvfx.client.BbsVfxIcons;

import java.util.List;
import java.util.function.Consumer;

/**
 * Adds a single "Capture" icon to the replay list bar that opens a dropdown menu with all the wand-
 * selection captures (Destruction Box / Explosion / Beam / levelling Dome) plus "Undo cut". Each capture
 * turns the wand selection into the matching VFX actor in the open film and cuts the real blocks (see
 * {@code DestructionCapture}); undo restores the last cut.
 */
@Mixin(UIReplaysListPanel.class)
public abstract class UIReplaysListPanelMixin
{
    /* BBS keeps these private; mirrored so the added icon lines up with its own. */
    @Unique
    private static final int BBSVFX_BAR_HEIGHT = 20;

    @Unique
    private static final int BBSVFX_ICON_SIZE = 20;

    @Unique
    private static final int BBSVFX_ICON_SLOT = BBSVFX_ICON_SIZE + 2;

    @Shadow @Final public UIReplayList replays;
    @Shadow @Final public UIElement bar;
    @Shadow @Final public UITextbox search;

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addCaptureButtons(UIFilmPanel panel, Consumer<List<Replay>> callback, Consumer<Form> formConsumer, Consumer<String> partConsumer, CallbackInfo ci)
    {
        UIIcon capture = new UIIcon(BbsVfxIcons.DESTRUCTION, (b) ->
            b.getContext().replaceContextMenu((menu) ->
            {
                menu.action(BbsVfxIcons.DESTRUCTION, IKey.constant("Capture Destruction Box"), () ->
                {
                    DestructionCapture.capture(this.replays.panel.getData());
                    this.bbsvfx$afterCapture();
                });
                menu.action(BbsVfxIcons.EXPLOSION, IKey.constant("Capture Explosion (sphere)"), () ->
                {
                    DestructionCapture.captureExplosion(this.replays.panel.getData());
                    this.bbsvfx$afterCapture();
                });
                menu.action(BbsVfxIcons.BEAM, IKey.constant("Capture devouring Beam"), () ->
                {
                    DestructionCapture.captureBeam(this.replays.panel.getData());
                    this.bbsvfx$afterCapture();
                });
                menu.action(BbsVfxIcons.DOME, IKey.constant("Capture levelling Dome"), () ->
                {
                    DestructionCapture.captureDome(this.replays.panel.getData());
                    this.bbsvfx$afterCapture();
                });
                menu.action(Icons.UNDO, IKey.constant("Undo the last cut"), DestructionCapture::undo);
            }));
        capture.tooltip(IKey.constant("Capture from wand selection…"));

        /* BBS 2.6 rebuilt this bar: the left button group (leftBar) is gone, and what is left is the
         * add-replay icon plus a search box that takes the rest of the row. The capture icon takes the
         * second icon slot and the search box gives up its width, which is the only free room there is. */
        capture.relative(this.bar).x(BBSVFX_ICON_SLOT).y(0).w(BBSVFX_ICON_SIZE).h(BBSVFX_BAR_HEIGHT);
        this.search.x(BBSVFX_ICON_SLOT * 2).w(1F, -BBSVFX_ICON_SLOT * 2);

        this.bar.add(capture);
    }

    /** Refresh the replay list and spawn the new actor immediately (mirrors UIReplayList.addReplay, which
     *  calls createEntities) so it shows without having to press Edit on the replay. */
    private void bbsvfx$afterCapture()
    {
        this.replays.refreshReplayList();
        this.replays.panel.getController().createEntities();
    }
}
