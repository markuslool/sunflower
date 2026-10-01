package com.sunflower.client.rt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.BlendFactor;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.sunflower.Sunflower;
import java.nio.ByteBuffer;
import java.util.Optional;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.state.GameRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * Оверлей RT-теней v1: fullscreen-треугольник с DDA в фрагменте.
 *
 * <p>Все через поддерживаемый ванильный API (никакого сырого Vulkan):
 * кастомный RenderPipeline + UNIFORM_BUFFER кадра + TEXEL_BUFFER вокселей
 * + multiply-blend (ZERO, SRC_COLOR) поверх мира. Вызывается в хвосте
 * GameRenderer.renderLevel — мир уже нарисован (включая Sodium), GUI еще нет.
 *
 * <p>Известные артефакты v1 (честно):
 * <ul>
 *   <li>Рука/сущности затемняются как фон (их нет в воксельном объеме).</li>
 *   <li>Матрица лучей useBob=1 повторяет ваниллу один в один; если террейн рисует
 *       Sodium без боба — тени плывут в такт шагам, лечится /sunflower bob 0.</li>
 *   <li>Шаг луча 2x2/4x4 — квантование центра пикселя (ResPad.w); фрагмент всё ещё
 *       выполняется на пиксель, выигрыш — за счёт когерентности лучей, не 4x/16x.</li>
 * </ul>
 */
public final class RtOverlay {
    /** mat4 + vec3 + vec3 + ivec3 + ivec3 + vec4 + vec4 + vec4 по правилам std140. */
    private static final int FRAME_SIZE = 64 + 16 * 7;

    /**
     * usage воксельного кольца: UNIFORM_TEXEL_BUFFER(256) | MAP_WRITE(2) |
     * COPY_SRC(16) | COPY_DST(8) = 282.
     * COPY_SRC/DST нужны для device-copy предыдущего слота в новый при частичной
     * загрузке — см. комментарий у voxelRing.
     */
    private static final int VOXEL_USAGE = 256 | 2 | 16 | 8;

    /** usage буфера кадра: UNIFORM(128) | MAP_WRITE(2) = 130. */
    private static final int FRAME_USAGE = 128 | 2;

    private static RenderPipeline pipeline;
    private static MappableRingBuffer frameUbo;
    /**
     * Воксели в КОЛЬЦЕ на 3 слота. usage = TEXEL_BUFFER(256) | MAP_WRITE(2) |
     * COPY_SRC(16) | COPY_DST(8) = 282.
     *
     * <p>COPY_SRC/COPY_DST обязательны: при частичной записи новый слот кольца
     * содержит данные трёх загрузок назад, поэтому перед патчем мы копируем в
     * него предыдущий (актуальный) слот целиком на стороне GPU. Без этой копии
     * негрязные строки в новом слоте оказываются чужими — шейдер рисует по смеси
     * свежего и устаревшего, и картинка мерцает синхронно с аплоадами.
     */
    private static MappableRingBuffer voxelRing;
    private static GpuBuffer triBuf;
    private static volatile boolean ready;
    private static volatile String skipReason = "not initialized";
    private static volatile float lastStrength;
    private static volatile double lastSunX;
    private static volatile double lastSunY;
    private static volatile long framesDrawn;
    /** Предыдущий уровень для детекта смены мира. WeakReference чтобы не держать ClientLevel в памяти после выхода. */
    private static volatile java.lang.ref.WeakReference<Level> lastLevelRef;
    /** Счётчик кадров для троттлинга полных аплоадов 3.5МБ во время активной докачки. */
    private static volatile long uploadThrottleCounter;
    /** Диагностика для /sunflower status: сколько секций долито в прошлом кадре. */
    private static volatile int lastFilled;
    /** Диагностика: ячеек L2 пересчитано в последнем снапшоте (должно быть мало, не 55296). */
    private static volatile int lastMipRecomputed;
    /** Диагностика: байт в последнем аплоаде (должно быть мало, а не 3.5МБ). */
    private static volatile int lastUploadedBytes;
    /** 0 = тени, 1 = чернить найденные поверхности, 2 = весь экран -50% (проверка пасса). */
    private static volatile int debugMode;
    /**
     * 0 = лучи по базовой проекции, 1 = с ванильным view-bob (bobHurt/bobView).
     * Дефолт 1: доказан байткодом renderLevel 26.2 — ванилла сама считает
     * levelProj = P*bob и рендерит террейн через (P*bob)*Vrot, миксин повторяет
     * ту же последовательность один в один. 0 нужен только если террейн рисует
     * Sodium/шейдер со своей копией матриц без боба — сверяется командой
     * /sunflower bob на ходу: при правильной матрице тени стоят, при чужой —
     * плывут в такт шагам. Портально-тошнотный спин не повторяем (редкий кейс).
     */
    private static volatile int useBob = 1;

