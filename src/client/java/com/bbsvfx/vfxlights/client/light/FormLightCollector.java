package com.bbsvfx.vfxlights.client.light;

import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.forms.renderers.FormRenderType;
import mchorse.bbs_mod.forms.renderers.FormRenderingContext;
import mchorse.bbs_mod.utils.colors.Color;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import com.bbsvfx.vfxlights.forms.AmbientLightForm;
import com.bbsvfx.vfxlights.forms.AreaLightForm;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;
import com.bbsvfx.vfxlights.forms.values.LightAir;
import com.bbsvfx.vfxlights.forms.values.LightBarn;
import com.bbsvfx.vfxlights.forms.values.LightStyle;
import com.bbsvfx.vfxlights.forms.values.LightToon;
import com.bbsvfx.vfxlights.light.Light;
import com.bbsvfx.vfxlights.light.LightRegistry;

/**
 * Turns a light form being rendered into a {@link Light} in the registry.
 *
 * <p><b>Collected during rendering, on purpose.</b> The obvious alternative — walking the film's replays
 * and the world's model blocks once per frame — is what a light mod naturally reaches for, and it has a
 * hole in it: a form attached to a BONE is positioned by the animation, and a tree walk has no idea where
 * that bone ended up. Torch in a hand, glow in an eye, light on a sword: all silently unlit. Rendering is
 * the one moment the answer exists, because BBS has already composed the full transform — actor, form,
 * bone and all — into {@code context.world}. Reading it here means a light on a bone simply works, and
 * costs nothing extra.</p>
 *
 * <p>The passes that must NOT contribute are gated out: the UI preview (a dummy entity near the origin,
 * not a place in the world) and Iris's shadow pass (the same lamp again, seen from the sun).</p>
 */
public final class FormLightCollector
{
    private FormLightCollector()
    {
    }

    /**
     * Inverse of THIS frame's actual view matrix, captured at world-render start.
     *
     * <p>Reconstructing the view from camera pitch and yaw missed everything else vanilla folds in —
     * view BOBBING first of all: a standing test read rock-stable while walking made a static lamp
     * wander by the bob amplitude, measured as ±5 cm in the collected position. Inverting the real
     * matrix undoes whatever the renderer did, current and future.</p>
     */
    private static final Matrix4f INVERSE_VIEW = new Matrix4f();
    private static boolean inverseViewValid;

    /** Called once per frame from the world-render start hook with the frame's view stack. */
    public static void captureView(Matrix4f view)
    {
        if (view != null)
        {
            INVERSE_VIEW.set(view).invert();
            inverseViewValid = true;
        }
    }

    /* Shared with the glass-form collector, which un-projects model-block glass the same way. */
    static boolean hasInverseView()
    {
        return inverseViewValid;
    }

    static Matrix4f inverseView()
    {
        return INVERSE_VIEW;
    }

    /**
     * The lamp's registry key: STABLE across BBS recreating the form (edits, drags, pause
     * refreshes) wherever a stable host exists — instance-keyed lamps turned every recreation
     * into a new light (ghost churn + shadow rebake churn = the pause/drag flicker). See LampKey.
     *
     * <p>The id half is the lamp's SERIALISED vfx id, minted here on first collection: BBS's own
     * getId() is the value key ("form" on every lamp), so two lamps sharing a host AND a body
     * part would have collided into one light; the minted id cannot collide and survives both
     * recreation and the editor's detached copy (they carry the form's data).</p>
     */
    private static Object lampKey(LightForm form, FormRenderingContext context)
    {
        String formId = form.ensureVfxId();

        if (context != null && context.entity instanceof mchorse.bbs_mod.forms.entities.MCEntity mc)
        {
            return com.bbsvfx.vfxlights.light.LampKey.ofUuid(mc.getMcEntity().getUuid(), formId);
        }

        if (context != null && context.entity != null)
        {
            return com.bbsvfx.vfxlights.light.LampKey.ofEntity(context.entity, formId);
        }

        return com.bbsvfx.vfxlights.light.LampKey.ofForm(form, formId);
    }

