package com.sunflower.client.rt;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.DepthStencilState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.CompareOp;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderPassDescriptor;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.sunflower.Sunflower;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.OptionalDouble;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

/**
 * Растеризованные тени: depth-only проходы клипмапы по реальной геометрии чанков.
 *
 * <p>Схема кадра:
 * <ol>
 *   <li>Для каждого каскада берём ортокамеру света из {@link ShadowClipmap}.</li>
 *   <li>Секции из {@code LevelRenderer.visibleSections()} отсекаем на CPU по AABB
 *       против орто-бокса каскада (видимость + «кэш кастеров» в одном лице).</li>
 *   <li>Прошедшие секции рисуем в depth-атлас реальными GPU-буферами чанков:
 *       {@code SectionRenderDispatcher.getRenderSectionSlice()} отдаёт их напрямую,
 *       читать геометрию на CPU не нужно.</li>
 *   <li>Основной пасс {@code rt_overlay.fsh} читает атлас сам и домножает картинку.</li>
 * </ol>
 *
 * <p>Все каскады лежат в ОДНОЙ текстуре 2x2. Так нужен ровно один сэмплер и одно
 * чтение на каскад — на слабом GPU лишние биндинги дороже, чем сам сэмпл.
 *
 * <p>Глубина кладётся ортографической проекцией, а значит {@code ndc.z} линейно
 * по глубине вдоль взгляда света. Обе стороны сравнивают линейные глубины в
 * мировых единицах, поэтому bias задаётся в блоках и не зависит от near/far.
 *
 * <p>Compute-шейдеров в Blaze3D 26.2 нет вообще ({@code ShaderType} = VERTEX |
 * FRAGMENT), поэтому отсечение и сборка списка кастеров — на CPU. Это не
 * «GPU-driven rendering», но интерфейс совпадает: отсечённые секции идут в
 * {@code drawIndexed}, а не рисуются все подряд.
 */
public final class RtShadowMap {
    /** Смещение между слотами uniform-буфера, привязываемыми через slice. */
    private static final int SLOT_STRIDE = 256;
    /** Размер одного слота в байтах: mat4 + 4 vec4. */
    private static final int SLOT_BYTES = 64 + 4 * 16;
    /** Байт в матричном UBO каскадов оверлея: 4 * (mat4 + 4 * vec4). */
    private static final int CASCADE_UBO_BYTES = 4 * (64 + 4 * 16);
    /** usage UBO: UNIFORM(128) | MAP_WRITE(2). */
    private static final int UBO_USAGE = 128 | 2;
    /** Слотов в атласе: сетка 2x2 под MAX_CASCADES. */
    private static final int ATLAS_GRID = 2;

    private static GpuTexture atlas;
    private static GpuTextureView atlasView;
    private static GpuSampler pointSampler;
    private static RenderPipeline depthPipeline;
    private static com.mojang.blaze3d.buffers.GpuBuffer perDrawUbo;
    private static com.mojang.blaze3d.buffers.GpuBuffer cascadeUbo;
    private static int atlasRes;
    private static int slotCount;

    private static volatile boolean ready;
    private static volatile String skipReason = "not initialized";
    /** Диагностика: сколько секций отрисовано в прошлом кадре. */
    private static volatile int lastCastersDrawn;
    /** Диагностика: сколько секций отсеяно по AABB. */
    private static volatile int lastCastersCulled;
    /** Диагностика: сколько секций рассмотрено. */
    private static volatile int lastCastersSeen;

    private RtShadowMap() {}

    public static boolean isReady() {
        return ready;
    }

    public static String skipReason() {
        return skipReason;
    }

    public static int lastCastersDrawn() {
        return lastCastersDrawn;
    }

    public static int lastCastersCulled() {
        return lastCastersCulled;
    }

    public static int lastCastersSeen() {
        return lastCastersSeen;
    }

    /** UBO со всеми каскадами — его читает основной пасс оверлея. */
    public static com.mojang.blaze3d.buffers.GpuBufferSlice cascadeUboSlice() {
        return ready && cascadeUbo != null
                ? new com.mojang.blaze3d.buffers.GpuBufferSlice(cascadeUbo, 0, CASCADE_UBO_BYTES)
                : null;
    }

    /** Текстура карт теней для биндинга в основной пасс (null, если не готово). */
    public static GpuTextureView atlasView() {
        return ready ? atlasView : null;
    }

    public static GpuSampler sampler() {
        return ready ? pointSampler : null;
    }

    /** Число каскадов, реально записанных в UBO в прошлом кадре. */
    public static int cascadeCount() {
        return ready ? slotCount : 0;
    }

