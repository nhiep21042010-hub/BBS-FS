package com.bbsvfx.bbsvfx.client;

import mchorse.bbs_mod.forms.FormUtilsClient;
import mchorse.bbs_mod.forms.CustomVertexConsumerProvider;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.client.texture.NativeImageBackedTexture;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fire & smoke of the {@code bbsvfx:explosion} form (Ф3 skeleton). NOT vanilla particles: our own
 * camera-facing billboards over PROCEDURAL flipbook atlases, emitted through BBS's consumer provider
 * with the vanilla entity-translucent layer — packs light the smoke, flames render fullbright.
 *
 * <p>Everything is a deterministic closed form of the scrub time: the wind front ignites the sites
 * (rest distance / front speed — the same clock the debris shove uses), flames flicker from
 * hash+time, each site emits smoke puffs on a fixed schedule and every puff's position/size/alpha is
 * f(age) with the SAME wind field the debris flies on (ambient + front kick). Scrubbing backwards is
 * frame-exact, nothing is simulated. Cost is bounded by fireCount, never by the scan size.</p>
 */
public final class BbsVfxExplosionFx
{
    private static final Identifier FLAME_TEX = Identifier.of("bbsvfx", "fx/flame");
    private static final Identifier SMOKE_TEX = Identifier.of("bbsvfx", "fx/smoke");
    private static final Identifier FIREBALL_TEX = Identifier.of("bbsvfx", "fx/fireball");
    private static final Identifier SPARK_TEX = Identifier.of("bbsvfx", "fx/spark");
    private static final Identifier RING_TEX = Identifier.of("bbsvfx", "fx/ring");
    private static final Identifier DEBRIS_TEX = Identifier.of("bbsvfx", "fx/debris");
    private static final Identifier BIRD_TEX = Identifier.of("bbsvfx", "fx/bird");
    /** Soft radial glow for defocused ember bokeh — the drifting glowing motes that sell the atmosphere. */
    private static final Identifier BOKEH_TEX = Identifier.of("bbsvfx", "fx/bokeh");

    /* Quad kinds. */
    private static final int K_FLAME = 0;
    private static final int K_FIREBALL = 1;
    private static final int K_SMOKE = 2;
    private static final int K_RING = 3;
    /** Solid little chips — ground litter, shed leaves (drawn with the particle program too). */
    private static final int K_CHIP = 4;
    /** Defocused ember bokeh — soft additive glow motes, drawn emissive like the sparks. */
    private static final int K_BOKEH = 5;
    /** Long bright motion-streaked spark heads for the fountain from the core. */
    private static final int K_SPARK = 6;

    private static boolean texturesReady;

    /** Ignition sites (rest positions of the fireCount blocks nearest the epicenter), cached. */
    private static final LinkedHashMap<Long, float[]> SITES = new LinkedHashMap<>(8, 0.75F, true);

    /** Scratch list of translucent quads, sorted back-to-front each frame. */
    private static final List<Quad> QUADS = new ArrayList<>();

    private BbsVfxExplosionFx()
    {
    }

    private static final Vector3f SAMPLE_POS = new Vector3f();
    private static final Quaternionf SAMPLE_ROT = new Quaternionf();

