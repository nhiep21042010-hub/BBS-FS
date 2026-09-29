package com.bbsvfx.bbsvfx.forms;

/**
 * Duck interface on BBS's {@code PoseTransform} (woven in by {@code PoseTransformBlendMixin}) exposing a
 * per-bone blend: a {@link BlendMode} index ({@code 0} = Normal = this bone isn't blended) and a 0..1
 * strength. Stored per bone so the whole-form {@code blend_mode} Pose channel carries which bones blend
 * and how, animating like the pose.
 */
public interface IBlendBone
{
    /**
     * Reserved key in the {@code blend} pose for the WHOLE-MODEL blend (a non-bone sentinel). Storing it in
     * the same keyframed pose channel as the per-bone data makes it apply to the rendered entity each frame
     * (immediately / animatably), unlike a plain form value which only syncs when the entity is recreated.
     * A non-Normal mode on this entry = whole-model blend is on.
     *
     * <p>LEGACY SENTINEL: the "xavin$whole" key predates the xavin → bbsvfx rename; it is stored inside
     * saved blend poses, so it must stay as-is for old scenes/films to keep the whole-model blend.</p>
     */
    String WHOLE_BONE = "xavin$whole";

    int bbsvfx$blendMode();

    void bbsvfx$setBlendMode(int mode);

    float bbsvfx$blendFactor();

    void bbsvfx$setBlendFactor(float factor);
}
