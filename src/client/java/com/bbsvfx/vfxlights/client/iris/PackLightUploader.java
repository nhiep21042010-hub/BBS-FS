package com.bbsvfx.vfxlights.client.iris;

import net.minecraft.util.math.Vec3d;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import com.bbsvfx.vfxlights.light.Light;

import java.nio.FloatBuffer;
import java.util.List;

/**
 * Ships the frame's lights to the patched pack shaders through a shader storage buffer.
 *
 * <p><b>Positions go up CAMERA-RELATIVE.</b> Pack surface shading works in player space — the eye at
 * the origin — so subtracting the camera on the CPU means the shader needs no {@code cameraPosition}
 * uniform at all. That sidesteps the classic multi-pack landmine: redeclaring a uniform some programs
 * already have is a compile error, and which programs have it varies by pack. (Photonics grew a whole
 * lazy-uniform system for this; not needing the uniform is simpler than injecting it carefully.)</p>
 *
 * <p><b>One frame of latency, accepted.</b> The registry fills DURING form rendering — that is what
 * makes bone-attached lamps exact — but pack shading runs in the same pass, so the upload at frame
 * start carries the previous frame's list. At film framerates light lagging a frame behind an emitter
 * is imperceptible; the alternative (a tree walk at frame start) is the bone-blind design we rejected.</p>
 *
 * <p>Layout must match the GLSL in the pack patch: header vec4s + 38 vec4 per light.</p>
 */
public final class PackLightUploader
{
    /** Binding point for the light SSBO. IRLite squats on 7; staying clear lets both mods coexist. */
    public static final int BINDING = 5;
    public static final int MAX_LIGHTS = 64;
    /** 16 vec4 of light/shadow data ([0..15]) + 4 × 5 of actor-fitted slots (1 params vec4 + 4
     * matrix columns each, [16..35]) + 1 of rim/falloff/group-filter extras ([36]) + 1 of
     * outline extras ([37]). */
    private static final int VEC4_PER_LIGHT = 38;
    /** Header vec4s: vfxCount + vfxAtlas. */
    private static final int HEADER_VEC4 = 2;

    private static int buffer = -1;
    private static FloatBuffer scratch;
    private static Boolean supported;

    private PackLightUploader()
    {
    }

    public static boolean isSupported()
    {
        if (supported == null)
        {
            try
            {
                supported = GL.getCapabilities().GL_ARB_shader_storage_buffer_object
                    || GL.getCapabilities().OpenGL43;
            }
            catch (Throwable t)
            {
                supported = false;
            }
        }

        return supported;
    }

