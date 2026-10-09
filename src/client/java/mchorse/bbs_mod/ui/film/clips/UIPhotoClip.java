package mchorse.bbs_mod.ui.film.clips;

import mchorse.bbs_mod.camera.clips.misc.PhotoClip;
import mchorse.bbs_mod.data.types.MapType;
import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.Keys;
import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.film.IUIClipsDelegate;
import mchorse.bbs_mod.ui.film.UIClipsPanel;
import mchorse.bbs_mod.ui.film.replays.UIReplaysEditor;
import mchorse.bbs_mod.ui.film.utils.keyframes.UIFilmKeyframes;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITexturePicker;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeEditor;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.UIKeyframeSheet;
import mchorse.bbs_mod.utils.clips.Clips;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;

import java.util.List;

public class UIPhotoClip extends UIClip<PhotoClip>
{
    public UIButton pickTexture;
    public UIToggle smooth;
    public UIKeyframeEditor keyframes;
    public UIButton edit;

    public UIPhotoClip(PhotoClip clip, IUIClipsDelegate editor)
    {
        super(clip, editor);
    }

    @Override
    protected void registerUI()
    {
        super.registerUI();

        this.pickTexture = new UIButton(UIKeys.CAMERA_PANELS_IMAGE_PICK, (b) ->
        {
            UITexturePicker.open(this.getContext(), this.clip.texture.get(), (l) -> this.editor.editMultiple(this.clip.texture, (value) -> value.set(l)));
        });
        this.smooth = this.toggle(UIKeys.CAMERA_PANELS_IMAGE_SMOOTH, this.clip.smooth);

        this.keyframes = new UIKeyframeEditor((consumer) -> new UIFilmKeyframes(this.editor, consumer));
        this.keyframes.view.rulerRenderer((context) ->
        {
            UIReplaysEditor.renderRuler(context, this.keyframes.view, (UIClipsPanel) this.editor, (Clips) this.clip.getParent(), this.clip.tick.get());
        });
        this.keyframes.view.duration(() -> this.clip.duration.get());
        this.keyframes.setUndoId("photo_keyframes");

        this.edit = new UIButton(UIKeys.CAMERA_PANELS_EDIT_KEYFRAMES, (b) ->
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
            this.keyframes.view.getGraph().clearSelection();
        });
        this.edit.keys().register(Keys.FORMS_EDIT, () -> this.edit.clickItself());
    }

    @Override
    protected void registerPanels()
    {
        super.registerPanels();

        this.panels.add(this.section(UIKeys.CAMERA_PANELS_IMAGE_IMAGE, this.pickTexture, this.smooth));
        this.panels.add(this.section(UIKeys.C_CLIP.get("bbs:photo"), this.edit));
    }

    @Override
    public void fillData()
    {
        super.fillData();

        this.keyframes.view.removeAllSheets();

        List<KeyframeChannel<Double>> channels = this.clip.getChannels();

        for (int i = 0; i < channels.size(); i++)
        {
            KeyframeChannel<Double> channel = channels.get(i);
            int color = UIKeyframeEditor.COLORS[i % UIKeyframeEditor.COLORS.length];

            this.keyframes.view.addSheet(new UIKeyframeSheet(channel.getId(), IKey.constant(channel.getId()), color, channel, null));
        }
    }

    @Override
    public void applyUndoData(MapType data)
    {
        if (data.getString("embed").equals("photo"))
        {
            this.editor.embedView(this.keyframes);
            this.keyframes.view.resetView();
        }

        super.applyUndoData(data);
    }

    @Override
    public void collectUndoData(MapType data)
    {
        if (this.keyframes.hasParent())
        {
            data.putString("embed", "photo");
        }

        super.collectUndoData(data);
    }
}