    /** Register {@code form} as a light for this frame, if this render pass should contribute one. */
    public static void collect(LightForm form, FormRenderingContext context)
    {
        if (!shouldCollect(form, context))
        {
            /* The form editor's preview can't PLACE a light (dummy matrices near the origin), but its
             * render proves an editor session is open — keep every world light alive through it
             * instead of expiring the grace window ~2s in. The per-key touch() missed here: the
             * previewed form is not reliably the world's collected instance. Also refresh the
             * snapshot's parameters so editor tweaks (groups, intensity, dials) apply live.
             *
             * ONLY for that preview. shouldCollect refuses for five other reasons, and four of them
             * fire during ordinary play — our shadow pass, the mask captures and Iris's own shadow
             * pass all re-render the very same forms every frame. Renewing the whole persist set from
             * those made the grace window unreachable: any lamp rendering anywhere held EVERY light
             * that ever existed at full grace, so a film played in the world (right Ctrl) left its
             * lamps burning after the last frame, with nothing left to switch them off. */
            if (context != null && (context.ui || context.type == FormRenderType.PREVIEW))
            {
                LightRegistry.touchAll();
                refreshSnapshot(form);
            }

            return;
        }

        Light light = LightRegistry.submit(lampKey(form, context));

        if (light == null)
        {
            return;
        }

        /* The lamp's SERIALISED identity (minted by ensureVfxId, inside lampKey): survives BBS
         * recreating the form on edits — the editor's live refresh and the ghost prune match
         * persisted lights by it. */
        light.formId = form.vfxId.get();

        /* MODEL BLOCKS read the actual RENDER matrix. Reconstructing their placement (block position +
         * centring + the panel's transform) kept chasing the renderer: the "Look at" toggle yaws the
         * model toward the player every frame INSIDE the render, and the reconstruction missed it — a
         * spotlight that swept with the player while its cone pointed elsewhere. The render stack has
         * everything by definition; for block entities it is relative to the real render camera, which
         * is the game camera in every mode (the film-camera drift trap applies only to the entity
         * path, whose context.world is already absolute). */
        boolean modelBlock = context.type == FormRenderType.MODEL_BLOCK;
        MatrixStack stack = !modelBlock && context.world != null ? context.world : context.stack;
        Matrix4f matrix = stack.peek().getPositionMatrix();

        Vector4f origin = matrix.transform(new Vector4f(0F, 0F, 0F, 1F));
        Vector4f forward = matrix.transform(new Vector4f(0F, 0F, 1F, 0F));
        Vector4f up = matrix.transform(new Vector4f(0F, 1F, 0F, 0F));

        /* ★VERIFIED IN GAME (2026-07-19). context.world is ABSOLUTE WORLD space for actors: while the
         * camera moved across several blocks, a stationary lamp's matrix origin did not budge. Two
         * readings were ruled out by measurement, not by argument — adding the camera position (would
         * have pinned every light to the viewer), and using context.stack, whose values drifted because
         * BBS renders a film through its OWN camera while we could only see the player's.
         *
         * Inside a model block the same matrix covers only the FORM's transforms. The block's renderer
         * hands us the missing outer layers — centring plus the block's properties transform (what the
         * in-world quick-edit panel drives) — and the block position stays as integers so precision
         * survives far from spawn. */
        if (modelBlock)
        {
            /* The block-entity stack is VIEW space. Undo it with the inverse of the frame's ACTUAL view
             * matrix — angle reconstruction was defeated by view bobbing (see captureView). The bob's
             * translation cancels exactly, because it lives inside the same matrix being inverted. */
            if (!inverseViewValid)
            {
                LightRegistry.discard(light);

                return;
            }

            Vector4f position = INVERSE_VIEW.transform(new Vector4f(origin.x, origin.y, origin.z, 1F));

            INVERSE_VIEW.transform(forward);
            INVERSE_VIEW.transform(up);

            net.minecraft.util.math.Vec3d cameraPos =
                net.minecraft.client.MinecraftClient.getInstance().gameRenderer.getCamera().getPos();

            light.x = position.x + cameraPos.x;
            light.y = position.y + cameraPos.y;
            light.z = position.z + cameraPos.z;
            /* The model block rendering this form right now — lets a block break kill exactly its
             * lamps instead of the persist grace. */
            light.sourceBlock = com.bbsvfx.vfxlights.client.light.ModelBlockRenderTracker.get();

            /* Post-break kill-cooldown: stale chunk-section contents keep rendering the broken
             * block's forms for a few frames — drop them, or the just-killed lamp resurrects. */
            if (LightRegistry.isKilled(light.sourceBlock))
            {
                LightRegistry.discard(light);

                return;
            }
        }
        else
        {
            light.x = origin.x;
            light.y = origin.y;
            light.z = origin.z;
        }

        light.dirX = forward.x;
        light.dirY = forward.y;
        light.dirZ = forward.z;
        light.upX = up.x;
        light.upY = up.y;
        light.upZ = up.z;
        light.normaliseDirection();

        copyParams(form, light);
    }

