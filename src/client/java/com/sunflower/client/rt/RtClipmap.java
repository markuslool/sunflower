package com.sunflower.client.rt;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/**
 * Сборка раскладки каскадов клипмапа из конфига и состояния камеры.
 *
 * <p>Тонкий мост над {@link ShadowClipmap}: берёт у камеры FOV и направление
 * взгляда, у конфига — число каскадов, разрешение и дальность, и отдаёт готовый
 * массив {@code Cascade}. Сама математика намеренно остаётся в
 * {@link ShadowClipmap} без единого импорта Minecraft, чтобы её покрывали
 * юнит-тесты — здесь только дёргаем игровые API.
 */
public final class RtClipmap {
    /**
     * Практический split scheme (Lauritzen 2014) — смесь логарифмического и
     * равномерного деления. 0.7 — общепринятое значение: чисто логарифмическое
     * отдаёт слишком много карты ближнему каскаду и оставляет дальний в одном
     * текселе на всю округу.
     */
    public static final float SPLIT_LAMBDA = 0.7F;

    private RtClipmap() {}

    /** Раскладка каскадов для текущей камеры и конфига. */
    public static ShadowClipmap.Cascade[] layout(RtConfig cfg, float camX, float camY, float camZ) {
        return layout(cfg, camX, camY, camZ,
                RtOverlay.lastSunDirection(), halfFovY(), aspect(), viewForward());
    }

    /** То же, но со всеми входами наружу — для отладки и тестов. */
    public static ShadowClipmap.Cascade[] layout(RtConfig cfg, float camX, float camY, float camZ,
            float[] sunDir, float halfFovY, float aspect, float[] viewFwd) {
        return ShadowClipmap.build(
                camX, camY, camZ,
                viewFwd, sunDir,
                ShadowClipmap.MIN_NEAR, cfg.clipmapDistance,
                halfFovY, aspect,
                cfg.clipmapResolution, cfg.cascades, SPLIT_LAMBDA);
    }

    /**
     * Направление взгляда камеры из состояния рендера.
     *
     * <p>Берём {@code xRot}/{@code yRot} по ванильной формуле Minecraft. Матрицу
     * {@code viewRotationMatrix} не используем: в меню настроек последнего кадра
     * может ещё не быть, а {@code xRot}/{@code yRot} заполняются сразу при входе в мир.
     *
     * @return единичный вектор длиной 3, никогда null
     */
    public static float[] viewForward() {
        Minecraft mc = Minecraft.getInstance();
        float[] out = new float[3];
        try {
            GameRenderStateAccess camera = GameRenderStateAccess.of(mc);
            float xRot = camera.xRot;
            float yRot = camera.yRot;
            if (!camera.initialized) {
                // Камера ещё не инициализирована (вне мира) — дефолт «смотрим в +Z».
                out[2] = 1.0F;
                return out;
            }
            float yaw = (float) Math.toRadians(yRot);
            float pitch = (float) Math.toRadians(xRot);
            float cp = (float) Math.cos(pitch);
            out[0] = -(float) Math.sin(yaw) * cp;
            out[1] = -(float) Math.sin(pitch);
            out[2] = (float) Math.cos(yaw) * cp;
            return out;
        } catch (Exception e) {
            out[0] = 0.0F;
            out[1] = 0.0F;
            out[2] = 1.0F;
            return out;
        }
    }

    /**
     * Прямой доступ к {@code CameraRenderState} последнего кадра.
     *
     * <p>Отдельный класс, потому что {@code gameRenderState()} есть только у
     * {@code GameRenderer}, а меню настроек работает и в момент, когда рендер
     * мира ещё не отработал. Все обращения обёрнуты в try/catch на стороне вызова.
     */
    private record GameRenderStateAccess(float xRot, float yRot, boolean initialized) {
        static GameRenderStateAccess of(Minecraft mc) {
            CameraRenderState cam = mc.gameRenderer.gameRenderState()
                    .levelRenderState.cameraRenderState;
            return new GameRenderStateAccess(cam.xRot, cam.yRot, cam.initialized);
        }
    }

    /** Половина вертикального FOV в радианах из настроек графики. */
    public static float halfFovY() {
        Minecraft mc = Minecraft.getInstance();
        try {
            // fov() в 26.2 — OptionInstance<Integer>, а не поле; значение в градусах.
            int degrees = mc.options.fov().get();
            return (float) Math.toRadians(degrees) * 0.5F;
        } catch (Exception e) {
            return (float) Math.toRadians(70.0);
        }
    }

    /** Отношение сторон окна в игре. */
    public static float aspect() {
        Minecraft mc = Minecraft.getInstance();
        try {
            int w = mc.getWindow().getWidth();
            int h = mc.getWindow().getHeight();
            return h > 0 ? (float) w / (float) h : 16.0F / 9.0F;
        } catch (Exception e) {
            return 16.0F / 9.0F;
        }
    }
}