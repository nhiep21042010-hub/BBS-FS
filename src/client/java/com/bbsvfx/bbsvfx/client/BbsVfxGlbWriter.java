package com.bbsvfx.bbsvfx.client;

import org.joml.Quaternionf;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Hand-rolled binary glTF ({@code .glb}) writer for the recorded film camera — a single animated
 * perspective camera for Blender, in the spirit of Replay Mod's {@code CameraPathExporter}.
 *
 * <p>The camera node carries two animation channels: {@code translation} (Minecraft world position,
 * 1 block = 1 m) and {@code rotation} (a quaternion, so there is none of the Euler/gimbal pain the AE
 * export needed). Field of view rides along as the camera's static {@code yfov}; when it changes over
 * the shot (a zoom) a third channel animates it through the <b>{@code KHR_animation_pointer}</b>
 * extension targeting {@code /cameras/0/perspective/yfov}, which Blender 4.x imports as an animated
 * focal length. The extension is only ever listed in {@code extensionsUsed} (never required), so an
 * importer that does not understand it simply falls back to the static {@code yfov}.</p>
 *
 * <h2>Orientation</h2>
 * <p>BBS builds its view matrix as {@code Rz(roll)·Rx(pitch)·Ry(yaw)}; the camera-to-world rotation a
 * glTF node needs is its inverse, {@code Ry(-yaw)·Rx(-pitch)·Rz(-roll)}, built directly as a
 * quaternion. glTF cameras look down local {@code -Z} (the same convention BBS/OpenGL render with).
 * Axis signs and units map 1:1 from Minecraft; if a Blender import comes out mirrored or rolled the
 * wrong way, that is the one empirical sign to flip here.</p>
 */
public final class BbsVfxGlbWriter
{
    /* Frame index layout — must mirror BbsVfxAeTracker.capture(). */
    private static final int POS_X = 0, POS_Y = 1, POS_Z = 2;
    private static final int ROLL = 6, FOV = 7, PITCH = 8, YAW = 9;

    /** glTF accessor component type for 32-bit float. */
    private static final int FLOAT = 5126;

    /** Radians: FOV swings smaller than this across the shot are treated as constant (no zoom channel). */
    private static final double FOV_EPSILON = 1.0e-4;

    private static final float ZNEAR = 0.05F;
    private static final float ZFAR = 10000.0F;

    private BbsVfxGlbWriter()
    {}

    /**
     * Build the {@code .glb} byte payload for the captured camera frames.
     *
     * @param frames    per-frame camera samples (see {@link BbsVfxAeTracker} layout)
     * @param width     recorded video width (for aspect ratio)
     * @param height    recorded video height (for aspect ratio)
     * @param frameRate frames per second (for keyframe times)
     */
    public static byte[] build(List<double[]> frames, int width, int height, int frameRate)
    {
        int n = frames.size();

        float[] times = new float[n];
        float[] trans = new float[n * 3];
        float[] rot = new float[n * 4];
        float[] fov = new float[n];

        float fovMin = Float.MAX_VALUE;
        float fovMax = -Float.MAX_VALUE;
        Quaternionf q = new Quaternionf();

        for (int i = 0; i < n; i++)
        {
            double[] f = frames.get(i);

            times[i] = (float) (i / (double) frameRate);

            trans[i * 3] = (float) f[POS_X];
            trans[i * 3 + 1] = (float) f[POS_Y];
            trans[i * 3 + 2] = (float) f[POS_Z];

            /* camera-to-world = inverse of the BBS view rotation Rz·Rx·Ry */
            q.identity()
                .rotateY((float) -f[YAW])
                .rotateX((float) -f[PITCH])
                .rotateZ((float) -f[ROLL])
                .normalize();

            rot[i * 4] = q.x;
            rot[i * 4 + 1] = q.y;
            rot[i * 4 + 2] = q.z;
            rot[i * 4 + 3] = q.w;

            float ff = (float) f[FOV];
            fov[i] = ff;
            fovMin = Math.min(fovMin, ff);
            fovMax = Math.max(fovMax, ff);
        }

        boolean fovVaries = (fovMax - fovMin) > FOV_EPSILON;

        /* Binary buffer: tightly packed float sections, each already 4-byte aligned. */
        int timeLen = n * 4;
        int transLen = n * 3 * 4;
        int rotLen = n * 4 * 4;
        int fovLen = fovVaries ? n * 4 : 0;

        int timeOff = 0;
        int transOff = timeOff + timeLen;
        int rotOff = transOff + transLen;
        int fovOff = rotOff + rotLen;
        int binLen = fovOff + fovLen;

        ByteBuffer bin = ByteBuffer.allocate(binLen).order(ByteOrder.LITTLE_ENDIAN);

        for (float t : times)
        {
            bin.putFloat(t);
        }
        for (float t : trans)
        {
            bin.putFloat(t);
        }
        for (float r : rot)
        {
            bin.putFloat(r);
        }
        if (fovVaries)
        {
            for (float ff : fov)
            {
                bin.putFloat(ff);
            }
        }

        String json = buildJson(n, width, height, fov[0], fovVaries,
            times[0], times[n - 1], fovMin, fovMax,
            timeOff, timeLen, transOff, transLen, rotOff, rotLen, fovOff, fovLen,
            binLen, trans, rot);

        return assembleGlb(json, bin.array());
    }

