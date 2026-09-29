package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.l10n.keys.IKey;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIButton;
import mchorse.bbs_mod.ui.framework.elements.buttons.UIToggle;
import mchorse.bbs_mod.ui.framework.elements.input.UITrackpad;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIListOverlayPanel;
import mchorse.bbs_mod.ui.framework.elements.overlay.UIOverlay;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.pose.UIPoseEditor;
import mchorse.bbs_mod.utils.pose.Pose;
import mchorse.bbs_mod.utils.pose.PoseTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.IBlendPoseEditor;
import com.bbsvfx.bbsvfx.client.BbsVfxUI;
import com.bbsvfx.bbsvfx.forms.BlendMode;
import com.bbsvfx.bbsvfx.forms.IBlendBone;

import java.util.List;
import java.util.function.Consumer;

/**
 * Adds the {@code blend} channel's controls to the pose editor: a WHOLE-MODEL section (toggle + mode +
 * strength, stored in the {@link IBlendBone#WHOLE_BONE} sentinel of the pose) and a per-bone "Bone blend"
 * block (mode + strength for the selected bone(s)). Both edit the same keyframed pose, so the whole-vs-per
 * choice and all parameters live in this one track and apply immediately. Mirrors {@code UIPoseEditorSmearMixin}.
 */
@Mixin(UIPoseEditor.class)
public abstract class UIPoseEditorBlendMixin implements IBlendPoseEditor
{
    @Shadow
    private Pose pose;

    @Shadow
    private void forEachSelectedPose(Consumer<? super PoseTransform> consumer)
    {
        throw new AssertionError();
    }

    @Unique private UIToggle bbsvfx$wholeToggle;
    @Unique private UIButton bbsvfx$wholeMode;
    @Unique private UITrackpad bbsvfx$wholeFactor;
    @Unique private UIButton bbsvfx$blendMode;
    @Unique private UITrackpad bbsvfx$blendFactor;
    @Unique private UIElement bbsvfx$blendBlock;

    @Override
    public UIElement bbsvfx$blendBlock()
    {
        return this.bbsvfx$blendBlock;
    }

    @Override
    public void bbsvfx$syncBlend()
    {
        this.bbsvfx$syncWhole();
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void bbsvfx$addBlend(CallbackInfo ci)
    {
        /* Whole-model: applies one blend to the entire form (stored in the WHOLE_BONE sentinel). */
        this.bbsvfx$wholeToggle = new UIToggle(IKey.constant("Blend whole model"), (b) ->
        {
            IBlendBone w = this.bbsvfx$whole();

            if (w != null)
            {
                if (b.getValue())
                {
                    if (w.bbsvfx$blendMode() == BlendMode.NORMAL.ordinal())
                    {
                        w.bbsvfx$setBlendMode(BlendMode.ADD.ordinal());
                    }
                }
                else
                {
                    w.bbsvfx$setBlendMode(BlendMode.NORMAL.ordinal());
                }

                this.bbsvfx$syncWhole();
            }
        });

        this.bbsvfx$wholeMode = new UIButton(IKey.constant("Blend mode"), (b) -> this.bbsvfx$openWholePicker());

        this.bbsvfx$wholeFactor = new UITrackpad((v) ->
        {
            IBlendBone w = this.bbsvfx$whole();

            if (w != null)
            {
                w.bbsvfx$setBlendFactor(v.floatValue());
            }
        });
        this.bbsvfx$wholeFactor.limit(0, 1).values(0.05F);
        this.bbsvfx$wholeFactor.tooltip(IKey.constant("Whole-model blend strength"));

        /* Per-bone: applies to the selected bone(s). */
        this.bbsvfx$blendMode = new UIButton(IKey.constant("Blend mode"), (b) -> this.bbsvfx$openPicker());

        this.bbsvfx$blendFactor = new UITrackpad((v) -> this.bbsvfx$forEach((s) -> s.bbsvfx$setBlendFactor(v.floatValue())));
        this.bbsvfx$blendFactor.limit(0, 1).values(0.05F);
        this.bbsvfx$blendFactor.tooltip(IKey.constant("Blend strength"));

        this.bbsvfx$blendBlock = UI.column(
            BbsVfxUI.section(IKey.constant("Whole model"), this.bbsvfx$wholeToggle, this.bbsvfx$wholeMode, this.bbsvfx$wholeFactor),
            BbsVfxUI.section(IKey.constant("Bone blend"), this.bbsvfx$blendMode, this.bbsvfx$blendFactor));
    }

    /** The whole-model sentinel entry (auto-created on the pose), or null if no pose. */
    @Unique
    private IBlendBone bbsvfx$whole()
    {
        return this.pose == null ? null : (IBlendBone) this.pose.getOrCreate(IBlendBone.WHOLE_BONE);
    }

    @Unique
    private void bbsvfx$forEach(Consumer<IBlendBone> action)
    {
        this.forEachSelectedPose((pt) -> action.accept((IBlendBone) pt));
    }

    @Unique
    private void bbsvfx$openWholePicker()
    {
        UIListOverlayPanel overlay = new UIListOverlayPanel(IKey.constant("Blend mode"), (value) ->
        {
            int index = BlendMode.DISPLAY_NAMES.indexOf(value);
            IBlendBone w = this.bbsvfx$whole();

            if (index >= 0 && w != null)
            {
                w.bbsvfx$setBlendMode(index);
                this.bbsvfx$syncWhole();
            }
        });

        overlay.addValues(BlendMode.DISPLAY_NAMES);
        UIOverlay.addOverlay(((UIElement) (Object) this).getContext(), overlay);
    }

    @Unique
    private void bbsvfx$openPicker()
    {
        UIListOverlayPanel overlay = new UIListOverlayPanel(IKey.constant("Blend mode"), (value) ->
        {
            int index = BlendMode.DISPLAY_NAMES.indexOf(value);

            if (index >= 0)
            {
                this.bbsvfx$forEach((s) -> s.bbsvfx$setBlendMode(index));
                this.bbsvfx$blendMode.label = IKey.constant(value);
            }
        });

        overlay.addValues(BlendMode.DISPLAY_NAMES);
        UIOverlay.addOverlay(((UIElement) (Object) this).getContext(), overlay);
    }

    @Unique
    private void bbsvfx$syncWhole()
    {
        PoseTransform wp = this.pose == null ? null : this.pose.transforms.get(IBlendBone.WHOLE_BONE);
        int wm = wp instanceof IBlendBone w ? w.bbsvfx$blendMode() : 0;

        this.bbsvfx$wholeToggle.setValue(wm != BlendMode.NORMAL.ordinal());
        this.bbsvfx$wholeMode.label = IKey.constant(BlendMode.byIndex(wm).display);
        this.bbsvfx$wholeFactor.setValue(wp instanceof IBlendBone w ? w.bbsvfx$blendFactor() : 1F);
    }

    @Inject(method = "pickBones", at = @At("TAIL"))
    private void bbsvfx$sync(List<String> bones, CallbackInfo ci)
    {
        this.bbsvfx$syncWhole();

        if (bones.isEmpty() || this.pose == null)
        {
            this.bbsvfx$blendMode.label = IKey.constant(BlendMode.NORMAL.display);
            this.bbsvfx$blendFactor.setValue(1F);

            return;
        }

        IBlendBone s = (IBlendBone) this.pose.getOrCreate(bones.get(0));

        this.bbsvfx$blendMode.label = IKey.constant(BlendMode.byIndex(s.bbsvfx$blendMode()).display);
        this.bbsvfx$blendFactor.setValue(s.bbsvfx$blendFactor());
    }
}
