package com.sunflower.client.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Клипмап теней: деление фрустума, базис камеры теней и снап к сетке текселей.
 *
 * <p>Ключевой тест — {@link #texelSnapKeepsWorldGridStill()}. Дрожание тени
 * (shadow swimming) не даёт ни ошибки компиляции, ни визуально очевидного бага в
 * логах: картинка просто «дышит» при ходьбе, и это почти невозможно отладить без
 * теста на снап.
 */
class ShadowClipmapTest {
    private static final float NEAR = 0.1F;
    private static final float FAR = 512.0F;
    private static final float HALF_FOV = (float) Math.toRadians(45.0);
    private static final float ASPECT = 16.0F / 9.0F;
    private static final int RES = 1024;
    private static final float[] VIEW_FWD = {0.0F, 0.0F, 1.0F};
    /** Полдень, солнце в зените — худший случай для вырожденного базиса. */
    private static final float[] SUN_UP = {0.0F, 1.0F, 0.0F};
    /** Полдень-в-полдень с наклоном, обычный случай. */
    private static final float[] SUN_TILT = {0.4F, 0.85F, 0.34F};

    @Test
    @DisplayName("split: первая граница = near, последняя = far")
    void splitBounds() {
        for (int c = 1; c <= ShadowClipmap.MAX_CASCADES; c++) {
            float[] b = ShadowClipmap.splitDistances(NEAR, FAR, c, 0.7F);
            assertEquals(c + 1, b.length);
            assertEquals(NEAR, b[0], 1e-6F);
            assertEquals(FAR, b[c], 1e-6F, "последняя граница обязана быть ровно far, иначе дыра у горизонта");
            for (int i = 1; i <= c; i++) {
                assertTrue(b[i] > b[i - 1], "границы должны строго расти: " + b[i - 1] + " -> " + b[i]);
            }
        }
    }

    @Test
    @DisplayName("split: lambda=0 даёт равномерное деление, lambda=1 — логарифмическое")
    void splitLambdaExtremes() {
        float far = 400.0F;
        float[] uniform = ShadowClipmap.splitDistances(NEAR, far, 4, 0.0F);
        for (int i = 1; i <= 3; i++) {
            // Равномерное деление интерполирует МЕЖДУ near и far, а не шагом far/n:
            // шаг должен считаться от длины всего интервала (far - near).
            assertEquals(NEAR + (far - NEAR) * i / 4.0F, uniform[i], 1e-3F);
        }

        float[] log = ShadowClipmap.splitDistances(NEAR, far, 4, 1.0F);
        for (int i = 1; i <= 4; i++) {
            float expected = (float) (NEAR * Math.pow(far / NEAR, i / 4.0F));
            assertEquals(expected, log[i], 1e-3F);
        }
        // Логарифмическое деление обязано отдать БЛИЖНЕМУ каскаду больше:
        // log[1] = 0.79 против uniform[1] = 100. Ближний каскад меньше — значит
        // его extent меньше, а тексель в нём мельче. Именно это и нужно:
        // тени под ногами должны быть резкими.
        assertTrue(log[1] < uniform[1], "лог-деление обязано сжать ближний каскад");
    }

    @Test
    @DisplayName("split: неверные аргументы отвергаются")
    void splitRejectsBadInput() {
        assertThrows(IllegalArgumentException.class,
                () -> ShadowClipmap.splitDistances(NEAR, FAR, 0, 0.5F));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowClipmap.splitDistances(NEAR, FAR, ShadowClipmap.MAX_CASCADES + 1, 0.5F));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowClipmap.splitDistances(0.0F, FAR, 4, 0.5F));
        assertThrows(IllegalArgumentException.class,
                () -> ShadowClipmap.splitDistances(NEAR, NEAR, 4, 0.5F));
    }

