package com.bbsvfx.bbsvfx.client;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import mchorse.bbs_mod.client.BBSRendering;
import mchorse.bbs_mod.cubic.render.vao.ModelVAORenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlUniform;
import net.minecraft.client.gl.ShaderProgram;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import com.bbsvfx.bbsvfx.forms.DestructionBlock;
import com.bbsvfx.bbsvfx.forms.ExplosionForm;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Environment bend (Ф2b): the captured foliage rendered as PROXY UNITS that react to the blast — a
 * tree (its logs + the leaves assigned to the nearest trunk) pivots at its root, a small plant tilts
 * at its base. When the wind front reaches a unit it kicks into a damped oscillator (lean away from
 * the epicenter, spring back, settle) — a CLOSED FORM of the scrub time, so the far field plays
 * scrub-exact with zero simulation.
 *
 * <p>Rendering follows the {@link DestructionPhysVAO} recipe: rest geometry captured once into VBOs
 * (unit-local around each pivot), one tight CPU transform per frame, one draw with the VANILLA entity
 * CUTOUT shader (crisp leaves, packs light it). At rest (before the front / after settling) nothing
 * is re-uploaded — the sway window is the only time the CPU loop runs.</p>
 */
public class ExplosionFoliageVAO
{
    private static final int MAX_CACHED = 2;

    /* Keyed by CONTENT STAMP, not list identity: BBS re-copies the form (and its foliage list) every
     * frame while the editor is open — an identity key rebuilt this 1M+-vert VBO per frame (GL churn
     * to the point of a driver crash). Same lesson as the global physics bake cache. */
    private static final LinkedHashMap<Long, ExplosionFoliageVAO> CACHE = new LinkedHashMap<>(4, 0.75F, true);

    private int vao = -1;
    private int posBuffer, colorBuffer, uvBuffer, normalBuffer, tangentBuffer, midUvBuffer, lightBuffer;
    private int vertexCount;

    /* Per unit: pivot xyz + type (0 = tree, 1 = plant) + vertex range via unitStart. */
    private float[] unitPivot;
    private byte[] unitType;
    private int[] unitStart;
    /** Height of each unit above its pivot — the normaliser for the height-weighted wind bend. */
    private float[] unitTopY;

    /** Height bands for the wind bend: one rotation matrix per band, so the trig cost is per BAND, not per
     *  vertex, while the base of every unit still stays nailed to the ground. */
    private static final int BANDS = 12;
    private final float[] bendLut = new float[BANDS * 9];
    private int units;

    /* Tree table for the FX bursts (leaf shed, birds): trees are the FIRST {@code treeCount} units
     * (build order), each with an average captured leaf tint (r,g,b per tree). */
    private int treeCount;
    private float[] treeLeafTint;

    private float[] localPos;
    private float[] localNormal;
    private float[] localTangent;
    private float[] tmpPos;
    private float[] tmpNormal;
    private float[] tmpTangents;
    private float[] lastAngle;
    private boolean restUploaded;
    private int frame;

    /** Deterministic 0..1 hash per (unit, salt) — the fell rolls and per-tree timing variety. */
    private static float hash(int i, int salt)
    {
        int x = (i ^ 0x9e3779b9) * 0x85ebca6b + salt * 0x165667b1;

        x ^= x >>> 13;
        x *= 0x27d4eb2d;
        x ^= x >>> 15;

        return (x & 0xFFFF) / (float) 0xFFFF;
    }

    /** Content stamp = the cache key: size + a handful of sampled block positions. Collisions across
     *  genuinely different captures are astronomically unlikely and only cost a wrong sway shape. */
    private static long stampOf(List<DestructionBlock> foliage)
    {
        int n = foliage.size();
        long s = n;

        for (int k = 0; k < 8; k++)
        {
            DestructionBlock block = foliage.get((int) ((long) n * k / 8));

            s = s * 31 + BlockPos.asLong(block.x.get(), block.y.get(), block.z.get());
        }

        DestructionBlock last = foliage.get(n - 1);

        return s * 31 + BlockPos.asLong(last.x.get(), last.y.get(), last.z.get());
    }

    /** Fetch (or build) the foliage proxy for this form. Null when there is nothing to bend. */
    public static ExplosionFoliageVAO of(ExplosionForm form)
    {
        return of(form.foliage.getAllTyped());
    }

    /** Fetch (or build) a foliage proxy from a raw block list (shared by the WindForm — the build and the
     * VAO are form-agnostic; only the per-frame bend driver differs, see {@link #renderWind}). */
    public static ExplosionFoliageVAO of(List<DestructionBlock> foliage)
    {
        if (foliage.isEmpty())
        {
            return null;
        }

        long stamp = stampOf(foliage);
        ExplosionFoliageVAO cached = CACHE.get(stamp);

        if (cached != null)
        {
            return cached;
        }

        ExplosionFoliageVAO built = new ExplosionFoliageVAO();

        try
        {
            built.build(foliage);
        }
        catch (Exception e)
        {
            System.err.println("[bbsvfx] Explosion foliage VAO build failed: " + e);
            built.delete();

            return null;
        }

        CACHE.put(stamp, built);

        while (CACHE.size() > MAX_CACHED)
        {
            Map.Entry<Long, ExplosionFoliageVAO> eldest = CACHE.entrySet().iterator().next();

            eldest.getValue().delete();
            CACHE.remove(eldest.getKey());
        }

        return built;
    }