    private RtOverlay() {}

    public static boolean isReady() {
        return ready;
    }

    public static String skipReason() {
        return skipReason;
    }

    public static float lastStrength() {
        return lastStrength;
    }

    public static long framesDrawn() {
        return framesDrawn;
    }

    public static double lastSunX() {
        return lastSunX;
    }

    public static double lastSunY() {
        return lastSunY;
    }

    public static int debugMode() {
        return debugMode;
    }

    public static void setDebugMode(int mode) {
        debugMode = Math.max(0, Math.min(2, mode));
        Sunflower.LOGGER.warn("[sunflower-rt] debug mode = {}", debugMode);
    }

    public static int useBob() {
        return useBob;
    }

    /** Диагностика докачки: секций долито в прошлом кадре. */
    public static int lastFilled() {
        return lastFilled;
    }

    /** Ячеек L2 пересчитано в последнем снапшоте (диагностика инкрементальности). */
    public static int lastMipRecomputed() {
        return lastMipRecomputed;
    }

    /** Байт в последнем аплоаде объёма (диагностика частичной загрузки). */
    public static int lastUploadedBytes() {
        return lastUploadedBytes;
    }

    public static void setUseBob(int mode) {
        useBob = mode != 0 ? 1 : 0;
        Sunflower.LOGGER.warn("[sunflower-rt] useBob = {} ({})", useBob,
                useBob != 0 ? "с view-bob (как террейн ваниллы)" : "базовая проекция (для Sodium без боба)");
        // Персист выбора, чтобы не сбрасывался каждый рестарт.
        try {
            RtBoot.config().useBob = useBob;
            RtBoot.saveConfig();
        } catch (Exception e) {
            Sunflower.LOGGER.debug("[sunflower-rt] useBob persist failed: {}", e.toString());
        }
    }

    /** Применить значение из конфига без сохранения (при старте). */
    static void syncBobFromConfig(int mode) {
        useBob = mode != 0 ? 1 : 0;
    }

    private static synchronized void ensureInit() {
        if (ready) {
            return;
        }
        try {
            GpuDevice device = RenderSystem.getDevice();
            // DepthStencilState не задаём намеренно: отсутствие = без depth-теста,
            // оверлей обязан лечь поверх мира на fullscreen-треугольнике.
            pipeline = RenderPipeline.builder()
                .withLocation(Sunflower.id("pipeline/rt_overlay"))
                .withVertexShader(Sunflower.id("core/rt_overlay"))
                .withFragmentShader(Sunflower.id("core/rt_overlay"))
                .withBindGroupLayout(BindGroupLayout.builder()
                        .withUniform("RtFrame", UniformType.UNIFORM_BUFFER)
                        .withUniform("Voxels", UniformType.TEXEL_BUFFER, GpuFormat.R8_UINT)
                        .build())
                .withColorTargetState(new ColorTargetState(
                        new BlendFunction(BlendFactor.ZERO, BlendFactor.SRC_COLOR, BlendFactor.ZERO, BlendFactor.ONE)))
                .withVertexBinding(0, DefaultVertexFormat.POSITION)
                .withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
                .withCull(false)
                .build();
        frameUbo = new MappableRingBuffer(() -> "Sunflower RT frame", FRAME_USAGE, FRAME_SIZE);
        VoxelVolume vol = RtBoot.volume();
        voxelRing = new MappableRingBuffer(() -> "Sunflower RT voxels", VOXEL_USAGE, (int) vol.bytesSize());
        triBuf = device.createBuffer(() -> "Sunflower RT triangle",
                GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, 36L);
        try (GpuBufferSlice.MappedView view = triBuf.map(false, true)) {
            ByteBuffer buf = view.data();
            buf.putFloat(-1.0F).putFloat(-1.0F).putFloat(0.0F);
            buf.putFloat(3.0F).putFloat(-1.0F).putFloat(0.0F);
            buf.putFloat(-1.0F).putFloat(3.0F).putFloat(0.0F);
        }
        ready = true;
        Sunflower.LOGGER.info("[sunflower-rt] overlay pipeline ready (frameUbo={}B, voxels={}B).",
                FRAME_SIZE, vol.bytesSize());
        } catch (Exception e) {
            ready = false;
            pipeline = null;
            Sunflower.LOGGER.warn("[sunflower-rt] overlay init failed, retry next frame: {}", e.toString());
        }
    }

