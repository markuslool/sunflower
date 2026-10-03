package com.sunflower.client.rt;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.sunflower.Sunflower;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Конфиг RT v1. Хранится в config/sunflower-rt.json.
 *
 * <p>Что правит пользователь (и ты при доводке под GT 650M):
 * <ul>
 *   <li>{@link #rayStride} — шаг луча: 1 = 1x1 (каждый пиксель), 2 = один луч на 2x2, 4 = на 4x4.
 *       Главный рычаг производительности на Kepler. Дефолт 2.</li>
 *   <li>{@link #shadowDistance} — длина теневого луча в блоках.</li>
 *   <li>{@link #maxSteps} — кап итераций DDA (защита от зависания на слабом GPU).</li>
 * </ul>
 */
public final class RtConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "sunflower-rt.json";

    /** Включен ли RT-пасс вообще. */
    public volatile boolean enabled = true;
    /** 1, 2 или 4. Другие значения нормализуются в load()/setRayStride(). */
    public volatile int rayStride = 2;
    /** Дальность теневого луча, блоков. Рекомендовано 32..96 для GT 650M. */
    public volatile int shadowDistance = 64;
    /**
     * Макс. шагов DDA на луч. Диагональ ест ~1.73 вокселя/блок,
     * поэтому шагов нужно примерно вдвое больше дистанции, иначе длинные
     * лучи обрываются раньше препятствия и свет протекает сквозь блоки.
     */
    public volatile int maxSteps = 128;
    /** Секций 16x16x16 в очередь вокселей за кадр. Больше = быстрее заливка, но спайки. */
    public volatile int sectionsPerFrame = 6;
    /**
     * Матрица лучей: 1 = с view-bob (как террейн ваниллы, дефолт),
     * 0 = базовая проекция (если террейн рисует Sodium без боба и тени плывут).
     */
    public volatile int useBob = 1;
    /**
     * Базовая сила солнечных теней 0..1 (множится на день/дождь).
     * Раньше было жёстко 0.65 — теперь пользователь настраивает под вкус и GPU.
     */
    public volatile float shadowStrength = 0.65F;
    /**
     * Мягкость теней: 0 = жёсткие (1 тап), 1 = 4 тапа по диску солнца,
     * 2 = 8 тапов. Каждый тап — полноценный марш, поэтому на слабом GPU это
     * прямое умножение стоимости. Дефолт 0 (жёсткие): на GT 650M один тап давал
     * 34 FPS, четыре тапа могут уронить вдвое — включать осознанно.
     */
    public volatile int softShadows = 0;
    /** Тени облаков: пятна от облаков, едущие по земле вместе с ними. */
    public volatile boolean cloudShadows = true;

    // --- Вкладка «Свет». Всё это уходит в уже существующие слоты uniform-блока
    // (Misc.y и силу пасса), поэтому GLSL трогать не пришлось. ---

    /**
     * Экспозиция солнечного света, множитель к итоговой силе пасса.
     * 1.0 = как было; 0.5 = тени вдвое мягче по контрасту; 1.5 = тени гуще.
     * Итог всё равно зажимается в 1.0, иначе multiply-бленд ушёл бы в отрицательные
     * значения и кадр стал бы чёрным за пределами тени.
     */
    public volatile float lightExposure = 1.0F;
    /**
     * Угловой радиус солнца в радианах (Misc.y шейдера). Радиус диска задаёт,
     * насколько широко расходятся тапы мягкости по золотому углу: меньше — тени
     * резче, больше — мягче и пятнистее у самой границы тени.
     */
    public volatile float sunSize = 0.035F;
    /**
     * Считать свет ночью. Ночью солнце за горизонтом (sunY &lt; 0), поэтому пасс
     * целиком гаснет и картинка остаётся без трассировки. С этой опцией берётся
     * направление на «луну» (зеркальное) и мягкая сила — как лунный свет.
     */
    public volatile boolean nightLight = false;

    // --- Вкладка «Трассировка»: каскадная клипмап теней (CSM + shadow clipmap).
    // Математика живёт в ShadowClipmap, здесь только ручки. ---

    /**
     * Включить клипмап вместо одиночного марча. Выключенный режим — это прежнее
     * поведение v1 (один марч на shadowDistance), включённый — каскады с картами
     * глубины. Дефолт выключен: клипмап требует depth-прохода по геометрии мира,
     * поэтому включать его имеет смысл только после того, как проход отработает.
     */
    public volatile boolean clipmap = false;
    /** Каскадов в клипмапе, 1..ShadowClipmap.MAX_CASCADES. */
    public volatile int cascades = 3;
    /** Разрешение карты глубины одного каскада, пикселей. */
    public volatile int clipmapResolution = 1024;
    /**
     * Дальность, до которой клипмап вообще считает тени, блоков.
     * Дальше — небо без теней. Должна быть кратна {@link #shadowDistance},
     * иначе дальние каскады вырождаются в пустые.
     */
    public volatile int clipmapDistance = 128;
    /**
     * Перекрытие каскадов 0..1. Больше — меньше швов на границах, но больше
     * разрешения тратится впустую. 0.1 — рабочее значение по умолчанию.
     */
    public volatile float cascadeBlend = 0.1F;
    /** Тапов PCF на каскад: 1 = жёсткая тень, 4/9 = мягче. */
    public volatile int clipmapPcf = 1;
    /**
     * Нормальное смещение теневого луча вдоль нормали поверхности, в текселях.
     * 0 = без смещения, 2 = типичное значение. Слишком мало — acne (тени полоса��
     * на плоских полах), слишком много — тени «отрываются» от поверхности (peter-panning).
     */
    public volatile float shadowBias = 1.5F;

    private RtConfig() {}

    public static RtConfig load() {
        Path path = file();
        if (Files.isRegularFile(path)) {
            try {
                String json = Files.readString(path);
                RtConfig loaded = GSON.fromJson(json, RtConfig.class);
                if (loaded != null) {
                    loaded.normalized();
                    return loaded;
                }
            } catch (Exception e) {
                Sunflower.LOGGER.warn("[sunflower-rt] не смог прочитать конфиг, использую дефолт: {}", e.toString());
            }
        }
        RtConfig fresh = new RtConfig();
        fresh.save();
        return fresh;
    }

    public void save() {
        normalized();
        try {
            Files.createDirectories(file().getParent());
            Files.writeString(file(), GSON.toJson(this));
        } catch (IOException e) {
            Sunflower.LOGGER.warn("[sunflower-rt] не смог записать конфиг: {}", e.toString());
        }
    }

    public void setRayStride(int stride) {
        if (stride <= 1) {
            this.rayStride = 1;
        } else if (stride == 3) {
            this.rayStride = 2;
        } else if (stride >= 4) {
            this.rayStride = 4;
        } else {
            this.rayStride = stride;
        }
    }

    /** Пресеты для GT 650M. Вызываются из экрана настроек. */
    public void applyPotatoPreset() {
        this.rayStride = 4;
        this.shadowDistance = 32;
        this.maxSteps = 64;
        this.softShadows = 0; // на GT 650M каждый тап — реальная цена
        this.lightExposure = 1.0F;
    }

    public void applyLowPreset() {
        this.rayStride = 2;
        this.shadowDistance = 64;
        this.maxSteps = 128;
        this.softShadows = 1;
        this.lightExposure = 1.0F;
    }

    public void applyMediumPreset() {
        this.rayStride = 1;
        this.shadowDistance = 96;
        this.maxSteps = 192;
        this.softShadows = 2;
        this.lightExposure = 1.0F;
    }

    /** Вкладка «Свет» — вернуть как было: экспозиция 1.0, солнце 0.035, ночь выкл. */
    public void resetLightSettings() {
        this.lightExposure = 1.0F;
        this.sunSize = 0.035F;
        this.nightLight = false;
    }

    /** Вкладка «Shadow» — сила 0.65, жёсткие тени, 64 блока, облака включены. */
    public void resetShadowSettings() {
        this.shadowDistance = 64;
        this.maxSteps = 128;
        this.shadowStrength = 0.65F;
        this.softShadows = 0;
    }

    /** Вкладка «Трассировка» — клипмап выключен, 3 каскада, 1024px, PCF 1, bias 1.5. */
    public void resetClipmapSettings() {
        this.clipmap = false;
        this.cascades = 3;
        this.clipmapResolution = 1024;
        this.clipmapDistance = 128;
        this.cascadeBlend = 0.1F;
        this.clipmapPcf = 1;
        this.shadowBias = 1.5F;
    }

    private void normalized() {
        setRayStride(rayStride);
        shadowDistance = Math.max(16, Math.min(160, shadowDistance));
        maxSteps = Math.max(16, Math.min(256, maxSteps));
        useBob = useBob != 0 ? 1 : 0;
        softShadows = Math.max(0, Math.min(2, softShadows));
        shadowStrength = Math.max(0.0F, Math.min(1.0F, shadowStrength));
        lightExposure = Math.max(0.25F, Math.min(1.5F, lightExposure));
        sunSize = Math.max(0.005F, Math.min(0.15F, sunSize));
        cascades = Math.max(1, Math.min(ShadowClipmap.MAX_CASCADES, cascades));
        // Минимум 256: ниже карта теней настолько крупная, что тень от блока
        // размазывается в кашу, и клипмап становится хуже одиночного марча.
        clipmapResolution = Math.max(256, Math.min(4096, snapPow2(clipmapResolution)));
        clipmapDistance = Math.max(32, Math.min(256, snapPow2(clipmapDistance)));
        cascadeBlend = Math.max(0.0F, Math.min(0.5F, cascadeBlend));
        // PCF — только квадрат из нечётных чисел: 1 (жёсткая), 3, 5, 9.
        if (clipmapPcf != 1 && clipmapPcf != 3 && clipmapPcf != 5 && clipmapPcf != 9) {
            clipmapPcf = 1;
        }
        shadowBias = Math.max(0.0F, Math.min(4.0F, shadowBias));
    }

    /** Округление вниз до степени двойки — текстуры любят степени двойки. */
    private static int snapPow2(int v) {
        int p = 1;
        while (p * 2 <= v && p < (1 << 20)) {
            p *= 2;
        }
        return p;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