    private void build(List<DestructionBlock> foliage)
    {
        int n = foliage.size();

        /* Classify + index. */
        boolean[] isLog = new boolean[n];
        boolean[] isLeaves = new boolean[n];
        Long2IntOpenHashMap posToIdx = new Long2IntOpenHashMap(n * 2);

        posToIdx.defaultReturnValue(-1);

        for (int i = 0; i < n; i++)
        {
            DestructionBlock block = foliage.get(i);

            isLog[i] = block.blockState().isIn(BlockTags.LOGS);
            isLeaves[i] = block.blockState().isIn(BlockTags.LEAVES);
            posToIdx.put(BlockPos.asLong(block.x.get(), block.y.get(), block.z.get()), i);
        }

        /* CONNECTED TREES: flood-fill the logs into components (26-connectivity), each ONE rigid unit
         * pivoting at its base — so a whole tree (trunk + diagonal branches) bends together. The old "a log
         * with no log directly below = a root" made every branch TIP its own unit, which then pivoted about
         * itself (a stub lever) and DETACHED from the swaying trunk. Unit order: trees first, then plants. */
        int[] unitOf = new int[n];
        java.util.Arrays.fill(unitOf, -1);

        List<int[]> roots = new ArrayList<>();
        int treeCount = 0;

        java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>();

        for (int seed = 0; seed < n; seed++)
        {
            if (!isLog[seed] || unitOf[seed] != -1)
            {
                continue;
            }

            int comp = treeCount++;
            int minY = Integer.MAX_VALUE, baseSumX = 0, baseSumZ = 0, baseCount = 0;

            unitOf[seed] = comp;
            queue.add(seed);

            while (!queue.isEmpty())
            {
                int c = queue.poll();
                DestructionBlock cb = foliage.get(c);
                int cx = cb.x.get(), cy = cb.y.get(), cz = cb.z.get();

                /* Track the base = the centre of the LOWEST-Y logs (a 2×2 trunk pivots at its middle). */
                if (cy < minY)
                {
                    minY = cy;
                    baseSumX = 0;
                    baseSumZ = 0;
                    baseCount = 0;
                }

                if (cy == minY)
                {
                    baseSumX += cx;
                    baseSumZ += cz;
                    baseCount++;
                }

                for (int dy = -1; dy <= 1; dy++)
                {
                    for (int dx = -1; dx <= 1; dx++)
                    {
                        for (int dz = -1; dz <= 1; dz++)
                        {
                            if ((dx | dy | dz) == 0)
                            {
                                continue;
                            }

                            int nb = posToIdx.get(BlockPos.asLong(cx + dx, cy + dy, cz + dz));

                            if (nb >= 0 && isLog[nb] && unitOf[nb] == -1)
                            {
                                unitOf[nb] = comp;
                                queue.add(nb);
                            }
                        }
                    }
                }
            }

            roots.add(new int[] {Math.round((float) baseSumX / baseCount), minY,
                Math.round((float) baseSumZ / baseCount)});
        }

        /* Leaves join the nearest tree by CONNECTIVITY: a multi-source BFS out of every log, spreading only
         * through leaves. Orphan leaves (touching no log) stay unassigned → their own units. */
        for (int i = 0; i < n; i++)
        {
            if (isLog[i])
            {
                queue.add(i);
            }
        }

        while (!queue.isEmpty())
        {
            int c = queue.poll();
            DestructionBlock cb = foliage.get(c);
            int cx = cb.x.get(), cy = cb.y.get(), cz = cb.z.get();

            for (int dy = -1; dy <= 1; dy++)
            {
                for (int dx = -1; dx <= 1; dx++)
                {
                    for (int dz = -1; dz <= 1; dz++)
                    {
                        if ((dx | dy | dz) == 0)
                        {
                            continue;
                        }

                        int nb = posToIdx.get(BlockPos.asLong(cx + dx, cy + dy, cz + dz));

                        if (nb >= 0 && isLeaves[nb] && unitOf[nb] == -1)
                        {
                            unitOf[nb] = unitOf[c];
                            queue.add(nb);
                        }
                    }
                }
            }
        }

        /* Build the unit tables: trees, then per-block plant units. */
        List<float[]> pivots = new ArrayList<>();
        List<Byte> types = new ArrayList<>();
        List<List<Integer>> members = new ArrayList<>();

        for (int r = 0; r < treeCount; r++)
        {
            int[] root = roots.get(r);

            pivots.add(new float[] {root[0] + 0.5F, root[1], root[2] + 0.5F});
            types.add((byte) 0);
            members.add(new ArrayList<>());
        }

        for (int i = 0; i < n; i++)
        {
            if (unitOf[i] >= 0)
            {
                members.get(unitOf[i]).add(i);
            }
            else
            {
                DestructionBlock block = foliage.get(i);

                pivots.add(new float[] {block.x.get() + 0.5F, block.y.get(), block.z.get() + 0.5F});
                types.add((byte) 1);

                List<Integer> own = new ArrayList<>(1);

                own.add(i);
                members.add(own);
                unitOf[i] = members.size() - 1;
            }
        }

        /* Tree leaf tints for the FX bursts: average the captured biome tint of each tree's leaves. */
        this.treeCount = treeCount;
        this.treeLeafTint = new float[Math.max(1, treeCount) * 3];

        for (int r = 0; r < treeCount; r++)
        {
            float sr = 0F, sg = 0F, sb = 0F;
            int leaves = 0;

            for (int i : members.get(r))
            {
                if (!isLeaves[i])
                {
                    continue;
                }

                int tint = foliage.get(i).tint.get();

                if (tint == -1)
                {
                    continue;
                }

                sr += ((tint >> 16) & 0xFF) / 255F;
                sg += ((tint >> 8) & 0xFF) / 255F;
                sb += (tint & 0xFF) / 255F;
                leaves++;
            }

            if (leaves > 0)
            {
                this.treeLeafTint[r * 3] = sr / leaves;
                this.treeLeafTint[r * 3 + 1] = sg / leaves;
                this.treeLeafTint[r * 3 + 2] = sb / leaves;
            }
            else
            {
                /* Untinted / leafless: a generic foliage green. */
                this.treeLeafTint[r * 3] = 0.35F;
                this.treeLeafTint[r * 3 + 1] = 0.55F;
                this.treeLeafTint[r * 3 + 2] = 0.22F;
            }
        }

        /* Capture the rest geometry unit by unit (recentred to the pivot). */
        Recorder recorder = new Recorder();
        Provider provider = new Provider(recorder);
        MatrixStack ms = new MatrixStack();

        this.units = pivots.size();
        this.unitPivot = new float[this.units * 3];
        this.unitType = new byte[this.units];
        this.unitStart = new int[this.units + 1];
        this.lastAngle = new float[this.units];

        int quadsRecorded = 0, quadsKept = 0;

        for (int u = 0; u < this.units; u++)
        {
            float[] pivot = pivots.get(u);
            final int unit = u;

            this.unitPivot[u * 3] = pivot[0];
            this.unitPivot[u * 3 + 1] = pivot[1];
            this.unitPivot[u * 3 + 2] = pivot[2];
            this.unitType[u] = types.get(u);
            this.unitStart[u] = recorder.triCount();

            recorder.beginUnit();

            for (int i : members.get(u))
            {
                DestructionBlock block = foliage.get(i);

                /* PER-BLOCK light captured PRE-cut: under-canopy trunks stay dark, sunny crowns stay
                 * bright — instead of the one flat sample the whole proxy forest used to get. */
                recorder.setLight(block.light.get());
                recorder.markBlock();
                ms.push();
                ms.translate(block.x.get(), block.y.get(), block.z.get());
                DestructionBlockDraw.draw(block.blockState(), block.tint.get(),
                    block.blockState().getRenderingSeed(new BlockPos(block.x.get(), block.y.get(), block.z.get())),
                    ms.peek(), provider, 0x00F000F0, OverlayTexture.DEFAULT_UV);
                ms.pop();

                /* INTERIOR-FACE CULL — the big scale win for wide scans: a face between two blocks of
                 * the SAME unit (leaf↔leaf inside a crown, log↔log inside a trunk) is never visible —
                 * the unit moves rigidly, even a felled tree keeps them glued. Dense crowns lose ~half
                 * their quads, so a 200+ radius forest fits the same CPU sway budget. Cross-UNIT faces
                 * are kept (two adjacent trees bend apart and would expose holes). */
                boolean leaf = isLeaves[i];

                if (leaf || isLog[i])
                {
                    final int bx = block.x.get(), by = block.y.get(), bz = block.z.get();
                    final boolean asLeaf = leaf;

                    quadsRecorded += recorder.blockQuads();
                    recorder.filterBlock((dx, dy, dz) ->
                    {
                        int nb = posToIdx.get(BlockPos.asLong(bx + dx, by + dy, bz + dz));

                        return nb < 0 || unitOf[nb] != unit
                            || (asLeaf ? !isLeaves[nb] : !isLog[nb]);
                    });
                    quadsKept += recorder.blockQuads();
                }
                else
                {
                    int q = recorder.blockQuads();

                    quadsRecorded += q;
                    quadsKept += q;
                }
            }

            recorder.endUnit(pivot[0], pivot[1], pivot[2]);
        }

        this.unitStart[this.units] = recorder.triCount();
        this.vertexCount = recorder.triCount();
        this.localPos = recorder.positions();
        this.localNormal = recorder.normals();

        /* PLANTS (cross models) render UNSHADED in the vanilla block pipeline (shade=false, full
         * brightness) — but the entity program applies directional diffuse by normal, and a cross
         * quad's sideways normal dimmed the proxies visibly darker than the world's own grass
         * ("всё выделяется" report). Force plant normals UP: entity diffuse ≈ 1, matching vanilla. */
        for (int u = 0; u < this.units; u++)
        {
            if (this.unitType[u] != 1)
            {
                continue;
            }

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                this.localNormal[v * 3] = 0F;
                this.localNormal[v * 3 + 1] = 1F;
                this.localNormal[v * 3 + 2] = 0F;
            }
        }
        /* Height of each unit above its pivot — the wind bend is weighted by it, so the geometry sitting on
         * the ground never moves and the sway grows toward the crown. */
        this.unitTopY = new float[this.units];