    /**
     * Draw the explosion's fire + smoke + dust at the given scrub time (form-local space).
     *
     * <p>{@code ox/oy/oz} = the actor's world origin (camera-relative depth sorting). {@code bake} /
     * {@code blockFirstUnit} / {@code physPose} (all nullable) let the flames RIDE the simulated
     * debris: ignition site k is the k-th block nearest the epicenter = tier-A subset block k (the
     * same distance sort), so its flame sits on the block's CURRENT baked pose and its smoke spawns
     * where the block WAS at each puff's birth — burning blocks, not campfires at rest spots.</p>
     */
    public static void render(ExplosionForm form, List<DestructionBlock> allBlocks, long contentKey,
        float simTime, MatrixStack stack, int light, double ox, double oy, double oz, boolean inWorld,
        DestructionPhysics.Bake bake, int[] blockFirstUnit, float[] physPose)
    {
        int fireCount = form.fireCount.get();

        if (simTime <= 0F)
        {
            return;
        }

        ensureTextures();

        /* Fire is optional — the WORLD REACTION (ground wave / haze / litter) runs regardless. */
        float[] sites = fireCount > 0 ? sites(form, allBlocks, contentKey, fireCount) : new float[0];
        Vector3f epic = form.point();
        float front = Math.max(1F, form.windFrontSpeed.get());
        float burn = form.fireDuration.get();
        float flameSize = form.fireSize.get();
        float smoke = form.smokeScale.get();
        float dust = form.dustScale.get();
        float ambX = form.windAmbientX();
        float ambZ = form.windAmbientZ();
        float windStr = form.windStrength.get();
        float windDecay = Math.max(0.05F, form.windDecay.get());
        float suck = form.windSuction.get();
        float hold = form.windHold.get();
        float bakeDuration = bake != null ? bake.duration : form.physDuration.get();

        MinecraftClient mc = MinecraftClient.getInstance();
        Quaternionf camRot = mc.gameRenderer.getCamera().getRotation();
        Vector3f right = camRot.transform(new Vector3f(1F, 0F, 0F));
        Vector3f up = camRot.transform(new Vector3f(0F, 1F, 0F));
        /* Billboard normal FACES the camera — packs light the quads flat instead of shading the
         * up-facing normal into "spheres". */
        Vector3f toCam = camRot.transform(new Vector3f(0F, 0F, 1F));
        /* Camera in form-local space (world camera minus the actor origin) — for depth sorting only. */
        Vector3f camLocal = new Vector3f(
            (float) (mc.gameRenderer.getCamera().getPos().x - ox),
            (float) (mc.gameRenderer.getCamera().getPos().y - oy),
            (float) (mc.gameRenderer.getCamera().getPos().z - oz));
        Matrix4f matrix = stack.peek().getPositionMatrix();

        QUADS.clear();

        /* --- The blast fireball: an expanding emissive bloom at the epicenter, the first ~1.5s. ★It is now
         * VOLUMETRIC (an emissive term of ExplosionSmokeVolume, see the mushroom block below) rather than a
         * flipbook of textured fragments — sprite fire read as pasted-on cut-outs against the fractal cloud
         * the rest of the explosion is made of. Only its radius/heat curve is computed here. --- */
        float fbLife = 1.5F;
        float ballR = 0F;
        float ballHeat = 0F;

        if (simTime < fbLife)
        {
            float ft = simTime / fbLife;

            ballR = Math.max(3F, form.physExplosionRadius.get() * 0.45F) * (float) Math.pow(ft, 0.55D);
            /* Gentle tail — this now drives the ball's density too, so a steep curve would pop it out. */
            ballHeat = (float) Math.pow(1F - ft, 0.5D);
        }

        /* --- Detonation STAGING: a blinding flash beat, then crisp shockwave rings racing ahead of
         * everything — the anime "punch" the smooth ramps lacked. --- */
        if (simTime < 0.3F)
        {
            float ft = simTime / 0.3F;
            float flashR = Math.max(4F, form.physExplosionRadius.get() * 0.8F) * (0.6F + 0.6F * ft);

            QUADS.add(new Quad(K_FIREBALL, epic.x, epic.y + 2F, epic.z,
                flashR * (1F - 0.55F * ft), 0, 1F, 1F, 0.97F, 1F - 0.4F * ft));
        }

        if (simTime < 1.2F)
        {
            float rt = simTime / 1.2F;
            float ringR = front * simTime * 2.4F + 1.5F;
            float fade = (1F - rt) * (1F - rt);

            /* Ground-parallel ring hugging the terrain + a camera-facing halo ring. */
            QUADS.add(new Quad(K_RING, epic.x, epic.y + 1.3F, epic.z, ringR * 2F,
                (int) (simTime * 9F) & 3, 1F, 0.96F, 0.86F, fade).flat());
            QUADS.add(new Quad(K_RING, epic.x, epic.y + 2.5F, epic.z, ringR * 1.5F,
                (int) (simTime * 9F + 1) & 3, 1F, 0.9F, 0.75F, fade * 0.7F));
        }

        /* --- Debris smoke TRAILS: the most energetic chunks (nearest sites = strongest launch) drag
         * a fading smoke tail along their REAL baked trajectory — bake.sample into the past makes the
         * history free and scrub-exact. Settled debris stops trailing automatically. --- */
        if (bake != null && blockFirstUnit != null && smoke > 0F)
        {
            int trailBlocks = bake.count;
            int trailCount = Math.min(26, trailBlocks);

            for (int s = 0; s < trailCount; s++)
            {
                int unit = s < trailBlocks && blockFirstUnit[s] >= 0 ? blockFirstUnit[s] : -1;

                if (unit < 0 || hash(s, 81) > 0.8F)
                {
                    continue;
                }

                /* Still flying? Compare now vs a moment ago. */
                bake.sample(unit, Math.min(1F, simTime / bakeDuration), SAMPLE_POS, SAMPLE_ROT);

                float nx = SAMPLE_POS.x, nyy = SAMPLE_POS.y, nz = SAMPLE_POS.z;

                bake.sample(unit, Math.max(0F, (simTime - 0.15F) / bakeDuration), SAMPLE_POS, SAMPLE_ROT);

                float mdx = nx - SAMPLE_POS.x, mdy = nyy - SAMPLE_POS.y, mdz = nz - SAMPLE_POS.z;

                if (mdx * mdx + mdy * mdy + mdz * mdz < 0.05F)
                {
                    continue;
                }

                for (int p = 0; p < 6; p++)
                {
                    float back = 0.08F + p * 0.14F;
                    float ts = simTime - back;

                    if (ts <= 0F)
                    {
                        break;
                    }

                    bake.sample(unit, Math.min(1F, ts / bakeDuration), SAMPLE_POS, SAMPLE_ROT);

                    float u = p / 6F;
                    float hp = hash(s * 7 + p, 82);
                    float alpha = 0.34F * (1F - u) * (0.7F + 0.3F * hp);
                    float size = 0.55F + 0.45F * hp + 1.1F * u;
                    float grey = 0.4F - 0.12F * u;
                    /* The pouch closest to the chunk glows warm. */
                    float warm = p == 0 ? 0.25F : 0F;

                    QUADS.add(new Quad(K_SMOKE, SAMPLE_POS.x, SAMPLE_POS.y + 0.4F * u, SAMPLE_POS.z,
                        size, (s + p) & 3, grey + warm, grey + warm * 0.4F, grey, alpha));
                }
            }
        }

        /* --- SCORCH: a charred ring on the REAL terrain around the crater, appearing as the front
         * passes and staying — the ground should not look untouched past the crater lip. --- */
        if (inWorld && mc.world != null)
        {
            for (int i = 0; i < 44; i++)
            {
                float h1 = hash(i, 85), h2 = hash(i, 86), h3 = hash(i, 87);
                float ang = i * 2.399963F + h1 * 0.6F;
                float dist = Math.max(4F, form.physExplosionRadius.get()) * (0.4F + 0.8F * h2);
                float arrive = dist / front;

                if (simTime <= arrive)
                {
                    continue;
                }

                float lx = epic.x + (float) Math.cos(ang) * dist;
                float lz = epic.z + (float) Math.sin(ang) * dist;
                int wx = (int) Math.floor(ox + lx);
                int wz = (int) Math.floor(oz + lz);
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                if (top <= mc.world.getBottomY() + 1)
                {
                    continue;
                }

                float ramp = Math.min(1F, (simTime - arrive) / 0.8F);
                float ly = (float) (top - oy) + 0.07F;
                float size = (2.4F + 3.2F * h3) * (0.5F + 0.5F * ramp);

                QUADS.add(new Quad(K_SMOKE, lx, ly, lz, size, i & 3,
                    0.07F, 0.055F, 0.045F, 0.5F * ramp).flat());
            }
        }

        /* --- POPCORN: staggered secondary mini-bursts around the blast — anime explosions never pop
         * just once. Each: a quick fire-lick cluster + a spark spray + a smoke puffet after. --- */
        float popReach = Math.max(4F, form.physExplosionRadius.get());

        for (int i = 0; i < 7; i++)
        {
            float h1 = hash(i, 71), h2 = hash(i, 72), h3 = hash(i, 73), h4 = hash(i, 74);
            float delay = 0.45F + 2.4F * h1;
            float t = simTime - delay;
            float life = 0.8F + 0.35F * h2;

            if (t <= 0F || t >= life + 1.8F)
            {
                continue;
            }

            float ang = h3 * 6.2832F;
            float dist = popReach * (0.35F + 0.55F * h4);
            float bx = epic.x + (float) Math.cos(ang) * dist;
            float bz = epic.z + (float) Math.sin(ang) * dist;
            float by = epic.y + 1F + 2.5F * h2;
            float maxS = popReach * 0.16F * (0.7F + 0.6F * h1);

            if (t < life)
            {
                float ft = t / life;
                /* Fast pop, slower die — by SIZE (pack-safe). */
                float envelope = ft < 0.22F ? ft / 0.22F : 1F - (ft - 0.22F) / 0.78F;

                for (int j = 0; j < 3; j++)
                {
                    float hj = hash(i * 13 + j, 75);
                    float ox2 = ((hash(i * 13 + j, 76) - 0.5F) * 1.6F) * maxS;
                    float oy2 = (hj - 0.3F) * maxS + envelope * maxS * 0.4F;
                    float oz2 = ((hash(i * 13 + j, 77) - 0.5F) * 1.6F) * maxS;
                    int frame = (int) (t * 14F + hj * 4F) & 3;

                    QUADS.add(new Quad(K_FIREBALL, bx + ox2, by + oy2, bz + oz2,
                        maxS * (0.8F + 0.5F * hj) * envelope, frame, 1F, 0.95F, 0.85F, 1F));
                }

                /* A handful of spark streaks at birth. */
                if (t < 0.55F)
                {
                    for (int j = 0; j < 6; j++)
                    {
                        float hs = hash(i * 29 + j, 78);
                        float sAng = hs * 6.2832F;
                        float sEl = 0.3F + 1.1F * hash(i * 29 + j, 79);
                        float sSpd = 8F + 14F * hs;
                        float sdx = (float) (Math.cos(sAng) * Math.cos(sEl));
                        float sdy = (float) Math.sin(sEl);
                        float sdz = (float) (Math.sin(sAng) * Math.cos(sEl));
                        float fade = 1F - t / 0.55F;

                        QUADS.add(new Quad(K_FIREBALL,
                            bx + sdx * sSpd * t, by + sdy * sSpd * t - 8F * t * t, bz + sdz * sSpd * t,
                            (0.3F + 0.03F * sSpd) * fade, 0, 1F, 0.9F, 0.6F, 1F)
                            .streak(sdx * sSpd, sdy * sSpd - 16F * t, sdz * sSpd, 0.06F * fade + 0.015F));
                    }
                }
            }

            /* The puffet of smoke left behind. */
            if (smoke > 0F && t > life * 0.45F)
            {
                float st = t - life * 0.45F;
                float sLife = 1.6F;

                if (st < sLife)
                {
                    float u = st / sLife;
                    float alpha = 0.42F * (float) Math.pow(Math.sin(Math.PI * Math.min(u * 1.15F, 1F)), 0.7D);
                    float grey = 0.4F - 0.15F * u;

                    QUADS.add(new Quad(K_SMOKE, bx, by + 0.5F + 1.6F * st, bz,
                        maxS * (0.9F + 1.3F * u), i & 3, grey + 0.12F, grey + 0.05F, grey, alpha));
                }
            }
        }

        /* --- ★SPARK FOUNTAIN: burning embers thrown off the FIRE CORE itself (the rising ball/cap), each
         * trailing SMOKE — "fire with a smoke trail behind it". ★They eject from the core SURFACE, which
         * rises over time, so they come out of the bright fireball, NOT up from a point on the ground.
         * Closed-form → the trail is the head's own past positions (warm behind the head → smoke-grey at
         * the tail), free. Staggered through the fire; arcs under gravity; distance-scaled. --- */
        {
            /* ONE burst at detonation (not a sustained stream), but the embers are LONG-lived so they keep
             * flying and fading well into the later stage. */
            float fw = Math.min(1F, simTime / 0.15F);

            if (simTime < 9F)
            {
                float mushF = Math.max(0.001F, form.mushroomScale.get());
                float rc = Math.max(4F, form.physExplosionRadius.get() * 0.45F) * mushF;
                float riseHc = rc * 2.8F;

                for (int i = 0; i < 120; i++)
                {
                    float h1 = hash(i, 101), h2 = hash(i, 102), h3 = hash(i, 103), h4 = hash(i, 104), h5 = hash(i, 105);
                    float spawn = h1 * 0.3F;             /* a quick burst, not a sustained fountain */
                    float age = simTime - spawn;
                    float life = 3F + 3F * h4;           /* long-lived so they persist into the later stage */

                    if (age <= 0F || age >= life)
                    {
                        continue;
                    }

                    /* Core centre AT THE EMBER'S BIRTH — the fireball rises, so later embers leave higher. */
                    float coreY = epic.y + 2F + riseHc * (1F - (float) Math.exp(-spawn / 4.5F));
                    float coreX = epic.x + 0.15F * ambX * spawn * spawn;
                    float coreZ = epic.z + 0.15F * ambZ * spawn * spawn;
                    float coreR = rc * (0.5F + 0.5F * Math.min(1F, spawn / 2F));

                    /* Eject radially off the core surface, biased upward/outward. */
                    float el = 0.1F + 1.2F * h3;
                    float az = h5 * 6.2832F;
                    float dx = (float) (Math.cos(az) * Math.cos(el));
                    float dy = (float) Math.sin(el);
                    float dz = (float) (Math.sin(az) * Math.cos(el));
                    float orgX = coreX + dx * coreR;
                    float orgY = coreY + dy * coreR;
                    float orgZ = coreZ + dz * coreR;

                    float speed = 11F + 20F * h2;
                    float grav = 18F;
                    float headFade = (float) Math.cos(age / life * 1.5708F) * fw;

                    /* Head + a short SMOKE TRAIL sampled from the ember's own past trajectory. */
                    for (int j = 0; j <= 5; j++)
                    {
                        float a = age - j * 0.07F;

                        if (a <= 0.001F)
                        {
                            break;
                        }

                        float px = orgX + dx * speed * a;
                        float py = orgY + dy * speed * a - 0.5F * grav * a * a;
                        float pz = orgZ + dz * speed * a;

                        if (py < epic.y + 0.15F)
                        {
                            continue;
                        }

                        float ddx = px - camLocal.x, ddy = py - camLocal.y, ddz = pz - camLocal.z;
                        float dscale = Math.min(3F, Math.max(1F,
                            (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz) / 45F));

                        if (j == 0)
                        {
                            float sz = (0.7F + 0.6F * h4) * dscale;

                            QUADS.add(new Quad(K_FIREBALL, px, py, pz, sz, 0, 1F, 0.85F, 0.5F, headFade));
                        }
                        else
                        {
                            float tj = j / 5F;
                            float ta = headFade * (1F - tj);
                            float sz = (0.35F + 0.7F * tj) * dscale;

                            if (j <= 1)
                            {
                                QUADS.add(new Quad(K_FIREBALL, px, py, pz, sz, 0, 1F, 0.62F, 0.3F, ta * 0.75F));
                            }
                            else
                            {
                                float grey = 0.36F;

                                QUADS.add(new Quad(K_SMOKE, px, py, pz, sz, j & 3,
                                    grey * 1.1F, grey, grey * 0.92F, ta * 0.6F));
                            }
                        }
                    }
                }
            }
        }

        /* --- BIG SPARK FOUNTAIN: the user-tuned burst of bright streaked embers (spark_* values),
         * on top of the built-in ember spray above. --- */
        renderSparks(form, simTime, epic, camLocal);

        /* The sharp sparks are the single burst above (not a sustained ground shower). emberDur below
         * spans the full fire+smoke life for the soft-atmosphere bokeh only. */
        float emberDur = burn + 8F;

        /* --- BOKEH EMBERS: soft defocused glow motes drifting through the whole blast — the layer that
         * carries the reference's atmosphere. A WIDE size spread fakes depth of field (mostly small sharp
         * motes, a few big soft low-alpha ones reading as out-of-focus foreground/background). Rise on
         * buoyancy, drift on the wind, flicker, cool from ember-orange to red over life. Additive/emissive
         * (packs bloom them), closed-form in simTime, cost fixed regardless of block count. --- */
        {
            float bokehWin = Math.min(1F, simTime / 0.5F)
                * Math.max(0F, 1F - Math.max(0F, simTime - emberDur) / 3F);

            if (bokehWin > 0.01F)
            {
                float br = Math.max(4F, form.physExplosionRadius.get() * 0.55F);

                for (int i = 0; i < 360; i++)
                {
                    float h1 = hash(i, 71), h2 = hash(i, 72), h3 = hash(i, 73), h4 = hash(i, 74), h5 = hash(i, 75);
                    float spawn = h1 * emberDur;
                    float mlife = 2F + 3.5F * h2;
                    float ma = simTime - spawn;

                    if (ma <= 0F || ma >= mlife)
                    {
                        continue;
                    }

                    float mu = ma / mlife;                       /* 0..1 age */
                    /* Origin: a disk around the epicentre, low to mid height. */
                    float ang = h3 * 6.2832F;
                    float rad = br * (0.15F + 0.85F * h4);
                    float bx = epic.x + (float) Math.cos(ang) * rad;
                    float bz = epic.z + (float) Math.sin(ang) * rad;
                    float by = epic.y + 0.4F + 3F * h5;

                    /* Rise + wind + gentle sway. */
                    float rise = 1.4F + 3F * h5;
                    float px = bx + 0.35F * ambX * ma * ma + (float) Math.sin(h2 * 40F + ma * 1.7F) * 0.6F;
                    float py = by + rise * ma;
                    float pz = bz + 0.35F * ambZ * ma * ma + (float) Math.cos(h3 * 33F + ma * 1.5F) * 0.6F;

                    /* ★Fake DoF: cubic size distribution — most motes small & sharp, a few large & soft.
                     * Large motes are DIMMER (defocused reads as low-contrast). */
                    float depth = h1;
                    float size = 0.09F + 2.1F * depth * depth * depth;
                    float soft = Math.min(1F, size / 1.2F);       /* how defocused this one is */
                    float grow = 1F + 0.35F * mu;

                    /* Flicker + fade in/out over life. */
                    float flick = 0.7F + 0.3F * (float) Math.sin(h4 * 60F + ma * 9F);
                    float fade = Math.min(1F, mu / 0.15F) * (1F - mu) * bokehWin * flick;
                    float alpha = fade * (0.85F - 0.5F * soft);   /* big soft ones dimmer */

                    if (alpha <= 0.004F)
                    {
                        continue;
                    }

                    /* Ember ramp: cools orange → red over life. */
                    float g = 0.42F + 0.28F * (1F - mu);
                    float b = 0.12F + 0.14F * (1F - mu);

                    QUADS.add(new Quad(K_BOKEH, px, py, pz, size * grow, i & 3, 1F, g, b, alpha));
                }
            }
        }

        /* --- The hero MUSHROOM (the Fate reference): a cauliflower fireball that RISES, cools from
         * white-orange into dark smoke and spreads into a cap over a dust stem. Hot phase = emissive
         * flame quads fading by SIZE (pack-safe); cool phase = smoke through the particle pipeline
         * (alpha blending is safe there). All closed-form in simTime. --- */
        float mush = form.mushroomScale.get();
        int shape = form.shape.get();

        /* SPHERE / fireball shape: NO cap/stem at all — a single roiling ball stays at the epicentre,
         * carried entirely by the volume (its fire is the emissive term, driven by sphere_heat).
         * ★It uses its own sphereScale, so turning mushroomScale down does NOT kill the fireball. */
        float sphereScale = form.sphereScale.get();

        if (sphereScale > 0F && simTime > 0.05F && shape == 1)
        {
            if (stack != null)
            {
                org.joml.Matrix4f vm = new org.joml.Matrix4f(stack.peek().getPositionMatrix())
                    .translate(epic.x, epic.y + 1F, epic.z);

                float expand = Math.max(0.1F, form.sphereExpand.get());
                /* The ball GROWS out of the flash (exponential approach to the full radius). */
                float grow = 1F - (float) Math.exp(-simTime * expand * 0.45F);
                float radius = form.sphereRadius.get() * sphereScale * (0.2F + 0.8F * grow);
                float heat = form.sphereHeat.get() * Math.min(1F, simTime * 2.5F)
                    * (float) Math.exp(-simTime / 3.5F);
                float fadeAll = Math.max(0F, 1F - Math.max(0F, simTime - 9F) / 5F);
                float driftX = 0.15F * ambX * simTime * simTime;
                float driftZ = 0.15F * ambZ * simTime * simTime;
                float grey = 0.35F - 0.07F * Math.min(1F, simTime / 4F)
                    + 0.08F * Math.max(0F, simTime - 7F) / 7F;

                ExplosionSmokeVolume.queue(vm,
                    0F, 0F, 0F,
                    0.16F * fadeAll * sphereScale,
                    1F, 1.1F,
                    heat, 1.7F, fadeAll,
                    simTime % 3600F,
                    grey + heat * 0.30F, grey + heat * 0.12F, grey,
                    driftX, driftZ, 0F, 0F,
                    1F, radius, heat);
            }
        }
        /* Starts almost immediately (not at 0.3s) because the volume now also carries the blast fireball. */
        else if (mush > 0F && simTime > 0.05F)
        {
            float R = Math.max(4F, form.physExplosionRadius.get() * 0.45F) * mush;
            float riseH = R * 2.8F;
            float h = riseH * (1F - (float) Math.exp(-simTime / 4.5F));
            /* Birth ramp: the ball GROWS out of the initial flash instead of popping in mid-size. */
            float birth = Math.min(1F, simTime / 1.6F);
            float capR = R * (0.85F + 0.6F * (1F - (float) Math.exp(-simTime / 6F))) * (0.25F + 0.75F * birth);
            /* Slower cooling: at 3.2s the fire was already down to a third and read as "gone". */
            float heat = (float) Math.exp(-simTime / 4.8F);
            float fadeAll = Math.max(0F, 1F - Math.max(0F, simTime - 11F) / 5F);
            float driftX = 0.15F * ambX * simTime * simTime;
            float driftZ = 0.15F * ambZ * simTime * simTime;

            /* ★The cap+stem SMOKE is now a volumetric fractal-noise cloud (ExplosionSmokeVolume) rather than
             * a swarm of billboards, with the FIRE as an emissive term inside the very same field — that is
             * what makes the hot core cool into the smoke continuously instead of blinking out. The volume
             * rides the form matrix translated to the epicentre, so no world origin is reconstructed. The
             * billboard cap/stem puffs below are kept only as a thin garnish on top of the volume. */
            if (stack != null)
            {
                org.joml.Matrix4f vm = new org.joml.Matrix4f(stack.peek().getPositionMatrix())
                    .translate(epic.x, epic.y + 1F, epic.z);

                /* ★Darker smoke (0.25–0.35 band), LIT BY THE FIRE while the core is hot — the warm
                 * term dies with the heat, so the cloud visibly goes out with its fire. */
                float grey = 0.35F - 0.07F * Math.min(1F, simTime / 4F)
                    + 0.08F * Math.max(0F, simTime - 7F) / 7F;

                ExplosionSmokeVolume.queue(vm,
                    h, capR, R * 0.42F,
                    0.16F * fadeAll,
                    1F, 1.1F,
                    heat, 1.7F, fadeAll,
                    simTime % 3600F,
                    grey + heat * 0.30F, grey + heat * 0.12F, grey,
                    driftX, driftZ, ballR, ballHeat,
                    0F, 0F, 0F);
            }

            /* EMBERS that OUTLIVE the flame: the cloud keeps shedding glowing motes for a few seconds after
             * the hot core has cooled, so the fire doesn't hand over to an empty grey mass. Deterministic
             * loops off simTime, fading by SIZE like every other emissive here. */
            float emberWin = Math.min(1F, simTime / 0.8F) * Math.max(0F, 1F - Math.max(0F, simTime - 9F) / 4.5F);

            if (emberWin > 0.01F)
            {
                for (int k = 0; k < 26; k++)
                {
                    float ha = hash(k, 51), hb = hash(k, 52), hc = hash(k, 53);
                    float period = 1.3F + 1.2F * ha;
                    float phased = simTime + hb * period;
                    float tau = phased - (float) Math.floor(phased / period) * period;
                    float elife = period * 0.85F;

                    if (tau >= elife)
                    {
                        continue;
                    }

                    float eu = tau / elife;
                    float az = hc * 6.2832F + tau * 0.8F;
                    float rad = R * (0.22F + 0.5F * ha) * (0.5F + 0.7F * eu);
                    float ex = epic.x + (float) Math.cos(az) * rad + driftX * eu;
                    float ez = epic.z + (float) Math.sin(az) * rad + driftZ * eu;
                    float ey = epic.y + 1.5F + (3F + 4F * hb) * tau;
                    float fade = (1F - eu) * emberWin;

                    QUADS.add(new Quad(K_FIREBALL, ex, ey, ez, (0.17F + 0.13F * hc) * fade, 0,
                        1F, 0.70F, 0.36F, 1F));
                }
            }

            /* CAP: puffs ride a TOROIDAL ROLL — the real mushroom physics (a buoyant vortex ring):
             * each puff orbits in its vertical (radial, up) plane — up through the middle, outward
             * over the top, DOWN the outer face — while the ring itself rises and widens. */
            for (int k = 0; k < 30; k++)
            {
                float ha = hash(k, 31), hb = hash(k, 32), hc = hash(k, 33);
                float az = k * 2.399963F + ha;
                float theta = hb * 6.2832F - simTime * 0.42F;
                float ringR = capR * 0.55F;
                float tubeR = capR * (0.42F + 0.1F * hc);
                float radial = ringR + tubeR * (float) Math.cos(theta) * 0.85F;
                float px = epic.x + (float) Math.cos(az) * radial + driftX;
                float pz = epic.z + (float) Math.sin(az) * radial + driftZ;
                float py = epic.y + 1F + h + tubeR * (float) Math.sin(theta) * 0.8F;
                float size = capR * (0.55F + 0.4F * hc);

                /* ★No billboard fire here any more. Crisp textured sprites read as pasted-on cut-outs
                 * against the soft volumetric cloud — two different visual languages in one shot. The hot
                 * core is an emissive term INSIDE the volume instead, so it shares the cloud's own
                 * turbulence and cools into it with nothing left to pop. */

                /* Cooling smoke takes over: darkens, then lightens as it thins out. */
                float cool = Math.min(1F, simTime / 4F);
                float grey = 0.35F - 0.07F * cool + 0.08F * Math.max(0F, simTime - 7F) / 7F;
                float warm = Math.max(0F, heat - 0.15F) * 0.8F;
                /* Garnish only — the volume carries the body of the cloud now. */
                float alpha = 0.18F * cool * fadeAll;

                if (alpha > 0.01F)
                {
                    QUADS.add(new Quad(K_SMOKE, px, py, pz, size * (1.05F + 0.25F * cool), (k + 1) & 3,
                        grey + warm, grey + warm * 0.45F, grey, alpha));
                }
            }

            /* STEM: a dust column the cap leaves behind, flaring at the base. */
            for (int k = 0; k < 22; k++)
            {
                float f = (k + 0.5F) / 22F;
                float hk = hash(k, 34);
                float stemY = h * f * 0.88F;
                float stemR = R * (0.32F + 0.3F * (1F - f) + 0.06F * (float) Math.sin(simTime * 0.7F + hk * 6.28F));
                float az = hk * 6.2832F + f * 2.2F;
                float px = epic.x + (float) Math.cos(az) * stemR * 0.6F + driftX * f;
                float pz = epic.z + (float) Math.sin(az) * stemR * 0.6F + driftZ * f;
                float alpha = Math.min(0.5F, Math.max(0F, simTime * 0.7F - f * 2.2F)) * fadeAll * 0.35F;

                if (alpha > 0.01F)
                {
                    float grey = 0.42F + 0.12F * hk;

                    QUADS.add(new Quad(K_SMOKE, px, epic.y + 0.6F + stemY, pz, stemR * (1.5F + 0.6F * hk), k & 3,
                        grey * 1.05F, grey * 0.95F, grey * 0.82F, alpha));
                }
            }
        }

        int siteCount = sites.length / 3;
        int bakeBlocks = bake != null ? bake.count : 0;

        for (int s = 0; s < siteCount; s++)
        {
            /* Rest site (= ignition clock anchor). */
            float rx = sites[s * 3] + 0.5F;
            float ry = sites[s * 3 + 1] + 0.5F;
            float rz = sites[s * 3 + 2] + 0.5F;
            float h1 = hash(s, 1), h2 = hash(s, 2), h3 = hash(s, 3), h4 = hash(s, 4);

            float dx = rx - epic.x, dy = ry - epic.y, dz = rz - epic.z;
            float dist = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            float ignite = dist / front;
            float age = simTime - ignite;

            if (age <= 0F)
            {
                continue;
            }

            /* The burning BLOCK's current pose (site s = tier-A subset block s, same distance sort) —
             * flames ride the flying/settled debris instead of hovering at the rest spot. */
            int unit = bake != null && s < bakeBlocks && blockFirstUnit != null && blockFirstUnit[s] >= 0
                ? blockFirstUnit[s]
                : -1;
            float sx = rx, sy = ry, sz = rz;

            if (unit >= 0 && physPose != null)
            {
                sx = physPose[unit * 7];
                sy = physPose[unit * 7 + 1];
                sz = physPose[unit * 7 + 2];
            }

            /* Flame: quick ramp-in, long fade-out, per-site flicker; a 4-frame flipbook. */
            float life = burn * (0.9F + 0.3F * h1);

            if (age < life)
            {
                /* Slow ignition (0.9s ramp-in) — the front sweeping past used to pop each site alight. */
                float envelope = Math.min(age / 0.9F, 1F) * Math.min((life - age) / (life * 0.35F), 1F);

                if (envelope > 0.005F)
                {
                    /* Fade by SIZE, smoothly to zero and with NO cutoff threshold: shaderpacks
                     * alpha-TEST this layer, so an alpha fade holds fully opaque and then pops —
                     * a shrinking flame dies visibly under both pipelines. */
                    float flicker = 0.85F + 0.2F * (float) Math.sin(h2 * 37F + simTime * (11F + 5F * h3));
                    float size = flameSize * (0.8F + 0.7F * h4) * envelope * flicker;
                    int frame = (int) (simTime * (10F + 4F * h1) + h2 * 4F) & 3;

                    QUADS.add(new Quad(K_FLAME, sx, sy + 0.1F + 0.35F * size, sz, size,
                        frame, 1F, 1F, 1F, Math.min(1F, envelope * 2F)));
                }
            }

            /* Embers: a third of the burning sites shed tiny glowing sparks that float up and die. */
            if (h4 > 0.66F && age < life + 1F)
            {
                float period = 0.7F + 0.4F * h1;
                int last = (int) (age / period);

                for (int k = Math.max(0, last - 1); k <= last; k++)
                {
                    float spawn = k * period + hash(s, 45 + (k & 3)) * 0.3F;
                    float emberAge = age - spawn;
                    float emberLife = 1.1F + 0.6F * h2;

                    if (emberAge <= 0F || emberAge >= emberLife || spawn > life)
                    {
                        continue;
                    }

                    float he = hash(s * 17 + k, 46);
                    float fade = 1F - emberAge / emberLife;
                    float exx = sx + (float) Math.sin(he * 50F + emberAge * 3F) * 0.5F + 0.5F * ambX * emberAge * emberAge;
                    float ezz = sz + (float) Math.cos(he * 33F + emberAge * 2.6F) * 0.5F + 0.5F * ambZ * emberAge * emberAge;
                    float eyy = sy + 0.6F + (2.2F + 1.5F * he) * emberAge;

                    QUADS.add(new Quad(K_FIREBALL, exx, eyy, ezz, (0.14F + 0.12F * he) * fade, 0,
                        1F, 0.85F, 0.55F, 1F));
                }
            }

            /* Smoke: a steady puff schedule; each puff spawns where the block WAS at its birth, then
             * rises, grows, darkens and drifts on the wind. Smolders on after the flame dies. */
            if (smoke > 0F)
            {
                float period = 0.5F + 0.25F * h3;
                float puffLife = 3.5F * (0.8F + 0.4F * h2) * Math.max(0.4F, smoke);
                int last = (int) (age / period);

                for (int k = Math.max(0, last - 9); k <= last; k++)
                {
                    float spawn = k * period + hash(s, 5 + (k & 7)) * 0.2F;
                    float puffAge = age - spawn;

                    if (puffAge <= 0F || puffAge >= puffLife || spawn > life + 4F)
                    {
                        continue;
                    }

                    float bx = rx, by = ry, bz = rz;

                    if (unit >= 0 && bake != null)
                    {
                        bake.sample(unit, Math.min(1F, (ignite + spawn) / bakeDuration), SAMPLE_POS, SAMPLE_ROT);
                        bx = SAMPLE_POS.x;
                        by = SAMPLE_POS.y;
                        bz = SAMPLE_POS.z;
                    }

                    float u = puffAge / puffLife;
                    float hp = hash(s * 31 + k, 6);
                    float rise = (1.1F + 0.7F * hp) * puffAge * (1F - 0.25F * u);
                    float wobble = 0.35F * u;
                    float px = bx + (float) Math.sin(hp * 43F + puffAge * 0.9F) * wobble + windX(dx, dist, windStr, windDecay, hold, front, puffAge, ambX, simTime - spawn, suck);
                    float pz = bz + (float) Math.cos(hp * 61F + puffAge * 0.8F) * wobble + windZ(dz, dist, windStr, windDecay, hold, front, puffAge, ambZ, simTime - spawn, suck);
                    float py = by + 0.6F + rise;
                    float size = (0.55F + 0.45F * hp + 1.7F * u) * smoke;

                    /* Smolder tail: post-flame smoke thins out. */
                    float smolder = spawn > life ? Math.max(0.25F, 1F - (spawn - life) / 4F) : 1F;
                    float alpha = 0.5F * smolder * (float) Math.pow(Math.sin(Math.PI * Math.min(u * 1.2F, 1F)), 0.7D);
                    float grey = 0.38F - 0.13F * u;

                    /* Warm glow while the fire below is young. */
                    float warm = Math.max(0F, 0.35F - u) * (age < life ? 1F : 0F);

                    QUADS.add(new Quad(K_SMOKE, px, py, pz, size,
                        (s * 7 + k) & 3, grey + warm * 0.5F, grey + warm * 0.25F, grey, alpha));
                }
            }
        }

        /* --- The dust wave: ground-hugging billows riding the blast front, then lingering. --- */
        if (dust > 0F)
        {
            float waveSpeed = front * 0.85F;
            float reach = Math.max(6F, form.physExplosionRadius.get() * 0.9F);
            float linger = 3.5F;

            for (int j = 0; j < 44; j++)
            {
                float hj = hash(j, 21);
                float az = j * 2.399963F + hj * 0.5F;
                float cos = (float) Math.cos(az), sin = (float) Math.sin(az);
                float rNow = Math.min(waveSpeed * simTime, reach) * (0.9F + 0.2F * hj);

                for (int p = 0; p < 3; p++)
                {
                    float r = rNow - p * 2.4F - hj * 1.6F;

                    if (r < 1.5F)
                    {
                        continue;
                    }

                    float passT = r / waveSpeed;
                    float pAge = simTime - passT;
                    float hpp = hash(j * 5 + p, 22);
                    float pLife = linger * (0.75F + 0.5F * hpp);

                    if (pAge <= 0F || pAge >= pLife)
                    {
                        continue;
                    }

                    float u = pAge / pLife;
                    float size = (2.4F + 0.09F * r + 2.8F * u) * dust;
                    float alpha = 0.45F * (float) Math.pow(1F - u, 1.15D) * Math.min(simTime * 2.5F, 1F);
                    /* Sustained blast wind keeps driving the billow out; the negative phase then
                     * visibly drags it back toward the epicenter. */
                    float rEff = Math.max(1F, r + sustained(windStr, hold, pAge) * 0.5F
                        - suction(windStr, windDecay, hold, pAge, suck));
                    float px = epic.x + cos * rEff + 0.5F * ambX * pAge * pAge;
                    float pz = epic.z + sin * rEff + 0.5F * ambZ * pAge * pAge;
                    float py = epic.y + 0.5F + size * 0.3F + u * 1.4F;
                    float tone = 0.82F + 0.25F * hpp;

                    QUADS.add(new Quad(K_SMOKE, px, py, pz, size, (j + p) & 3,
                        0.62F * tone, 0.55F * tone, 0.46F * tone, alpha));
                }
            }
        }

        /* ==================== WORLD REACTION: the landscape itself answers. ==================== */
        float groundWave = form.groundWave.get();
        float haze = form.hazeScale.get();
        /* Decoupled from the capture: the REAL world reacts out to its own radius. */
        float reactReach = Math.max(form.physExplosionRadius.get() * 1.2F, form.worldReach.get());

        /* --- The GROUND WAVE: as the front crosses each terrain column, the column kicks up a dust
         * puff that STAYS where it was born and lives out its ~2.5 s — so the wave reads as a racing
         * ring with a wide lingering skirt behind it, running across the ACTUAL landscape (grass,
         * roads, rooftops "boil" as it passes). Heightmap-following, deterministic per (ray, band).
         * NB the first version tied puff positions to the CURRENT front radius — a thin fleeting
         * ring nobody could even see. --- */
        if (inWorld && mc.world != null && groundWave > 0F)
        {
            /* Wider reach = coarser bands, so the alive-quad budget stays flat. */
            float spacing = Math.max(4F, reactReach / 48F);
            int bands = (int) (reactReach / spacing);
            net.minecraft.util.math.BlockPos.Mutable surfPos = new net.minecraft.util.math.BlockPos.Mutable();

            for (int j = 0; j < 56; j++)
            {
                float hj = hash(j, 121);
                float ang = j * 2.399963F + hj * 0.9F;
                float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);

                for (int b = 0; b < bands; b++)
                {
                    float hb = hash(j * 61 + b, 122);
                    /* FIXED birth radius per (ray, band) — the puff ages in place. */
                    float r = (b + 0.3F + 0.55F * hb) * spacing;

                    if (r > reactReach)
                    {
                        break;
                    }

                    float arrive = r / front;
                    float age = simTime - arrive;
                    float life = 2.4F * (0.75F + 0.5F * hb);

                    if (age <= 0F || age >= life)
                    {
                        continue;
                    }

                    /* A short outward roll after birth + the sustained wind + the negative-phase
                     * pull-back. */
                    float roll = 2.2F * (1F - (float) Math.exp(-age / 0.7F))
                        + sustained(windStr, hold, age) * 0.6F
                        - suction(windStr, windDecay, hold, age, suck) * 0.5F;
                    float lx = epic.x + cos * (r + roll);
                    float lz = epic.z + sin * (r + roll);
                    int wx = (int) Math.floor(ox + lx);
                    int wz = (int) Math.floor(oz + lz);
                    int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                    if (top <= mc.world.getBottomY() + 1)
                    {
                        continue;
                    }

                    float u = age / life;
                    /* Weaker at the far edge — the wave dies out instead of stopping on a line. */
                    float falloff = 0.35F + 0.65F * Math.max(0F, 1F - r / reactReach);
                    float size = (2.2F + 2.8F * u) * groundWave * (0.7F + 0.6F * hb);
                    float alpha = 0.5F * (float) Math.pow(1F - u, 1.3D) * falloff;
                    float py = (float) (top - oy) + 0.3F + size * 0.3F + age * 1.1F;
                    float tone = 0.8F + 0.3F * hb;

                    /* THE REAL WORLD reacts: tint the puff with the actual surface block's map colour
                     * (green wave over grass, sandy over desert, grey over stone) — the ground itself
                     * boils up, not a generic overlay. */
                    surfPos.set(wx, top - 1, wz);

                    int mapCol = mc.world.getBlockState(surfPos).getMapColor(mc.world, surfPos).color;
                    float mr = ((mapCol >> 16) & 0xFF) / 255F;
                    float mg = ((mapCol >> 8) & 0xFF) / 255F;
                    float mb = (mapCol & 0xFF) / 255F;
                    /* Mix toward dusty grey so it still reads as dust. */
                    float cr = (mr * 0.55F + 0.6F * 0.45F) * tone;
                    float cg = (mg * 0.55F + 0.54F * 0.45F) * tone;
                    float cb = (mb * 0.55F + 0.45F * 0.45F) * tone;

                    QUADS.add(new Quad(K_SMOKE, lx, py, lz, size, (j + b) & 3, cr, cg, cb, alpha));
                }
            }

            /* --- Ground LITTER skitter: pebbles/splinters chased downwind along the surface with
             * decaying hops — the small stuff near the ground is what sells the WIND. --- */
            for (int i = 0; i < 120; i++)
            {
                float h1 = hash(i, 125), h2 = hash(i, 126), h3 = hash(i, 127), h4 = hash(i, 128);
                float ang = h1 * 6.2832F;
                float d0 = (0.1F + 0.75F * h2) * reactReach * 0.85F;
                float arrive = d0 / front;
                float age = simTime - arrive;

                if (age <= 0F)
                {
                    continue;
                }

                /* Bounded slide: a hard kick that friction eats + the sustained wind keeps the litter
                 * tumbling while it blows. */
                float slideTau = 0.9F + 0.7F * h3;
                float slideDist = (6F + 10F * h4) * slideTau * (1F - (float) Math.exp(-age / slideTau))
                    + sustained(windStr, hold, age) * (0.4F + 0.5F * h4);
                float fadeStart = 3F * slideTau;
                float alpha = 1F - Math.max(0F, (age - fadeStart) / 0.8F);

                if (alpha <= 0F)
                {
                    continue;
                }

                float cos = (float) Math.cos(ang), sin = (float) Math.sin(ang);
                float lx = epic.x + cos * (d0 + slideDist) + 0.5F * ambX * age * age * 0.3F;
                float lz = epic.z + sin * (d0 + slideDist) + 0.5F * ambZ * age * age * 0.3F;
                int wx = (int) Math.floor(ox + lx);
                int wz = (int) Math.floor(oz + lz);
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                if (top <= mc.world.getBottomY() + 1)
                {
                    continue;
                }

                float hopH = (0.45F + 0.5F * h2) * (float) Math.exp(-age / 1.1F);
                float py = (float) (top - oy) + 0.2F
                    + Math.abs((float) Math.sin(Math.PI * age * (2.2F + 1.5F * h3))) * hopH;
                float size = (0.28F + 0.27F * h4) * Math.min(1.5F, groundWave);
                /* Dark dirt/splinter tones. */
                float tr = 0.28F + 0.2F * h1, tg = 0.22F + 0.15F * h1, tb = 0.16F + 0.1F * h1;

                QUADS.add(new Quad(K_CHIP, lx, py, lz, size, i & 3, tr, tg, tb, Math.min(1F, alpha)));
            }
        }

