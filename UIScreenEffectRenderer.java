package mchorse.bbs_mod.ui.film;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import mchorse.bbs_mod.camera.clips.screen.ScreenEffect;
import mchorse.bbs_mod.camera.data.Placement;
import mchorse.bbs_mod.ui.framework.elements.utils.Batcher2D;
import mchorse.bbs_mod.utils.colors.Colors;
import net.minecraft.client.util.math.MatrixStack;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Random;

/**
 * Draws the screen effects (letterbox, vignette, grain) over the finished frame, in the same
 * frame units the image and subtitle overlays use (the frame is {@link Placement#HEIGHT} high).
 */
public class UIScreenEffectRenderer
{
    public static void renderEffects(MatrixStack stack, Batcher2D batcher, List<ScreenEffect> effects)
    {
        if (effects.isEmpty())
        {
            return;
        }

        float width = UIImageRenderer.getUnitWidth();
        float height = Placement.HEIGHT;

        Matrix4f cache = new Matrix4f(RenderSystem.getProjectionMatrix());

        RenderSystem.setProjectionMatrix(new Matrix4f().ortho(0, width, height, 0, -100, 100), VertexSorter.BY_Z);
        RenderSystem.depthFunc(GL11.GL_ALWAYS);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (ScreenEffect effect : effects)
        {
            if (effect.type == ScreenEffect.LETTERBOX)
            {
                renderLetterbox(batcher, effect, width, height);
            }
            else if (effect.type == ScreenEffect.VIGNETTE)
            {
                renderVignette(batcher, effect, width, height);
            }
            else if (effect.type == ScreenEffect.GRAIN)
            {
                renderGrain(batcher, effect, width, height);
            }
        }

        RenderSystem.setProjectionMatrix(cache, VertexSorter.BY_Z);
        RenderSystem.enableCull();
    }

    private static float clamp01(float value)
    {
        return Math.max(0F, Math.min(1F, value));
    }

    /** Bars close in from the edges until only the wanted aspect ratio is left; the envelope slides them. */
    private static void renderLetterbox(Batcher2D batcher, ScreenEffect effect, float width, float height)
    {
        float factor = clamp01(effect.factor);
        float ratio = Math.max(0.1F, effect.value);

        if (factor <= 0F)
        {
            return;
        }

        float visibleHeight = width / ratio;

        if (visibleHeight < height)
        {
            float bar = (height - visibleHeight) / 2F * factor;

            batcher.box(0, 0, width, bar, effect.color);
            batcher.box(0, height - bar, width, height, effect.color);

            return;
        }

        float visibleWidth = height * ratio;

        if (visibleWidth < width)
        {
            float bar = (width - visibleWidth) / 2F * factor;

            batcher.box(0, 0, bar, height, effect.color);
            batcher.box(width - bar, 0, width, height, effect.color);
        }
    }

    /** The edges darken and fade towards the middle; the corners get it from two sides. */
    private static void renderVignette(Batcher2D batcher, ScreenEffect effect, float width, float height)
    {
        float amount = clamp01(effect.value) * clamp01(effect.factor);

        if (amount <= 0F)
        {
            return;
        }

        int solid = Colors.setA(effect.color, Colors.getA(effect.color) * amount);
        int clear = Colors.setA(effect.color, 0F);
        float thickness = Math.max(0.01F, Math.min(1F, effect.extra)) * height * 0.5F;

        batcher.gradientVBox(0, 0, width, thickness, solid, clear);
        batcher.gradientVBox(0, height - thickness, width, height, clear, solid);
        batcher.gradientHBox(0, 0, thickness, height, solid, clear);
        batcher.gradientHBox(width - thickness, 0, width, height, clear, solid);
    }

    /** Sparse light and dark specks, drawn again every frame so they crawl like film grain. */
    private static void renderGrain(Batcher2D batcher, ScreenEffect effect, float width, float height)
    {
        float amount = clamp01(effect.value) * clamp01(effect.factor);

        if (amount <= 0F)
        {
            return;
        }

        float speck = Math.max(0.5F, effect.extra);
        int count = (int) Math.min(12000F, width * height / (speck * speck) * 0.02F * amount);
        Random random = new Random(System.nanoTime() / 16_000_000L);

        batcher.beginBatch();

        for (int i = 0; i < count; i++)
        {
            float x = random.nextFloat() * width;
            float y = random.nextFloat() * height;
            int color = random.nextBoolean() ? Colors.WHITE : Colors.A100;

            batcher.box(x, y, x + speck, y + speck, Colors.setA(color, 0.05F + random.nextFloat() * 0.3F * amount));
        }

        batcher.endBatch();
    }
}