    /**
     * Растеризовать карты теней всех каскадов.
     *
     * @param encoder   уже открытый энкодер кадра
     * @param cascades  раскладка каскадов
     * @param cfg       конфиг (разрешение, число каскадов)
     * @return true, если карты перерисованы
     */
    public static boolean render(CommandEncoder encoder, ShadowClipmap.Cascade[] cascades, RtConfig cfg) {
        if (!RtBoot.isVulkanActive()) {
            skipReason = "not Vulkan";
            return false;
        }
        int want = Math.min(cfg.cascades, ShadowClipmap.MAX_CASCADES);
        if (want < 1 || cascades == null || cascades.length < want) {
            skipReason = "clipmap off";
            return false;
        }
        try {
            ensureInit(cfg.clipmapResolution, want);
            if (!ready) {
                return false;
            }

            float[] sun = RtOverlay.lastSunDirection();
            float[] right = new float[3];
            float[] up = new float[3];
            float[] fwd = new float[3];
            ShadowClipmap.lightBasis(sun, right, up, fwd);

            int res = atlasRes;
            // 1) Пишем матрицы всех каскадов в оба буфера: в per-draw (для растра)
            //    и в общий (для чтения в основном пассе).
            ByteBuffer perDraw = perDrawUbo.map(false, true).data();
            ByteBuffer all = cascadeUbo.map(false, true).data();
            // Матрицы пишем через FloatBuffer: Matrix4f.get(int, FloatBuffer) —
            // единственный API записи с абсолютным смещением. Слот per-draw
            // выровнен по 256 (требование динамического смещения), общий — по 128.
            FloatBuffer perDrawF = perDraw.asFloatBuffer();
            FloatBuffer allF = all.asFloatBuffer();
            for (int i = 0; i < want; i++) {
                ShadowClipmap.Cascade c = cascades[i];
                float ex = c.cx - fwd[0] * c.depthRange;
                float ey = c.cy - fwd[1] * c.depthRange;
                float ez = c.cz - fwd[2] * c.depthRange;
                Matrix4f view = new Matrix4f().lookAt(ex, ey, ez,
                        c.cx, c.cy, c.cz, up[0], up[1], up[2]);
                Matrix4f ortho = new Matrix4f().ortho(-c.radius, c.radius, -c.radius, c.radius,
                        0.0F, c.depthRange);
                Matrix4f vp = ortho.mul(view);

                int base = i * SLOT_STRIDE;
                vp.get(base / 4, perDrawF);
                writeSlotInfo(perDraw, base, i, c, ex, ey, ez, fwd, res);
                int packed = i * SLOT_BYTES;
                vp.get(packed / 4, allF);
                writeSlotInfo(all, packed, i, c, ex, ey, ez, fwd, res);
            }
            // Неиспользуемые слоты обнуляем: иначе шейдер прочитает прошлый кадр.
            for (int i = want; i < ShadowClipmap.MAX_CASCADES; i++) {
                int from = i * SLOT_BYTES;
                for (int b = from; b < from + SLOT_BYTES; b += 4) {
                    all.putInt(b, 0);
                }
            }

            // 2) Рисуем кастеров в атлас. Один проход, на каскад — свой scissor.
            LevelRenderer lr = Minecraft.getInstance().levelRenderer;
            SectionRenderDispatcher dispatcher = lr != null ? lr.sectionRenderDispatcher() : null;
            lastCastersSeen = 0;
            lastCastersCulled = 0;
            lastCastersDrawn = 0;
            if (dispatcher == null) {
                skipReason = "no section dispatcher";
                return false;
            }

            RenderPassDescriptor desc = RenderPassDescriptor.create(() -> "Sunflower shadow")
                    .withUnusedColorAttachment()
                    .withDepthAttachment(atlasView, OptionalDouble.of(1.0));
            try (RenderPass pass = encoder.createRenderPass(desc)) {
                pass.setPipeline(depthPipeline);
                for (int i = 0; i < want; i++) {
                    ShadowClipmap.Cascade c = cascades[i];
                    int tx = (i % ATLAS_GRID) * res;
                    int ty = (i / ATLAS_GRID) * res;
                    pass.enableScissor(tx, ty, res, res);
                    pass.setUniform("RtShadowFrame",
                            new com.mojang.blaze3d.buffers.GpuBufferSlice(perDrawUbo,
                                    i * SLOT_STRIDE, SLOT_BYTES));
                    drawCasters(pass, lr, dispatcher, c, fwd);
                }
            }
            slotCount = want;
            skipReason = "drawing (" + lastCastersDrawn + " casters)";
            return true;
        } catch (Exception e) {
            ready = false;
            skipReason = "render failed: " + e;
            Sunflower.LOGGER.warn("[sunflower-rt] shadow raster failed: {}", e.toString());
            return false;
        }
    }

    /** Заливка info-слотов каскада: eye, depthRange, тексель, разрешение, дистанции. */
    private static void writeSlotInfo(ByteBuffer buf, int base, int index, ShadowClipmap.Cascade c,
            float ex, float ey, float ez, float[] fwd, int res) {
        int p = base + 64;
        putVec4(buf, p, ex, ey, ez, c.orthoWidth);
        putVec4(buf, p + 16, c.depthRange, c.texelWorldSize, (float) res, 0.0F);
        putVec4(buf, p + 32, c.nearDist, c.farDist, 0.0F, 0.0F);
        putVec4(buf, p + 48, fwd[0], fwd[1], fwd[2], 0.0F);
    }