        /* --- LINGERING HAZE: the air stays murky for ~half a minute after the front — big slow
         * billows hanging over the terrain, drifting on the ambient wind and slowly pulled toward the
         * rising column (the suction). Bonus: atmospheric perspective makes the mushroom read BIGGER. --- */
        if (inWorld && mc.world != null && haze > 0F)
        {
            for (int i = 0; i < 48; i++)
            {
                float h1 = hash(i, 131), h2 = hash(i, 132), h3 = hash(i, 133);
                float ang = i * 2.399963F + h1;
                float d0 = (float) Math.sqrt(h2) * reactReach * 0.85F;
                float arrive = d0 / front + 0.5F;
                float age = simTime - arrive;
                float life = 30F + 25F * h3;

                if (age <= 0F || age >= life)
                {
                    continue;
                }

                float u = age / life;
                /* The blast wind first drives the murk out, then the inward creep: the column feeds
                 * on the surrounding air. */
                float rEff = Math.max(2F, d0 + sustained(windStr, hold, age) * 0.7F
                    - suction(windStr, windDecay, hold, age, suck) * 0.6F
                    - age * 0.12F * haze);
                /* Velocity-capped ambient drift (t² over 40 s would fly away). */
                float tA = Math.min(age, 8F);
                float driftX = 0.5F * ambX * tA * tA + ambX * 8F * Math.max(0F, age - 8F);
                float driftZ = 0.5F * ambZ * tA * tA + ambZ * 8F * Math.max(0F, age - 8F);
                /* Gusty churn: the murk rolls sideways in decaying waves instead of hanging dead. */
                float churn = 2.2F * (float) Math.sin(age * 0.55F + h1 * 6.28F) * (float) Math.exp(-age / 14F);
                float perpX = -(float) Math.sin(ang), perpZ = (float) Math.cos(ang);
                float lx = epic.x + (float) Math.cos(ang) * rEff + driftX + perpX * churn;
                float lz = epic.z + (float) Math.sin(ang) * rEff + driftZ + perpZ * churn;
                int wx = (int) Math.floor(ox + lx);
                int wz = (int) Math.floor(oz + lz);
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                if (top <= mc.world.getBottomY() + 1)
                {
                    continue;
                }

                float size = (6.5F + 5.5F * h2) * (1F + 0.7F * u);
                float ramp = Math.min(1F, age / 3F);
                float alpha = 0.12F * Math.min(1.5F, haze) * ramp * (float) Math.pow(1F - u, 1.3D);
                float py = (float) (top - oy) + 2F + 2.5F * h3 + Math.min(age * 0.12F, 2.5F);
                float tone = 0.85F + 0.25F * h1;

                QUADS.add(new Quad(K_SMOKE, lx, py, lz, size, i & 3,
                    0.58F * tone, 0.54F * tone, 0.47F * tone, alpha));
            }
        }