    /** Every form-driven parameter (no positions/matrices — those need a live render context). */
    private static void copyParams(LightForm form, Light light)
    {
        Color color = form.effectiveColor();

        light.r = color.r;
        light.g = color.g;
        light.b = color.b;
        light.intensity = form.intensity.get() * color.a;
        light.flicker = form.flicker.get();
        light.flickerSpeed = form.flickerSpeed.get();

        if (light.flicker > 0.001F)
        {
            /* Flame-like flicker: a smooth random walk on the WORLD clock (pauses with the game),
             * each form on its own phase. Tempo scales the walk; the depth reaches FULL darkness
             * at 1 (the walk dips below zero brightness, clamped). */
            net.minecraft.world.World world = net.minecraft.client.MinecraftClient.getInstance().world;
            float t = (world == null ? 0L : world.getTime()) * 0.15F * light.flickerSpeed;
            int i = (int) Math.floor(t);
            float f = t - i;
            int seed = System.identityHashCode(form);
            float from = hashFlicker(i + seed);
            float to = hashFlicker(i + 1 + seed);
            float n = from + (to - from) * f * f * (3F - 2F * f);

            light.intensity *= Math.max(1F - light.flicker * n * 1.5F, 0F);
        }

        light.range = form.range.get();
        light.falloffMode = form.falloffPhysical.get() ? 1 : 0;
        light.shadows = form.shadows.get();
        light.shadowSoft = form.shadowSoftness.get();
        /* The grouped properties: get() hands back the KEYFRAMED value during playback exactly like a
         * plain one would (BaseValueBasic.runtimeValue), so animation needs nothing special here. */
        LightAir air = form.air.get();
        LightStyle style = form.style.get();
        LightToon toon = form.toon.get();

        light.volumetric = air.beam;
        light.haze = air.haze;
        light.dust = air.dust;
        light.dustSize = air.dustSize;
        light.dispersion = air.prism;
        light.dispersionScale = air.prismScale;
        light.bounce = air.bounce;
        light.flare = form.flare.get();
        light.flareStyle = form.flareStyle.get();
        light.translucency = style.translucency;
        light.iesProfile = form.iesProfile.get();
        light.rim = style.rim;
        light.rimWidth = style.rimWidth;
        light.specRim = style.sheen;
        light.outline = style.outline;
        light.outlineWidth = style.outlineWidth;
        light.outlineBlur = style.outlineBlur;
        light.outlineInner = style.outlineInner;
        light.outlineTarget = style.outlineTarget;
        light.outlineBlend = style.outlineBlend;
        light.cel = toon.enabled;
        light.celSoftness = toon.softness;
        light.celShadowShift = toon.shadowTint;
        light.celShadowLevel = toon.shadowLevel;
        light.affectBlocks = form.affectBlocks.get();
        light.affectEntities = form.affectEntities.get();
        /* Authoritative copy: a deselected category must leave the set, or the filter can only
         * ever tighten (the "removing a group does not apply" bug). */
        light.groups.clear();
        light.groups.addAll(form.groups.get());
        light.groupFilter = form.groupFilter.get();

        fillShape(form, light);
    }

    /** Re-copy every form-driven parameter onto the persisted snapshot (editor tweaks mid-session). */
    private static void refreshSnapshot(mchorse.bbs_mod.forms.forms.Form form)
    {
        if (form instanceof LightForm lightForm)
        {
            refreshFromForm(lightForm);
        }
    }

    /**
     * Push an editor tweak into the registry NOW: the persisted snapshot (what a culled or
     * in-UI-previewed lamp lives on) and this frame's live light alike, matched by the lamp's
     * serialised vfx id. Called from the light panels' dial wrapper on every human edit — the
     * preview-render refresh alone is the wrong moment for it: the preview may not render at all
     * while the panel is open, and when it does render (UI pass) every world-render consumer of
     * the frame has already read the list, so fallback never showed the edit at all.
     */
    public static void refreshFromForm(LightForm lightForm)
    {
        /* Grouped structs first: a film session masks them behind the keyframe channel's runtime
         * copy, and a dial (or preset stamp) that just wrote the ORIGINAL would otherwise push the
         * stale runtime. Live playback re-evaluates the channels every frame anyway, so mirroring
         * only ever repaints the snapshot a paused editor keeps (the panel dials' own mirror). */
        mirrorRuntime(lightForm.air);
        mirrorRuntime(lightForm.style);
        mirrorRuntime(lightForm.toon);

        /* The persisted key is a LampKey now, never the form instance — the identity lookup that
         * used to come first cannot hit anymore. The serialised vfx id is the match that survives
         * recreation: BBS's own getId() is the value key ("form" on every lamp) and matched
         * nothing uniquely in any scene with two lights (the "в редакторе не видно изменений"
         * report). */
        Light persisted = LightRegistry.findPersistedByFormId(lightForm.ensureVfxId());

        if (persisted != null)
        {
            copyParams(lightForm, persisted);
        }

        /* The live light too, when the world keeps rendering the lamp mid-edit: in merged() the
         * ACTIVE entry wins over the persisted snapshot, and a tweak landing only on the snapshot
         * shows nothing until the next collection carries the same values. */
        Light active = LightRegistry.findActiveByFormId(lightForm.ensureVfxId());

        if (active != null)
        {
            copyParams(lightForm, active);
        }
    }

