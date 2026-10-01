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
    }

    public void applyLowPreset() {
        this.rayStride = 2;
        this.shadowDistance = 64;
        this.maxSteps = 128;
        this.softShadows = 1;
    }

    public void applyMediumPreset() {
        this.rayStride = 1;
        this.shadowDistance = 96;
        this.maxSteps = 192;
        this.softShadows = 2;
    }

    private void normalized() {
        setRayStride(rayStride);
        shadowDistance = Math.max(16, Math.min(160, shadowDistance));
        maxSteps = Math.max(16, Math.min(256, maxSteps));
        useBob = useBob != 0 ? 1 : 0;
        softShadows = Math.max(0, Math.min(2, softShadows));
        shadowStrength = Math.max(0.0F, Math.min(1.0F, shadowStrength));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
