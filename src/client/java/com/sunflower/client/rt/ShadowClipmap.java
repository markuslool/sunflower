package com.sunflower.client.rt;

/**
 * Математика каскадной клипмапы теней (CSM + shadow clipmap).
 *
 * <p>Вынесено отдельно от рендера ради тестируемости без Minecraft — ровно как
 * {@link VolumeLayout} и {@link BoxHysteresis}. Здесь нет ни одного импорта Minecraft:
 * векторы — обычные {@code float[3]}, чтобы тесты оставались чистыми.
 *
 * <p>Что считаем:
 * <ol>
 *   <li><b>Practical split</b> — деление дальности на N каскадов по формуле
 *       Lauritzen (2014): логарифмическое смешивание uniform и log2 распределения.
 *       Чисто логарифмическое съедает слишком много карты на ближний каскад,
 *       чисто равномерное — слишком мало, и дальние тени в 1 тексель.</li>
 *   <li><b>Ограничивающая сфера</b> — вместо AABB-фитта. Тень не «дышит» при
 *       повороте камеры: сфера вокруг слэва фрустума не меняет форму при вращении,
 *       поэтому extent каскада стабилен.</li>
 *   <li><b>Снап к сетке текселей</b> — критично. Если центры каскадов плавают
 *       вместе с камерой, то при движении тексели «проезжают» по поверхности и
 *       тень дрожит (shadow swimming). Квантование центра в пространстве света
 *       к размеру текселя делает сетку текселей мировой и неподвижной.</li>
 * </ol>
 *
 * <p>Соглашение о направлении: {@code sunDir} — единичный вектор, направленный
 * <b>к солнцу</b> (как {@code SunDir} в {@code rt_overlay.fsh}, где
 * {@code dot(nrm, SunDir) > 0} означает освещённую грань). Камера теней смотрит
 * вдоль {@code -sunDir}.
 */
public final class ShadowClipmap {
    /** Больше 4 каскадов на слабом GPU бессмысленно: каждый = свой проход растра. */
    public static final int MAX_CASCADES = 4;

    /** Минимальная дальность первого каскада, блоков. */
    public static final float MIN_NEAR = 0.1F;

    /** Базис камеры теней для одного каскада. */
    public static final class Cascade {
        /** Номер каскада, 0 = ближний. */
        public final int index;
        /** Границы слэва фрустума вдоль взгляда, блоки. */
        public final float nearDist;
        public final float farDist;
        /** Центр сферы, ограничивающей слэв (в мире). */
        public final float cx;
        public final float cy;
        public final float cz;
        /** Радиус ограничивающей сферы = половина ширины ортокамеры. */
        public final float radius;
        /** Полная ширина орто-проекции вдоль right и up, блоки. */
        public final float orthoWidth;
        /** Глубина вдоль forward (от камеры теней), блоки. */
        public final float depthRange;
        /** Размер одного текселя в мировых блоках — им же меряют bias и PCF-радиус. */
        public final float texelWorldSize;

        Cascade(int index, float nearDist, float farDist,
                float cx, float cy, float cz, float radius, float depthRange, float resolution) {
            this.index = index;
            this.nearDist = nearDist;
            this.farDist = farDist;
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.radius = radius;
            this.orthoWidth = radius * 2.0F;
            this.depthRange = depthRange;
            this.texelWorldSize = orthoWidth / resolution;
        }
    }

    private ShadowClipmap() {}

    // ------------------------------------------------------------------ split

