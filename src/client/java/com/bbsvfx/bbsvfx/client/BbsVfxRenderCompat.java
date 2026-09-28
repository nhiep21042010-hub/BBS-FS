package com.bbsvfx.bbsvfx.client;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;

/**
 * Source-compat shim over the 1.20 ↔ 1.21 vertex-building API split, so ONE source tree compiles and
 * runs on both eras:
 *
 * <ul>
 *   <li>{@link #next} — 1.20 finalizes every vertex with {@code VertexConsumer.next()}; 1.21 removed it
 *   (a vertex is finalized by the next {@code vertex()} call or the buffer end). The shim invokes the
 *   real method when it exists and is a no-op otherwise.</li>
 *   <li>{@link #begin} — 1.20: {@code Tessellator.getBuffer()} + {@code BufferBuilder.begin(...)};
 *   1.21: {@code Tessellator.begin(...)} returns a fresh builder. Both end with the portable
 *   {@code BufferRenderer.drawWithGlobalProgram(builder.end())}.</li>
 *   <li>{@link #positionColorTexture} — the {@code POSITION_COLOR_TEXTURE} format was dropped in 1.21;
 *   falls back to {@code POSITION_TEXTURE_COLOR} there.</li>
 * </ul>
 *
 * <p>Runtime method/field names are resolved through the Fabric MAPPING RESOLVER from intermediary
 * names — a hardcoded yarn name like {@code "next"} would only work in dev, where the runtime is
 * yarn-named; production runtimes are intermediary-named.</p>
 */
public final class BbsVfxRenderCompat
{
    private static final MethodHandle NEXT = method(VertexConsumer.class, "net.minecraft.class_4588", "method_1344", "()V");
    private static final MethodHandle TESS_GET_BUFFER = method(Tessellator.class, "net.minecraft.class_289", "method_1349", "()Lnet/minecraft/class_287;");
    private static final MethodHandle BB_BEGIN_1_20 = method(BufferBuilder.class, "net.minecraft.class_287", "method_1328",
        "(Lnet/minecraft/class_293$class_5596;Lnet/minecraft/class_293;)V", VertexFormat.DrawMode.class, VertexFormat.class);
    private static final MethodHandle TESS_BEGIN_1_21 = method(Tessellator.class, "net.minecraft.class_289", "method_60827",
        "(Lnet/minecraft/class_293$class_5596;Lnet/minecraft/class_293;)Lnet/minecraft/class_287;", VertexFormat.DrawMode.class, VertexFormat.class);

    private static final VertexFormat POSITION_COLOR_TEXTURE = field(VertexFormats.class, "net.minecraft.class_290", "field_20887", "Lnet/minecraft/class_293;");

    private BbsVfxRenderCompat()
    {
    }

    /** Finalize the current vertex where the runtime still requires it (1.20); no-op on 1.21+. */
    public static void next(VertexConsumer consumer)
    {
        if (NEXT == null)
        {
            return;
        }

        try
        {
            NEXT.invoke(consumer);
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }

    /** Acquire the tessellator's build for one immediate-mode draw, on either era's API. */
    public static BufferBuilder begin(VertexFormat.DrawMode mode, VertexFormat format)
    {
        Tessellator tessellator = Tessellator.getInstance();

        try
        {
            if (TESS_BEGIN_1_21 != null)
            {
                return (BufferBuilder) TESS_BEGIN_1_21.invoke(tessellator, mode, format);
            }

            BufferBuilder builder = (BufferBuilder) TESS_GET_BUFFER.invoke(tessellator);

            BB_BEGIN_1_20.invoke(builder, mode, format);

            return builder;
        }
        catch (Throwable e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * The pos/color/uv format (1.20); on 1.21 (where it was removed) — pos/uv/color. NB the element
     * ORDER differs on the fallback: re-verify anything binding attributes by order (the label picker).
     */
    public static VertexFormat positionColorTexture()
    {
        return POSITION_COLOR_TEXTURE != null ? POSITION_COLOR_TEXTURE : VertexFormats.POSITION_TEXTURE_COLOR;
    }

    /**
     * Partial tick from Fabric's WorldRenderContext: 1.20 exposes {@code tickDelta()}, 1.21 replaced it
     * with {@code tickCounter().getTickDelta(false)}. The context itself is Fabric API (unobfuscated,
     * plain names), but the returned {@code RenderTickCounter} is a MINECRAFT class — its method must be
     * resolved through the mapping resolver or production (intermediary-named) runtimes crash with
     * NoSuchMethodException (tester crash on 1.21.1: only dev runtimes carry the yarn name).
     */
    public static float tickDelta(Object worldRenderContext)
    {
        try
        {
            try
            {
                return (float) worldRenderContext.getClass().getMethod("tickDelta").invoke(worldRenderContext);
            }
            catch (NoSuchMethodException modern)
            {
                Object counter = worldRenderContext.getClass().getMethod("tickCounter").invoke(worldRenderContext);
                String getTickDelta = FabricLoader.getInstance().getMappingResolver()
                    .mapMethodName("intermediary", "net.minecraft.class_9779", "method_60637", "(Z)F");

                return (float) counter.getClass().getMethod(getTickDelta, boolean.class).invoke(counter, false);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException(e);
        }
    }

    /**
     * Push an identity model-view for an offscreen replay. 1.20's {@code getModelViewStack()} returns a
     * {@code MatrixStack}, 1.21's — a JOML {@code Matrix4fStack}; both classes exist on both targets, so
     * two instanceof branches compile everywhere and the right one runs.
     */
    public static void pushIdentityModelView()
    {
        Object stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();

        if (stack instanceof net.minecraft.client.util.math.MatrixStack ms)
        {
            ms.push();
            ms.loadIdentity();
        }
        else if (stack instanceof org.joml.Matrix4fStack js)
        {
            js.pushMatrix();
            js.identity();
        }

        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    public static void popModelView()
    {
        Object stack = com.mojang.blaze3d.systems.RenderSystem.getModelViewStack();

        if (stack instanceof net.minecraft.client.util.math.MatrixStack ms)
        {
            ms.pop();
        }
        else if (stack instanceof org.joml.Matrix4fStack js)
        {
            js.popMatrix();
        }

        com.mojang.blaze3d.systems.RenderSystem.applyModelViewMatrix();
    }

    private static MethodHandle method(Class<?> owner, String ownerIntermediary, String methodIntermediary, String descriptor, Class<?>... parameters)
    {
        try
        {
            String runtime = FabricLoader.getInstance().getMappingResolver()
                .mapMethodName("intermediary", ownerIntermediary, methodIntermediary, descriptor);
            Method method = owner.getMethod(runtime, parameters);

            return MethodHandles.publicLookup().unreflect(method);
        }
        catch (Throwable absent)
        {
            return null;
        }
    }

    private static VertexFormat field(Class<?> owner, String ownerIntermediary, String fieldIntermediary, String descriptor)
    {
        try
        {
            String runtime = FabricLoader.getInstance().getMappingResolver()
                .mapFieldName("intermediary", ownerIntermediary, fieldIntermediary, descriptor);

            return (VertexFormat) owner.getField(runtime).get(null);
        }
        catch (Throwable absent)
        {
            return null;
        }
    }
}
