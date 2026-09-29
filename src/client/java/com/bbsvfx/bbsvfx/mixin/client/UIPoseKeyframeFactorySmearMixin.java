package com.bbsvfx.bbsvfx.mixin.client;

import mchorse.bbs_mod.ui.UIKeys;
import mchorse.bbs_mod.ui.framework.elements.UIElement;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory;
import mchorse.bbs_mod.ui.framework.elements.input.keyframes.factories.UIPoseKeyframeFactory.UIPoseFactoryEditor;
import mchorse.bbs_mod.ui.utils.UI;
import mchorse.bbs_mod.ui.utils.pose.UIPoseEditor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import com.bbsvfx.bbsvfx.client.ISmearPoseEditor;
import com.bbsvfx.bbsvfx.client.UILinesKeyframeFactory;
import com.bbsvfx.bbsvfx.client.UISmearKeyframeFactory;

/**
 * The pose-keyframe editor rebuilds its bone-editor layout on every {@code resize()} (removeAll +
 * re-add of fix/color/lighting/transform), dropping anything an editor mixin added. The smear / lines
 * channels reuse this same factory (via {@link UISmearKeyframeFactory} / {@link UILinesKeyframeFactory}
 * subclasses), so re-add just the relevant block here: the smear factory shows the smear block, the
 * lines factory shows the lines block, and the plain pose factory shows neither.
 */
@Mixin(UIPoseKeyframeFactory.class)
public abstract class UIPoseKeyframeFactorySmearMixin
{
    @Shadow
    public UIPoseFactoryEditor poseEditor;

    /* BBS changed the TYPE of the public field UIPoseEditor.groups (UIPoseBoneStringList -> UIBoneList,
     * commit 70a08277) without bumping the "2.3.1" version. A direct field access compiles against one
     * build and throws NoSuchFieldError on the other, so resolve it reflectively by NAME only — both
     * types are UIElements, which is all the layout needs. */
    @Unique
    private static java.lang.reflect.Field bbsvfx$groupsField;

    @Unique
    private UIElement bbsvfx$boneList()
    {
        try
        {
            if (bbsvfx$groupsField == null)
            {
                bbsvfx$groupsField = UIPoseEditor.class.getField("groups");
            }

            return (UIElement) bbsvfx$groupsField.get(this.poseEditor);
        }
        catch (ReflectiveOperationException | ClassCastException e)
        {
            return null;
        }
    }

    @Inject(method = "resize", at = @At("TAIL"))
    private void bbsvfx$readdSmear(CallbackInfo ci)
    {
        ISmearPoseEditor smear = (ISmearPoseEditor) this.poseEditor;

        if ((Object) this instanceof UISmearKeyframeFactory)
        {
            /* The smear track only needs bone selection + the smear params, so drop the inherited pose
             * controls (fix/color/lighting/transform) the base resize() laid out and rebuild the editor
             * with just the bone list + the smear block. */
            this.poseEditor.removeAll();

            UIElement boneList = this.bbsvfx$boneList();
            UIElement bones = boneList == null
                ? UI.column(UI.label(UIKeys.FORMS_EDITOR_BONE))
                : UI.column(UI.label(UIKeys.FORMS_EDITOR_BONE), boneList);

            if (((UIElement) (Object) this).getFlex().getW() > 240)
            {
                this.poseEditor.add(UI.row(smear.bbsvfx$smearBlock(), bones));
            }
            else
            {
                this.poseEditor.add(bones, smear.bbsvfx$smearBlock());
            }

            for (UIElement child : ((UIKeyframeFactory) (Object) this).scroll.getChildren(UIElement.class))
            {
                child.noCulling();
            }
        }
        else if ((Object) this instanceof UILinesKeyframeFactory)
        {
            this.poseEditor.add(smear.bbsvfx$linesBlock());
        }
        else if ((Object) this instanceof com.bbsvfx.bbsvfx.client.UIBlendKeyframeFactory)
        {
            /* Per-bone blend track: bone list + the blend block (mode + strength), like smear. */
            this.poseEditor.removeAll();

            UIElement boneList = this.bbsvfx$boneList();
            UIElement bones = boneList == null
                ? UI.column(UI.label(UIKeys.FORMS_EDITOR_BONE))
                : UI.column(UI.label(UIKeys.FORMS_EDITOR_BONE), boneList);
            com.bbsvfx.bbsvfx.client.IBlendPoseEditor blend = (com.bbsvfx.bbsvfx.client.IBlendPoseEditor) this.poseEditor;

            if (((UIElement) (Object) this).getFlex().getW() > 240)
            {
                this.poseEditor.add(UI.row(blend.bbsvfx$blendBlock(), bones));
            }
            else
            {
                this.poseEditor.add(bones, blend.bbsvfx$blendBlock());
            }

            for (UIElement child : ((UIKeyframeFactory) (Object) this).scroll.getChildren(UIElement.class))
            {
                child.noCulling();
            }
        }
        else
        {
            /* Plain pose track — no smear/lines/blend controls. */
            return;
        }

        /* Re-flow the whole scroll (controls + pose editor) consistently rather than just the pose
         * editor — re-laying-out only the pose editor left a gap because its column stretches. */
        ((UIKeyframeFactory) (Object) this).scroll.resize();
    }
}