    /**
     * Границы каскадов по practical split scheme.
     *
     * @param near       ближняя плоскость, блоков
     * @param far        дальняя плоскость, блоков
     * @param cascades   число каскадов, 1..{@link #MAX_CASCADES}
     * @param lambda     0 = равномерное деление, 1 = логарифмическое
     * @return массив длиной {@code cascades + 1}: {@code out[i]} = near граница каскада i,
     *         {@code out[cascades]} = far
     */
    public static float[] splitDistances(float near, float far, int cascades, float lambda) {
        if (cascades < 1 || cascades > MAX_CASCADES) {
            throw new IllegalArgumentException("cascades должен быть 1.." + MAX_CASCADES + ": " + cascades);
        }
        if (!(near > 0.0F) || !(far > near)) {
            throw new IllegalArgumentException("плоскости должны быть 0 < near < far: " + near + ".." + far);
        }
        float l = Math.max(0.0F, Math.min(1.0F, lambda));
        float out[] = new float[cascades + 1];
        out[0] = near;
        for (int i = 1; i <= cascades; i++) {
            float si = i / (float) cascades;
            float logSplit = near * (float) Math.pow(far / near, si);
            float uniformSplit = near + (far - near) * si;
            out[i] = logSplit * l + uniformSplit * (1.0F - l);
        }
        // Последняя граница обязана быть ровно far, иначе самый дальний каскад
        // обрезает фрустум и у самого горизонта остаётся дыра без тени.
        out[cascades] = far;
        return out;
    }

    // ------------------------------------------------------------------ базис

    /**
     * Базис камеры теней, смотрящей вдоль {@code -sunDir}.
     *
     * <p>Мировой up берётся как (0,1,0), но при почти вертикальном солнце он
     * вырождается (cross → 0), поэтому в этом случае подставляем (0,0,1).
     * Это единственный «магический» поворот: без него карта теней на полдне
     * сдвигалась бы на произвольный угол при каждом кадре.
     *
     * <p>{@code sunDir} нормализуется внутри: вызывающий код не обязан следить за
     * длиной вектора, а без нормализации {@code up = cross(fwd, right)} получается
     * чуть короче единицы, и обратное проецирование центра перестаёт попадать
     * точно на сетку текселей — тихо, без NaN.
     *
     * @param outRight массив из 3 элементов — заполняется правым вектором
     * @param outUp    массив из 3 — верхним вектором
     * @param outFwd   массив из 3 — направлением взгляда камеры теней
     */
    public static void lightBasis(float[] sunDir, float[] outRight, float[] outUp, float[] outFwd) {
        float sunLen = length(sunDir[0], sunDir[1], sunDir[2]);
        float sx = sunDir[0] / sunLen;
        float sy = sunDir[1] / sunLen;
        float sz = sunDir[2] / sunLen;
        float[] fwd = {-sx, -sy, -sz};
        // |dot(fwd, worldUp)| близко к 1 — солнце в зените, worldUp вырожден.
        float upX = 0.0F;
        float upY = 1.0F;
        float upZ = 0.0F;
        if (Math.abs(fwd[1]) > 0.9999F) {
            upX = 0.0F;
            upY = 0.0F;
            upZ = 1.0F;
        }
        float rx = upY * fwd[2] - upZ * fwd[1];
        float ry = upZ * fwd[0] - upX * fwd[2];
        float rz = upX * fwd[1] - upY * fwd[0];
        float rl = length(rx, ry, rz);
        // rl не может быть 0: условие выше гарантирует |fwd.y| < 0.9999, значит
        // worldUp не параллелен fwd, значит cross ненулевой.
        rx /= rl;
        ry /= rl;
        rz /= rl;
        float ux = fwd[1] * rz - fwd[2] * ry;
        float uy = fwd[2] * rx - fwd[0] * rz;
        float uz = fwd[0] * ry - fwd[1] * rx;

        outRight[0] = rx;
        outRight[1] = ry;
        outRight[2] = rz;
        outUp[0] = ux;
        outUp[1] = uy;
        outUp[2] = uz;
        outFwd[0] = fwd[0];
        outFwd[1] = fwd[1];
        outFwd[2] = fwd[2];
    }

    // ------------------------------------------------------------------ каскады

