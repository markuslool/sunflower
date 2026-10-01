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
 *   <li>Проекция без view-bob: при ходьбе тени могут ехать на пару пикселей.</li>
 *   <li>Настройка шага луча 2x2/4x4 вступит в v1.1 (coarse-таргет); сейчас full-res.</li>
 * </ul>
 */
public final class RtOverlay {
    /** mat4 + vec3 + vec3 + ivec3 + ivec3 + vec4 + vec4 по правилам std140. */
    private static final int FRAME_SIZE = 64 + 16 * 6;

    private static RenderPipeline pipeline;
    private static MappableRingBuffer frameUbo;
    private static GpuBuffer voxelBuf;
    private static GpuBuffer triBuf;
    private static volatile boolean ready;
    private static volatile String skipReason = "not initialized";
    private static volatile float lastStrength;
    private static volatile double lastSunX;
    private static volatile double lastSunY;
    private static volatile long framesDrawn;
    private static volatile Level lastLevel;
    /** 0 = тени, 1 = чернить найденные поверхности, 2 = весь экран -50% (проверка пасса). */
    private static volatile int debugMode;

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

    private static synchronized void ensureInit() {
        if (ready) {
            return;
        }
        GpuDevice device = RenderSystem.getDevice();
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
        frameUbo = new MappableRingBuffer(() -> "Sunflower RT frame", 130, FRAME_SIZE);
        VoxelVolume vol = RtBoot.volume();
        voxelBuf = device.createBuffer(() -> "Sunflower RT voxels",
                GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER | GpuBuffer.USAGE_MAP_WRITE, vol.bytesSize());
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
    }

    /**
     * Рисует оверлей. Вызывать в хвосте GameRenderer.renderLevel (render-поток).
     */
    public static void render(GameRenderer gameRenderer, DeltaTracker deltaTracker) {
        if (!RtBoot.isVulkanActive() || !RtBoot.config().enabled) {
            skipReason = "disabled";
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null || mc.player == null) {
            skipReason = "no level";
            return;
        }
        if (!level.dimension().equals(Level.OVERWORLD)) {
            skipReason = "not overworld";
            return;
        }
        if (lastLevel != level) {
            lastLevel = level;
            RtBoot.volume().resetForNewLevel();
            Sunflower.LOGGER.info("[sunflower-rt] new level, volume reset.");
        }
        ensureInit();

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
        float strength = 0.65F * dayF * rainK;
        lastStrength = strength;
        if (strength <= 0.01F) {
            skipReason = "night (sunY=" + String.format("%.2f", sunY) + ")";
            return;
        }

        // Воксели: recenter + бюджетная докачка + аплоад.
        VoxelVolume vol = RtBoot.volume();
        Vec3 camPos = cam.pos;
        // Оверворлд v1: фиксированные границы (-64 .. 320). Кастомные измерения — позже.
        vol.recenterSmart((int) Math.floor(camPos.x), (int) Math.floor(camPos.y), (int) Math.floor(camPos.z),
                -64, 320);
        int filled = vol.drain(level, Math.max(1, RtBoot.config().sectionsPerFrame));
        if (vol.consumeUploadDirty()) {
            uploadVoxels(vol);
        }

        // Кадр: inv(P*V), камера, солнце, бокс, параметры.
        RtConfig cfg = RtBoot.config();
        Matrix4f invVp = new Matrix4f(cam.projectionMatrix).mul(cam.viewRotationMatrix).invert();
        int fw = grs.windowRenderState.width;
        int fh = grs.windowRenderState.height;
        GpuBuffer frame = frameUbo.currentBuffer();
        try (GpuBufferSlice.MappedView view = frame.map(false, true)) {
            Std140Builder.intoBuffer(view.data())
                    .putMat4f(invVp)
                    .putVec3((float) camPos.x, (float) camPos.y, (float) camPos.z)
                    .putVec3((float) sunX, (float) sunY, 0.0F)
                    .putIVec3(vol.originX(), vol.originY(), vol.originZ())
                    .putIVec3(vol.width(), vol.height(), vol.depth())
                    .putVec4((float) cfg.shadowDistance, (float) cfg.maxSteps, strength, 160.0F)
                    .putVec4((float) fw, (float) fh, (float) debugMode, 0.0F);
        }

        GpuDevice device = RenderSystem.getDevice();
        CommandEncoder encoder = device.createCommandEncoder();
        GpuBufferSlice triSlice = triBuf.slice();
        try (RenderPass pass = encoder.createRenderPass(() -> "Sunflower RT",
                gameRenderer.mainRenderTarget().getColorTextureView(), Optional.empty())) {
            pass.setPipeline(pipeline);
            pass.setUniform("RtFrame", frame);
            pass.setUniform("Voxels", voxelBuf);
            pass.setVertexBuffer(0, triSlice);
            pass.draw(3, 1, 0, 0);
        }
        frameUbo.rotate();
        framesDrawn++;
        skipReason = "drawing (fill=" + (int) (vol.fillFraction() * 100) + "% +" + filled + " sections)";
    }

    private static void uploadVoxels(VoxelVolume vol) {
        byte[] flat = vol.snapshotFlat();
        try (GpuBufferSlice.MappedView view = voxelBuf.map(false, true)) {
            ByteBuffer buf = view.data();
            buf.position(0);
            buf.put(flat);
        } catch (Exception e) {
            Sunflower.LOGGER.warn("[sunflower-rt] voxel upload failed: {}", e.toString());
        }
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
