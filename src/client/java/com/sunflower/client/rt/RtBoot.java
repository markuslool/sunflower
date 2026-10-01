package com.sunflower.client.rt;

import com.sunflower.Sunflower;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

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
    private static volatile boolean greetedVulkan;
    private static volatile boolean greetedNonVulkan;
    private static volatile int tickCounter;

    private RtBoot() {}

    public static synchronized void init() {
        if (config != null) {
            return;
        }
        config = RtConfig.load();
        volume = new VoxelVolume();
        RtOverlay.syncBobFromConfig(config.useBob);
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
     * (девайс мог еще не создаться на момент init), и пишет итог в чат при входе
     * в мир — отдельно для Vulkan и не-Vulkan, чтобы смена бэкенда без рестарта
     * тоже была видна (повторный грит при смене состояния).
     */
    public static void tick() {
        if (config == null) {
            return;
        }
        tickCounter++;
        // Пробуем каждые ~2с пока бэкенд UNKNOWN; после определения — каждые ~10с
        // подхватываем смену Video Settings без рестарта. Капа попыток нет.
        boolean wantRetry = !vulkanActive
                ? tickCounter % 40 == 0
                : tickCounter % 200 == 0;
        if (wantRetry) {
            GpuBridge.BackendInfo info = GpuBridge.probeBackend();
            if (info.kind() != GpuBridge.BackendKind.UNKNOWN) {
                boolean was = vulkanActive;
                refreshBackendState();
                if (vulkanActive != was) {
                    greetedVulkan = false;
                    greetedNonVulkan = false;
                }
            } else if (!vulkanActive) {
                refreshBackendState();
            }
        }
        if (tickCounter % 20 == 0) {
            try {
                Minecraft mc = Minecraft.getInstance();
                if (mc.player != null && mc.level != null) {
                    if (vulkanActive && !greetedVulkan) {
                        greetedVulkan = true;
                        greetedNonVulkan = false;
                        String msg = "[Sunflower RT] Vulkan OK, тени вкл (шаг " + config.rayStride + "x" + config.rayStride + "). /sunflower status";
                        mc.player.sendSystemMessage(Component.literal(msg));
                        Sunflower.LOGGER.warn("[sunflower-rt] GREET: {}", msg);
                    } else if (!vulkanActive && !greetedNonVulkan) {
                        greetedNonVulkan = true;
                        greetedVulkan = false;
                        String msg = "[Sunflower RT] НЕ Vulkan — RT выключен. Нужен Video Settings -> Graphics API -> Prefer Vulkan + рестарт.";
                        mc.player.sendSystemMessage(Component.literal(msg));
                        Sunflower.LOGGER.warn("[sunflower-rt] GREET: {}", msg);
                    }
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
        out.add("enabled=" + config.enabled + " stride=" + config.rayStride + "x" + config.rayStride
                + " dist=" + config.shadowDistance + " steps=" + config.maxSteps + " (eff=" + effectiveSteps(config) + ")");
        out.add("overlay ready=" + RtOverlay.isReady() + " frames=" + RtOverlay.framesDrawn()
                + " debug=" + RtOverlay.debugMode() + " bob=" + RtOverlay.useBob()
                + (RtOverlay.useBob() != 0 ? " (+view-bob, как террейн)" : " (базовая, для Sodium)")
                + " state=" + RtOverlay.skipReason());
        out.add("sodium=" + isSodiumLoaded() + " (если true и тени плывут — попробуй /sunflower bob 0)");
        out.add("sun=(" + String.format("%.2f", RtOverlay.lastSunX()) + "," + String.format("%.2f", RtOverlay.lastSunY())
                + ") strength=" + String.format("%.2f", RtOverlay.lastStrength()));
        out.add("volume=" + volume.width() + "x" + volume.height() + "x" + volume.depth()
                + " origin=" + volume.originX() + "," + volume.originY() + "," + volume.originZ()
                + " fill=" + (int) (volume.fillFraction() * 100) + "% queued=" + volume.queuedSections()
                + " filledLastFrame=" + RtOverlay.lastFilled()
                + " mipCellsRecomputed=" + RtOverlay.lastMipRecomputed()
                + " uploadPending=" + volume.uploadPending()
                + " originUploaded=" + volume.uploadedOriginValid()
                + " originShiftPending=" + volume.originShiftPending()
                + " prefetch=" + volume.prefetchReady()
                + " liveUpdates=" + volume.liveUpdateCount());
        out.add("shouldRenderRt=" + shouldRenderRt());
        return out;
    }

    /** Активен ли RT-пасс в этом кадре. Вызывает рендер-хук. */
    public static boolean shouldRenderRt() {
        return config != null && config.enabled && vulkanActive && RtOverlay.isReady();
    }

    /**
     * Эффективный кап шагов DDA: диагональ ест ~1.73 вокселя/блок, поэтому шагов
     * нужно минимум вдвое больше дистанции, иначе длинные лучи обрываются и свет протекает.
     * Единая формула для Java и шейдера (цикл шейдера — до 320).
     */
    public static int effectiveSteps(RtConfig cfg) {
        return Math.max(cfg.maxSteps, cfg.shadowDistance * 2);
    }

    /** Загружен ли Sodium — его террейн-пайплайн может хранить свои копии матриц без боба. */
    public static boolean isSodiumLoaded() {
        try {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded("sodium");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Мгновенная запись вокселя при одиночной правке блока.
     * Вызывается из LevelChunkMixin.setBlockState — единственной точки, через которую
     * идут ВСЕ одиночные правки: предикт клиента, server-verify (зовет Level.setBlock
     * напрямую через invokespecial, минуя ClientLevel.setBlock), /fill и т.п.
     * Старый хук на ClientLevel.setBlock пропускал server-verify — тени отставали навсегда.
     */
    public static void onBlockStateChanged(BlockPos pos, BlockState newState) {
        try {
            if (config == null || volume == null || !volume.isInitialized()) {
                return; // зальется drain'ом при инициализации объема
            }
            volume.setVoxel(pos.getX(), pos.getY(), pos.getZ(), MaterialTable.classify(newState));
        } catch (Exception e) {
            Sunflower.LOGGER.debug("[sunflower-rt] onBlockStateChanged failed: {}", e.toString());
        }
    }

    /**
     * Bulk-замена чанка пакетом (загрузка, ресинк): весь столбец секций в очередь на перепек.
     * Y-диапазон берёт сам объем — не зависим от майнкрафтовских геттеров высоты чанка.
     * Перепек идет бюджетно через drain — на время заливки fail-open (лишний свет, не фантомы).
     */
    public static void onChunkDataReplaced(int chunkX, int chunkZ) {
        try {
            if (config == null || volume == null) {
                return;
            }
            volume.markColumnDirty(chunkX, chunkZ);
        } catch (Exception e) {
            Sunflower.LOGGER.debug("[sunflower-rt] onChunkDataReplaced failed: {}", e.toString());
        }
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
