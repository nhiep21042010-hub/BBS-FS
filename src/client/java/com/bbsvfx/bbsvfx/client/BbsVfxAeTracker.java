package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.BBSModClient;
import mchorse.bbs_mod.BBSSettings;
import mchorse.bbs_mod.camera.Camera;
import mchorse.bbs_mod.camera.controller.CameraController;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.utils.MatrixUtils;
import mchorse.bbs_mod.utils.StringUtils;
import org.joml.Matrix3d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bbsvfx.bbsvfx.BbsVfxAddon;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Records BBS's film camera frame-by-frame during a video render and, on stop, bakes it into an
 * After Effects camera script ({@code .jsx}) written next to the rendered clip.
 *
 * <p>Driven by {@code com.bbsvfx.bbsvfx.mixin.client.VideoRecorderMixin}, which mirrors the recorder's
 * own lifecycle: {@link #start(int, int)} on {@code startRecording}, {@link #capture()} on each
 * {@code recordFrame}, {@link #finish()} on {@code stopRecording}. Capturing exactly one camera
 * sample per recorded frame keeps the keyframes aligned with the footage.</p>
 *
 * <h2>Coordinate mapping (Minecraft → After Effects)</h2>
 * <ul>
 *   <li>1 block = {@value #SCALE} px ({@code AE.x = mc.x·S}, {@code AE.y = -mc.y·S}, {@code AE.z = mc.z·S}).
 *       Flipping Y converts MC's right-handed, Y-up world into AE's left-handed, Y-down space.</li>
 *   <li>The whole scene is recentered on the first frame's camera position, so the camera starts at
 *       AE {@code [0,0,0]} and coordinates stay small — attached layers can be placed with sane
 *       numbers near the origin instead of the camera's raw world coordinates.</li>
 *   <li>Orientation is baked as a two-node (point-of-interest) camera: the POI is the camera position
 *       pushed one block along BBS's own look vector ({@link Camera#getLookDirection()}), so yaw/pitch
 *       come straight from BBS with no Euler-order guesswork. Roll is the camera's Z rotation.</li>
 *   <li>Zoom is derived in the script from the target comp's height: {@code (compH/2) / tan(fov/2)};
 *       BBS's {@code fov} is the vertical FOV in radians.</li>
 * </ul>
 *
 * <p>The script applies the camera to the comp open in After Effects, only creating a new one when
 * nothing is active. Assumes Motion blur = 0 (the default): with motion blur the recorder oversamples
 * frames, which would desync the baked keyframes from the final footage.</p>
 */
public class BbsVfxAeTracker
{
    private static final Logger LOG = LoggerFactory.getLogger(BbsVfxAddon.MOD_ID);

    /** Pixels per Minecraft block in the generated AE comp. */
    private static final double SCALE = 100.0;

    /** Distance (in blocks) the point-of-interest is pushed ahead of the camera. */
    private static final double POI_DISTANCE = 1.0;

    private static boolean active;
    private static int width;
    private static int height;
    private static int frameRate;
    private static String name;

    private static final List<double[]> frames = new ArrayList<>();

    /**
     * Tracked anchors. Keyed by the form instance identity (forms override {@code equals}, so an
     * IdentityHashMap is required). {@link #anchorOrder} preserves first-seen order for stable AE
     * layer ordering; {@link #stagedAnchors} holds the matrices captured during the current frame's
     * world render, flushed into the tracks on {@link #capture()}.
     */
    private static final Map<Object, AnchorTrack> anchorTracks = new IdentityHashMap<>();
    private static final List<Object> anchorOrder = new ArrayList<>();
    private static final Map<Object, Matrix4f> stagedAnchors = new IdentityHashMap<>();

    /** One tracked anchor: its AE layer label plus a per-frame world matrix (null where absent). */
    private static class AnchorTrack
    {
        final String label;
        final List<Matrix4f> perFrame = new ArrayList<>();

        AnchorTrack(String label)
        {
            this.label = label;
        }
    }

    private BbsVfxAeTracker()
    {}

    /** Whether a capture session is currently running (gates the per-frame anchor staging). */
    public static boolean isCapturing()
    {
        return active;
    }

    /** Whether the After Effects ({@code .jsx}) export toggle is on. */
    private static boolean aeEnabled()
    {
        return BbsVfxAddon.aeTracking != null && BbsVfxAddon.aeTracking.get();
    }

    /** Whether the binary glTF ({@code .glb}) camera export toggle is on. */
    private static boolean glbEnabled()
    {
        return BbsVfxAddon.glbExport != null && BbsVfxAddon.glbExport.get();
    }

    /**
     * Begin a capture session. No-op unless at least one export ("AE tracking" or "GLB export") is on.
     * The camera/anchor sampling is shared; {@link #finish()} picks which files to write.
     *
     * @param textureWidth  recorded video width (comp width)
     * @param textureHeight recorded video height (comp height)
     */
    public static void start(int textureWidth, int textureHeight)
    {
        /* startRecording() bails early when already recording; that path also reaches our hook.
         * Ignore it so a redundant call can't reset an in-progress capture. */
        if (active)
        {
            return;
        }

        if (!aeEnabled() && !glbEnabled())
        {
            return;
        }

        frames.clear();
        anchorTracks.clear();
        anchorOrder.clear();
        stagedAnchors.clear();

        width = textureWidth;
        height = textureHeight;
        frameRate = BBSSettings.videoFrameRate.get();
        name = StringUtils.createTimestampFilename();
        active = true;
    }

    /**
     * Stage a tracked anchor's world matrix for the current frame. Called from the form renderer for
     * each tracked {@link mchorse.bbs_mod.forms.forms.AnchorForm} during the export world pass; the
     * staged values are committed, frame-aligned with the camera, on the next {@link #capture()}.
     *
     * @param key   the form instance (identity key — stable across frames of one render)
     * @param label desired AE layer name (the form's display name; empty falls back to "Tracker N")
     * @param world the anchor's world matrix this frame
     */
    public static void stageAnchor(Object key, String label, Matrix4f world)
    {
        if (!active)
        {
            return;
        }

        stagedAnchors.put(key, new Matrix4f(world));

        if (!anchorTracks.containsKey(key))
        {
            String resolved = label == null || label.isEmpty()
                ? "Tracker " + (anchorOrder.size() + 1)
                : label;

            anchorTracks.put(key, new AnchorTrack(resolved));
            anchorOrder.add(key);
        }
    }

    /** Sample the current film camera and commit this frame's staged anchors. Once per recorded frame. */
    public static void capture()
    {
        if (!active)
        {
            return;
        }

        CameraController controller = BBSModClient.getCameraController();
        Camera camera = controller.camera;
        Vector3f dir = camera.getLookDirection();

        /* Layout (per recorded frame), shared by the AE and GLB writers:
         *   [0..2] world position   [3..5] look direction   [6] roll   [7] vertical FOV
         *   [8] pitch   [9] yaw     — all angles in radians (camera.rotation is pitch/yaw/roll). */
        frames.add(new double[] {
            camera.position.x, camera.position.y, camera.position.z,
            dir.x, dir.y, dir.z,
            camera.rotation.z, /* roll, radians */
            camera.fov,        /* vertical FOV, radians */
            camera.rotation.x, /* pitch, radians */
            camera.rotation.y  /* yaw, radians */
        });

        int frameIndex = frames.size() - 1;

        for (Object key : anchorOrder)
        {
            AnchorTrack track = anchorTracks.get(key);

            while (track.perFrame.size() < frameIndex)
            {
                track.perFrame.add(null);
            }

            track.perFrame.add(stagedAnchors.get(key));
        }

        stagedAnchors.clear();
    }

    /** Finish the session and write the enabled exports ({@code .jsx} and/or {@code .glb}). Called on {@code stopRecording}. */
    public static void finish()
    {
        if (!active)
        {
            return;
        }

        active = false;

        if (frames.isEmpty())
        {
            return;
        }

        try
        {
            File folder = BBSRendering.getVideoFolder();

            folder.mkdirs();

            if (aeEnabled())
            {
                writeAeScripts(folder);
            }

            if (glbEnabled())
            {
                writeGlbCamera(folder);
            }
        }
        catch (IOException e)
        {
            LOG.error("BbsVfxAeTracker failed to write export(s)", e);
        }
        finally
        {
            frames.clear();
            anchorTracks.clear();
            anchorOrder.clear();
            stagedAnchors.clear();
        }
    }

    /** Write the After Effects camera script plus one tracker script per anchor. */
    private static void writeAeScripts(File folder) throws IOException
    {
        File camera = new File(folder, name + ".jsx");

        Files.write(camera.toPath(),
            buildCameraScript().getBytes(StandardCharsets.UTF_8));

        int trackers = 0;

        for (Object key : anchorOrder)
        {
            AnchorTrack track = anchorTracks.get(key);
            String script = buildTrackerScript(track);

            if (script == null)
            {
                continue;
            }

            File file = new File(folder, name + "_" + safeFileName(track.label) + ".jsx");

            Files.write(file.toPath(), script.getBytes(StandardCharsets.UTF_8));
            trackers += 1;
        }

        LOG.info("BbsVfxAeTracker wrote {} ({} frames) + {} tracker script(s)",
            camera, frames.size(), trackers);
    }

    /** Write the animated binary glTF camera ({@code .glb}) for Blender. */
    private static void writeGlbCamera(File folder) throws IOException
    {
        File glb = new File(folder, name + ".glb");

        Files.write(glb.toPath(), BbsVfxGlbWriter.build(frames, width, height, frameRate));

        LOG.info("BbsVfxGlbWriter wrote {} ({} frames)", glb, frames.size());
    }

    /** Resolves {@code comp} to the active composition, creating a fallback only if none is open. */
    private static final String COMP_RESOLVE =
          "    var comp = null;\n"
        + "    var active = app.project.activeItem;\n"
        + "    if (active && active instanceof CompItem) { comp = active; }\n"
        + "    if (comp === null) {\n"
        + "        comp = app.project.items.addComp(NAME, W, H, 1.0, DUR, FPS);\n"
        + "        comp.openInViewer();\n"
        + "    }\n";

    /**
     * Emits ExtendScript that finds — or, if absent, creates — the render's MC camera in {@code comp}.
     * Keyed by a fixed layer name, so the standalone camera script and every (self-contained) tracker
     * script converge on a single shared camera instead of duplicating it. Assumes {@code comp} is in
     * scope.
     */
    private static String cameraJs()
    {
        StringBuilder times = new StringBuilder();
        StringBuilder pos = new StringBuilder();
        StringBuilder ori = new StringBuilder();
        StringBuilder fov = new StringBuilder();
        double[] prevOri = null;

        for (int i = 0; i < frames.size(); i++)
        {
            double[] f = frames.get(i);

            double px = f[0], py = f[1], pz = f[2];
            double rollRad = f[6];
            double fovRad = f[7];
            double pitchRad = f[8];
            double yawRad = f[9];

            /* Position in AE px, EXACTLY the proven Blender→AE mapping: AE.X = MC.x, AE.Y = -MC.y,
             * AE.Z = -MC.z (MC Y-up right-handed → glTF Rx(+90) into Blender → AE's X/-Z/Y), ×SCALE, with
             * the world origin placed at comp centre (+W/2, +H/2). No path-recentering: the working export
             * uses raw ±100k-px coordinates and tracks fine, so float precision is NOT the issue. */
            double aePosX = px * SCALE + width / 2.0;
            double aePosY = -py * SCALE + height / 2.0;
            double aePosZ = -pz * SCALE;

            /* ONE-NODE camera with explicit ORIENTATION (autoOrient OFF) reproducing MC's exact view via
             * the proven MC→glTF→Blender→AE math (see cameraOrientation). */
            double[] o = cameraOrientation(yawRad, pitchRad, rollRad);

            /* Unwrap against the previous frame so AE interpolates the short way (no ±360 spins). */
            if (prevOri != null)
            {
                for (int k = 0; k < 3; k++)
                {
                    while (o[k] - prevOri[k] > 180.0) o[k] -= 360.0;
                    while (o[k] - prevOri[k] < -180.0) o[k] += 360.0;
                }
            }

            prevOri = o;

            if (i > 0)
            {
                times.append(',');
                pos.append(',');
                ori.append(',');
                fov.append(',');
            }

            times.append(num(i / (double) frameRate));
            pos.append('[').append(num(aePosX)).append(',').append(num(aePosY)).append(',').append(num(aePosZ)).append(']');
            ori.append('[').append(num(o[0])).append(',').append(num(o[1])).append(',').append(num(o[2])).append(']');
            fov.append(num(fovRad));
        }

        return "    var cam_t = [" + times + "];\n"
            + "    var cam_p = [" + pos + "];\n"
            + "    var cam_o = [" + ori + "];\n"
            + "    var cam_fov = [" + fov + "];\n"
            + "    var camName = " + jsString(name + " camera") + ";\n"
            + "    var cam = null;\n"
            + "    for (var ci = 1; ci <= comp.numLayers; ci++) { if (comp.layer(ci).name === camName) { cam = comp.layer(ci); break; } }\n"
            + "    if (cam === null && cam_t.length > 0) {\n"
            /* Square pixels are REQUIRED: AE projects with a single isotropic Zoom, so any non-1.0 pixel
             * aspect (or a comp aspect ≠ the render aspect) makes horizontal FOV disagree with vertical and
             * a world point slides toward the frame edges on pan. MC always renders square pixels. */
            + "        comp.pixelAspect = 1;\n"
            + "        if (Math.abs(comp.frameRate - FPS) > 0.01) { alert('BBS VFX: comp is ' + comp.frameRate.toFixed(3) + ' fps but the render is ' + FPS + ' fps. Set the comp to ' + FPS + ' fps or the track will drift over time.'); }\n"
            /* Zoom = focal length in comp PIXELS, from MC's VERTICAL fov and the comp HEIGHT:
             * Zoom = (H/2)/tan(Vfov/2). Correct only when the comp aspect equals the render aspect. */
            + "        var cam_zoom = [];\n"
            + "        for (var zi = 0; zi < cam_fov.length; zi++) { cam_zoom.push((comp.height / 2) / Math.tan(cam_fov[zi] / 2)); }\n"
            + "        cam = comp.layers.addCamera(camName, [comp.width / 2, comp.height / 2]);\n"
            + "        cam.autoOrient = AutoOrientType.NO_AUTO_ORIENT;\n"
            + "        var cam_tr = cam.property(\"Transform\");\n"
            + "        cam_tr.property(\"Position\").setValuesAtTimes(cam_t, cam_p);\n"
            + "        cam_tr.property(\"Orientation\").setValuesAtTimes(cam_t, cam_o);\n"
            + "        cam.property(\"Camera Options\").property(\"Zoom\").setValuesAtTimes(cam_t, cam_zoom);\n"
            /* LINEAR keyframes: our samples are one exact pose per frame; bezier smoothing would let the
             * camera drift off the true pose between keys (worst on fast pans). */
            + "        var bbsvfxLin = function (p) { for (var k = 1; k <= p.numKeys; k++) { p.setInterpolationTypeAtKey(k, KeyframeInterpolationType.LINEAR, KeyframeInterpolationType.LINEAR); } };\n"
            + "        bbsvfxLin(cam_tr.property(\"Position\"));\n"
            + "        bbsvfxLin(cam_tr.property(\"Orientation\"));\n"
            + "        bbsvfxLin(cam.property(\"Camera Options\").property(\"Zoom\"));\n"
            /* A null PARENTED to the camera with no keyframes of its own → it inherits the camera's exact
             * transform and sits screen-locked. Parent HUD text/images to this null (with an offset) and
             * they stay PINNED on screen wherever you place them, no matter how the camera moves or turns. */
            + "        var camNull = comp.layers.addNull();\n"
            + "        camNull.name = " + jsString(name + " HUD") + ";\n"
            + "        camNull.threeDLayer = true;\n"
            + "        camNull.parent = cam;\n"
            + "        var camNullTr = camNull.property(\"Transform\");\n"
            + "        camNullTr.property(\"Position\").setValue([0, 0, 0]);\n"
            + "        camNullTr.property(\"Orientation\").setValue([0, 0, 0]);\n"
            + "    }\n";
    }

    /** Header shared by every generated script. */
    private static String header(String title)
    {
        return "// BBS VFX — After Effects " + title + " export\n"
            + "// Generated automatically by the BBS VFX addon (author: Xavin).\n"
            + "// Run in After Effects: File > Scripts > Run Script File... — then view through the Active Camera.\n"
            + "// 1 Minecraft block = " + (int) SCALE + " px, world origin at comp centre. Assumes Motion blur = 0.\n";
    }

    /** Per-IIFE preamble declaring NAME/W/H/FPS/DUR and resolving the comp. */
    private static String preamble(String nameLiteral, String undoLabel)
    {
        return "(function () {\n"
            + "    var NAME = " + nameLiteral + ";\n"
            + "    var W = " + width + ", H = " + height + ", FPS = " + frameRate
            + ", DUR = " + num(frames.size() / (double) frameRate) + ";\n"
            + "\n"
            + "    app.beginUndoGroup(\"BBS VFX " + undoLabel + "\");\n"
            + "\n"
            + COMP_RESOLVE
            + "\n";
    }

    /** Standalone camera script (just the shared MC camera) for pure 3D-scene work. */
    private static String buildCameraScript()
    {
        return header("camera tracking")
            + preamble(jsString(name), "camera import")
            + cameraJs()
            + "\n    app.endUndoGroup();\n})();\n";
    }

    /**
     * Self-contained ExtendScript for one tracked anchor: ensures the shared MC camera exists in the
     * comp, then adds a 3D null carrying the anchor's world position, 3D orientation and (relative)
     * scale. Being self-contained, it does not require running the separate camera script. Returns
     * {@code null} when the anchor has no rendered frames.
     */
    private static String buildTrackerScript(AnchorTrack track)
    {
        StringBuilder times = new StringBuilder();
        StringBuilder pos = new StringBuilder();
        StringBuilder ori = new StringBuilder();
        StringBuilder scale = new StringBuilder();
        Matrix4f baseRot = null;
        Vector3f baseScale = null;
        Vector3f prevEuler = null;
        boolean any = false;

        for (int i = 0; i < track.perFrame.size(); i++)
        {
            Matrix4f m = track.perFrame.get(i);

            if (m == null)
            {
                continue;
            }

            if (baseRot == null)
            {
                baseRot = new Matrix4f(m);
            }

            Vector3f t = m.getTranslation(new Vector3f());
            /* Same proven AE mapping as the camera: X, -Y, -Z ×SCALE, origin at comp centre. */
            double aeX = t.x * SCALE + width / 2.0;
            double aeY = -t.y * SCALE + height / 2.0;
            double aeZ = -t.z * SCALE;

            Vector3f euler = aeOrientation(baseRot, m);

            if (prevEuler != null)
            {
                unwrap(euler, prevEuler);
            }

            prevEuler = euler;

            Vector3f s = m.getScale(new Vector3f());

            if (baseScale == null)
            {
                baseScale = new Vector3f(s);
            }

            /* Scale is reported relative to the first frame: starts at 100% and only moves if the
             * tracked bone itself scales, so attached layers keep a sane base size. */
            double sx = baseScale.x != 0F ? s.x / baseScale.x * 100.0 : 100.0;
            double sy = baseScale.y != 0F ? s.y / baseScale.y * 100.0 : 100.0;
            double sz = baseScale.z != 0F ? s.z / baseScale.z * 100.0 : 100.0;

            if (any)
            {
                times.append(',');
                pos.append(',');
                ori.append(',');
                scale.append(',');
            }

            times.append(num(i / (double) frameRate));
            pos.append('[').append(num(aeX)).append(',').append(num(aeY)).append(',').append(num(aeZ)).append(']');
            ori.append('[').append(num(euler.x)).append(',').append(num(euler.y)).append(',').append(num(euler.z)).append(']');
            scale.append('[').append(num(sx)).append(',').append(num(sy)).append(',').append(num(sz)).append(']');
            any = true;
        }

        if (!any)
        {
            return null;
        }

        return header("tracker (3D null)")
            + preamble(jsString(name), "tracker import")
            + cameraJs()
            + "\n"
            + "    var n_t = [" + times + "];\n"
            + "    var n_p = [" + pos + "];\n"
            + "    var n_o = [" + ori + "];\n"
            + "    var n_s = [" + scale + "];\n"
            + "    var n = comp.layers.addNull();\n"
            + "    n.threeDLayer = true;\n"
            + "    n.name = " + jsString(track.label) + ";\n"
            + "    var n_tr = n.property(\"Transform\");\n"
            + "    n_tr.property(\"Position\").setValuesAtTimes(n_t, n_p);\n"
            + "    n_tr.property(\"Orientation\").setValuesAtTimes(n_t, n_o);\n"
            + "    n_tr.property(\"Scale\").setValuesAtTimes(n_t, n_s);\n"
            + "\n    app.endUndoGroup();\n})();\n";
    }

    /**
     * After Effects Orientation (degrees) for a tracked bone, expressed <b>relative to the first
     * frame</b>. The bone's absolute world rotation carries a large constant offset (rest pose + BBS's
     * render flips); decomposing it directly cross-couples that offset into all three Euler components,
     * so the "tilt" drifts inconsistently as the bone turns. Taking the world-space delta
     * {@code ΔR = R·R0⁻¹} makes the first frame identity ({@code [0,0,0]}) and keeps the angles clean —
     * the null simply turns by however much the bone turned from its starting pose.
     *
     * <p>The delta is re-expressed in AE's Y-down basis ({@code B·ΔR·B}, {@code B = diag(1,-1,1)}) and
     * decomposed in <b>ZYX</b> — the order After Effects rebuilds Orientation in — using BBS's own
     * {@link MatrixUtils}. Its sequential, flip-aware extraction avoids the chaotic gimbal jumps JOML's
     * naive {@code getEulerAnglesZYX} produced (the original root cause), so the result reconstructs in
     * AE exactly as intended.</p>
     */
    /**
     * The AE camera Orientation (Euler degrees) reproducing MC's EXACT view — the same result as the
     * PROVEN pipeline MC → our glTF ({@link BbsVfxGlbWriter}) → Blender import → Blender's
     * {@code io_export_after_effects.py}, replicated in one step:
     *   1. camera-to-world quaternion, IDENTICAL to BbsVfxGlbWriter: {@code Ry(-yaw)·Rx(-pitch)·Rz(-roll)}
     *      (glTF cameras look down local -Z, like MC/OpenGL).
     *   2. glTF (Y-up) → Blender (Z-up) is a world {@code Rx(+90°)}: {@code M = Rx(90)·R_gltf}.
     *   3. Blender's AE exporter decomposes {@code M} as 'ZYX' Euler and maps to AE as
     *      {@code [deg(ex)-90, -deg(ey), -deg(ez)]} (the -90 on X = Blender camera lies on the floor at
     *      zero rotation, an AE layer at zero orientation stands up).
     */
    private static double[] cameraOrientation(double yawRad, double pitchRad, double rollRad)
    {
        Quaternionf q = new Quaternionf()
            .rotateY((float) -yawRad)
            .rotateX((float) -pitchRad)
            .rotateZ((float) -rollRad)
            .normalize();

        Matrix3d rGltf = new Matrix3d().set(q);
        Matrix3d m = new Matrix3d().rotationX(Math.toRadians(90.0)).mul(rGltf);

        double[] e = blenderEulerZYX(m);

        return new double[] {
            Math.toDegrees(e[0]) - 90.0,
            -Math.toDegrees(e[1]),
            -Math.toDegrees(e[2])
        };
    }

    /**
     * Decompose a rotation matrix to XYZ Euler radians using Blender's EXACT {@code 'ZYX'} convention
     * (mathutils {@code Matrix.to_euler('ZYX')} — Shoemake decomposition, axis order {2,1,0}, parity 1).
     *
     * <p>This must NOT be replaced with {@link MatrixUtils.RotationOrder#getEulerAngles} — BBS's ZYX uses a
     * different Euler convention (verified: for the same matrix it returns a rotationally-equivalent but
     * numerically different triple), which After Effects' Orientation rebuild does not accept. The whole
     * MC→glTF→Blender→AE chain was reproduced offline against a proven Blender-exported {@code .jsx} and
     * matched to ~0.01°; the match holds ONLY with this Blender-faithful decomposition. Blender stores
     * matrices column-major ({@code M[col][row]}), which equals JOML's {@code m<col><row>} field naming,
     * so the element reads map across directly.</p>
     *
     * @return {@code [ex, ey, ez]} radians — rotations about X, Y, Z (indexed by axis, not order).
     */
    private static double[] blenderEulerZYX(Matrix3d m)
    {
        double cy = Math.hypot(m.m22, m.m21);
        double ex, ey, ez;

        if (cy > 16.0 * 2.220446049250313e-16)
        {
            ez = Math.atan2(m.m10, m.m00);
            ey = Math.atan2(-m.m20, cy);
            ex = Math.atan2(m.m21, m.m22);
        }
        else
        {
            ez = Math.atan2(-m.m01, m.m11);
            ey = Math.atan2(-m.m20, cy);
            ex = 0.0;
        }

        /* Order ZYX has odd parity → Blender negates the whole triple. */
        return new double[] { -ex, -ey, -ez };
    }

    private static Vector3f aeOrientation(Matrix4f base, Matrix4f current)
    {
        Quaternionf q0 = base.getNormalizedRotation(new Quaternionf());
        Quaternionf qi = current.getNormalizedRotation(new Quaternionf());

        /* Inverse world-space delta from the rest pose: (R_i · R0⁻¹)⁻¹ = R0 · R_i⁻¹.
         * MC is right-handed (Y-up); AE is left-handed (Y-down) and turns the opposite sense. Inverting
         * the delta already maps the rotation into AE's handedness — yaw and roll come out right and the
         * tilt stays decoupled. (A further Y-down basis flip B·R·B would double-negate the pitch, so it
         * is deliberately omitted.) Decomposed in AE's ZYX rebuild order, flip-aware via MatrixUtils. */
        Quaternionf delta = new Quaternionf(q0).mul(qi.conjugate());
        Matrix3d r = new Matrix3d().set(delta);

        Vector3d euler = MatrixUtils.RotationOrder.ZYX.getEulerAngles(r);

        return new Vector3f(
            (float) Math.toDegrees(euler.x),
            (float) Math.toDegrees(euler.y),
            (float) Math.toDegrees(euler.z)
        );
    }

    /** Unwrap each Euler component to within 180° of the previous frame, killing 360°/branch jumps. */
    private static void unwrap(Vector3f euler, Vector3f prev)
    {
        euler.x = unwrap(euler.x, prev.x);
        euler.y = unwrap(euler.y, prev.y);
        euler.z = unwrap(euler.z, prev.z);
    }

    private static float unwrap(float value, float prev)
    {
        while (value - prev > 180F)
        {
            value -= 360F;
        }

        while (value - prev < -180F)
        {
            value += 360F;
        }

        return value;
    }

    private static String num(double value)
    {
        return String.format(Locale.US, "%.4f", value);
    }

    private static String jsString(String value)
    {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** Make a label safe for a filename: strip characters Windows/Unix disallow, trim, default. */
    private static String safeFileName(String label)
    {
        String safe = label.replaceAll("[\\\\/:*?\"<>|]", "_").trim();

        return safe.isEmpty() ? "tracker" : safe;
    }
}
