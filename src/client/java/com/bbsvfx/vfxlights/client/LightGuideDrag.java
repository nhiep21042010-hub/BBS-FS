package com.bbsvfx.vfxlights.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.CameraUtils;
import mchorse.bbs_mod.film.replays.Replay;
import mchorse.bbs_mod.forms.FormUtils;
import mchorse.bbs_mod.forms.forms.Form;
import mchorse.bbs_mod.settings.values.numeric.ValueFloat;
import mchorse.bbs_mod.ui.dashboard.UIDashboard;
import mchorse.bbs_mod.ui.film.UIFilmPanel;
import mchorse.bbs_mod.ui.film.controller.UIFilmController;
import mchorse.bbs_mod.ui.forms.editors.utils.UIPickableFormRenderer;
import mchorse.bbs_mod.ui.framework.UIContext;
import mchorse.bbs_mod.ui.framework.UIScreen;
import mchorse.bbs_mod.ui.utils.Area;
import mchorse.bbs_mod.ui.utils.StencilFormFramebuffer;
import mchorse.bbs_mod.utils.Pair;
import mchorse.bbs_mod.utils.keyframes.Keyframe;
import mchorse.bbs_mod.utils.keyframes.KeyframeChannel;
import mchorse.bbs_mod.utils.keyframes.KeyframeSegment;
import net.minecraft.client.MinecraftClient;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import com.bbsvfx.vfxlights.client.render.LightGuide;
import com.bbsvfx.vfxlights.forms.LightForm;
import com.bbsvfx.vfxlights.forms.PointLightForm;
import com.bbsvfx.vfxlights.forms.SpotLightForm;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Mouse dragging of light parameters through the gizmo's stencil grab handles.
 *
 * <p>The flow is IRLite's (qualet, MIT), adapted to this mod's forms: during the pick pass the
 * renderers paint invisible fat handles into the stencil framebuffer, each registered under a handle
 * name ({@link #HANDLE_RADIUS}, {@link #HANDLE_INNER}, {@link #HANDLE_RANGE}). A click that lands on a
 * known handle starts a drag; every UI frame the mouse ray is un-projected into form-local space using
 * the guide matrix captured during the world/preview render, and the parameter is solved analytically —
 * range as the projection onto the local Z axis, a cone angle from where the ray pierces the cap plane,
 * a point light's radius as the ray's closest approach to the origin.</p>
 *
 * <p><b>Where the value lands.</b> In the form editor the drag writes the {@link ValueFloat} directly
 * (and pushes the lamp live, the same push a dial does). In the film editor a plain write would fight
 * the keyframe channel that re-evaluates every frame, so the drag only starts when the property HAS a
 * channel with a keyframe at the cursor, and writes that keyframe — BBS's own runtime does the rest.</p>
 */
public final class LightGuideDrag
{
    /** Outer cone angle of a spot, the emitting radius of a point light, or an ambient zone's radius. */
    public static final String HANDLE_RADIUS = "radius";
    /** Inner cone angle of a spot. */
    public static final String HANDLE_INNER = "inner_radius";
    /** How far the light reaches (the cap disc of the spot guide). */
    public static final String HANDLE_RANGE = "range";
    /** An area light's first dimension (width / diameter / length, by shape). */
    public static final String HANDLE_WIDTH = "width";
    /** An area light's second dimension (RECT only). */
    public static final String HANDLE_HEIGHT = "height";

    /** The visible spot guide caps its length here — a 128-block beam gizmo is a wall, not a hint. */
    private static final float MAX_GUIDE_LENGTH = 12F;

    /** Weak keys: BBS recreates form instances on edits, and a stale entry must not pin one. */
    private static final Map<LightForm, Matrix4f> GUIDE_MATRICES = new WeakHashMap<>();

    private static WeakReference<GuideSync> panel = new WeakReference<>(null);
    private static WeakReference<UIFilmPanel> filmPanelCache = new WeakReference<>(null);

    private static LightForm dragForm;
    private static String dragHandle;
    private static Object dragHost;
    private static Camera dragCamera;
    private static Area dragViewport;
    @SuppressWarnings("rawtypes")
    private static Keyframe dragKeyframe;

    private LightGuideDrag()
    {
    }

    /** The panel a drag should sync its trackpads into after writing the form. */
    public interface GuideSync
    {
        void syncShape(LightForm form);
    }

    public static void bindPanel(GuideSync activePanel)
    {
        panel = new WeakReference<>(activePanel);
    }

    /** Called from the form renderers every frame the form renders — the solver's anchor. */
    public static void captureGuideMatrix(Form form, Matrix4f matrix)
    {
        if (form instanceof LightForm light)
        {
            GUIDE_MATRICES.put(light, new Matrix4f(matrix));
        }
    }

    public static boolean isDragging()
    {
        return dragForm != null;
    }

    /* Drag start */

    public static boolean tryStart(UIPickableFormRenderer renderer, UIContext context)
    {
        return tryStartWith(renderer, renderer.getStencil(), renderer.camera, renderer.area, context);
    }

    public static boolean tryStartFilm(UIFilmController controller, UIContext context)
    {
        /* No picking happens while flying (the stencil pass is skipped), and a locked cursor means
         * the user is steering the camera, not aiming at a handle. */
        if (controller.panel.isFlying() || MinecraftClient.getInstance().mouse.isCursorLocked())
        {
            return false;
        }

        if (!tryStartWith(controller, controller.getGizmoStencil(), controller.panel.getCamera(),
            controller.panel.preview.getViewport(), context))
        {
            return false;
        }

        dragKeyframe = resolveFilmKeyframe(controller);

        if (dragKeyframe == null)
        {
            /* The property has no keyframe at the cursor — a direct write would be re-evaluated away
             * next frame. Let the click fall through to BBS's normal picking instead. */
            stop();

            return false;
        }

        return true;
    }

    @SuppressWarnings("rawtypes")
    private static Keyframe resolveFilmKeyframe(UIFilmController controller)
    {
        Replay replay = selectedReplay();

        if (replay == null || dragForm == null || dragHandle == null)
        {
            return null;
        }

        ValueFloat value = valueForHandle(dragForm, dragHandle);
        String key = value == null ? null : FormUtils.getPropertyPath(value);
        KeyframeChannel channel = key == null ? null : replay.properties.get(mchorse.bbs_mod.film.replays.tracks.TrackId.parse(key));

        if (channel == null || channel.isEmpty())
        {
            return null;
        }

        KeyframeSegment segment = channel.findSegment(controller.panel.getCursor());

        return segment == null ? null : segment.a;
    }

    private static boolean tryStartWith(Object host, StencilFormFramebuffer stencil, Camera camera,
        Area viewport, UIContext context)
    {
        if (context.mouseButton != 0 || !stencil.hasPicked())
        {
            return false;
        }

        Pair<Form, String> pair = stencil.getPicked();

        if (pair == null || !(pair.a instanceof LightForm light) || pair.b == null)
        {
            return false;
        }

        if (!isHandle(pair.b) || !GUIDE_MATRICES.containsKey(light))
        {
            return false;
        }

        dragForm = light;
        dragHandle = pair.b;
        dragHost = host;
        dragCamera = camera;
        dragViewport = viewport;

        return true;
    }

    /* Drag end */

    public static boolean mouseReleased()
    {
        if (dragForm == null)
        {
            return false;
        }

        stop();

        return true;
    }

    public static void stop()
    {
        dragForm = null;
        dragHandle = null;
        dragHost = null;
        dragCamera = null;
        dragViewport = null;
        dragKeyframe = null;
    }

    /* Drag update — called from the host's render tail every UI frame */

    public static void update(Object host, UIContext context)
    {
        if (dragForm == null || dragHost != host)
        {
            return;
        }

        if (host instanceof UIFilmController controller && controller.panel.isFlying())
        {
            return;
        }

        Matrix4f guide = GUIDE_MATRICES.get(dragForm);

        if (guide == null)
        {
            /* The form stopped rendering (closed panel, culled entity) — the anchor is gone. */
            stop();

            return;
        }

        Matrix4f localToWorld = new Matrix4f(dragCamera.view).invert().mul(guide);

        if (Math.abs(localToWorld.determinant()) < 1.0E-12F)
        {
            return;
        }

        Matrix4f worldToLocal = new Matrix4f(localToWorld).invert();
        Vector3f dirWorld = CameraUtils.getMouseDirection(dragCamera.projection, dragCamera.view,
            context.mouseX, context.mouseY, dragViewport.x, dragViewport.y, dragViewport.w, dragViewport.h);

        Vector3f o = worldToLocal.transformPosition(new Vector3f(0F, 0F, 0F));
        Vector3f d = worldToLocal.transformDirection(new Vector3f(dirWorld));

        if (d.lengthSquared() < 1.0E-10F)
        {
            return;
        }

        if (HANDLE_RANGE.equals(dragHandle)
            && (dragForm instanceof SpotLightForm || dragForm instanceof com.bbsvfx.vfxlights.forms.AreaLightForm))
        {
            updateRange(o, d, dragForm.range);
        }
        else if (dragForm instanceof SpotLightForm spot)
        {
            updateAngle(o, d, spot, HANDLE_INNER.equals(dragHandle));
        }
        else if (dragForm instanceof PointLightForm point)
        {
            updateSphereRadius(o, d, point.sourceRadius, 0F, 16F);
        }
        else if (dragForm instanceof com.bbsvfx.vfxlights.forms.AmbientLightForm ambient)
        {
            updateSphereRadius(o, d, ambient.range, 0.1F, 256F);
        }
        else if (dragForm instanceof com.bbsvfx.vfxlights.forms.AreaLightForm area)
        {
            updateAreaDimension(o, d, area, HANDLE_HEIGHT.equals(dragHandle));
        }

        GuideSync sync = panel.get();

        if (sync != null)
        {
            sync.syncShape(dragForm);
        }
    }

    /** Range is where the mouse ray, projected on the light's axis, crosses it — the closest-points
     * formula between the ray and the local Z axis. Shared by the spot's beam and the area's reach. */
    private static void updateRange(Vector3f o, Vector3f d, ValueFloat target)
    {
        float a = d.lengthSquared();
        float b = d.z;
        float denom = a - b * b;

        if (denom < 1.0E-5F)
        {
            /* Ray parallel to the axis — no projection to read. */
            return;
        }

        float axisZ = (a * o.z - b * o.dot(d)) / denom;

        writeValue(LightGuide.clamp(axisZ, 0.1F, 256F), target);
    }

    /** A cone angle from where the ray pierces the cap plane: angle = 2·atan2(radial, |capZ|). */
    private static void updateAngle(Vector3f o, Vector3f d, SpotLightForm spot, boolean inner)
    {
        float capZ = Math.min(Math.max(spot.range.get(), 0.05F), MAX_GUIDE_LENGTH);

        if (Math.abs(d.z) < 1.0E-5F)
        {
            return;
        }

        float t = (capZ - o.z) / d.z;

        if (t <= 0F)
        {
            /* The cap plane is behind the eye. */
            return;
        }

        float hx = o.x + d.x * t;
        float hy = o.y + d.y * t;
        float radial = (float) Math.sqrt(hx * hx + hy * hy);
        float angle = (float) Math.toDegrees(2D * Math.atan2(radial, Math.abs(capZ)));

        if (inner)
        {
            writeValue(LightGuide.clamp(angle, 0F, spot.angle.get()), spot.innerAngle);
        }
        else
        {
            writeValue(LightGuide.clamp(angle, 1F, 179F), spot.angle);
        }
    }

    /** A spherical handle's radius is the mouse ray's closest approach to the lamp itself —
     * the point light's bulb, the ambient zone's boundary. */
    private static void updateSphereRadius(Vector3f o, Vector3f d, ValueFloat target, float min, float max)
    {
        Vector3f dn = new Vector3f(d).normalize();
        float along = -o.dot(dn);
        float dx = o.x + dn.x * along;
        float dy = o.y + dn.y * along;
        float dz = o.z + dn.z * along;
        float radius = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);

        writeValue(LightGuide.clamp(radius, min, max), target);
    }

    /** An area light's dimension from where the ray pierces the emitter plane (z = 0): the grabbed
     * edge midpoint tracks the mouse, so the dimension is twice the hit's coordinate. */
    private static void updateAreaDimension(Vector3f o, Vector3f d,
        com.bbsvfx.vfxlights.forms.AreaLightForm area, boolean height)
    {
        if (Math.abs(d.z) < 1.0E-5F)
        {
            /* Looking at the panel edge-on — the plane is unreachable. */
            return;
        }

        float t = -o.z / d.z;

        if (t <= 0F)
        {
            return;
        }

        float hx = o.x + d.x * t;
        float hy = o.y + d.y * t;

        if (height)
        {
            writeValue(LightGuide.clamp(2F * Math.abs(hy), 0.01F, 128F), area.height);
        }
        else if (area.getShape() == com.bbsvfx.vfxlights.forms.AreaLightForm.Shape.RECT)
        {
            writeValue(LightGuide.clamp(2F * Math.abs(hx), 0.01F, 128F), area.width);
        }
        else
        {
            /* Round shapes are grabbed by a ring or an end cap, not by an edge: the radial distance
             * is the radius (a plain |x| would collapse the width when the ring's top is grabbed). */
            float radial = (float) Math.sqrt(hx * hx + hy * hy);

            writeValue(LightGuide.clamp(2F * radial, 0.01F, 128F), area.width);
        }
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(float value, ValueFloat directTarget)
    {
        if (dragKeyframe != null)
        {
            dragKeyframe.setValue(value);
        }
        else
        {
            directTarget.set(value);

            /* The same push a dial does: the lamp being dragged usually lives on the persisted
             * snapshot or behind a keyframe runtime copy — land the edit for the very next frame. */
            com.bbsvfx.vfxlights.client.light.FormLightCollector.refreshFromForm(dragForm);
        }
    }

    /* Film editor state */

    /** Which form field a handle steers. */
    private static ValueFloat valueForHandle(LightForm form, String handle)
    {
        if (form instanceof SpotLightForm spot)
        {
            return switch (handle)
            {
                case HANDLE_RANGE -> spot.range;
                case HANDLE_INNER -> spot.innerAngle;
                default -> spot.angle;
            };
        }

        if (form instanceof PointLightForm point)
        {
            return point.sourceRadius;
        }

        if (form instanceof com.bbsvfx.vfxlights.forms.AmbientLightForm ambient)
        {
            return ambient.range;
        }

        if (form instanceof com.bbsvfx.vfxlights.forms.AreaLightForm area)
        {
            return switch (handle)
            {
                case HANDLE_RANGE -> area.range;
                case HANDLE_HEIGHT -> area.height;
                default -> area.width;
            };
        }

        return null;
    }

    private static UIFilmPanel filmPanel()
    {
        UIDashboard dashboard = BBSModClient.getDashboardIfCreated();

        if (dashboard == null || UIScreen.getCurrentMenu() != dashboard)
        {
            return null;
        }

        UIFilmPanel film = filmPanelCache.get();

        if (film == null)
        {
            film = dashboard.getPanel(UIFilmPanel.class);
            filmPanelCache = new WeakReference<>(film);
        }

        return film;
    }

    private static Replay selectedReplay()
    {
        UIFilmPanel film = filmPanel();

        return film == null ? null : film.replayEditor.getReplay();
    }

    /** True while the replay (keyframe) editor is up — the context in which film handles exist. */
    public static boolean isReplayEditorActive()
    {
        UIFilmPanel film = filmPanel();

        return film != null && film.replayEditor != null && film.replayEditor.isVisible()
            && !film.replayEditor.isActionsMode();
    }

    public static boolean isHandle(String bone)
    {
        return HANDLE_RADIUS.equals(bone) || HANDLE_INNER.equals(bone) || HANDLE_RANGE.equals(bone)
            || HANDLE_WIDTH.equals(bone) || HANDLE_HEIGHT.equals(bone);
    }
}
