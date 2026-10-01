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
    public boolean enabled = true;
    /** 1, 2 или 4. Другие значения нормализуются в load()/setRayStride(). */
    public int rayStride = 2;
    /** Дальность теневого луча, блоков. Рекомендовано 32..96 для GT 650M. */
    public int shadowDistance = 64;
    /**
     * Макс. шагов DDA на луч. Диагональ ест ~1.73 вокселя/блок,
     * поэтому шагов нужно примерно вдвое больше дистанции, иначе длинные
     * лучи обрываются раньше препятствия и свет протекает сквозь блоки.
     */
    public int maxSteps = 128;
    /** Секций 16x16x16 в очередь вокселей за кадр. Больше = быстрее заливка, но спайки. */
    public int sectionsPerFrame = 6;

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
    }

    public void applyLowPreset() {
        this.rayStride = 2;
        this.shadowDistance = 64;
        this.maxSteps = 128;
    }

    public void applyMediumPreset() {
        this.rayStride = 1;
        this.shadowDistance = 96;
        this.maxSteps = 192;
    }

    private void normalized() {
        setRayStride(rayStride);
        shadowDistance = Math.max(16, Math.min(160, shadowDistance));
        maxSteps = Math.max(16, Math.min(256, maxSteps));
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }
}