        /* --- WIND STREAKS: the AIR made visible — stretched dust wisps racing radially, fast with the
         * front, then in decaying turbulent gusts for ~15 s. Two bands: ground-hugging wisps and
         * airborne ones. Each slot re-fires in waves (per-life random offset/speed), all closed-form. --- */
        float streaks = form.windStreaks.get();

        if (inWorld && mc.world != null && streaks > 0F)
        {
            int slots = Math.round(70F * Math.min(2F, streaks));

            for (int i = 0; i < slots; i++)
            {
                float h1 = hash(i, 181), h2 = hash(i, 182), h3 = hash(i, 183), h5 = hash(i, 185);
                float ang = h1 * 6.2832F;
                float d0 = (0.08F + 0.85F * h2) * reactReach;
                float arrive = d0 / front;
                float since = simTime - arrive;

                if (since <= 0F)
                {
                    continue;
                }

                /* Gust envelope: the wind dies down after the sustained phase. */
                float gustEnv = (float) Math.exp(-since / (4F + 2F * hold));

                if (gustEnv < 0.06F)
                {
                    continue;
                }

                float period = 1.6F + 1.4F * h3;
                int k = (int) (since / period);
                float lifeStart = k * period + hash(i * 57 + k, 186) * 0.5F;
                float tau = since - lifeStart;
                float life = 0.9F + 0.5F * hash(i * 13 + k, 187);

                if (tau <= 0F || tau >= life)
                {
                    continue;
                }

                float h4 = hash(i * 91 + k, 188);
                /* The first wave rides the front; later waves blow hard while the sustained wind
                 * holds, then fall back to dying gusts. */
                float pressure = hold > 0.05F ? (float) Math.exp(-since / Math.max(hold, 0.5F)) : 0F;
                float v = (k == 0 ? front * 0.7F : (7F + 9F * h4) * (0.5F + 1.1F * pressure))
                    * (0.6F + 0.4F * gustEnv);
                float swirl = 0.5F * (hash(i * 7 + k, 189) - 0.5F);
                float dirX = (float) Math.cos(ang + swirl), dirZ = (float) Math.sin(ang + swirl);
                float bx = epic.x + (float) Math.cos(ang) * d0 + (hash(i * 3 + k, 190) - 0.5F) * 16F;
                float bz = epic.z + (float) Math.sin(ang) * d0 + (hash(i * 5 + k, 191) - 0.5F) * 16F;
                float lx = bx + dirX * v * tau;
                float lz = bz + dirZ * v * tau;
                int wx = (int) Math.floor(ox + lx);
                int wz = (int) Math.floor(oz + lz);
                int top = mc.world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                if (top <= mc.world.getBottomY() + 1)
                {
                    continue;
                }

                /* 60% hug the ground, the rest fly at chest-to-treetop height. */
                boolean low = h5 < 0.6F;
                float py = (float) (top - oy) + (low ? 0.4F + 0.9F * h4 : 2.5F + 4.5F * h4);
                float halfLen = (1.6F + 2F * h4) * (0.5F + 0.5F * v / Math.max(front, 1F)) + 0.8F;
                float envA = (float) Math.sin(Math.PI * tau / life);
                float alpha = 0.28F * envA * (0.45F + 0.55F * gustEnv) * Math.min(1.5F, streaks);

                DEFERRED.add(new float[] {
                    (float) (lx + ox), (float) (py + oy), (float) (lz + oz),
                    halfLen, (i + k) & 3, 0.78F, 0.74F, 0.67F, alpha, light, 0F,
                    dirX, 0F, dirZ, 0.1F + 0.1F * h4});
            }
        }

        /* --- REAL-WORLD TREES (uncaptured): a non-destructive crown scan over the loaded terrain —
         * every ACTUAL tree in reach sheds a burst of leaves as the front slams it and seeds the bird
         * scatter, far past the captured zone. World blocks are never touched — the burst is what
         * sells the hit. Crowns inside the captured-foliage zone are skipped (the proxies there
         * already shed). --- */
        float leafScale = form.bendLeaves.get();
        int birdCount = form.birdCount.get();

