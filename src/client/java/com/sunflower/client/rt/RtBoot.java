package com.sunflower.client.rt;

import com.sunflower.Sunflower;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Точка входа RT. Вызывается из SunflowerClient и из миксина.
 *
 * <p>Порядок:
 * <ol>
 *   <li>Загрузить {@link RtConfig}.</li>
 *   <li>Создать {@link VoxelVolume}.</li>
 *   <li>Проверить бэкенд через {@link GpuBridge}. Если не Vulkan — выключить RT,
 *       залогировать, показать один раз предупреждение (без GL-фолбэка — так задумано).</li>
 * </ol>
 *
 * <p>Важно: на момент {@code onInitializeClient} GPU-девайс еще может не существовать,
 * поэтому {@link #tick()} повторяет пробу, пока бэкенд UNKNOWN, а при первом входе
 * в мир пишет итог прямо в чат (чтобы не надо было копать latest.log).
 */
public final class RtBoot {
    private static RtConfig config;
    private static VoxelVolume volume;
    private static volatile boolean vulkanActive;
    private static volatile boolean warned;
    private static volatile boolean greeted;
    private static volatile int tickCounter;
    private static volatile int unknownRetries;

    private RtBoot() {}

    public static synchronized void init() {
        if (config != null) {
            return;
        }
        config = RtConfig.load();
        volume = new VoxelVolume();
        MaterialTable.logSelfCheck();
        GpuBridge.logRequirementsReminder();
        refreshBackendState();
        Sunflower.LOGGER.info("[sunflower-rt] init: enabled={} stride={} dist={} steps={} vulkan={}",
                config.enabled, config.rayStride, config.shadowDistance, config.maxSteps, vulkanActive);
        Sunflower.LOGGER.warn("[sunflower-rt] BANNER: мод загружен. Введи /sunflower status для проверки. vulkan={}", vulkanActive);
    }

    /** Перепроверить бэкенд (после смены Video Settings + рестарта). */
    public static synchronized void refreshBackendState() {
        if (config == null) {
            init();
            return;
        }
        GpuBridge.BackendInfo info = GpuBridge.probeBackend();
        vulkanActive = info.kind() == GpuBridge.BackendKind.VULKAN;
        if (!vulkanActive && !warned) {
            warned = true;
            Sunflower.LOGGER.warn(
                    "[sunflower-rt] RT выключен: активный бэкенд '{}' ({}). Нужен ванильный Vulkan 26.2"
                            + " (Video Settings -> Graphics API -> Prefer Vulkan). Без OpenGL-фолбэка.",
                    info.rawName(), info.detail());
        } else if (vulkanActive) {
            Sunflower.LOGGER.info("[sunflower-rt] Vulkan-бэкенд подтвержден ({}).", info.detail());
        }
    }

    /**
     * Вызывать каждый клиентский тик. Повторяет пробу бэкенда, пока он UNKNOWN
     * (девайс мог еще не создаться на момент init), и один раз за сессию пишет
     * итог в чат при входе в мир.
     */
    public static void tick() {
        if (config == null) {
            return;
        }
        tickCounter++;
        if (!vulkanActive && unknownRetries < 30 && tickCounter % 40 == 0) {
            unknownRetries++;
            GpuBridge.BackendInfo info = GpuBridge.probeBackend();
            if (info.kind() != GpuBridge.BackendKind.UNKNOWN) {
                refreshBackendState();
            }
        }
        if (!greeted && tickCounter % 20 == 0) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null) {
                    greeted = true;
                    String msg = vulkanActive
                            ? "[Sunflower RT] Vulkan OK, тени вкл (шаг " + config.rayStride + "x" + config.rayStride + "). /sunflower status"
                            : "[Sunflower RT] НЕ Vulkan — RT выключен. Нужен Video Settings -> Graphics API -> Prefer Vulkan + рестарт.";
                    mc.player.sendSystemMessage(Component.literal(msg));
                    Sunflower.LOGGER.warn("[sunflower-rt] GREET: {}", msg);
                }
            } catch (Exception e) {
                Sunflower.LOGGER.debug("[sunflower-rt] greet failed: {}", e.toString());
            }
        }
    }

    /** Строки для команды /sunflower status — живая проба, а не кэш. */
    public static List<String> statusLines() {
        List<String> out = new ArrayList<>();
        GpuBridge.BackendInfo live = GpuBridge.probeBackend();
        out.add("backend now: " + live.rawName() + " (" + live.detail() + ")");
        out.add("vulkanActive(cached): " + vulkanActive);
        if (config == null) {
            out.add("config: NOT LOADED");
            return out;
        }
        out.add("enabled=" + config.enabled + " stride=" + config.rayStride + " (full-res v1)"
                + " dist=" + config.shadowDistance + " steps=" + config.maxSteps);
        out.add("overlay ready=" + RtOverlay.isReady() + " frames=" + RtOverlay.framesDrawn()
                + " debug=" + RtOverlay.debugMode() + " bob=" + RtOverlay.useBob() + " state=" + RtOverlay.skipReason());
        out.add("sun=(" + String.format("%.2f", RtOverlay.lastSunX()) + "," + String.format("%.2f", RtOverlay.lastSunY())
                + ") strength=" + String.format("%.2f", RtOverlay.lastStrength()));
        out.add("volume=" + volume.width() + "x" + volume.height() + "x" + volume.depth()
                + " origin=" + volume.originX() + "," + volume.originY() + "," + volume.originZ()
                + " fill=" + (int) (volume.fillFraction() * 100) + "% queued=" + volume.queuedSections());
        out.add("shouldRenderRt=" + shouldRenderRt());
        return out;
    }

    /** Активен ли RT-пасс в этом кадре. Вызывает рендер-хук. */
    public static boolean shouldRenderRt() {
        return config != null && config.enabled && vulkanActive && RtOverlay.isReady();
    }

    public static RtConfig config() {
        if (config == null) {
            init();
        }
        return config;
    }

    public static VoxelVolume volume() {
        if (volume == null) {
            init();
        }
        return volume;
    }

    public static boolean isVulkanActive() {
        return vulkanActive;
    }

    public static void saveConfig() {
        if (config != null) {
            config.save();
        }
    }
}