    /** Sync a grouped struct's keyframe-runtime copy from the original, when a channel left one. */
    private static void mirrorRuntime(mchorse.bbs_mod.settings.values.base.BaseValue group)
    {
        if (group instanceof mchorse.bbs_mod.settings.values.base.BaseValueBasic<?> basic
            && basic.getRuntimeValue() instanceof com.bbsvfx.vfxlights.forms.values.LightStruct<?> runtime)
        {
            runtime.setFields(((com.bbsvfx.vfxlights.forms.values.LightStruct<?>) basic.getOriginalValue()).fields());
        }
    }

    /* Deterministic int → [0,1) hash for the flicker walk (the DustPass bit-mixing recipe). */
    private static float hashFlicker(int i)
    {
        int h = i * 73856093;

        h ^= h >>> 13;
        h *= 0x85ebca6b;
        h ^= h >>> 16;

        return (h & 0xFFFFFF) / (float) 0xFFFFFF;
    }

    private static void fillShape(LightForm form, Light light)
    {
        if (form instanceof AreaLightForm area)
        {
            light.type = Light.Type.AREA;
            light.areaShape = Light.AreaShape.values()[area.shape.get()];
            light.width = area.width.get();
            light.height = area.height.get();
            light.thickness = area.thickness.get();
            light.twoSided = area.twoSided.get();
            light.spread = area.spread.get();
            LightBarn barn = area.barn.get();

            light.barnTop = barn.top;
            light.barnBottom = barn.bottom;
            light.barnLeft = barn.left;
            light.barnRight = barn.right;
            light.barnSoftness = barn.softness;
        }
        else if (form instanceof AmbientLightForm ambient)
        {
            Color ground = ambient.groundColor.get();

            light.type = Light.Type.AMBIENT;
            light.hemisphere = ambient.getMode() == AmbientLightForm.Mode.HEMISPHERE;
            light.boxVolume = ambient.getVolume() == AmbientLightForm.Volume.BOX;
            light.sizeX = ambient.sizeX.get();
            light.sizeY = ambient.sizeY.get();
            light.sizeZ = ambient.sizeZ.get();
            light.edgeFalloff = ambient.edgeFalloff.get();
            light.groundR = ground.r;
            light.groundG = ground.g;
            light.groundB = ground.b;
            light.occlusion = ambient.occlusion.get();
        }
        else if (form instanceof SpotLightForm spot)
        {
            light.type = Light.Type.SPOT;
            light.sourceRadius = spot.sourceRadius.get();
            light.setCone(spot.angle.get(), spot.innerAngle.get());
        }
        else if (form instanceof PointLightForm point)
        {
            light.type = Light.Type.POINT;
            light.sourceRadius = point.sourceRadius.get();
        }
    }

    private static boolean shouldCollect(LightForm form, FormRenderingContext context)
    {
        if (context == null || !form.visible.get())
        {
            return false;
        }

        /* Master toggle. Without this gate the registry kept filling with the module off, and the
         * pack backend (fed straight from the registry at frame start) kept lighting the scene —
         * the "Off ⇒ the mod no-ops" promise held only for the fallback compositor. */
        if (!com.bbsvfx.vfxlights.VfxLightsModule.isEnabled())
        {
            return false;
        }

        /* Our own shadow pass re-renders entities, form lights included. A lamp seen from another
         * lamp's viewpoint is not a new light — and submitting here mutates the list the shadow pass is
         * iterating (crashed once; see ShadowMapper). */
        if (com.bbsvfx.vfxlights.client.shadow.ShadowMapper.isActive())
        {
            return false;
        }

        /* The character/group mask passes re-render the same forms into their depth FBOs. A lamp
         * collected there is a duplicate of the world-render one, not a new light. */
        if (com.bbsvfx.vfxlights.client.render.CharacterMask.isCapturing())
        {
            return false;
        }

        /* The editor preview (form editor's UIFormRenderer) renders a dummy entity near the origin —
         * its lights are not in the world. NOTE: a stencilMap is NOT a reject reason — the film
         * editor's normal replay pass carries one constantly (hover picking), and the keyed
         * submit-dedup already collapses any genuine double-collection within the frame. */
        if (context.ui || context.type == FormRenderType.PREVIEW)
        {
            return false;
        }

        /* Under Iris every form renders again for the shadow map, with the sun's matrices. Collecting
         * there would place a second copy of the lamp somewhere off in the light's frame. */
        try
        {
            if (BBSRendering.isIrisShadowPass())
            {
                return false;
            }
        }
        catch (Throwable ignored)
        {
            /* Iris absent — nothing to guard against. */
        }

        return true;
    }
}