    private static void putVec4(ByteBuffer buf, int at, float x, float y, float z, float w) {
        buf.putFloat(at, x).putFloat(at + 4, y).putFloat(at + 8, z).putFloat(at + 12, w);
    }

    /** Рисует пересекающиеся с орто-боксом каскада секции слоя SOLID. */
    private static void drawCasters(RenderPass pass, LevelRenderer lr, SectionRenderDispatcher dispatcher,
            ShadowClipmap.Cascade c, float[] fwd) {
        float minX = c.cx - c.radius;
        float maxX = c.cx + c.radius;
        float minY = c.cy - c.radius;
        float maxY = c.cy + c.radius;
        float minZ = c.cz - c.radius;
        float maxZ = c.cz + c.radius;

        for (Object obj : lr.visibleSections()) {
            lastCastersSeen++;
            if (!(obj instanceof SectionRenderDispatcher.RenderSection section)) {
                continue;
            }
            // CPU-отсечение по AABB: и видимость, и «кэш кастеров» в одном месте.
            AABB box = section.getBoundingBox();
            if (box == null || box.maxX < minX || box.minX > maxX
                    || box.maxY < minY || box.minY > maxY
                    || box.maxZ < minZ || box.minZ > maxZ) {
                lastCastersCulled++;
                continue;
            }
            SectionMesh mesh = section.getSectionMesh();
            if (mesh == null) {
                continue;
            }
            SectionMesh.SectionDraw draw = mesh.getSectionDraw(ChunkSectionLayer.SOLID);
            if (draw == null || draw.indexCount() <= 0) {
                continue;
            }
            SectionRenderDispatcher.RenderSectionBufferSlice slice =
                    dispatcher.getRenderSectionSlice(mesh, ChunkSectionLayer.SOLID);
            if (slice == null) {
                continue;
            }
            try {
                pass.setVertexBuffer(0, new com.mojang.blaze3d.buffers.GpuBufferSlice(
                        slice.vertexBuffer(), slice.vertexBufferOffset(), Integer.MAX_VALUE));
                pass.setIndexBuffer(slice.indexBuffer(), draw.indexType());
                pass.drawIndexed(draw.indexCount(), 1, 0, 0, 0);
                lastCastersDrawn++;
            } catch (Exception e) {
                // Одна плохая секция не должна ронять весь проход теней.
                Sunflower.LOGGER.debug("[sunflower-rt] skip caster: {}", e.toString());
            }
        }
    }

    private static synchronized void ensureInit(int resolution, int cascades) {
        GpuDevice device = RenderSystem.getDevice();
        if (ready && resolution == atlasRes && cascades <= slotCount) {
            return;
        }
        if (ready && resolution == atlasRes) {
            return;
        }
        // Разрешение изменилось — пересоздаём атлас и пайплайн.
        close();
        atlasRes = resolution;
        slotCount = cascades;
        int size = resolution * ATLAS_GRID;
        atlas = device.createTexture(() -> "Sunflower shadow atlas",
                GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING,
                GpuFormat.D32_FLOAT, size, size, 1, 1);
        atlasView = device.createTextureView(atlas);
        pointSampler = device.createSampler(AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());
        perDrawUbo = device.createBuffer(() -> "Sunflower shadow frames",
                UBO_USAGE, (long) SLOT_STRIDE * ShadowClipmap.MAX_CASCADES);
        cascadeUbo = device.createBuffer(() -> "Sunflower cascades",
                UBO_USAGE, CASCADE_UBO_BYTES);

        // Depth-only пайплайн. Цветового выхода нет, поэтому с ColorTargetState
        // нельзя — слот 0 объявляем неиспользуемым, depth пишется всегда.
        depthPipeline = RenderPipeline.builder()
                .withLocation(Sunflower.id("pipeline/rt_shadow"))
                .withVertexShader(Sunflower.id("core/rt_shadow"))
                .withFragmentShader(Sunflower.id("core/rt_shadow"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("RtShadowFrame", UniformType.UNIFORM_BUFFER)
                        .build())
                .withUnusedColorTargetState(0)
                .withDepthStencilState(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true))
                .withVertexBinding(0, ChunkSectionLayer.SOLID.vertexFormat())
                .withCull(false)
                .build();
        ready = true;
        Sunflower.LOGGER.info("[sunflower-rt] shadow raster ready ({}x{} atlas, {} cascades).",
                size, size, ShadowClipmap.MAX_CASCADES);
    }

    private static void close() {
        ready = false;
        atlas = null;
        atlasView = null;
        pointSampler = null;
        depthPipeline = null;
        perDrawUbo = null;
        cascadeUbo = null;
        slotCount = 0;
    }
}