@Test
    @DisplayName("базис камеры теней ортонормирован и смотрит против солнца")
    void lightBasisIsOrthonormal() {
        float[][] suns = {SUN_UP, SUN_TILT, {0.0F, -0.3F, 0.95F}, {0.577F, 0.577F, 0.577F}};
        for (float[] sun : suns) {
            float[] r = new float[3];
            float[] u = new float[3];
            float[] f = new float[3];
            ShadowClipmap.lightBasis(sun, r, u, f);
            assertEquals(1.0F, len(r), 1e-4F, "right единичный");
            assertEquals(1.0F, len(u), 1e-4F, "up единичный");
            assertEquals(1.0F, len(f), 1e-4F, "forward единичный");
            assertEquals(0.0F, dot(r, u), 1e-4F, "right ⊥ up");
            assertEquals(0.0F, dot(r, f), 1e-4F, "right ⊥ forward");
            assertEquals(0.0F, dot(u, f), 1e-4F, "up ⊥ forward");
            // Камера теней смотрит ОТ солнца: forward == -normalize(sunDir).
            // Именно нормализованного: не-единичный вход lightBasis обязан принять
            // сам (иначе up выходит 0.999 и обратное проецирование едет).
            float n = (float) Math.sqrt(sun[0] * sun[0] + sun[1] * sun[1] + sun[2] * sun[2]);
            assertEquals(-sun[0] / n, f[0], 1e-5F);
            assertEquals(-sun[1] / n, f[1], 1e-5F);
            assertEquals(-sun[2] / n, f[2], 1e-5F);
        }
    }

    @Test
    @DisplayName("базис нормализует не-единичный sunDir (иначе up не единичный)")
    void lightBasisNormalizesInput() {
        // SUN_TILT длиной 0.999 — с такой погрешностью up = cross(fwd, right)
        // получался 0.99905, и центр каскада переставал попадать на сетку текселей.
        float[] r = new float[3];
        float[] u = new float[3];
        float[] f = new float[3];
        ShadowClipmap.lightBasis(SUN_TILT, r, u, f);
        assertEquals(1.0F, len(u), 1e-5F, "up обязан быть ровно единичным");
        assertEquals(1.0F, len(f), 1e-5F, "forward обязан быть ровно единичным");
    }

    @Test
    @DisplayName("базис не вырождается при солнце строго в зените")
    void lightBasisSurvivesZenithSun() {
        float[] r = new float[3];
        float[] u = new float[3];
        float[] f = new float[3];
        ShadowClipmap.lightBasis(new float[] {0.0F, 1.0F, 0.0F}, r, u, f);
        // При worldUp, параллельном forward, cross даёт (0,0,0) и без подмены
        // up на (0,0,1) весь клипмап рассыпался бы в NaN.
        assertTrue(len(r) > 0.9F, "right не вырожден: " + len(r));
        assertTrue(len(u) > 0.9F, "up не вырожден: " + len(u));
        assertEquals(1.0F, len(f), 1e-4F);
    }

    @Test
    @DisplayName("клипмап: extent каскадов растёт с расстоянием")
    void extentGrowsWithDistance() {
        ShadowClipmap.Cascade[] cs = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
        for (int i = 1; i < cs.length; i++) {
            assertTrue(cs[i].radius > cs[i - 1].radius,
                    "каскад " + i + " должен быть шире предыдущего");
            assertTrue(cs[i].texelWorldSize > cs[i - 1].texelWorldSize,
                    "тексель каскада " + i + " должен быть крупнее");
        }
    }

    @Test
    @DisplayName("клипмап: каждый каскад покрывает свой слэв фрустума")
    void cascadesCoverFractions() {
        ShadowClipmap.Cascade[] cs = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
        float[] bounds = ShadowClipmap.splitDistances(NEAR, 256.0F, 4, 0.7F);
        for (int i = 0; i < cs.length; i++) {
            assertEquals(i, cs[i].index);
            assertEquals(bounds[i], cs[i].nearDist, 1e-4F);
            assertEquals(bounds[i + 1], cs[i].farDist, 1e-4F);
        }
    }

    @Test
    @DisplayName("клипмап: сетка текселей квантована — непрерывного дрейфа нет")
    void texelSnapKeepsWorldGridStill() {
        // Главный тест клипмапа. Дрожание тени (shadow swimming) не даёт ни ошибки
        // компиляции, ни заметного бага в логах — картинка просто «дышит» при ходьбе.
        //
        // Инвариант, который реально запрещает дрейф: центр в пространстве света
        // ВСЕГДА лежит на целом числе текселей. Тогда при движении камеры он может
        // только прыгнуть на тексель, но не ехать плавно. Поэтому проверяем не
        // «сдвинулся ли центр», а кратность текселю при самых разных положениях камеры.
        float[] r = new float[3];
        float[] u = new float[3];
        float[] f = new float[3];
        ShadowClipmap.lightBasis(SUN_TILT, r, u, f);

        ShadowClipmap.Cascade[] cs = build(100.0F, 64, 100.0F);
        for (int step = 0; step < 40; step++) {
            // Каждый раз сдвигаем камеру на дробь текселя своего каскада — так
            // гарантированно проверяем квантование, а не конкретное фазовое смещение.
            ShadowClipmap.Cascade[] moved = ShadowClipmap.build(
                    100.0F + r[0] * step * 0.137F,
                    64 + r[1] * step * 0.137F,
                    100.0F + r[2] * step * 0.137F,
                    VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
            for (int i = 0; i < cs.length; i++) {
                float texel = cs[i].texelWorldSize;
                float lx = lightX(moved[i], r);
                float ly = lightY(moved[i], u);
                assertEquals(Math.round(lx / texel), lx / texel, 1e-3F,
                        "каскад " + i + ", шаг " + step + ": центр уехал с сетки текселей по X");
                assertEquals(Math.round(ly / texel), ly / texel, 1e-3F,
                        "каскад " + i + ", шаг " + step + ": центр уехал с сетки текселей по Y");
            }
        }
    }

    @Test
    @DisplayName("клипмап: сдвиг ровно на тексель сдвигает сетку ровно на тексель")
    void texelStepIsDiscretelyExact() {
        // Второй край инварианта: разность двух центров — целое число текселей.
        // Если бы центр плавал непрерывно, разность «гуляла» бы вместе с камерой.
        float[] r = new float[3];
        float[] u = new float[3];
        float[] f = new float[3];
        ShadowClipmap.lightBasis(SUN_TILT, r, u, f);

        ShadowClipmap.Cascade[] ref = build(100.0F, 64, 100.0F);
        for (int i = 0; i < ref.length; i++) {
            float texel = ref[i].texelWorldSize;
            // Сдвигаем ровно на два своих текселя вдоль right.
            ShadowClipmap.Cascade[] moved = ShadowClipmap.build(
                    100.0F + r[0] * texel * 2.0F,
                    64 + r[1] * texel * 2.0F,
                    100.0F + r[2] * texel * 2.0F,
                    VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
            float delta = lightX(moved[i], r) - lightX(ref[i], r);
            assertEquals(2.0F * texel, delta, 1e-3F,
                    "каскад " + i + ": сдвиг на 2 текселя дал " + delta);
        }
    }

    @Test
    @DisplayName("клипмап: центр действительно лежит на сетке текселей")
    void centerIsOnTexelGrid() {
        ShadowClipmap.Cascade[] cs = ShadowClipmap.build(
                137.4F, 71.2F, -58.9F, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 3, 0.7F);
        float[] r = new float[3];
        float[] u = new float[3];
        float[] f = new float[3];
        ShadowClipmap.lightBasis(SUN_TILT, r, u, f);
        for (ShadowClipmap.Cascade c : cs) {
            // Центр спроецирован на плоскость (right, up) — там он лежит
            // на кратном текселя от центра карты теней.
            float lx = c.cx * r[0] + c.cy * r[1] + c.cz * r[2];
            float ly = c.cx * u[0] + c.cy * u[1] + c.cz * u[2];
            float qx = lx / c.texelWorldSize;
            float qy = ly / c.texelWorldSize;
            assertEquals(Math.round(qx), qx, 1e-3F, "центр не на сетке по X");
            assertEquals(Math.round(qy), qy, 1e-3F, "центр не на сетке по Y");
        }
    }

    @Test
    @DisplayName("клипмап: extent каскада покрывает срез фрустума (тень не обрезается)")
    void extentCoversFrustumSlice() {
        ShadowClipmap.Cascade[] cs = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
        for (ShadowClipmap.Cascade c : cs) {
            // Радиус должен покрывать худший угол слэва: ближний угол по краю
            // экрана лежит на расстоянии d0 * tan(halfFovDiag) от оси взгляда.
            float worst = (c.farDist - c.nearDist) * 0.5F
                    + c.nearDist * (float) Math.tan(HALF_FOV) * (float) Math.sqrt(1 + ASPECT * ASPECT);
            assertTrue(c.radius >= worst,
                    "каскад " + c.index + ": radius " + c.radius + " < нужного " + worst);
        }
    }

    @Test
    @DisplayName("выбор каскада по глубине: монотонный и зажат в границы")
    void cascadeSelection() {
        ShadowClipmap.Cascade[] cs = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
        int prev = -1;
        for (float d = 0.0F; d <= 300.0F; d += 0.5F) {
            int c = ShadowClipmap.cascadeForViewDepth(cs, d);
            assertTrue(c >= 0 && c < cs.length, "каскад вне диапазона: " + c);
            assertTrue(c >= prev, "выбор каскада должен быть монотонным по глубине");
            prev = c;
        }
        assertEquals(0, ShadowClipmap.cascadeForViewDepth(cs, 0.0F));
        // За самым дальним каскадом отдаём последний, а не выходим за массив.
        assertEquals(cs.length - 1, ShadowClipmap.cascadeForViewDepth(cs, 99999.0F));
    }

    @Test
    @DisplayName("клипмап: солнце в зените не ломает extent")
    void zenithSunKeepsSaneExtent() {
        ShadowClipmap.Cascade[] up = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_UP, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
        for (ShadowClipmap.Cascade c : up) {
            assertTrue(Float.isFinite(c.radius) && c.radius > 0, "radius испорчен: " + c.radius);
            assertTrue(Float.isFinite(c.depthRange) && c.depthRange > 0, "depth испорчен");
            assertTrue(Float.isFinite(c.texelWorldSize) && c.texelWorldSize > 0, "texel испорчен");
        }
        assertNotEquals(Float.NaN, up[0].cx);
    }

    @Test
    @DisplayName("клипмап: разное разрешение даёт обратно пропорциональный тексель")
    void texelScalesWithResolution() {
        ShadowClipmap.Cascade[] lo = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, 512, 3, 0.7F);
        ShadowClipmap.Cascade[] hi = ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, 1024, 3, 0.7F);
        for (int i = 0; i < lo.length; i++) {
            // Радиус зависит только от геометрии, разрешение его не трогает.
            assertEquals(lo[i].radius, hi[i].radius, 1e-3F);
            // А тексель = 2*radius/res, то есть ОБРАТНО пропорционален разрешению:
            // карта вдвое крупнее — тексель вдвое мельче.
            assertEquals(lo[i].texelWorldSize * 0.5F, hi[i].texelWorldSize, 1e-6F);
        }
    }

    @Test
    @DisplayName("клипмап: неверное разрешение отвергается")
    void rejectsBadResolution() {
        assertThrows(IllegalArgumentException.class, () -> ShadowClipmap.build(
                0, 64, 0, VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, 0, 3, 0.7F));
    }

    // ------------------------------------------------------------------ утилиты

    /** Сборка клипмапа с дефолтами теста — чтобы не повторять 12 аргументов. */
    private static ShadowClipmap.Cascade[] build(double camX, double camY, double camZ) {
        return ShadowClipmap.build((float) camX, (float) camY, (float) camZ,
                VIEW_FWD, SUN_TILT, NEAR, 256.0F, HALF_FOV, ASPECT, RES, 4, 0.7F);
    }

    /** Проекция центра каскада на ось right камеры теней. */
    private static float lightX(ShadowClipmap.Cascade c, float[] right) {
        return c.cx * right[0] + c.cy * right[1] + c.cz * right[2];
    }

    /** Проекция центра каскада на ось up камеры теней. */
    private static float lightY(ShadowClipmap.Cascade c, float[] up) {
        return c.cx * up[0] + c.cy * up[1] + c.cz * up[2];
    }

    private static float len(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }

    private static float dot(float[] a, float[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }
}