    /** Upload {@code lights} for this frame and bind the buffer where the patched shaders expect it.
     * {@code view}/{@code projection} are the world render's live matrices (bob included) — the
     * cluster grid is rasterised against exactly what the pack shades with. */
    public static void upload(List<Light> lights, Vec3d camera,
        org.joml.Matrix4f view, org.joml.Matrix4f projection)
    {
        if (!isSupported())
        {
            return;
        }

        if (buffer == -1)
        {
            buffer = GL15.glGenBuffers();
            scratch = BufferUtils.createFloatBuffer(HEADER_VEC4 * 4 + MAX_LIGHTS * VEC4_PER_LIGHT * 4);
        }

        /* Module OFF ⇒ vfxCount 0. The patch stays spliced into the loaded pack either way; a zero
         * count is what actually turns the lamps off there (VfxLightsModule's "Off ⇒ no-op" promise
         * used to be broken exactly here — lights kept shining on any patched pack). */
        int count = com.bbsvfx.vfxlights.VfxLightsModule.isEnabled()
            ? Math.min(lights.size(), MAX_LIGHTS) : 0;

        /* Over budget: ship the STRONGEST lights, not the first in the list. The registry's raw
         * order is chunk-section visit order, which reshuffles as the camera moves — cutting there
         * made the lamps on the cut line blink with every step. Same ranking the fallback uses. */
        if (count > 0 && lights.size() > MAX_LIGHTS)
        {
            java.util.List<Light> sorted = new java.util.ArrayList<>(lights);

            sorted.sort((a, b) -> Float.compare(
                b.importance(camera.x, camera.y, camera.z),
                a.importance(camera.x, camera.y, camera.z)));
            lights = sorted;
        }

        /* .yz = framebuffer size, so the patch (GLSL 120, no textureSize) can turn gl_FragCoord into a
         * screen UV for the character mask. */
        net.minecraft.client.gl.Framebuffer fb = net.minecraft.client.MinecraftClient.getInstance().getFramebuffer();

        scratch.clear();
        /* .w = dev probe flag (VFXLIGHTS_PROBE): the patch paints gate values instead of light. */
        scratch.put(count).put((float) fb.textureWidth).put((float) fb.textureHeight)
            .put(com.bbsvfx.vfxlights.client.render.LightCompositor.PROBE);

        /* vfxAtlas: .x = shadow-atlas tiles across, so the patched shader can compute tile coordinates
         * no matter which size the driver allowed; .y = the side of one tile in texels (the Shadow
         * quality setting), which the model measures every filter radius against; .z = the shadow
         * filter step 0..3 (tap counts and kernel width). */
        scratch.put(com.bbsvfx.vfxlights.client.shadow.ShadowAtlas.TILES_ACROSS)
            .put(com.bbsvfx.vfxlights.client.shadow.ShadowAtlas.TILE_SIZE)
            .put(com.bbsvfx.vfxlights.VfxLightsAddon.shadowFilterStep()).put(0F);

        ClusterGridUploader.begin();

        for (int i = 0; i < count; i++)
        {
            Light light = lights.get(i);

            /* [0] position (camera-relative), type */
            scratch.put((float) (light.x - camera.x))
                .put((float) (light.y - camera.y))
                .put((float) (light.z - camera.z))
                .put(typeIndex(light));

            /* Same lamp, same index, into the cluster grid — the tile mask's bit i IS this slot. */
            ClusterGridUploader.record((float) (light.x - camera.x), (float) (light.y - camera.y),
                (float) (light.z - camera.z), light.range, i);

            /* [1] colour, intensity */
            scratch.put(light.r).put(light.g).put(light.b).put(light.intensity);

            /* [2] direction, range */
            scratch.put(light.dirX).put(light.dirY).put(light.dirZ).put(light.range);

            /* [3] up axis, source radius */
            scratch.put(light.upX).put(light.upY).put(light.upZ).put(light.effectiveSourceRadius());

            /* [4] cone cosines, emitter size (spot: coneScaleX/Y instead of width/height) */
            if (light.type == Light.Type.SPOT)
            {
                scratch.put(light.cosOuter).put(light.cosInner).put(light.coneScaleX).put(light.coneScaleY);
            }
            else
            {
                scratch.put(light.cosOuter).put(light.cosInner).put(light.width).put(light.height);
            }

            /* [5] barn doors — or the ground bounce colour for hemisphere ambient */
            if (light.type == Light.Type.AMBIENT)
            {
                scratch.put(light.groundR).put(light.groundG).put(light.groundB).put(light.edgeFalloff);
            }
            else
            {
                scratch.put(light.barnTop).put(light.barnBottom).put(light.barnLeft).put(light.barnRight);
            }

            /* [6] spread, shape id (+10 two-sided; ambient: mode), barn softness, thickness */
            float shape = light.type == Light.Type.AMBIENT
                ? (light.hemisphere ? 2F : (light.boxVolume ? 1F : 0F))
                : light.areaShape.ordinal() + (light.twoSided ? 10F : 0F);

            scratch.put(light.spread).put(shape).put(light.barnSoftness).put(light.thickness);

            /* [7] shadow tile (-1 = none) — assigned by the shadow pass that ran just before this —
             * whether the map is a six-face cube (y=1), the penumbra multiplier (z; 0 = crisp), and
             * .w = affect flags: bit 1 (=1) light blocks, bit 2 (=2) light entities/characters. */
            float affect = (light.affectBlocks ? 1F : 0F) + (light.affectEntities ? 2F : 0F);

            scratch.put(light.shadowTile)
                .put(com.bbsvfx.vfxlights.client.shadow.ShadowMapper.usesCube(light) ? 1F : 0F)
                .put(light.shadowSoft).put(affect);

            /* [8..11] spot shadow matrix, columns, PRE-TRANSLATED by the camera: the shader works in
             * camera-relative space, and folding the offset in here keeps cameraPosition out of the
             * GLSL — the same trick as the positions themselves. */
            if (light.type == Light.Type.SPOT && light.shadowTile >= 0)
            {
                org.joml.Matrix4f m = new org.joml.Matrix4f(light.shadowMatrix)
                    .translate((float) camera.x, (float) camera.y, (float) camera.z);
                org.joml.Vector4f column = new org.joml.Vector4f();

                for (int c = 0; c < 4; c++)
                {
                    m.getColumn(c, column);
                    scratch.put(column.x).put(column.y).put(column.z).put(column.w);
                }
            }
            else
            {
                for (int c = 0; c < 16; c++)
                {
                    scratch.put(0F);
                }
            }

            /* [12] dispersion: strength, pattern scale, animation phase (Java clock — see
             * Light.dispersionPhase for why the pack gets time as DATA, not a uniform), and the
             * distance at which the beam enters water (-1 = it never does). */
            scratch.put(light.dispersion).put(light.dispersionScale)
                .put(Light.dispersionPhase()).put(light.waterDist);

            /* [13] translucency (SSS), photometric profile id, water surface Y (camera-relative —
             * the pack shades in player space), rim/edge-light dial. */
            scratch.put(light.translucency).put(light.iesProfile)
                .put((float) (light.waterY - camera.y)).put(light.rim);

            /* [14] cel toggle, terminator softness, cool shadow hue shift, shadow tone level. */
            scratch.put(light.cel ? 1F : 0F).put(light.celSoftness)
                .put(light.celShadowShift).put(light.celShadowLevel);

            /* [15] outline: how far the rim hardens into a thin step contour (0 = soft rim).
             * .y = the ambient occlusion dial — only the ambient branch reads it.
             * .z = contour width in px (blends the two precomputed edge radii), .w = contour blur. */
            scratch.put(light.outline).put(light.occlusion).put(light.outlineWidth).put(light.outlineBlur);

            /* [16..35] the actor-fitted shadow slots, five vec4 each: a params vec4 (.x = tile,
             * -1 = the slot carries no map; .y/.z = its near/far planes; .w = the frustum's
             * half-angle tangent, which sizes the bias in texels) followed by the four matrix
             * columns, pre-translated by the camera exactly like the spot matrix at [8..11].
             * Zeros when the slot has no map — the shader skips it on the tile alone. */
            for (int slot = 0; slot < Light.ACTOR_SLOTS; slot++)
            {
                scratch.put((float) light.actorTile[slot]).put(light.actorNear[slot])
                    .put(light.actorFar[slot]).put(light.actorTan[slot]);

                if (light.actorTile[slot] >= 0)
                {
                    org.joml.Matrix4f m = new org.joml.Matrix4f(light.actorMatrix[slot])
                        .translate((float) camera.x, (float) camera.y, (float) camera.z);
                    org.joml.Vector4f column = new org.joml.Vector4f();

                    for (int c = 0; c < 4; c++)
                    {
                        m.getColumn(c, column);
                        scratch.put(column.x).put(column.y).put(column.z).put(column.w);
                    }
                }
                else
                {
                    for (int c = 0; c < 16; c++)
                    {
                        scratch.put(0F);
                    }
                }
            }

            /* [36] rim/group extras: .x = rim strip width (the stepped Fresnel band's coverage).
             * .y = falloff law (0 = range fill, 1 = physical 1/d² windowed — the Blender look).
             * .z = the lamp's allowed-category bitmask as an EXACT float integer (bit index-1 per
             * frame-registered category; 16777215 = no filter). A float, not raw int bits: the
             * SSBO is float-based and the GLSL 120 packs have no uints — exact integers stop at
             * 2^24, so the mask is 24 bits (see CategoryMask).
             * .w = the physical rim dial (grazing Fresnel scale in the specular). */
            scratch.put(light.rimWidth).put((float) light.falloffMode)
                .put(com.bbsvfx.vfxlights.client.render.CategoryMask.groupBits(light))
                .put(light.specRim);

            /* [37] outline extras: .x = interior (in-geometry) contour strength, .y = contour
             * target (0 = world and models, 1 = models only, 2 = world only), .z = blend mode
             * (0 = add, 1 = screen, 2 = overlay). */
            scratch.put(light.outlineInner).put((float) light.outlineTarget)
                .put((float) light.outlineBlend).put(0F);
        }

        scratch.flip();

        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, scratch, GL15.GL_STREAM_DRAW);
        GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BINDING, buffer);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

        ClusterGridUploader.upload(view, projection);
    }

    private static float typeIndex(Light light)
    {
        switch (light.type)
        {
            case SPOT:
                return 1F;
            case AREA:
                return 2F;
            case AMBIENT:
                return 3F;
            case POINT:
            default:
                return 0F;
        }
    }
}