        if (inWorld && mc.world != null && (leafScale > 0F || birdCount > 0))
        {
            float skipR = form.foliage.getAllTyped().isEmpty() ? 0F : form.bendScanRadius.get();
            double epicWx = ox + epic.x, epicWz = oz + epic.z;
            Crowns crowns = crowns(mc.world, epicWx, epicWz, reactReach, skipR);
            int n = crowns.count;

            if (n > 0 && leafScale > 0F)
            {
                for (int i = 0; i < n; i++)
                {
                    float dist = crowns.dist[i];
                    float arrive = dist / front;

                    if (simTime <= arrive || simTime > arrive + 6F)
                    {
                        continue;
                    }

                    float falloff = Math.max(0F, 1.05F - dist / reactReach);

                    /* Gate: near the blast most crowns shed, at the edge only a few. */
                    if (falloff <= 0F || hash(i, 171) > 0.35F + 0.55F * falloff)
                    {
                        continue;
                    }

                    int count = Math.round((3F + 4F * hash(i, 172)) * Math.min(1F, falloff) * leafScale);

                    if (count <= 0)
                    {
                        continue;
                    }

                    float cx = crowns.pos[i * 3], cy = crowns.pos[i * 3 + 1], cz = crowns.pos[i * 3 + 2];
                    float dxw = (float) (cx - epicWx), dzw = (float) (cz - epicWz);
                    float inv = dist > 1e-3F ? 1F / dist : 0F;
                    float rx = dxw * inv, rz = dzw * inv;
                    float tintR = crowns.color[i * 3], tintG = crowns.color[i * 3 + 1], tintB = crowns.color[i * 3 + 2];

                    for (int k = 0; k < Math.min(count, 8); k++)
                    {
                        float h1 = hash(i * 37 + k, 173), h2 = hash(i * 37 + k, 174);
                        float h3 = hash(i * 37 + k, 175), h4 = hash(i * 37 + k, 176);
                        boolean caught = hash(i * 37 + k, 177) < 0.35F;
                        float spawn = arrive + h1 * 0.6F;
                        float age = simTime - spawn;
                        float life = (2.8F + 2.2F * h2) * (caught ? 2.2F : 1F);

                        if (age <= 0F || age >= life)
                        {
                            continue;
                        }

                        float crownAng = h3 * 6.2832F;
                        float crownR = 0.5F + 1.6F * h4;
                        float x0 = cx + (float) Math.cos(crownAng) * crownR;
                        float z0 = cz + (float) Math.sin(crownAng) * crownR;
                        float y0 = cy - 0.4F - 1.6F * h2;

                        float kick = (3F + 3.5F * h1) * (1F - (float) Math.exp(-age / 0.9F)) * 2F
                            + sustained(windStr, hold, age) * (0.5F + 0.6F * h1)
                            - suction(windStr, windDecay, hold, age, suck) * 0.5F;

                        if (caught)
                        {
                            kick += (5F + 6F * h1) * 3F * (1F - (float) Math.exp(-age / 3F));
                        }

                        float swirlAmp = caught ? 1F : 0.4F;
                        float lx = x0 + rx * kick + 0.5F * ambX * age * age
                            + swirlAmp * (float) Math.sin(age * (2.5F + 2F * h3) + h4 * 6.28F);
                        float lz = z0 + rz * kick + 0.5F * ambZ * age * age
                            + swirlAmp * (float) Math.cos(age * (2.1F + 2F * h4) + h3 * 6.28F);
                        float ly = y0 - (0.75F + 0.55F * h2) * (caught ? 0.45F : 1F) * age
                            + (caught ? 0.6F : 0.3F) * (float) Math.sin(age * (3F + 2F * h1));
                        float fade = Math.min(1F, (life - age) / (life * 0.3F));
                        float bright = 0.75F + 0.4F * h4;

                        DEFERRED_CHIP.add(new float[] {
                            lx, ly, lz, 0.3F + 0.18F * h3, (i + k) & 3,
                            tintR * bright, tintG * bright, tintB * bright, fade, light, 0F});
                    }
                }
            }

            /* Birds scatter off the trees at the detonation — REAL crowns plus the captured ones (a
             * full-radius capture leaves zero uncaptured crowns, which silently killed all birds). */
            int treePool = n + BURST_TREE_COUNT;

            if (treePool > 0 && birdCount > 0 && simTime < 9F)
            {
                for (int b = 0; b < birdCount; b++)
                {
                    float h1 = hash(b, 151), h2 = hash(b, 152), h3 = hash(b, 153), h4 = hash(b, 154);
                    int i = Math.min(treePool - 1, (int) (h1 * treePool));
                    float cx, cy, cz;

                    if (i < n)
                    {
                        cx = crowns.pos[i * 3];
                        cy = crowns.pos[i * 3 + 1];
                        cz = crowns.pos[i * 3 + 2];
                    }
                    else
                    {
                        int t = (i - n) * 3;

                        cx = BURST_TREES[t];
                        cy = BURST_TREES[t + 1] + 5F;
                        cz = BURST_TREES[t + 2];
                    }
                    float spawn = 0.12F + 0.35F * h2;
                    float age = simTime - spawn;
                    float life = 8F;

                    if (age <= 0F || age >= life)
                    {
                        continue;
                    }

                    float dxw = (float) (cx - epicWx), dzw = (float) (cz - epicWz);
                    float ang = (float) Math.atan2(dzw, dxw) + (h3 - 0.5F) * 1.4F;
                    float speed = 7F + 4F * h4;
                    float climbTau = 1.2F;
                    float alt = 4.5F * (age - climbTau * (1F - (float) Math.exp(-age / climbTau)));
                    float lx = cx + (float) Math.cos(ang) * speed * age;
                    float lz = cz + (float) Math.sin(ang) * speed * age;
                    float ly = cy + 0.5F + 2F * h2 + alt + 0.25F * (float) Math.sin(age * 7F + h1 * 6.28F);
                    float fade = Math.min(1F, (life - age) / 2F);
                    int frame = (int) (age * (9F + 3F * h3)) & 3;

                    DEFERRED_BIRD.add(new float[] {
                        lx, ly, lz, 0.8F + 0.35F * h4, frame,
                        0.07F, 0.07F, 0.09F, 0.9F * fade, light, 0F});
                }
            }
        }

        /* The epicenter column: a handful of big, long, slow mega-puffs. */
        if (smoke > 0F)
        {
            float colLife = burn + 8F;
            float period = 0.6F;
            int last = (int) (Math.min(simTime, colLife) / period);

            for (int k = Math.max(0, last - 14); k <= last; k++)
            {
                float spawn = k * period;
                float puffAge = simTime - spawn;
                float hp = hash(k, 9);
                float puffLife = 7F * (0.8F + 0.4F * hp);

                if (puffAge <= 0F || puffAge >= puffLife || spawn > colLife)
                {
                    continue;
                }

                float u = puffAge / puffLife;
                float rise = (2.2F + 1.2F * hp) * puffAge * (1F - 0.2F * u);
                float px = epic.x + (float) Math.sin(hp * 53F + puffAge * 0.5F) * (0.6F + 1.5F * u) + 0.5F * ambX * puffAge * puffAge;
                float pz = epic.z + (float) Math.cos(hp * 29F + puffAge * 0.45F) * (0.6F + 1.5F * u) + 0.5F * ambZ * puffAge * puffAge;
                float py = epic.y + 1F + rise;
                float size = (2.2F + 1.2F * hp + 4.5F * u) * smoke;
                float alpha = 0.55F * (float) Math.pow(Math.sin(Math.PI * Math.min(u * 1.15F, 1F)), 0.6D);
                float grey = 0.38F - 0.12F * u;
                float warm = Math.max(0F, 0.3F - u);

                QUADS.add(new Quad(K_SMOKE, px, py, pz, size,
                    k & 3, grey + warm * 0.6F, grey + warm * 0.3F, grey, alpha));
            }
        }

        if (QUADS.isEmpty())
        {
            return;
        }

        /* Back-to-front within each layer (smoke drawn first, flames — emissive — on top). */
        for (Quad quad : QUADS)
        {
            float ddx = quad.x - camLocal.x, ddy = quad.y - camLocal.y, ddz = quad.z - camLocal.z;

            quad.depth = ddx * ddx + ddy * ddy + ddz * ddz;
        }

        QUADS.sort((a, b) -> Float.compare(b.depth, a.depth));

        CustomVertexConsumerProvider provider = FormUtilsClient.getProvider();