    /**
     * Построить каскады клипмапы.
     *
     * <p>Каждый каскад описан ограничивающей сферой слэва фрустума; центр снапится
     * к сетке текселей в пространстве света. Радиус умножается на {@code depthBiasScale},
     * чтобы ближний каскад гарантированно перекрыл сам себя и в кадре не было щели
     * на границах каскадов (классическая проблема CSM — «швы» между каскадами).
     *
     * @param camX/camY/camZ  позиция камеры
     * @param viewFwd         единичный вектор взгляда камеры
     * @param sunDir          единичный вектор НА СОЛНЦЕ
     * @param near/far        плоскости каскадного фрустума, блоков
     * @param halfFovY        половина вертикального FOV в радианах
     * @param aspect          ширина/высота экрана
     * @param resolution      разрешение карты теней каскада, пикселей
     */
    public static Cascade[] build(float camX, float camY, float camZ,
            float[] viewFwd, float[] sunDir,
            float near, float far, float halfFovY, float aspect, int resolution,
            int cascades, float lambda) {
        if (resolution <= 0) {
            throw new IllegalArgumentException("resolution должен быть > 0: " + resolution);
        }
        float[] bounds = splitDistances(near, far, cascades, lambda);
        float[] right = new float[3];
        float[] up = new float[3];
        float[] fwd = new float[3];
        lightBasis(sunDir, right, up, fwd);

        Cascade[] out = new Cascade[cascades];
        for (int i = 0; i < cascades; i++) {
            float d0 = bounds[i];
            float d1 = bounds[i + 1];
            float mid = (d0 + d1) * 0.5F;
            // Сфера вокруг слэва: центр на середине дистанции, радиус покрывает
            // и ближний, и дальний углы фрустума. tan угла между вертикальным
            // и горизонтальным полу-углами = tan(halfFovY) * aspect.
            float halfDiagTangent = (float) Math.tan(halfFovY) * (float) Math.sqrt(1.0 + aspect * aspect);
            float radius = (d1 - d0) * 0.5F + d0 * halfDiagTangent;
            radius = Math.max(radius, 0.5F);

            float sx = camX + viewFwd[0] * mid;
            float sy = camY + viewFwd[1] * mid;
            float sz = camZ + viewFwd[2] * mid;

            // Закрываем каскад с запасом 5%: соседние каскады должны перекрываться,
            // иначе на их границе появляется щель без тени (классический шов CSM).
            float padded = radius * 1.05F;
            float texel = padded * 2.0F / resolution;

            // Проекция центра в базис камеры теней.
            float lx = sx * right[0] + sy * right[1] + sz * right[2];
            float ly = sx * up[0] + sy * up[1] + sz * up[2];
            float lz = sx * fwd[0] + sy * fwd[1] + sz * fwd[2];

            // Снап к сетке текселей: именно он убирает дрожание тени при движении.
            // Без снапа тексели «проезжают» по поверхности, пока камера на месте.
            float qx = snapToGrid(lx, texel);
            float qy = snapToGrid(ly, texel);

            // Возвращаем центр в мир. По Z ставим его так, чтобы ближняя плоскость
            // орто-камеры была на радиус позади центра и ничего не обрезала.
            float ncx = right[0] * qx + up[0] * qy + fwd[0] * (lz - padded);
            float ncy = right[1] * qx + up[1] * qy + fwd[1] * (lz - padded);
            float ncz = right[2] * qx + up[2] * qy + fwd[2] * (lz - padded);

            // Глубина: до самого дальнего каскада плюс запас на высоту мира —
            // иначе гора выше каскада вылезет за near-плоскость.
            float depth = (d1 + mid) + Math.max(radius, 64.0F);

            out[i] = new Cascade(i, d0, d1, ncx, ncy, ncz, padded, depth, resolution);
        }
        return out;
    }

    /** Какой каскад покрывает точку на расстоянии {@code viewDepth} от камеры. */
    public static int cascadeForViewDepth(Cascade[] cascades, float viewDepth) {
        for (int i = 0; i < cascades.length; i++) {
            if (viewDepth < cascades[i].farDist) {
                return i;
            }
        }
        return cascades.length - 1;
    }

    // ------------------------------------------------------------------ утилиты

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    /** Округление к ближайшему кратному шага. Работает и для отрицательных значений. */
    private static float snapToGrid(float v, float step) {
        return (float) Math.floor(v / step + 0.5F) * step;
    }
}