        for (int u = 0; u < this.units; u++)
        {
            float top = 0F;

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                top = Math.max(top, this.localPos[v * 3 + 1]);
            }

            this.unitTopY[u] = Math.max(0.001F, top);
        }

        this.tmpPos = this.localPos.clone();
        this.tmpNormal = this.localNormal.clone();
        this.localTangent = new float[this.vertexCount * 4];
        this.tmpTangents = new float[this.vertexCount * 4];

        float[] uvs = recorder.uvs();

        BBSRendering.calculateTangents(this.localTangent, this.localPos, this.localNormal, uvs);
        System.arraycopy(this.localTangent, 0, this.tmpTangents, 0, this.localTangent.length);

        /* Rest positions = pivot + local. */
        for (int u = 0; u < this.units; u++)
        {
            this.restoreUnit(u);
        }

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        this.vao = GL30.glGenVertexArrays();
        GL30.glBindVertexArray(this.vao);

        this.posBuffer = attrib(0, 3, this.tmpPos, GL15.GL_DYNAMIC_DRAW);
        this.colorBuffer = attrib(1, 4, recorder.colors(), GL15.GL_STATIC_DRAW);
        this.uvBuffer = attrib(2, 2, uvs, GL15.GL_STATIC_DRAW);
        this.lightBuffer = attribI(4, 2, recorder.lights());
        this.normalBuffer = attrib(5, 3, this.tmpNormal, GL15.GL_DYNAMIC_DRAW);
        this.midUvBuffer = attrib(8, 2, uvs, GL15.GL_STATIC_DRAW);
        this.tangentBuffer = attrib(9, 4, this.tmpTangents, GL15.GL_DYNAMIC_DRAW);

        GL30.glBindVertexArray(previousVao);
        this.restUploaded = true;

        System.out.println("[bbsvfx] foliage proxy: " + n + " blocks -> " + treeCount + " trees + "
            + (this.units - treeCount) + " plants, " + this.vertexCount + " verts (interior-face cull "
            + quadsRecorded + " -> " + quadsKept + " quads)");
    }

    private static int attrib(int location, int size, float[] data, int usage)
    {
        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, usage);
        GL30.glVertexAttribPointer(location, size, GL15.GL_FLOAT, false, 0, 0);

        return buffer;
    }

    /** Integer attribute (the UV2 lightmap ivec2 is per-VERTEX now, not one constant). */
    private static int attribI(int location, int size, int[] data)
    {
        int buffer = GL30.glGenBuffers();

        GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_STATIC_DRAW);
        GL30.glVertexAttribIPointer(location, size, GL15.GL_INT, 0, 0);

        return buffer;
    }

    /** Write unit {@code u}'s REST positions into the tmp array (normals/tangents always stay rest). */
    private void restoreUnit(int u)
    {
        float px = this.unitPivot[u * 3], py = this.unitPivot[u * 3 + 1], pz = this.unitPivot[u * 3 + 2];

        for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
        {
            int i = v * 3;

            this.tmpPos[i] = this.localPos[i] + px;
            this.tmpPos[i + 1] = this.localPos[i + 1] + py;
            this.tmpPos[i + 2] = this.localPos[i + 2] + pz;
        }
    }

    /**
     * Bend + draw. The oscillator: after the front's arrival ({@code dist/front}) the unit swings as
     * {@code e^(-d·τ)·sin(ω·τ)} — trees slow and heavy, plants quick — scaled by the form's bend
     * amount and the distance falloff, leaning AWAY from the epicenter.
     */
    public void render(ExplosionForm form, float simTime, MatrixStack stack,
        float r, float g, float b, float a, int light, int overlay)
    {
        if (this.vao == -1 || this.vertexCount == 0)
        {
            return;
        }

        Vector3f epic = form.point();
        float front = Math.max(1F, form.windFrontSpeed.get());
        float reach = Math.max(1F, form.bendRadius.get());
        float amount = (float) Math.toRadians(form.bendAmount.get());
        float fell = form.bendFell.get();
        float gusts = form.bendGusts.get();
        float hold = form.windHold.get();
        boolean iris = BBSRendering.isIrisShadersEnabled();
        boolean anyDirty = false;

        /* Perf: during the sway window nearly every unit moves every frame — on a 160-radius forest
         * that is 1M+ verts. Interleave: each unit re-transforms every OTHER frame (sway is slow, the
         * halved update rate is invisible), and only POSITIONS are transformed/uploaded — rest normals
         * and tangents stay (a ≤25° lean barely changes the lighting, and it's 2/3 of the bandwidth). */
        this.frame++;

        for (int u = 0; u < this.units; u++)
        {
            if (((u + this.frame) & 1) != 0 && this.lastAngle[u] != 0F)
            {
                continue;
            }
            float px = this.unitPivot[u * 3], py = this.unitPivot[u * 3 + 1], pz = this.unitPivot[u * 3 + 2];
            float dx = px - epic.x, dz = pz - epic.z;
            float dist = (float) Math.sqrt(dx * dx + dz * dz);
            float tau = simTime - dist / front;
            float angle = 0F;

            if (tau > 0F && amount > 0F)
            {
                boolean plant = this.unitType[u] == 1;
                float damp = plant ? 1.1F : 0.55F;
                float omega = plant ? 5.5F : 2.3F;
                /* Normalized impulse response (peak ≈ value at atan(ω/d)/ω). */
                float gT = (float) (Math.exp(-damp * tau) * Math.sin(omega * tau)) / 0.78F;
                /* Gentle falloff: the far edge of the captured ring still visibly rocks. */
                float falloff = Math.max(0F, 1.15F - dist / reach);

                falloff = (float) Math.pow(Math.min(falloff, 1F), 0.6D);

                /* SUSTAINED BLAST WIND: while the explosion keeps driving air (windHold seconds) the
                 * unit holds a BOWED lean — pressure ramps in fast, holds, wanes — with the front's
                 * punch riding on top as the initial overshoot. hold = 0 → the punch alone (legacy). */
                float pressure = hold > 0.05F
                    ? (1F - (float) Math.exp(-tau / 0.4F)) * (float) Math.exp(-tau / hold)
                    : 0F;
                float punch = hold > 0.05F ? 0.45F : 1F;

                angle = amount * (plant ? 2.5F : 1F) * falloff * (0.9F * pressure + punch * gT);

                /* TURBULENT GUST TAIL: after the main swing settles the forest keeps rocking with
                 * decaying noise for ~10 s (two incommensurate sines per unit = cheap turbulence) —
                 * instead of freezing the moment the oscillator dies. Flutter is strongest while the
                 * sustained wind blows. The decaying envelope drops the gust below the zero-snap
                 * threshold, so the units still settle for good. */
                if (gusts > 0F)
                {
                    float env = (float) Math.exp(-tau / (5F + hold));
                    float p1 = hash(u, 96) * 6.2832F, p2 = hash(u, 97) * 6.2832F;
                    float freq = plant ? 3.4F : 1.9F;
                    float turb = (float) (Math.sin(tau * freq + p1)
                        * (0.55F + 0.45F * Math.sin(tau * freq * 0.37F + p2)));

                    angle += amount * (plant ? 2F : 1F) * falloff * gusts * 0.16F
                        * (0.6F + 1.2F * pressure) * env * turb;
                }

                /* THE SHOCKWAVE FELLS a share of the units: a per-unit hash roll against the fell
                 * chance × a steeper falloff — most fall near the blast, a few at the edge, the rest
                 * just sway (для живости). */
                if (fell > 0F)
                {
                    float fellFalloff = (float) Math.pow(Math.max(0F, 1.05F - dist / reach), 1.2D);

                    if (!plant && hash(u, 91) < fell * fellFalloff)
                    {
                        /* Topple: after a per-tree delay (creak), the lean accelerates past the point
                         * of no return down to ~88°, bounces off the ground, stays down. */
                        float delay = 0.15F + 1.1F * hash(u, 92);
                        float tf = tau - delay;

                        if (tf > 0F)
                        {
                            float fallTime = 1.1F + 0.9F * hash(u, 93);
                            float p = Math.min(1F, tf / fallTime);
                            float topple = 1.536F * (float) Math.pow(p, 1.7D);

                            if (p >= 1F)
                            {
                                float tb = tf - fallTime;

                                topple = 1.536F - 0.11F * (float) (Math.exp(-2.6D * tb) * Math.abs(Math.sin(8.5D * tb)));
                            }

                            angle = Math.max(angle, topple);
                        }
                    }
                    else if (plant && hash(u, 94) < fell * fellFalloff * 1.3F)
                    {
                        /* Flattened grass: settles at a strong permanent bend instead of springing back. */
                        float target = amount * 2.5F * falloff * 0.75F;
                        float settle = 1F - (float) Math.exp(-1.4F * tau);

                        angle = Math.max(angle, target * settle);
                    }
                }

                if (Math.abs(angle) < 3e-3F)
                {
                    angle = 0F;
                }
            }

            if (Math.abs(angle - this.lastAngle[u]) < 2e-3F)
            {
                continue;
            }

            this.lastAngle[u] = angle;
            anyDirty = true;

            if (angle == 0F)
            {
                this.restoreUnit(u);

                continue;
            }

            /* Lean AWAY from the epicenter: axis = (rz, 0, -rx) — rotating +θ about it tips the top
             * along +radial (the (-rz, rx) choice leaned INTO the blast: "all trees combed one way"). */
            float inv = dist > 1e-3F ? 1F / dist : 0F;
            float rx = dx * inv, rz = dz * inv;
            float ax = rz, az = -rx;
            float sin = (float) Math.sin(angle), cos = (float) Math.cos(angle);
            float ic = 1F - cos;

            /* Rodrigues for axis (ax, 0, az). */
            float m00 = cos + ax * ax * ic, m01 = az * sin, m02 = ax * az * ic;
            float m10 = -az * sin, m11 = cos, m12 = ax * sin;
            float m20 = ax * az * ic, m21 = -ax * sin, m22 = cos + az * az * ic;

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                int i = v * 3;
                float x = this.localPos[i], y = this.localPos[i + 1], z = this.localPos[i + 2];

                this.tmpPos[i] = m00 * x + m10 * y + m20 * z + px;
                this.tmpPos[i + 1] = m01 * x + m11 * y + m21 * z + py;
                this.tmpPos[i + 2] = m02 * x + m12 * y + m22 * z + pz;
            }
        }

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GL30.glBindVertexArray(this.vao);

        if (anyDirty || !this.restUploaded)
        {
            /* Positions only — normals/tangents are static rest data uploaded at build. */
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.posBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpPos, GL15.GL_DYNAMIC_DRAW);
            GL30.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, 0, 0);

            this.restUploaded = true;
        }

        /* Vanilla entity CUTOUT shader (crisp leaf alpha; Iris swaps in the pack's program). */
        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.setShaderTexture(0, PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        mc.gameRenderer.getLightmapTextureManager().enable();
        mc.gameRenderer.getOverlayTexture().setupOverlayColor();
        RenderSystem.enableDepthTest();

        /* Yarn's famous "NoNull" typo = entity cutout NO-CULL (leaves need both faces). ⚠ verify the
         * name survives on the 1.21 yarn when building -Pmc=1.21.1. */
        ShaderProgram shader = GameRenderer.getRenderTypeEntityCutoutNoNullProgram();

        ModelVAORenderer.setupUniforms(stack, shader);

        GlUniform colorModulator = shader.getUniform("ColorModulator");

        if (colorModulator != null)
        {
            colorModulator.set(r, g, b, a);
        }

        shader.bind();

        GL30.glVertexAttribI2i(3, overlay & 0xFFFF, (overlay >> 16) & 0xFFFF);
        GL30.glEnableVertexAttribArray(0);
        GL30.glEnableVertexAttribArray(1);
        GL30.glEnableVertexAttribArray(2);
        /* Light is a per-VERTEX array (captured pre-cut per block), not one flat constant. */
        GL30.glEnableVertexAttribArray(4);
        GL30.glEnableVertexAttribArray(5);

        if (iris)
        {
            GL30.glEnableVertexAttribArray(8);
            GL30.glEnableVertexAttribArray(9);
        }

        GL30.glDrawArrays(GL15.GL_TRIANGLES, 0, this.vertexCount);

        GL30.glDisableVertexAttribArray(0);
        GL30.glDisableVertexAttribArray(1);
        GL30.glDisableVertexAttribArray(2);
        GL30.glDisableVertexAttribArray(4);
        GL30.glDisableVertexAttribArray(5);

        if (iris)
        {
            GL30.glDisableVertexAttribArray(8);
            GL30.glDisableVertexAttribArray(9);
        }

        shader.unbind();
        GL30.glBindVertexArray(previousVao);
        mc.gameRenderer.getLightmapTextureManager().disable();
        mc.gameRenderer.getOverlayTexture().teardownOverlayColor();
    }

    /**
     * Ambient WIND sway (for the WindForm) — the same VAO/upload/draw as {@link #render}, but a CONTINUOUS
     * directional driver instead of the blast impulse: every unit bows DOWNWIND (a steady lean that flutters
     * with a per-unit-phased pair of sines) about a fixed wind-perpendicular axis, never settling. At
     * {@code amountDeg == 0} every unit sits at rest, so the proxies look exactly like the captured world
     * (used while sway is off but the foliage is cut/replaced).
     *
     * @param tFlow continuous flow time (seconds); the wind never stops, so this drives the oscillation.
     * @param windX,windZ normalized horizontal wind direction (world axes).
     */
    public void renderWind(MatrixStack stack, double tFlow, float windX, float windZ, float amountDeg,
        float r, float g, float b, float a, int light, int overlay)
    {
        this.renderWind(stack, tFlow, windX, windZ, amountDeg, r, g, b, a, light, overlay,
            false, 0F, 0F, 0F, 0F, 1F);
    }

    /**
     * As above, but optionally driven by a VORTEX field: each unit's lean direction is sampled at its own
     * pivot, so trees near the funnel are swept tangentially around it (and pulled inward) while distant ones
     * barely move — instead of the whole forest leaning the same way.
     *
     * @param speedScale rate multiplier for the flutter, so a stronger wind also shakes the foliage FASTER
     *                   (the caller scales the bend angle to match).
     */
    public void renderWind(MatrixStack stack, double tFlow, float windX, float windZ, float amountDeg,
        float r, float g, float b, float a, int light, int overlay,
        boolean vortex, float coreR, float swirl, float suction, float updraft, float speedScale)
    {
        if (this.vao == -1 || this.vertexCount == 0)
        {
            return;
        }

        float amount = (float) Math.toRadians(amountDeg);
        boolean iris = BBSRendering.isIrisShadersEnabled();
        boolean anyDirty = false;

        /* Tip the tops ALONG the wind: axis ⟂ wind, horizontal (matches render()'s (rz,-rx) convention). */
        float ax = windZ, az = -windX;
        WindField.Sample fs = vortex ? new WindField.Sample() : null;

        /* NO interleave here (unlike the blast render): BBS renders the form several times per frame, so a
         * per-call frame counter has an even stride and would freeze half the units permanently (the "hard
         * jerk"). Every unit is recomputed; the angle-unchanged guard below makes the extra same-frame calls
         * cheap (they all skip, one upload per frame). */
        for (int u = 0; u < this.units; u++)
        {
            float angle = 0F;
            float uax = ax, uaz = az;

            if (vortex)
            {
                /* The pivot is already in the form's LOCAL frame (proxies are stored as x−origin), which is
                 * exactly the frame the field is defined in. */
                WindField.sample(this.unitPivot[u * 3], this.unitPivot[u * 3 + 2], true,
                    windX, windZ, coreR, swirl, suction, updraft, fs);

                uax = fs.z;
                uaz = -fs.x;
            }

            if (amount > 0F)
            {
                boolean plant = this.unitType[u] == 1;
                float freq = (plant ? 2.6F : 1.05F) * speedScale;
                float phase = hash(u, 71) * 6.2832F;

                /* Steady downwind bow (always ≥ 0) + a second incommensurate sine for gust flutter; grass
                 * bends further and quicker than trees. */
                float bow = 0.55F + 0.45F * (float) Math.sin(tFlow * freq + phase);
                float flutter = 0.22F * (float) Math.sin(tFlow * freq * 2.3F + phase * 1.7F);

                angle = amount * (plant ? 1.8F : 1F) * Math.max(0F, bow + flutter);

                /* In a vortex the bend falls off with distance from the funnel. */
                if (vortex)
                {
                    angle *= fs.mag;
                }
            }

            if (Math.abs(angle - this.lastAngle[u]) < 1e-3F)
            {
                continue;
            }

            this.lastAngle[u] = angle;
            anyDirty = true;

            if (angle == 0F)
            {
                this.restoreUnit(u);

                continue;
            }

            float px = this.unitPivot[u * 3], py = this.unitPivot[u * 3 + 1], pz = this.unitPivot[u * 3 + 2];

            /* ★HEIGHT-WEIGHTED BEND, not a rigid tip. Rotating the whole unit about its pivot lifted the
             * base off the ground — at any real angle the far corner of the bottom block rises, and since
             * the REAL blocks are cut away underneath, the trunk read as ripped out of the soil. Here the
             * rotation angle scales with height above the pivot (quadratic: stiff at the foot, whippy at the
             * crown), so ground-level geometry stays exactly put and the tree BENDS. Trig is done once per
             * height band rather than per vertex — the bands are what keep this cheap. */
            float top = this.unitTopY[u];

            for (int k = 0; k < BANDS; k++)
            {
                float w = k / (float) (BANDS - 1);
                float wa = angle * w * w;
                float s = (float) Math.sin(wa), c = (float) Math.cos(wa);
                float ic = 1F - c;
                int o = k * 9;

                this.bendLut[o] = c + uax * uax * ic;
                this.bendLut[o + 1] = uaz * s;
                this.bendLut[o + 2] = uax * uaz * ic;
                this.bendLut[o + 3] = -uaz * s;
                this.bendLut[o + 4] = c;
                this.bendLut[o + 5] = uax * s;
                this.bendLut[o + 6] = uax * uaz * ic;
                this.bendLut[o + 7] = -uax * s;
                this.bendLut[o + 8] = c + uaz * uaz * ic;
            }

            for (int v = this.unitStart[u], end = this.unitStart[u + 1]; v < end; v++)
            {
                int i = v * 3;
                float x = this.localPos[i], y = this.localPos[i + 1], z = this.localPos[i + 2];

                int band = (int) (Math.min(1F, Math.max(0F, y / top)) * (BANDS - 1) + 0.5F);
                int o = band * 9;
                float m00 = this.bendLut[o], m01 = this.bendLut[o + 1], m02 = this.bendLut[o + 2];
                float m10 = this.bendLut[o + 3], m11 = this.bendLut[o + 4], m12 = this.bendLut[o + 5];
                float m20 = this.bendLut[o + 6], m21 = this.bendLut[o + 7], m22 = this.bendLut[o + 8];

                this.tmpPos[i] = m00 * x + m10 * y + m20 * z + px;
                this.tmpPos[i + 1] = m01 * x + m11 * y + m21 * z + py;
                this.tmpPos[i + 2] = m02 * x + m12 * y + m22 * z + pz;
            }
        }

        int previousVao = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);

        GL30.glBindVertexArray(this.vao);

        if (anyDirty || !this.restUploaded)
        {
            GL30.glBindBuffer(GL15.GL_ARRAY_BUFFER, this.posBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, this.tmpPos, GL15.GL_DYNAMIC_DRAW);
            GL30.glVertexAttribPointer(0, 3, GL15.GL_FLOAT, false, 0, 0);

            this.restUploaded = true;
        }

        MinecraftClient mc = MinecraftClient.getInstance();

        RenderSystem.setShaderTexture(0, PlayerScreenHandler.BLOCK_ATLAS_TEXTURE);
        mc.gameRenderer.getLightmapTextureManager().enable();
        mc.gameRenderer.getOverlayTexture().setupOverlayColor();
        RenderSystem.enableDepthTest();

        ShaderProgram shader = GameRenderer.getRenderTypeEntityCutoutNoNullProgram();

        ModelVAORenderer.setupUniforms(stack, shader);

        /* ★FOG DISTANCE FIX. The vanilla entity shader computes
         *     vertexDistance = fog_distance(ModelViewMat, IViewRotMat * Position, FogShape)
         * because a normal entity's vertices live in a camera-rotated local space that IViewRotMat undoes.
         * We bake the whole transform into ModelViewMat and feed positions that must pass through untouched,
         * so that extra rotation threw our vertices (tens of blocks out from the form origin) somewhere else
         * relative to the camera and the fog distance came out wrong — underwater the proxies drowned in
         * full-strength water fog and rendered solid blue while the real plants beside them stayed green.
         * Identity here means the fog distance is simply the true view-space distance. */
        GlUniform viewRot = shader.getUniform("IViewRotMat");

        if (viewRot != null)
        {
            viewRot.set(new org.joml.Matrix3f());
        }

        GlUniform colorModulator = shader.getUniform("ColorModulator");

        if (colorModulator != null)
        {
            colorModulator.set(r, g, b, a);
        }

        shader.bind();

        GL30.glVertexAttribI2i(3, overlay & 0xFFFF, (overlay >> 16) & 0xFFFF);
        GL30.glEnableVertexAttribArray(0);
        GL30.glEnableVertexAttribArray(1);
        GL30.glEnableVertexAttribArray(2);
        GL30.glEnableVertexAttribArray(4);
        GL30.glEnableVertexAttribArray(5);

        if (iris)
        {
            GL30.glEnableVertexAttribArray(8);
            GL30.glEnableVertexAttribArray(9);
        }

        GL30.glDrawArrays(GL15.GL_TRIANGLES, 0, this.vertexCount);

        GL30.glDisableVertexAttribArray(0);
        GL30.glDisableVertexAttribArray(1);
        GL30.glDisableVertexAttribArray(2);
        GL30.glDisableVertexAttribArray(4);
        GL30.glDisableVertexAttribArray(5);

        if (iris)
        {
            GL30.glDisableVertexAttribArray(8);
            GL30.glDisableVertexAttribArray(9);
        }

        shader.unbind();
        GL30.glBindVertexArray(previousVao);
        mc.gameRenderer.getLightmapTextureManager().disable();
        mc.gameRenderer.getOverlayTexture().teardownOverlayColor();
    }

    /** Trees = the first {@code treeCount()} units of {@link #unitPivots()}. */
    public int treeCount()
    {
        return this.treeCount;
    }

    /** Unit pivots, xyz per unit (form-local). */
    public float[] unitPivots()
    {
        return this.unitPivot;
    }

    /** Average captured leaf tint per tree, rgb triplets. */
    public float[] treeLeafTints()
    {
        return this.treeLeafTint;
    }

    public void delete()
    {
        if (this.vao != -1)
        {
            GL30.glDeleteVertexArrays(this.vao);
            GL15.glDeleteBuffers(this.posBuffer);
            GL15.glDeleteBuffers(this.colorBuffer);
            GL15.glDeleteBuffers(this.uvBuffer);
            GL15.glDeleteBuffers(this.normalBuffer);
            GL15.glDeleteBuffers(this.tangentBuffer);
            GL15.glDeleteBuffers(this.midUvBuffer);
            GL15.glDeleteBuffers(this.lightBuffer);
            this.vao = -1;
        }
    }

    private static final class Provider implements VertexConsumerProvider
    {
        private final Recorder recorder;

        private Provider(Recorder recorder)
        {
            this.recorder = recorder;
        }

        @Override
        public VertexConsumer getBuffer(RenderLayer layer)
        {
            return this.recorder;
        }
    }

    /** Quad capture + triangulation, recentred per unit to its pivot (the PhysVAO recorder recipe). */
    private static final class Recorder implements VertexConsumer
    {
        private float[] qp = new float[1024 * 3];
        private float[] qc = new float[1024 * 4];
        private float[] qu = new float[1024 * 2];
        private float[] qn = new float[1024 * 3];
        private int[] ql = new int[1024 * 2];
        private int quadVerts;

        private float[] pos = new float[16384 * 3];
        private float[] col = new float[16384 * 4];
        private float[] uv = new float[16384 * 2];
        private float[] nrm = new float[16384 * 3];
        private int[] lit = new int[16384 * 2];
        private int tris;

        private float tx, ty, tz, tr = 1F, tg = 1F, tb = 1F, ta = 1F, tu, tv, tnx, tny, tnz = 1F;
        private int curLightBlock, curLightSky = 0xF0;
        private boolean pendingVertex;

        /** Per-block packed lightmap for the vertices recorded next; -1 = old save → sky-lit. */
        void setLight(int packed)
        {
            if (packed < 0)
            {
                this.curLightBlock = 0;
                this.curLightSky = 0xF0;
            }
            else
            {
                this.curLightBlock = packed & 0xFFFF;
                this.curLightSky = (packed >> 16) & 0xFFFF;
            }
        }

        void beginUnit()
        {
            this.quadVerts = 0;
            this.blockMark = 0;
            this.pendingVertex = false;
        }

        private int blockMark;

        /** Start of the CURRENT block's quads (for per-block face filtering). */
        void markBlock()
        {
            this.commit();
            this.blockMark = this.quadVerts;
        }

        /** Quads recorded by the current block so far. */
        int blockQuads()
        {
            this.commit();

            return (this.quadVerts - this.blockMark) / 4;
        }

        /** Keep-test per face: the quad's dominant NORMAL axis as a unit block offset. */
        interface FaceKeep
        {
            boolean keep(int dx, int dy, int dz);
        }

        /** Drop the current block's quads whose face fails {@code keep} (compacts in place). */
        void filterBlock(FaceKeep keep)
        {
            this.commit();

            int w = this.blockMark;

            for (int q = this.blockMark; q + 3 < this.quadVerts; q += 4)
            {
                float nx = this.qn[q * 3], ny = this.qn[q * 3 + 1], nz = this.qn[q * 3 + 2];
                float ax = Math.abs(nx), ay = Math.abs(ny), az = Math.abs(nz);
                int dx = 0, dy = 0, dz = 0;

                if (ax >= ay && ax >= az)
                {
                    dx = nx >= 0F ? 1 : -1;
                }
                else if (ay >= az)
                {
                    dy = ny >= 0F ? 1 : -1;
                }
                else
                {
                    dz = nz >= 0F ? 1 : -1;
                }

                if (!keep.keep(dx, dy, dz))
                {
                    continue;
                }

                if (w != q)
                {
                    System.arraycopy(this.qp, q * 3, this.qp, w * 3, 12);
                    System.arraycopy(this.qc, q * 4, this.qc, w * 4, 16);
                    System.arraycopy(this.qu, q * 2, this.qu, w * 2, 8);
                    System.arraycopy(this.qn, q * 3, this.qn, w * 3, 12);
                    System.arraycopy(this.ql, q * 2, this.ql, w * 2, 8);
                }

                w += 4;
            }

            this.quadVerts = w;
        }

        void endUnit(float cx, float cy, float cz)
        {
            this.commit();

            for (int q = 0; q + 3 < this.quadVerts; q += 4)
            {
                int[] order = {0, 1, 2, 2, 3, 0};

                for (int k : order)
                {
                    int i = q + k;

                    this.ensure();

                    int o = this.tris;

                    this.pos[o * 3] = this.qp[i * 3] - cx;
                    this.pos[o * 3 + 1] = this.qp[i * 3 + 1] - cy;
                    this.pos[o * 3 + 2] = this.qp[i * 3 + 2] - cz;
                    this.col[o * 4] = this.qc[i * 4];
                    this.col[o * 4 + 1] = this.qc[i * 4 + 1];
                    this.col[o * 4 + 2] = this.qc[i * 4 + 2];
                    this.col[o * 4 + 3] = this.qc[i * 4 + 3];
                    this.uv[o * 2] = this.qu[i * 2];
                    this.uv[o * 2 + 1] = this.qu[i * 2 + 1];
                    this.nrm[o * 3] = this.qn[i * 3];
                    this.nrm[o * 3 + 1] = this.qn[i * 3 + 1];
                    this.nrm[o * 3 + 2] = this.qn[i * 3 + 2];
                    this.lit[o * 2] = this.ql[i * 2];
                    this.lit[o * 2 + 1] = this.ql[i * 2 + 1];
                    this.tris++;
                }
            }

            this.quadVerts = 0;
        }

        private void ensure()
        {
            if (this.tris * 3 == this.pos.length)
            {
                this.pos = grow(this.pos);
                this.col = grow(this.col);
                this.uv = grow(this.uv);
                this.nrm = grow(this.nrm);
                this.lit = grow(this.lit);
            }
        }

        int triCount()
        {
            return this.tris;
        }

        float[] positions()
        {
            return java.util.Arrays.copyOf(this.pos, this.tris * 3);
        }

        float[] colors()
        {
            return java.util.Arrays.copyOf(this.col, this.tris * 4);
        }

        float[] uvs()
        {
            return java.util.Arrays.copyOf(this.uv, this.tris * 2);
        }

        float[] normals()
        {
            return java.util.Arrays.copyOf(this.nrm, this.tris * 3);
        }

        int[] lights()
        {
            return java.util.Arrays.copyOf(this.lit, this.tris * 2);
        }

        /* 1.20-era entry (abstract there; an extra method on 1.21) — hence no @Override. */
        public VertexConsumer vertex(double x, double y, double z)
        {
            return this.vertex((float) x, (float) y, (float) z);
        }

        /* 1.21-era entry (abstract there). A new vertex commits the pending one. */
        public VertexConsumer vertex(float x, float y, float z)
        {
            this.commit();
            this.tx = x;
            this.ty = y;
            this.tz = z;
            this.pendingVertex = true;

            return this;
        }

        @Override
        public VertexConsumer color(int red, int green, int blue, int alpha)
        {
            this.tr = red / 255F;
            this.tg = green / 255F;
            this.tb = blue / 255F;
            this.ta = alpha / 255F;

            return this;
        }

        @Override
        public VertexConsumer texture(float u, float v)
        {
            this.tu = u;
            this.tv = v;

            return this;
        }

        @Override
        public VertexConsumer overlay(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer light(int u, int v)
        {
            return this;
        }

        @Override
        public VertexConsumer normal(float x, float y, float z)
        {
            this.tnx = x;
            this.tny = y;
            this.tnz = z;

            return this;
        }

        /* 1.20-era finalizer; absent from the 1.21 interface — hence no @Override. */
        public void next()
        {
            this.commit();
        }

        private void commit()
        {
            if (!this.pendingVertex)
            {
                return;
            }

            if (this.quadVerts * 3 >= this.qp.length)
            {
                this.qp = grow(this.qp);
                this.qc = grow(this.qc);
                this.qu = grow(this.qu);
                this.qn = grow(this.qn);
                this.ql = grow(this.ql);
            }

            int i = this.quadVerts;

            this.qp[i * 3] = this.tx;
            this.qp[i * 3 + 1] = this.ty;
            this.qp[i * 3 + 2] = this.tz;
            this.qc[i * 4] = this.tr;
            this.qc[i * 4 + 1] = this.tg;
            this.qc[i * 4 + 2] = this.tb;
            this.qc[i * 4 + 3] = this.ta;
            this.qu[i * 2] = this.tu;
            this.qu[i * 2 + 1] = this.tv;
            this.qn[i * 3] = this.tnx;
            this.qn[i * 3 + 1] = this.tny;
            this.qn[i * 3 + 2] = this.tnz;
            this.ql[i * 2] = this.curLightBlock;
            this.ql[i * 2 + 1] = this.curLightSky;
            this.quadVerts++;
            this.pendingVertex = false;
        }

        private static float[] grow(float[] a)
        {
            float[] b = new float[a.length * 2];

            System.arraycopy(a, 0, b, 0, a.length);

            return b;
        }

        private static int[] grow(int[] a)
        {
            int[] b = new int[a.length * 2];

            System.arraycopy(a, 0, b, 0, a.length);

            return b;
        }

        /* 1.20-only interface members (gone in 1.21) — plain methods, no @Override. */
        public void fixedColor(int red, int green, int blue, int alpha)
        {
        }

        public void unfixColor()
        {
        }
    }
}