        /* Fire FIRST (it lives inside the smoke): translucent smoke quads write depth, so fire drawn
         * after dense low smoke was z-rejected — "no fire" report. The EMISSIVE layer keeps it
         * fullbright under packs (and most packs bloom emissive). Two batches: teardrop flames on the
         * burning debris, round fire-cloud puffs for the fireball/mushroom. */
        VertexConsumer vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(FLAME_TEX));

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_FLAME)
            {
                emit(vc, matrix, quad, right, up, toCam, 0xF000F0);
            }
        }

        provider.draw();

        /* Under a shaderpack, an invisible DEPTH PREPASS first (cutout program = the speckled
         * silhouette, colour writes off): pack volumetric clouds composite against the depth buffer
         * and were drawing IN FRONT of the translucent ball. The visible pass stays on the EMISSIVE
         * layer, so the pack's bloom is kept (a plain cutout ball stopped blooming). */
        boolean anyFireball = false;

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_FIREBALL)
            {
                anyFireball = true;

                break;
            }
        }

        if (anyFireball && mchorse.bbs_mod.client.BBSRendering.isIrisShadersEnabled())
        {
            RenderLayer depthLayer = RenderLayer.getEntityCutoutNoCull(FIREBALL_TEX);

            depthLayer.startDrawing();
            com.mojang.blaze3d.systems.RenderSystem.colorMask(false, false, false, false);

            net.minecraft.client.render.BufferBuilder prepass = BbsVfxRenderCompat.begin(
                net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
                net.minecraft.client.render.VertexFormats.POSITION_COLOR_TEXTURE_OVERLAY_LIGHT_NORMAL);

            for (Quad quad : QUADS)
            {
                if (quad.kind == K_FIREBALL)
                {
                    emit(prepass, matrix, quad, right, up, toCam, 0xF000F0);
                }
            }

            net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(prepass.end());
            com.mojang.blaze3d.systems.RenderSystem.colorMask(true, true, true, true);
            depthLayer.endDrawing();
        }

        vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(FIREBALL_TEX));

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_FIREBALL)
            {
                emit(vc, matrix, quad, right, up, toCam, 0xF000F0);
            }
        }

        provider.draw();

        /* Big spark fountain — long bright motion-streaked heads with fiery tails.
         * Drawn emissive like the fireball, but with its own sharp spark sprite. */
        vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(SPARK_TEX));

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_SPARK)
            {
                emit(vc, matrix, quad, right, up, toCam, 0xF000F0);
            }
        }

        provider.draw();

        /* Bokeh embers — soft defocused glow motes, emissive so packs bloom them. No depth prepass:
         * they are an additive haze layer that must not occlude the fire behind them. */
        vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(BOKEH_TEX));

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_BOKEH)
            {
                emit(vc, matrix, quad, right, up, toCam, 0xF000F0);
            }
        }

        provider.draw();

        vc = provider.getBuffer(RenderLayer.getEntityTranslucentEmissive(RING_TEX));

        for (Quad quad : QUADS)
        {
            if (quad.kind == K_RING)
            {
                emit(vc, matrix, quad, right, up, toCam, 0xF000F0);
            }
        }

        provider.draw();

        /* Smoke + dust go through the PARTICLE pipeline instead (deferred to the end of the world
         * render): entity-translucent programs in shaderpacks alpha-TEST and re-light the quads into
         * hard dark spheres, while the particles program is the one thing every pack blends softly
         * (clouds and vanilla smoke depend on it). No world placement (form-editor preview) → draw
         * inline the old way. */
        if (inWorld)
        {
            for (Quad quad : QUADS)
            {
                if (quad.kind == K_SMOKE)
                {
                    DEFERRED.add(new float[] {
                        (float) (quad.x + ox), (float) (quad.y + oy), (float) (quad.z + oz),
                        quad.size, quad.frame, quad.r, quad.g, quad.b, quad.a, light,
                        quad.flat ? 1F : 0F});
                }
                else if (quad.kind == K_CHIP)
                {
                    DEFERRED_CHIP.add(new float[] {
                        (float) (quad.x + ox), (float) (quad.y + oy), (float) (quad.z + oz),
                        quad.size, quad.frame, quad.r, quad.g, quad.b, quad.a, light, 0F});
                }
            }
        }
        else
        {
            vc = provider.getBuffer(RenderLayer.getEntityTranslucent(SMOKE_TEX));

            for (Quad quad : QUADS)
            {
                if (quad.kind == K_SMOKE)
                {
                    emit(vc, matrix, quad, right, up, toCam, light);
                }
            }

            provider.draw();
        }
    }

    /**
     * BIG SPARK FOUNTAIN — a configurable burst of bright embers thrown at the detonation: hot heads
     * with motion-stretched trails ({@link Quad#streak}), arcing under their own gravity. Separate from
     * the built-in ember spray (which is tied to the fire core) — this one is tuned by the user via the
     * form's {@code spark_*} values. Closed-form in {@code simTime} like everything else here.
     */
    private static void renderSparks(ExplosionForm form, float simTime, Vector3f epic, Vector3f camLocal)
    {
        int count = form.sparkCount.get();

        if (count <= 0)
        {
            return;
        }

        float size = form.sparkSize.get();
        float speed = form.sparkSpeed.get();
        float grav = form.sparkGravity.get();
        float life = form.sparkLife.get();
        float spread = form.sparkSpread.get();
        float cr = form.sparkColorR.get(), cg = form.sparkColorG.get(), cb = form.sparkColorB.get();
        /* Hotter white-yellow core for the head; the tail keeps the user tint. */
        float hr = Math.min(1F, cr * 0.25F + 0.75F);
        float hg = Math.min(1F, cg * 0.25F + 0.75F);
        float hb = Math.min(1F, cb * 0.25F + 0.55F);

        /* Air drag (1/s). Higher = sparks slow down faster, so the arcs stay visible longer. */
        float drag = 0.35F;

        for (int i = 0; i < count; i++)
        {
            float h1 = hash(i, 201), h2 = hash(i, 202), h3 = hash(i, 203), h4 = hash(i, 204), h5 = hash(i, 205);
            float spawn = h1 * 0.06F;                 /* one sharp burst at detonation */
            float age = simTime - spawn;
            float sLife = life * (0.7F + 0.6F * h4);

            if (age <= 0F || age >= sLife)
            {
                continue;
            }

            /* Upper-hemisphere fountain from the core. spread 0 = vertical jet,
             * spread 1 = full upward hemisphere. */
            float az = h5 * 6.2832F;
            float el = 1.5708F * (1F - spread * h3);
            float dx = (float) (Math.cos(az) * Math.sin(el));
            float dy = (float) Math.cos(el);
            float dz = (float) (Math.sin(az) * Math.sin(el));
            float v = speed * (0.55F + 0.9F * h2);

            /* Start inside the fire core. */
            float ox2 = (hash(i, 206) - 0.5F) * 1.2F;
            float oy2 = (hash(i, 207) - 0.5F) * 1.2F + 1.5F;
            float oz2 = (hash(i, 208) - 0.5F) * 1.2F;

            /* Smooth fade from the reference mod: full brightness most of the way, then soft out. */
            float fade = (float) Math.cos(age / sLife * 1.5708F);

            /* Closed-form position under constant gravity + exponential air drag.
             * term = integral of exp(-drag*t) from 0 to a. */
            float term = (1F - (float) Math.exp(-drag * age)) / drag;
            float dragFall = grav * (age - term) / drag;

            float orgX = epic.x + ox2;
            float orgY = epic.y + oy2;
            float orgZ = epic.z + oz2;

            float px = orgX + dx * v * term;
            float py = orgY + dy * v * term - dragFall;
            float pz = orgZ + dz * v * term;

            /* Let sparks fall several blocks below the core before killing them. */
            if (py < epic.y - 8F)
            {
                continue;
            }

            /* Velocity at current age (for orienting the streak). */
            float e = (float) Math.exp(-drag * age);
            float vx = dx * v * e;
            float vy = dy * v * e - grav * (1F - e) / drag;
            float vz = dz * v * e;

            float ddx = px - camLocal.x, ddy = py - camLocal.y, ddz = pz - camLocal.z;
            float dist = (float) Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz);
            /* Aggressive distance scale so sparks stay thick from far away. */
            float dscale = Math.min(5F, Math.max(1F, dist / 25F));

            /* Long motion streak: a thick bright line plus a bright core overlay. */
            float baseLen = (1.4F + 1.0F * h4) * size * dscale;
            float baseW = 0.22F * size * dscale;

            /* Main colored streak. */
            QUADS.add(new Quad(K_SPARK, px, py, pz, baseLen, variant(i),
                cr * 0.95F, cg * 0.65F, cb * 0.35F, fade * 0.95F)
                .streak(vx, vy, vz, baseW));

            /* Hot white core overlay — sells the bright head even against the fireball. */
            QUADS.add(new Quad(K_SPARK, px, py, pz, baseLen * 0.55F, variant(i),
                hr, hg, hb, fade)
                .streak(vx, vy, vz, baseW * 0.85F));
        }
    }

    private static int variant(int i)
    {
        return ((i * 73856093) >> 8) & 3;
    }

    /**
     * FOLIAGE BURSTS — leaves shed by the trees the front slams into + birds scattering at the
     * detonation. Called by the renderer next to the foliage sway pass; needs the tree table the
     * foliage proxy already built (pivots + per-tree leaf tints). World-space, deferred to the
     * particle pass. All closed-form in {@code simTime}.
     */
    public static void renderFoliageBursts(ExplosionForm form, ExplosionFoliageVAO foliage,
        float simTime, double ox, double oy, double oz, int light)
    {
        int trees = foliage.treeCount();

        if (trees <= 0 || simTime <= 0F)
        {
            return;
        }

        ensureTextures();

        Vector3f epic = form.point();
        float front = Math.max(1F, form.windFrontSpeed.get());
        float reach = Math.max(1F, form.bendRadius.get());
        float leafScale = form.bendLeaves.get();
        float windStr = form.windStrength.get();
        float windDecay = Math.max(0.05F, form.windDecay.get());
        float suck = form.windSuction.get();
        float hold = form.windHold.get();
        float ambX = form.windAmbientX();
        float ambZ = form.windAmbientZ();
        float[] pivots = foliage.unitPivots();
        float[] tints = foliage.treeLeafTints();

        /* Hand the captured-tree pivots (world space) to the bird pool in render(). */
        if (BURST_TREES.length < trees * 3)
        {
            BURST_TREES = new float[trees * 3];
        }

        for (int u = 0; u < trees; u++)
        {
            BURST_TREES[u * 3] = (float) (pivots[u * 3] + ox);
            BURST_TREES[u * 3 + 1] = (float) (pivots[u * 3 + 1] + oy);
            BURST_TREES[u * 3 + 2] = (float) (pivots[u * 3 + 2] + oz);
        }

        BURST_TREE_COUNT = trees;

        /* --- LEAF SHED: each tree hit by the front throws a burst of leaves ∝ its bend, tumbling
         * down and downwind. Leaves live ~3-5 s — they fade mid-air, no ground clamp needed.
         * (Birds live in the render() world-crown pass — REAL trees, not just the captured ones.) --- */
        if (leafScale > 0F)
        {
            for (int u = 0; u < trees; u++)
            {
                float px = pivots[u * 3], py = pivots[u * 3 + 1], pz = pivots[u * 3 + 2];
                float dx = px - epic.x, dz = pz - epic.z;
                float dist = (float) Math.sqrt(dx * dx + dz * dz);
                float arrive = dist / front;

                if (simTime <= arrive || simTime > arrive + 7F)
                {
                    continue;
                }

                float falloff = Math.max(0F, 1.1F - dist / reach);

                if (falloff <= 0F)
                {
                    continue;
                }

                int count = Math.round((6F + 7F * hash(u, 141)) * Math.min(1F, falloff) * leafScale);

                if (count <= 0)
                {
                    continue;
                }

                float inv = dist > 1e-3F ? 1F / dist : 0F;
                float rx = dx * inv, rz = dz * inv;
                float tintR = tints[u * 3], tintG = tints[u * 3 + 1], tintB = tints[u * 3 + 2];

                for (int k = 0; k < Math.min(count, 14); k++)
                {
                    float h1 = hash(u * 31 + k, 142), h2 = hash(u * 31 + k, 143);
                    float h3 = hash(u * 31 + k, 144), h4 = hash(u * 31 + k, 145);
                    /* A third of the leaves get CAUGHT by the wind — carried tens of blocks downwind
                     * in swirls instead of settling by the trunk. */
                    boolean caught = hash(u * 31 + k, 146) < 0.35F;
                    float spawn = arrive + h1 * 0.6F;
                    float age = simTime - spawn;
                    float life = (2.8F + 2.2F * h2) * (caught ? 2.2F : 1F);

                    if (age <= 0F || age >= life)
                    {
                        continue;
                    }

                    /* Crown point. */
                    float crownAng = h3 * 6.2832F;
                    float crownR = 0.6F + 1.8F * h4;
                    float x0 = px + (float) Math.cos(crownAng) * crownR;
                    float z0 = pz + (float) Math.sin(crownAng) * crownR;
                    float y0 = py + 3.2F + 2.6F * h2;

                    /* Bounded radial kick + sustained wind + ambient + negative-phase pull;
                     * fluttering fall. */
                    float kick = (3F + 3.5F * h1) * (1F - (float) Math.exp(-age / 0.9F)) * 2F
                        + sustained(windStr, hold, age) * (0.5F + 0.6F * h1)
                        - suction(windStr, windDecay, hold, age, suck) * 0.5F;

                    if (caught)
                    {
                        kick += (5F + 6F * h1) * 3F * (1F - (float) Math.exp(-age / 3F));
                    }

                    float swirlAmp = caught ? 1F : 0.4F;
                    float lx = x0 + rx * kick + 0.5F * ambX * age * age
                        + swirlAmp * (float) Math.sin(age * (2.5F + 2F * h3) + h4 * 6.28F);
                    float lz = z0 + rz * kick + 0.5F * ambZ * age * age
                        + swirlAmp * (float) Math.cos(age * (2.1F + 2F * h4) + h3 * 6.28F);
                    float ly = y0 - (0.75F + 0.55F * h2) * (caught ? 0.45F : 1F) * age
                        + (caught ? 0.6F : 0.3F) * (float) Math.sin(age * (3F + 2F * h1));
                    float fade = Math.min(1F, (life - age) / (life * 0.3F));
                    float bright = 0.75F + 0.4F * h4;

                    DEFERRED_CHIP.add(new float[] {
                        (float) (lx + ox), (float) (ly + oy), (float) (lz + oz),
                        0.3F + 0.18F * h3, (u + k) & 3,
                        tintR * bright, tintG * bright, tintB * bright, fade, light, 0F});
                }
            }
        }

    }

    /* World-space smoke quads queued for the end-of-world-render pass (x,y,z,size,frame,r,g,b,a,light). */
    private static final List<float[]> DEFERRED = new ArrayList<>();

    /* Same, drawn with the DEBRIS atlas (ground litter, leaves) / the BIRD flipbook. */
    private static final List<float[]> DEFERRED_CHIP = new ArrayList<>();
    private static final List<float[]> DEFERRED_BIRD = new ArrayList<>();

    /* Captured-tree pivots in WORLD space, handed from the foliage-burst pass to the bird pool in
     * render() (same frame, render thread). */
    private static float[] BURST_TREES = new float[0];
    private static int BURST_TREE_COUNT;

    /* ------------------------------------------------------------------------------------------ */
    /* REAL-WORLD tree crowns: a non-destructive scan of the loaded terrain around the epicenter.  */
    /* ------------------------------------------------------------------------------------------ */

    /** Leaf-topped columns = tree crowns: world-space position + biome leaf colour + epic distance. */
    private static final class Crowns
    {
        int count;
        float[] pos;
        float[] color;
        float[] dist;
        boolean provisional;
        long scanTime;
    }

    private static final LinkedHashMap<Long, Crowns> CROWNS = new LinkedHashMap<>(4, 0.75F, true);

    /**
     * Scan (or fetch) the crown table for this epicenter/reach: jittered grid columns over the loaded
     * chunks, a column whose heightmap top is a LEAVES block = a crown. READ-ONLY — world blocks are
     * never modified. Cached; re-scanned every 4 s while chunks were still loading (provisional).
     */
    private static Crowns crowns(net.minecraft.client.world.ClientWorld world, double ex, double ez,
        float reach, float skipRadius)
    {
        long key = ((long) Math.floor(ex / 4D) * 341873128712L) ^ ((long) Math.floor(ez / 4D) * 132897987541L)
            ^ ((long) reach << 20) ^ (long) skipRadius;
        Crowns cached = CROWNS.get(key);
        long now = System.currentTimeMillis();

        if (cached != null && (!cached.provisional || now - cached.scanTime < 4000L))
        {
            return cached;
        }

        Crowns crowns = new Crowns();

        crowns.scanTime = now;

        int r = (int) reach;
        int step = Math.max(6, r / 40);
        List<float[]> found = new ArrayList<>();
        net.minecraft.util.math.BlockPos.Mutable pos = new net.minecraft.util.math.BlockPos.Mutable();
        MinecraftClient mc = MinecraftClient.getInstance();
        int unloaded = 0, total = 0;

        for (int gx = -r; gx <= r; gx += step)
        {
            for (int gz = -r; gz <= r; gz += step)
            {
                if (gx * gx + gz * gz > r * r)
                {
                    continue;
                }

                int wx = (int) Math.floor(ex + gx + (hash(gx * 7349 + gz * 131, 161) - 0.5F) * step);
                int wz = (int) Math.floor(ez + gz + (hash(gx * 131 + gz * 7349, 162) - 0.5F) * step);

                total++;

                if (!world.getChunkManager().isChunkLoaded(wx >> 4, wz >> 4))
                {
                    unloaded++;

                    continue;
                }

                int top = world.getTopY(net.minecraft.world.Heightmap.Type.MOTION_BLOCKING, wx, wz);

                if (top <= world.getBottomY() + 1)
                {
                    continue;
                }

                pos.set(wx, top - 1, wz);

                net.minecraft.block.BlockState state = world.getBlockState(pos);

                if (!state.isIn(net.minecraft.registry.tag.BlockTags.LEAVES))
                {
                    continue;
                }

                double ddx = wx + 0.5D - ex, ddz = wz + 0.5D - ez;
                double d = Math.sqrt(ddx * ddx + ddz * ddz);

                /* The captured-foliage proxies already shed there. */
                if (skipRadius > 0F && d < skipRadius)
                {
                    continue;
                }

                int col = mc.getBlockColors().getColor(state, world, pos, 0);
                float lr, lg, lb;

                if (col == -1)
                {
                    lr = 0.35F;
                    lg = 0.55F;
                    lb = 0.22F;
                }
                else
                {
                    lr = ((col >> 16) & 0xFF) / 255F;
                    lg = ((col >> 8) & 0xFF) / 255F;
                    lb = (col & 0xFF) / 255F;
                }

                found.add(new float[] {wx + 0.5F, top - 0.5F, wz + 0.5F, lr, lg, lb, (float) d});
            }
        }

        crowns.count = found.size();
        crowns.pos = new float[crowns.count * 3];
        crowns.color = new float[crowns.count * 3];
        crowns.dist = new float[crowns.count];

        for (int i = 0; i < crowns.count; i++)
        {
            float[] f = found.get(i);

            crowns.pos[i * 3] = f[0];
            crowns.pos[i * 3 + 1] = f[1];
            crowns.pos[i * 3 + 2] = f[2];
            crowns.color[i * 3] = f[3];
            crowns.color[i * 3 + 1] = f[4];
            crowns.color[i * 3 + 2] = f[5];
            crowns.dist[i] = f[6];
        }

        crowns.provisional = unloaded > total / 10;

        CROWNS.put(key, crowns);

        while (CROWNS.size() > 4)
        {
            CROWNS.remove(CROWNS.entrySet().iterator().next().getKey());
        }

        System.out.println("[bbsvfx] world crown scan: " + crowns.count + " crowns (step " + step
            + (crowns.provisional ? ", provisional)" : ")"));

        return crowns;
    }

    /**
     * Draw the queued smoke through the PARTICLE program — called from a {@code WorldRenderEvents.LAST}
     * hook. Depth-tested but not depth-written: soft against everything, никаких «ядер».
     */
    public static void drawDeferred(net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext context)
    {
        if (DEFERRED.isEmpty() && DEFERRED_CHIP.isEmpty() && DEFERRED_BIRD.isEmpty())
        {
            return;
        }

        ensureTextures();

        MinecraftClient mc = MinecraftClient.getInstance();
        net.minecraft.util.math.Vec3d cam = context.camera().getPos();
        Quaternionf camRot = context.camera().getRotation();
        Vector3f right = camRot.transform(new Vector3f(1F, 0F, 0F));
        Vector3f up = camRot.transform(new Vector3f(0F, 1F, 0F));

        /* The world matrix stack carries the VIEW ROTATION (RenderSystem's own model-view is already
         * reset by the LAST event) — without it the quads rendered screen-fixed and unscaled ("smoke
         * follows the camera, tiny"). Same pattern as the wand-selection wireframe. */
        MatrixStack ms = context.matrixStack();

        if (ms == null)
        {
            DEFERRED.clear();
            DEFERRED_CHIP.clear();
            DEFERRED_BIRD.clear();

            return;
        }

        ms.push();
        ms.translate(-cam.x, -cam.y, -cam.z);

        Matrix4f mat = ms.peek().getPositionMatrix();

        com.mojang.blaze3d.systems.RenderSystem.setShader(GameRenderer::getParticleProgram);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        com.mojang.blaze3d.systems.RenderSystem.defaultBlendFunc();
        com.mojang.blaze3d.systems.RenderSystem.enableDepthTest();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(false);
        /* No culling: the FLAT quads (scorch decals) would backface-cull when seen from above. */
        com.mojang.blaze3d.systems.RenderSystem.disableCull();
        mc.gameRenderer.getLightmapTextureManager().enable();

        /* The little solid chips draw FIRST (they sit in/under the smoke), birds last. */
        drawDeferredBatch(DEFERRED_CHIP, DEBRIS_TEX, mat, cam, right, up);
        drawDeferredBatch(DEFERRED, SMOKE_TEX, mat, cam, right, up);
        drawDeferredBatch(DEFERRED_BIRD, BIRD_TEX, mat, cam, right, up);

        ms.pop();
        mc.gameRenderer.getLightmapTextureManager().disable();
        com.mojang.blaze3d.systems.RenderSystem.enableCull();
        com.mojang.blaze3d.systems.RenderSystem.depthMask(true);
        com.mojang.blaze3d.systems.RenderSystem.disableBlend();
    }

    /** One texture's worth of deferred quads: sort back-to-front, tessellate, draw, clear. */
    private static void drawDeferredBatch(List<float[]> list, Identifier texture, Matrix4f mat,
        net.minecraft.util.math.Vec3d cam, Vector3f right, Vector3f up)
    {
        if (list.isEmpty())
        {
            return;
        }

        list.sort((a, b) -> Float.compare(
            sq(b[0] - (float) cam.x) + sq(b[1] - (float) cam.y) + sq(b[2] - (float) cam.z),
            sq(a[0] - (float) cam.x) + sq(a[1] - (float) cam.y) + sq(a[2] - (float) cam.z)));

        com.mojang.blaze3d.systems.RenderSystem.setShaderTexture(0, texture);

        net.minecraft.client.render.BufferBuilder builder = BbsVfxRenderCompat.begin(
            net.minecraft.client.render.VertexFormat.DrawMode.QUADS,
            net.minecraft.client.render.VertexFormats.POSITION_TEXTURE_COLOR_LIGHT);

        for (float[] q : list)
        {
            float half = q[3] * 0.5F;
            int frame = (int) q[4];
            float u0 = (frame & 1) * 0.5F, v0 = (frame >> 1) * 0.5F;
            int lightPacked = (int) q[9];
            boolean flat = q.length > 10 && q[10] > 0.5F;
            /* STREAK quads (wind wisps): entry length 15 = [..., ax, ay, az, halfWidth] — stretched
             * along the axis, width ⊥ (axis, view). */
            boolean streak = q.length > 14 && q[14] > 0F;
            float srx = 0F, sry = 0F, srz = 0F, sux = 0F, suy = 0F, suz = 0F;

            if (streak)
            {
                srx = q[11] * q[3];
                sry = q[12] * q[3];
                srz = q[13] * q[3];

                float tx = q[0] - (float) cam.x, ty = q[1] - (float) cam.y, tz = q[2] - (float) cam.z;
                float cx = q[12] * tz - q[13] * ty;
                float cy = q[13] * tx - q[11] * tz;
                float cz = q[11] * ty - q[12] * tx;
                float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);

                if (cl < 1e-4F)
                {
                    cx = up.x;
                    cy = up.y;
                    cz = up.z;
                    cl = 1F;
                }

                sux = cx / cl * q[14];
                suy = cy / cl * q[14];
                suz = cz / cl * q[14];
            }

            for (int c = 0; c < 4; c++)
            {
                float sx = (c == 0 || c == 3) ? -half : half;
                float sy = (c < 2) ? half : -half;
                float x, y, z;

                if (streak)
                {
                    float fs = (c == 0 || c == 3) ? -1F : 1F;
                    float fu = (c < 2) ? 1F : -1F;

                    x = q[0] + srx * fs + sux * fu;
                    y = q[1] + sry * fs + suy * fu;
                    z = q[2] + srz * fs + suz * fu;
                }
                else if (flat)
                {
                    /* World-XZ quad (scorch decals hugging the terrain). */
                    x = q[0] + sx;
                    y = q[1];
                    z = q[2] + sy;
                }
                else
                {
                    x = q[0] + right.x * sx + up.x * sy;
                    y = q[1] + right.y * sx + up.y * sy;
                    z = q[2] + right.z * sx + up.z * sy;
                }

                float u = (c == 0 || c == 3) ? u0 : u0 + 0.5F;
                float v = (c < 2) ? v0 : v0 + 0.5F;

                builder.vertex(mat, x, y, z).texture(u, v).color(q[5], q[6], q[7], q[8]).light(lightPacked);
                BbsVfxRenderCompat.next(builder);
            }
        }

        net.minecraft.client.render.BufferRenderer.drawWithGlobalProgram(builder.end());
        list.clear();
    }

    private static float sq(float v)
    {
        return v * v;
    }

    private static void emit(VertexConsumer vc, Matrix4f matrix, Quad quad, Vector3f right, Vector3f up, Vector3f normal, int light)
    {
        float u0 = (quad.frame & 1) * 0.5F, v0 = (quad.frame >> 1) * 0.5F;
        float u1 = u0 + 0.5F, v1 = v0 + 0.5F;

        /* Streaks span their motion axis; flat quads lie in the world XZ plane; billboards face the
         * camera. */
        float rx, ry, rz, ux, uy, uz;

        if (quad.flat)
        {
            float half = quad.size * 0.5F;

            rx = half;
            ry = 0F;
            rz = 0F;
            ux = 0F;
            uy = 0F;
            uz = half;
        }
        else if (quad.w > 0F)
        {
            rx = quad.ax * quad.size;
            ry = quad.ay * quad.size;
            rz = quad.az * quad.size;

            /* Width axis ⊥ (motion, view). */
            float cx = quad.ay * normal.z - quad.az * normal.y;
            float cy = quad.az * normal.x - quad.ax * normal.z;
            float cz = quad.ax * normal.y - quad.ay * normal.x;
            float cl = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);

            if (cl < 1e-4F)
            {
                cx = up.x;
                cy = up.y;
                cz = up.z;
                cl = 1F;
            }

            ux = cx / cl * quad.w;
            uy = cy / cl * quad.w;
            uz = cz / cl * quad.w;
        }
        else
        {
            float half = quad.size * 0.5F;

            rx = right.x * half;
            ry = right.y * half;
            rz = right.z * half;
            ux = up.x * half;
            uy = up.y * half;
            uz = up.z * half;
        }

        for (int c = 0; c < 4; c++)
        {
            float fs = (c == 0 || c == 3) ? -1F : 1F;
            float fu = (c < 2) ? 1F : -1F;
            float x = quad.x + rx * fs + ux * fu;
            float y = quad.y + ry * fs + uy * fu;
            float z = quad.z + rz * fs + uz * fu;
            float u = (c == 0 || c == 3) ? u0 : u1;
            float v = (c < 2) ? v0 : v1;

            vc.vertex(matrix, x, y, z).color(quad.r, quad.g, quad.b, quad.a)
                .texture(u, v).overlay(OverlayTexture.DEFAULT_UV).light(light)
                .normal(normal.x, normal.y, normal.z);
            BbsVfxRenderCompat.next(vc);
        }
    }

    /* Wind drift of a puff: the ambient accel + the front's decaying kick + the SUSTAINED blast wind
     * along the site's radial — mirrors the debris' closed form so smoke and debris move as one
     * weather system. The NEGATIVE PHASE subtracts a delayed bounded pull-back (see {@link #suction}). */
    private static float windX(float dx, float dist, float str, float decay, float hold, float front, float t, float amb, float sinceIgnite, float suck)
    {
        float kick = frontKick(dist, str, decay, front, sinceIgnite) + sustained(str, hold, sinceIgnite)
            - suction(str, decay, hold, sinceIgnite, suck);

        return (dist > 1e-3F ? dx / dist : 0F) * kick + 0.5F * amb * t * t;
    }

    private static float windZ(float dz, float dist, float str, float decay, float hold, float front, float t, float amb, float sinceIgnite, float suck)
    {
        float kick = frontKick(dist, str, decay, front, sinceIgnite) + sustained(str, hold, sinceIgnite)
            - suction(str, decay, hold, sinceIgnite, suck);

        return (dist > 1e-3F ? dz / dist : 0F) * kick + 0.5F * amb * t * t;
    }

    private static float frontKick(float dist, float str, float decay, float front, float t)
    {
        if (str == 0F || t <= 0F)
        {
            return 0F;
        }

        return str * decay * (t - decay * (1F - (float) Math.exp(-t / decay))) * 0.35F;
    }

    /**
     * SUSTAINED BLAST WIND displacement (blocks, outward): after the front's punch the explosion keeps
     * DRIVING the air for ~{@code hold} seconds — velocity {@code 0.35·str·e^(−t/hold)}, integrated to
     * the bounded {@code 0.35·str·hold·(1−e^(−t/hold))}. This is "the wind of the blast", not the hit.
     */
    private static float sustained(float str, float hold, float t)
    {
        if (hold <= 0.05F || str == 0F || t <= 0F)
        {
            return 0F;
        }

        return 0.35F * str * hold * (1F - (float) Math.exp(-t / hold));
    }

    /**
     * NEGATIVE-PHASE pull-back displacement (blocks, along the radial, subtract from the outward
     * kick): the rarefaction behind the front reverses the wind for a beat — AFTER the sustained
     * blast wind has waned. Velocity pulse {@code v(u) = vs·(u/τ)·e^(1−u/τ)} (peak {@code vs} = suck ×
     * the outward terminal speed), integrated to a BOUNDED closed form — the puff drifts out, gets
     * visibly sucked back a few blocks, then hangs.
     */
    private static float suction(float str, float decay, float hold, float sinceFront, float suck)
    {
        float u = sinceFront - (1.4F * decay + 0.8F * hold);

        if (suck <= 0F || str == 0F || u <= 0F)
        {
            return 0F;
        }

        float tau = 1.3F * decay + 0.3F * hold;
        float vs = 0.5F * str * decay * suck;

        /* ∫ v = vs·e·(τ − (u+τ)·e^(−u/τ)) — rises to vs·e·τ and stays. */
        return vs * 2.71828F * (tau - (u + tau) * (float) Math.exp(-u / tau));
    }

    /** Nearest-{@code count} rest sites to the epicenter, cached by content+params. */
    private static float[] sites(ExplosionForm form, List<DestructionBlock> allBlocks, long contentKey, int count)
    {
        Vector3f epic = form.point();
        long key = contentKey * 31 + count;

        key = key * 31 + Float.floatToIntBits(epic.x);
        key = key * 31 + Float.floatToIntBits(epic.y);
        key = key * 31 + Float.floatToIntBits(epic.z);

        float[] cached = SITES.get(key);

        if (cached != null)
        {
            return cached;
        }

        int n = allBlocks.size();
        long[] keys = new long[n];

        for (int i = 0; i < n; i++)
        {
            DestructionBlock block = allBlocks.get(i);
            float dx = block.x.get() + 0.5F - epic.x;
            float dy = block.y.get() + 0.5F - epic.y;
            float dz = block.z.get() + 0.5F - epic.z;
            float d2 = dx * dx + dy * dy + dz * dz;

            keys[i] = ((long) Float.floatToIntBits(d2) << 20) | i;
        }

        java.util.Arrays.sort(keys);

        int take = Math.min(count, n);
        float[] sites = new float[take * 3];

        for (int k = 0; k < take; k++)
        {
            DestructionBlock block = allBlocks.get((int) (keys[k] & 0xFFFFF));

            sites[k * 3] = block.x.get();
            sites[k * 3 + 1] = block.y.get();
            sites[k * 3 + 2] = block.z.get();
        }

        SITES.put(key, sites);

        while (SITES.size() > 4)
        {
            SITES.remove(SITES.entrySet().iterator().next().getKey());
        }

        return sites;
    }

    private static float hash(int i, int salt)
    {
        int x = (i ^ 0x9e3779b9) * 0x85ebca6b + salt * 0x165667b1;

        x ^= x >>> 13;
        x *= 0x27d4eb2d;
        x ^= x >>> 15;

        return (x & 0xFFFF) / (float) 0xFFFF;
    }

    /* ------------------------------------------------------------------------------------------ */
    /* Procedural atlases: a 2×2 flipbook of teardrop flames (colour ramp baked in) and a 2×2 of    */
    /* soft noisy smoke blobs. Generated once, registered as dynamic textures — no shipped assets.  */
    /* ------------------------------------------------------------------------------------------ */

    private static void ensureTextures()
    {
        if (texturesReady)
        {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        NativeImage flame = new NativeImage(128, 128, false);

        for (int frame = 0; frame < 4; frame++)
        {
            int ox = (frame & 1) * 64, oy = (frame >> 1) * 64;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    /* Teardrop: base at the bottom, ragged tip up; noise gnaws the silhouette. */
                    float yn = (56F - y) / 48F;
                    float body = yn < 0F ? 0F : 1F - yn;
                    float width = 0.55F * (float) Math.pow(body, 0.65D) + 0.06F;
                    float dxn = (x - 32F) / 30F;
                    float d = Math.abs(dxn) / Math.max(width, 1e-3F);
                    float core = 1F - d * d - Math.max(0F, yn) * Math.max(0F, yn) * 0.55F;
                    float n = noise(x * 0.11F + frame * 17F, y * 0.13F - frame * 9F);
                    /* A second, finer octave gnaws the edge — ragged licks instead of a smooth taper. */
                    float n2 = noise(x * 0.27F + frame * 31F, y * 0.31F - frame * 13F);

                    core -= n * 0.4F + n2 * 0.22F;

                    if (core <= 0F || yn < 0F)
                    {
                        flame.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    /* White-yellow core → orange mid → deep red edge (blackbody-ish ramp). */
                    float i = Math.min(1F, core * 1.6F);
                    int r = 255;
                    int g = (int) (255 * Math.min(1F, 0.15F + i * 0.80F));
                    int b = (int) (255 * Math.max(0F, i * 1.9F - 1.10F));
                    int a = (int) (255 * Math.min(1F, core * 2.2F));

                    flame.setColor(ox + x, oy + y, a << 24 | b << 16 | g << 8 | r);
                }
            }
        }

        mc.getTextureManager().registerTexture(FLAME_TEX, new NativeImageBackedTexture(flame));

        NativeImage smoke = new NativeImage(128, 128, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int ox = (variant & 1) * 64, oy = (variant >> 1) * 64;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    float dxn = (x - 32F) / 30F;
                    float dyn = (y - 32F) / 30F;
                    float r2 = dxn * dxn + dyn * dyn;
                    float n = noise(x * 0.09F + variant * 23F, y * 0.09F + variant * 41F);
                    float a = (float) Math.pow(Math.max(0F, 1F - r2), 1.6D) * (0.62F + 0.38F * n);

                    /* Stochastic rim: shaderpacks that alpha-TEST entity translucency (instead of
                     * blending) turned the soft gradient into hard dark spheres — speckling the
                     * low-alpha rim reads as ragged smoke under a cutoff and is near-invisible when
                     * blending works. */
                    if (a < 0.4F && cell(x * 7 + variant * 131, y * 13) > a / 0.4F)
                    {
                        a = 0F;
                    }

                    if (a <= 0.01F)
                    {
                        smoke.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    int alpha = (int) (255 * Math.min(1F, a));

                    smoke.setColor(ox + x, oy + y, alpha << 24 | 0xFFFFFF);
                }
            }
        }

        mc.getTextureManager().registerTexture(SMOKE_TEX, new NativeImageBackedTexture(smoke));

        /* Fireball: soft-gradient fire like the smoke rendering the user liked, but with the FIRE'S
         * OWN SHAPE — a ragged burning mass with flame licks reaching UP (per-frame lick layout →
         * the flipbook flickers), instead of the round puff that read as a twin of the smoke. */
        NativeImage fireball = new NativeImage(128, 128, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int ox = (variant & 1) * 64, oy = (variant >> 1) * 64;
            float o1 = (hash(variant, 61) - 0.5F) * 14F;
            float o2 = (hash(variant, 62) - 0.5F) * 20F;
            float o3 = (hash(variant, 63) - 0.5F) * 26F;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    /* Union of stacked soft blobs: a fat base low in the tile + narrowing licks
                     * climbing up at per-frame offsets — the flame silhouette. */
                    float d = flameBlob(x, y, 32F, 44F, 21F);

                    d = Math.max(d, flameBlob(x, y, 32F + o1, 31F, 13.5F));
                    d = Math.max(d, flameBlob(x, y, 32F + o2, 19F, 8F));
                    d = Math.max(d, flameBlob(x, y, 32F + o3, 37F, 10F));

                    float n = noise(x * 0.11F + variant * 37F, y * 0.11F + variant * 53F);
                    /* Fine second octave gnaws the rim — a ragged burning edge instead of soft blobs. */
                    float n2 = noise(x * 0.29F + variant * 57F, y * 0.31F + variant * 13F);

                    d *= 0.55F + 0.45F * n;
                    d -= n2 * 0.22F;

                    if (d < 0.35F && cell(x * 11 + variant * 71, y * 17) > d / 0.35F)
                    {
                        d = 0F;
                    }

                    if (d <= 0.02F)
                    {
                        fireball.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    /* Colour ramp by density: white-yellow core → orange mid → dark-red ember edge. */
                    float r, g, b;

                    if (d > 0.7F)
                    {
                        float t = Math.min(1F, (d - 0.7F) / 0.3F);

                        r = 1F;
                        g = 0.55F + 0.40F * t;
                        b = 0.15F + 0.65F * t;
                    }
                    else if (d > 0.4F)
                    {
                        float t = (d - 0.4F) / 0.3F;

                        r = 0.5F + 0.5F * t;
                        g = 0.10F + 0.45F * t;
                        b = 0.02F + 0.13F * t;
                    }
                    else
                    {
                        float t = d / 0.4F;

                        r = 0.22F + 0.28F * t;
                        g = 0.04F + 0.06F * t;
                        b = 0.01F + 0.01F * t;
                    }

                    int alpha = (int) (255 * Math.min(1F, d * 2.4F));

                    fireball.setColor(ox + x, oy + y,
                        alpha << 24 | (int) (b * 255) << 16 | (int) (g * 255) << 8 | (int) (r * 255));
                }
            }
        }

        mc.getTextureManager().registerTexture(FIREBALL_TEX, new NativeImageBackedTexture(fireball));

        /* Spark: a long thin bright streak sprite — mostly white-hot core with a warm edge and a
         * soft taper at the tail. When stretched along the motion vector it reads as a sharp
         * spark trajectory rather than a blob. */
        NativeImage spark = new NativeImage(128, 128, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int ox = (variant & 1) * 64, oy = (variant >> 1) * 64;
            float lean = (hash(variant, 71) - 0.5F) * 6F;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    /* y = 0 is the tail, y = 63 is the head. */
                    float yn = 1F - (y + 0.5F) / 64F;
                    float dxn = (x + lean * (yn - 0.5F) - 31.5F) / 30F;
                    float rr = Math.abs(dxn);

                    /* Thin line: a little wider at the head, tapering to the tail. */
                    float width = 0.10F + 0.12F * yn;
                    float line = Math.max(0F, 1F - rr / width);
                    float taper = (float) Math.pow(yn, 0.35D);
                    float a = line * taper;

                    if (a <= 0.01F)
                    {
                        spark.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    /* White core → warm yellow edge; tail redder. */
                    float core = Math.min(1F, a * 3F);
                    float t = 1F - yn;
                    float r = 1F;
                    float g = 0.50F + 0.50F * core;
                    float b = 0.15F + 0.45F * core * core - t * 0.20F;
                    int alpha = (int) (255 * Math.min(1F, a * 2.5F));

                    spark.setColor(ox + x, oy + y,
                        alpha << 24 | (int) (Math.max(0F, b) * 255) << 16 | (int) (g * 255) << 8 | (int) (r * 255));
                }
            }
        }

        mc.getTextureManager().registerTexture(SPARK_TEX, new NativeImageBackedTexture(spark));

        /* Bokeh: a soft defocused glow disk — bright soft core, gentle falloff, a faint brighter rim on
         * some variants (the out-of-focus ember look from the reference). Warm white centre → orange edge. */
        NativeImage bokeh = new NativeImage(128, 128, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int bx0 = (variant & 1) * 64, by0 = (variant >> 1) * 64;
            float ring = variant == 1 || variant == 2 ? 0.35F : 0F;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    float dx = (x - 31.5F) / 30F, dy = (y - 31.5F) / 30F;
                    float rr = (float) Math.sqrt(dx * dx + dy * dy);

                    if (rr >= 1F)
                    {
                        bokeh.setColor(bx0 + x, by0 + y, 0);

                        continue;
                    }

                    float core = (1F - rr) * (1F - rr);
                    float rim = ring * Math.max(0F, 1F - Math.abs(rr - 0.78F) / 0.16F);
                    float a = Math.min(1F, core * 0.9F + rim * 0.5F);

                    float cr = 1F;
                    float cg = 0.68F + 0.3F * core;
                    float cb = 0.34F + 0.5F * core;

                    int alpha = (int) (255 * a);

                    bokeh.setColor(bx0 + x, by0 + y,
                        alpha << 24 | (int) (cb * 255) << 16 | (int) (cg * 255) << 8 | (int) (cr * 255));
                }
            }
        }

        mc.getTextureManager().registerTexture(BOKEH_TEX, new NativeImageBackedTexture(bokeh));

        /* Shockwave ring: a thin annulus with soft speckled edges — the detonation's punch frame. */
        NativeImage ring = new NativeImage(128, 128, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int ox = (variant & 1) * 64, oy = (variant >> 1) * 64;

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    float dxn = (x + 0.5F - 32F) / 31F;
                    float dyn = (y + 0.5F - 32F) / 31F;
                    float r = (float) Math.sqrt(dxn * dxn + dyn * dyn);
                    float band = 1F - Math.abs(r - 0.8F) / 0.14F;

                    if (band <= 0F || r > 0.98F)
                    {
                        ring.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    float n = noise(x * 0.2F + variant * 29F, y * 0.2F + variant * 47F);
                    float a = band * (0.65F + 0.35F * n);

                    if (a < 0.35F && cell(x * 5 + variant * 97, y * 9) > a / 0.35F)
                    {
                        a = 0F;
                    }

                    int alpha = (int) (255 * Math.min(1F, a * 1.4F));

                    ring.setColor(ox + x, oy + y, alpha << 24 | 0xFFFFFF);
                }
            }
        }

        mc.getTextureManager().registerTexture(RING_TEX, new NativeImageBackedTexture(ring));

        /* Debris chips: small solid irregular flakes (white — tinted at draw: brown = litter,
         * green = shed leaves). 2×2 variants. */
        NativeImage debris = new NativeImage(64, 64, false);

        for (int variant = 0; variant < 4; variant++)
        {
            int ox = (variant & 1) * 32, oy = (variant >> 1) * 32;

            for (int y = 0; y < 32; y++)
            {
                for (int x = 0; x < 32; x++)
                {
                    float dxn = (x + 0.5F - 16F) / 13F;
                    float dyn = (y + 0.5F - 16F) / 13F;
                    float angle = (float) Math.atan2(dyn, dxn);
                    /* Ragged radius: a lumpy silhouette, different per variant. */
                    float edge = 0.55F + 0.35F * noise(
                        (float) Math.cos(angle) * 2.3F + variant * 19F,
                        (float) Math.sin(angle) * 2.3F + variant * 31F);
                    float r = (float) Math.sqrt(dxn * dxn + dyn * dyn);

                    if (r > edge)
                    {
                        debris.setColor(ox + x, oy + y, 0);

                        continue;
                    }

                    /* Solid body, a touch of shading toward the rim. */
                    float shade = 0.82F + 0.18F * (1F - r / edge);
                    int c = (int) (255 * shade);

                    debris.setColor(ox + x, oy + y, 0xFF << 24 | c << 16 | c << 8 | c);
                }
            }
        }

        mc.getTextureManager().registerTexture(DEBRIS_TEX, new NativeImageBackedTexture(debris));

        /* Bird silhouette flipbook: 4 flap frames (wings up / mid / down / mid), white — tinted
         * near-black at draw. Wings = two tapering arcs off a tiny body. */
        NativeImage bird = new NativeImage(128, 128, false);
        float[] lifts = {14F, 3F, -10F, 3F};

        for (int frame = 0; frame < 4; frame++)
        {
            int ox = (frame & 1) * 64, oy = (frame >> 1) * 64;
            float lift = lifts[frame];

            for (int y = 0; y < 64; y++)
            {
                for (int x = 0; x < 64; x++)
                {
                    float dx = x + 0.5F - 32F;
                    float dy = y + 0.5F - 34F;
                    float a = 0F;

                    /* Body: a small ellipse. */
                    float bodyD = (dx * dx) / (4.5F * 4.5F) + (dy * dy) / (2.6F * 2.6F);

                    if (bodyD < 1F)
                    {
                        a = 1F;
                    }
                    else
                    {
                        /* Wings: y follows a curved profile toward the tips; thickness tapers. */
                        float span = Math.abs(dx) / 24F;

                        if (span <= 1F)
                        {
                            float wingY = -lift * (float) Math.pow(span, 0.85D)
                                + 4F * span * span;
                            float thick = 3.2F - 2.2F * span;

                            if (Math.abs(dy - wingY) < thick)
                            {
                                a = 1F;
                            }
                        }
                    }

                    bird.setColor(ox + x, oy + y, a > 0F ? (0xFF << 24 | 0xFFFFFF) : 0);
                }
            }
        }

        mc.getTextureManager().registerTexture(BIRD_TEX, new NativeImageBackedTexture(bird));

        texturesReady = true;
    }

    /** Soft blob field for the flame silhouette: 1 at (cx, cy), falling to 0 at radius r. */
    private static float flameBlob(int x, int y, float cx, float cy, float r)
    {
        float dx = (x - cx) / r;
        float dy = (y - cy) / r;

        return Math.max(0F, 1F - (dx * dx + dy * dy));
    }

    /** Two-octave value noise, 0..1. */
    private static float noise(float x, float y)
    {
        return (valueNoise(x, y) * 0.65F + valueNoise(x * 2.3F + 71F, y * 2.3F - 37F) * 0.35F);
    }

    private static float valueNoise(float x, float y)
    {
        int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
        float fx = x - x0, fy = y - y0;

        fx = fx * fx * (3F - 2F * fx);
        fy = fy * fy * (3F - 2F * fy);

        float a = cell(x0, y0), b = cell(x0 + 1, y0), c = cell(x0, y0 + 1), d = cell(x0 + 1, y0 + 1);

        return a + (b - a) * fx + (c - a) * fy + (a - b - c + d) * fx * fy;
    }

    private static float cell(int x, int y)
    {
        int h = x * 374761393 + y * 668265263;

        h = (h ^ (h >> 13)) * 1274126177;

        return ((h ^ (h >> 16)) & 0xFFFF) / (float) 0xFFFF;
    }

    /** One camera-facing translucent quad; with {@code w > 0} it is STRETCHED along (ax,ay,az)
     *  (half-length = size, half-width = w) — the spark streaks. */
    private static final class Quad
    {
        final int kind;
        final float x, y, z, size;
        final int frame;
        final float r, g, b, a;
        float ax, ay, az, w;
        boolean flat;
        float depth;

        Quad(int kind, float x, float y, float z, float size, int frame, float r, float g, float b, float a)
        {
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.z = z;
            this.size = size;
            this.frame = frame;
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }

        Quad streak(float ax, float ay, float az, float w)
        {
            float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);

            if (len > 1e-4F)
            {
                this.ax = ax / len;
                this.ay = ay / len;
                this.az = az / len;
                this.w = w;
            }

            return this;
        }

        /** Lay the quad FLAT in the world XZ plane (shock rings) instead of facing the camera. */
        Quad flat()
        {
            this.flat = true;

            return this;
        }
    }
}
