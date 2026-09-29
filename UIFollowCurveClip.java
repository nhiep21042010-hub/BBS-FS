package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.clips.UIClip;
import mchorse.bbs_mod.ui.film.clips.modules.UIPointModule;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIAnchorKeyframeFactory;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.UIConstants;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.colors.Colors;
import com.bbsvfx.bbsvfx.camera.FollowCurveClip;

/**
 * Editor panel for the {@link FollowCurveClip} camera modifier: pick the curve actor, animate the
 * arc-length {@code progress} along the curve via a keyframe channel (like a path clip), nudge the
 * result with a position offset, and toggle tangent alignment.
 */
public class UIFollowCurveClip extends UIClip<FollowCurveClip>
{
    public UIButton selector;
    public UIToggle align;
    public UIPointModule offset;
    public UIKeyframeEditor keyframes;
    public UIButton edit;

    public UIFollowCurveClip(FollowCurveClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.selector = new UIButton(IKey.constant("Curve actor"), (b) ->
        {
            UIFilmPanel panel = this.getParent(UIFilmPanel.class);

            if (panel != null)
            {
                UIAnchorKeyframeFactory.displayActors(this.getContext(), panel.getController().getEntities(),
                    this.clip.selector.get(), (i) -> this.clip.selector.set(i));
            }
        });

        this.align = new UIToggle(IKey.constant("Align to curve"), (b) -> this.clip.align.set(b.getValue()));
        this.offset = new UIPointModule(this.editor, IKey.constant("Offset")).contextMenu();

        /* Keyframe editor for the along-curve offset (embedded into the timeline area on Edit). */
        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.rulerRenderer((context) ->
            UIReplaysEditor.renderRuler(context, this.keyframes.view, (UIClipsPanel) this.editor,
                (Clips) this.clip.getParent(), this.clip.tick.get()));
        this.keyframes.view.single().duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("bbsvfx_follow_curve_progress");

        this.edit = new UIButton(IKey.constant("Edit offset keyframes"), (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.editSheet(this.keyframes.view.getGraph().getSheets().get(0));
            this.keyframes.view.getGraph().clearSelection();
        });
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(BbsVfxUI.section(IKey.constant("Follow curve"), this.selector, this.align));
        this.panels.add(this.offset.marginTop(UIConstants.SECTION_GAP));
        this.panels.add(BbsVfxUI.section(IKey.constant("Offset along curve"), this.edit));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.align.setValue(this.clip.align.get());
        this.offset.fill(this.clip.offset);
        this.keyframes.setChannel(this.clip.progress, Colors.ACTIVE);
    }
}