    private static String buildJson(int n, int width, int height, float staticYfov, boolean fovVaries,
        float timeFirst, float timeLast, float fovMin, float fovMax,
        int timeOff, int timeLen, int transOff, int transLen, int rotOff, int rotLen, int fovOff, int fovLen,
        int binLen, float[] trans, float[] rot)
    {
        double aspect = height == 0 ? 1.0 : width / (double) height;

        StringBuilder sb = new StringBuilder();

        sb.append('{');
        sb.append("\"asset\":{\"version\":\"2.0\",\"generator\":\"BBS VFX GLB camera\"},");
        sb.append("\"scene\":0,\"scenes\":[{\"nodes\":[0]}],");

        /* Node 0 — the camera. Base pose = first frame (animation overrides it within range). */
        sb.append("\"nodes\":[{\"camera\":0,\"translation\":[")
          .append(num(trans[0])).append(',').append(num(trans[1])).append(',').append(num(trans[2]))
          .append("],\"rotation\":[")
          .append(num(rot[0])).append(',').append(num(rot[1])).append(',').append(num(rot[2])).append(',').append(num(rot[3]))
          .append("]}],");

        sb.append("\"cameras\":[{\"type\":\"perspective\",\"perspective\":{")
          .append("\"yfov\":").append(num(staticYfov))
          .append(",\"aspectRatio\":").append(num(aspect))
          .append(",\"znear\":").append(num(ZNEAR))
          .append(",\"zfar\":").append(num(ZFAR))
          .append("}}],");

        /* Accessors: 0=time, 1=translation, 2=rotation, [3=yfov]. */
        sb.append("\"accessors\":[");
        sb.append("{\"bufferView\":0,\"componentType\":").append(FLOAT)
          .append(",\"count\":").append(n).append(",\"type\":\"SCALAR\",\"min\":[").append(num(timeFirst))
          .append("],\"max\":[").append(num(timeLast)).append("]},");
        sb.append("{\"bufferView\":1,\"componentType\":").append(FLOAT)
          .append(",\"count\":").append(n).append(",\"type\":\"VEC3\"},");
        sb.append("{\"bufferView\":2,\"componentType\":").append(FLOAT)
          .append(",\"count\":").append(n).append(",\"type\":\"VEC4\"}");
        if (fovVaries)
        {
            sb.append(",{\"bufferView\":3,\"componentType\":").append(FLOAT)
              .append(",\"count\":").append(n).append(",\"type\":\"SCALAR\",\"min\":[").append(num(fovMin))
              .append("],\"max\":[").append(num(fovMax)).append("]}");
        }
        sb.append("],");

        /* Buffer views into the single binary buffer. */
        sb.append("\"bufferViews\":[");
        sb.append("{\"buffer\":0,\"byteOffset\":").append(timeOff).append(",\"byteLength\":").append(timeLen).append("},");
        sb.append("{\"buffer\":0,\"byteOffset\":").append(transOff).append(",\"byteLength\":").append(transLen).append("},");
        sb.append("{\"buffer\":0,\"byteOffset\":").append(rotOff).append(",\"byteLength\":").append(rotLen).append('}');
        if (fovVaries)
        {
            sb.append(",{\"buffer\":0,\"byteOffset\":").append(fovOff).append(",\"byteLength\":").append(fovLen).append('}');
        }
        sb.append("],");

        sb.append("\"buffers\":[{\"byteLength\":").append(binLen).append("}],");

        /* Animation: translation + rotation channels, plus the yfov pointer channel when it varies. */
        sb.append("\"animations\":[{\"samplers\":[");
        sb.append("{\"input\":0,\"output\":1,\"interpolation\":\"LINEAR\"},");
        sb.append("{\"input\":0,\"output\":2,\"interpolation\":\"LINEAR\"}");
        if (fovVaries)
        {
            sb.append(",{\"input\":0,\"output\":3,\"interpolation\":\"LINEAR\"}");
        }
        sb.append("],\"channels\":[");
        sb.append("{\"sampler\":0,\"target\":{\"node\":0,\"path\":\"translation\"}},");
        sb.append("{\"sampler\":1,\"target\":{\"node\":0,\"path\":\"rotation\"}}");
        if (fovVaries)
        {
            sb.append(",{\"sampler\":2,\"target\":{\"path\":\"pointer\",\"extensions\":{")
              .append("\"KHR_animation_pointer\":{\"pointer\":\"/cameras/0/perspective/yfov\"}}}}");
        }
        sb.append("]}]");

        if (fovVaries)
        {
            sb.append(",\"extensionsUsed\":[\"KHR_animation_pointer\"]");
        }

        sb.append('}');

        return sb.toString();
    }