    /**
     * Рисует оверлей. Вызывать в хвосте GameRenderer.renderLevel (render-поток).
     *
     * @param levelProj проекция УЖЕ с view-bob (миксин повторяет ванильные
     *                  bobHurt/bobView) — лучи совпадают с отрендеренным кадром.
     */
    public static void render(GameRenderer gameRenderer, DeltaTracker deltaTracker, Matrix4f levelProj) {
        if (!RtBoot.isVulkanActive() || !RtBoot.config().enabled) {
            skipReason = "disabled";
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            skipReason = "no level";
            lastLevelRef = null;
            return;
        }
        // Измерение больше не ограничено оверворлдом: работает и в Нижнем мире, и в
        // Эндере, и в мод-измерениях. Без солнца (hasSkylight == false) солнечные
        // тени бессмысленны — там просто гасим пасс, а не отказываемся от RT целиком.
        boolean hasSky = true;
        try {
            hasSky = level.dimensionType().hasSkyLight();
        } catch (Exception ignored) {
            // экзотическая DimensionType без метода — считаем, что небо есть
        }
        if (!hasSky) {
            skipReason = "no skylight in this dimension";
            return;
        }
        Level prev = lastLevelRef != null ? lastLevelRef.get() : null;
        if (prev != level) {
            lastLevelRef = new java.lang.ref.WeakReference<>(level);
            RtBoot.volume().resetForNewLevel();
            Sunflower.LOGGER.info("[sunflower-rt] new level, volume reset.");
        }
        ensureInit();
        if (!ready || pipeline == null || frameUbo == null || voxelRing == null) {
            skipReason = "pipeline init failed";
            return;
        }

        GameRenderState grs = gameRenderer.gameRenderState();
        CameraRenderState cam = grs.levelRenderState.cameraRenderState;
        SkyRenderState sky = grs.levelRenderState.skyRenderState;

        // Солнце по ванильному углу (тот же, по которому рисуется диск солнца).
        double a = sky.sunAngle;
        double sunX = Math.sin(a);
        double sunY = Math.cos(a);
        lastSunX = sunX;
        lastSunY = sunY;
        float dayF = clamp((float) (sunY + 0.08) * 3.0F, 0.0F, 1.0F);
        float rainK = 0.35F + 0.65F * clamp(sky.rainBrightness, 0.0F, 1.0F);
        float strength = RtBoot.config().shadowStrength * dayF * rainK;
        lastStrength = strength;
        if (strength <= 0.01F) {
            skipReason = "night (sunY=" + String.format("%.2f", sunY) + ")";
            return;
        }

        // Воксели: recenter + бюджетная докачка + редкий аплоад.
        VoxelVolume vol = RtBoot.volume();
        Vec3 camPos = cam.pos;
        // Границы бокса берём у реального уровня, а не хардкод -64..320:
        // в Нижнем мире это 0..128, в мод-измерениях — вообще что угодно.
        int worldMinY = level.getMinY();
        int worldMaxY = level.getMaxY();
        vol.recenterSmart((int) Math.floor(camPos.x), (int) Math.floor(camPos.y), (int) Math.floor(camPos.z),
                worldMinY, worldMaxY);
        // Адаптивный бюджет: базовый из конфига + догоняющий при bulk-правках (/fill):
        // большую очередь разбираем ~за 30 кадров без вечных спайков (кап 24/кадр).
        RtConfig cfg = RtBoot.config();
        int baseBudget = Math.max(1, cfg.sectionsPerFrame);
        int catchUp = vol.queuedSections() / 30;
        int budget = Math.min(24, Math.max(baseBudget, catchUp));
        // Секция игрока — приоритет №1 в очереди докачки: после /fill или загрузки
        // чанка именно под ногами воксели обновляются первыми.
        int playerSec = (int) Math.floor(camPos.x) >> 4;
        int filled = vol.drain(level, budget, playerSec);
        lastFilled = filled;

        // Аплоад 3.5МБ — САМАЯ дорогая операция кадра (memcpy + fence на Kepler).
        // Раньше он делался каждый кадр, пока очередь не пуста, и кадр проседал ->
        // "мерцает при беге". Теперь: не чаще раза в 2 кадра в покое и раз в 6
        // при большой очереди (плюс сам снапшот считаем не чаще, чем аплоад).
        if (vol.consumeUploadDirty()) {
            uploadThrottleCounter++;
            // Сдвиг базы НЕ форсируем аплоад: 3.5МБ + fence на каждый сдвиг = жесткая
            // просадка ("все дергается"). В uniform идет uploadedOrigin — реально
            // залитая база, поэтому кадр всегда согласован; просто после сдвига
            // пара секунд видно границу старого бокса, и она уезжает сама.
            int minGap = vol.queuedSections() > 100 ? 6 : 2;
            if (uploadThrottleCounter % minGap == 1) {
                uploadVoxels(vol);
            }
            // Флаг НЕ сбрасываем здесь: сбросит сам uploadVoxels после успеха.
        }

        // Кадр: inv(P*V), камера, солнце, бокс, параметры.
        // Шагов принудительно >= 2x дистанции: диагональ ест ~1.73 вокселя/блок,
        // иначе длинные лучи обрываются раньше препятствия и свет протекает.
        // (Защита от старого конфига, где maxSteps мог остаться 64 при dist 64.)
        int effSteps = RtBoot.effectiveSteps(cfg);
        // До первого успешного аплоада в GPU нет валидной базы объема — рисовать
        // нечем, иначе шейдер читал бы нули с Origin=(0,0,0).
        if (!vol.uploadedOriginValid()) {
            skipReason = "waiting first voxel upload";
            return;
        }
        Matrix4f invVp = new Matrix4f(levelProj).mul(cam.viewRotationMatrix).invert();
        // gl_FragCoord — в пикселях текущего таргета: берём размер mainRenderTarget,
        // windowRenderState может отличаться при GUI-scale/render-scale (иначе лучи едут).
        int fw = grs.windowRenderState.width;
        int fh = grs.windowRenderState.height;
        try {
            fw = gameRenderer.mainRenderTarget().width;
            fh = gameRenderer.mainRenderTarget().height;
        } catch (Exception ignored) {
            // fallback на windowRenderState выше
        }
        GpuBuffer frame = frameUbo.currentBuffer();
        try (GpuBufferSlice.MappedView view = frame.map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(invVp)
                    .putVec3((float) camPos.x, (float) camPos.y, (float) camPos.z)
                    .putVec3((float) sunX, (float) sunY, 0.0F)
                    .putIVec3(vol.uploadedOriginX(), vol.uploadedOriginY(), vol.uploadedOriginZ())
                    .putIVec3(vol.width(), vol.height(), vol.depth())
                    .putVec4((float) cfg.shadowDistance, (float) effSteps, strength,
                        // Дальность primary-марша привязана к дальности тени, а не к
                        // захардкоженным 160: иначе при shadowDistance=96 пиксели за
                        // 96 блоками получали лучи без теней, а при 160 марш упирался
                        // в лимит итераций.
                        Math.max(160.0F, (float) cfg.shadowDistance + 32.0F))
                    .putVec4((float) fw, (float) fh, (float) debugMode, (float) cfg.rayStride)
                    // x: время для анимации облаков, y: угловой радиус солнца,
                    // z: тапов мягкости, w: облачные тени
                    .putVec4((float) (System.nanoTime() / 1_000_000_000.0 % 3600.0),
                            0.035F,
                            (float) (cfg.softShadows == 0 ? 1 : cfg.softShadows == 1 ? 4 : 8),
                            cfg.cloudShadows ? 1.0F : 0.0F);
        }

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        GpuBufferSlice triSlice = triBuf.slice();
        try (RenderPass pass = encoder.createRenderPass(() -> "Sunflower RT",
                gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("RtFrame", frame);
            pass.setUniform("Voxels", voxelRing.currentBuffer());
            pass.setVertexBuffer(0, triSlice);
            pass.draw(3, 1, 0, 0);
        }
        frameUbo.rotate();
        framesDrawn++;
        skipReason = "drawing (fill=" + (int) (vol.fillFraction() * 100) + "% +" + filled + " sections)";
    }

    private static void uploadVoxels(VoxelVolume vol) {
        // Частичная загрузка: только грязные строки Y (+ L2), одним непрерывным
        // срезом. В покое и при ходьбе это десятки КБ вместо 3.5 МБ.
        try {
            GpuBuffer prevSlot = voxelRing.currentBuffer();
            boolean fullWrite = vol.nextUploadIsFull();
            voxelRing.rotate();
            GpuBuffer target = voxelRing.currentBuffer();
            if (!fullWrite && prevSlot != target) {
                // Новый слот кольца содержит данные трёх загрузок назад. Перед
                // частичным патчем копируем в него предыдущий (актуальный) слот
                // целиком — копирование device-side, без PCIe. Иначе негрязные
                // строки остались бы чужими (был баг: тени мерцали при подгрузке
                // территорий, синхронно с аплоадами).
                long size = Math.min(prevSlot.size(), target.size());
                CommandEncoder enc = RenderSystem.getDevice().createCommandEncoder();
                enc.copyToBuffer(new GpuBufferSlice(prevSlot, 0, size),
                        new GpuBufferSlice(target, 0, size));
                enc.submit();
            }
            if (vol.uploadDirtyRange(target)) {
                // Флаг гасим ТОЛЬКО здесь: если map/put упал, данные докачаются позже.
                vol.markUploaded();
                lastUploadedBytes = vol.lastUploadedBytes();
                // Читаем ПОСЛЕ аплоада: раньше счётчик брался до пересборки, и в
                // статусе L2 всегда выглядел как "0 ячеек" (= небо, теней нет).
                lastMipRecomputed = vol.mipRecomputedCount();
            }
        } catch (Exception e) {
            Sunflower.LOGGER.warn("[sunflower-rt] voxel upload failed: {}", e.toString());
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