    /** Wrap the JSON + binary buffer into a GLB container (12-byte header + JSON chunk + BIN chunk). */
    private static byte[] assembleGlb(String json, byte[] bin)
    {
        byte[] jsonBytes = json.getBytes(StandardCharsets.UTF_8);

        int jsonPad = (4 - (jsonBytes.length % 4)) % 4;
        int binPad = (4 - (bin.length % 4)) % 4;

        int jsonChunkLen = jsonBytes.length + jsonPad;
        int binChunkLen = bin.length + binPad;
        int total = 12 + 8 + jsonChunkLen + 8 + binChunkLen;

        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);

        /* Header: magic "glTF", version 2, total length. */
        buf.put((byte) 'g').put((byte) 'l').put((byte) 'T').put((byte) 'F');
        buf.putInt(2);
        buf.putInt(total);

        /* JSON chunk (padded with spaces). */
        buf.putInt(jsonChunkLen);
        buf.put((byte) 'J').put((byte) 'S').put((byte) 'O').put((byte) 'N');
        buf.put(jsonBytes);
        for (int i = 0; i < jsonPad; i++)
        {
            buf.put((byte) 0x20);
        }

        /* BIN chunk (padded with zeros). */
        buf.putInt(binChunkLen);
        buf.put((byte) 'B').put((byte) 'I').put((byte) 'N').put((byte) 0x00);
        buf.put(bin);
        for (int i = 0; i < binPad; i++)
        {
            buf.put((byte) 0x00);
        }

        return buf.array();
    }

    private static String num(double value)
    {
        return String.format(Locale.US, "%.6f", value);
    }
}
