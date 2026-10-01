package com.sunflower.client.rt;

import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.sunflower.Sunflower;
import net.minecraft.client.Minecraft;

/**
 * Мост к ванильному Vulkan-бэкенду 26.2.
 *
 * <p>Использует стабильные точки Blaze3D:
 * <ul>
 *   <li>{@code RenderSystem.tryGetDevice().getDeviceInfo().backendName()} — строго
 *       "Vulkan" или "OpenGL". ВНИМАНИЕ: {@code RenderSystem.getBackendDescription()}
 *       для этого НЕ годится — он возвращает версию LWJGL ("LWJGL version ..."),
 *       а наивный contains("gl") матчит подстроку в слове LWJGL (был такой баг).</li>
 *   <li>{@code options.txt: preferredGraphicsBackend} — запасной путь.</li>
 * </ul>
 *
 * <p>TODO тебе при доводке (следующий этап):
 * <ul>
 *   <li>Добавить accessor-mixin к VulkanDevice: {@code VkDevice}, queue, VMA allocator.</li>
 *   <li>Создать VkBuffer/VkImage для VoxelVolume через VMA, аплоад через staging.</li>
 *   <li>Создать compute pipeline из SPIR-V (см. assets/sunflower/shaders/rt/).</li>
 * </ul>
 */
public final class GpuBridge {
    private GpuBridge() {}

    public enum BackendKind {
        VULKAN,
        OPENGL,
        UNKNOWN
    }

    public record BackendInfo(BackendKind kind, String rawName, String detail) {}

    public static BackendInfo probeBackend() {
        // Путь 1: прямой вопрос к девайсу. backendName — строгое имя ("Vulkan"/"OpenGL").
        try {
            GpuDevice device = RenderSystem.tryGetDevice();
            if (device != null) {
                String backend = device.getDeviceInfo().backendName();
                BackendInfo info = classifyStrict(backend, "GpuDevice.getDeviceInfo().backendName()");
                if (info.kind() != BackendKind.UNKNOWN) {
                    Sunflower.LOGGER.debug("[sunflower-rt] gpu: {} / {} / {}",
                            device.getDeviceInfo().name(),
                            device.getDeviceInfo().vendorName(),
                            device.getDeviceInfo().driverInfo());
                    return info;
                }
                Sunflower.LOGGER.warn("[sunflower-rt] неизвестный backendName='{}', считаю UNKNOWN.", backend);
                return info;
            }
        } catch (Exception e) {
            Sunflower.LOGGER.debug("[sunflower-rt] probe path GpuDevice failed: {}", e.toString());
        }

        // Путь 2: options.txt preferredGraphicsBackend (работает и до создания девайса).
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.gameDirectory != null) {
                java.nio.file.Path options = mc.gameDirectory.toPath().resolve("options.txt");
                if (java.nio.file.Files.isRegularFile(options)) {
                    String text = java.nio.file.Files.readString(options);
                    if (text.contains("preferredGraphicsBackend:\"vulkan\"")
                            || text.contains("preferredGraphicsBackend:vulkan")) {
                        return new BackendInfo(BackendKind.VULKAN, "vulkan", "options.txt");
                    }
                    if (text.contains("preferredGraphicsBackend:\"opengl\"")
                            || text.contains("preferredGraphicsBackend:opengl")) {
                        return new BackendInfo(BackendKind.OPENGL, "opengl", "options.txt");
                    }
                }
            }
        } catch (Exception e) {
            Sunflower.LOGGER.debug("[sunflower-rt] probe path options.txt failed: {}", e.toString());
        }

        return new BackendInfo(BackendKind.UNKNOWN, "unknown", "device=null, no options match");
    }

    /**
     * Строгое сравнение — ТОЛЬКО equals. Никаких contains: строка "LWJGL version ..."
     * содержит подстроку "gl", и contains-классификатор врал "OpenGL" на Vulkan-системе.
     */
    private static BackendInfo classifyStrict(String rawName, String via) {
        if (rawName == null) {
            return new BackendInfo(BackendKind.UNKNOWN, "null", via);
        }
        String norm = rawName.trim();
        if (norm.equalsIgnoreCase("vulkan")) {
            return new BackendInfo(BackendKind.VULKAN, rawName, via);
        }
        if (norm.equalsIgnoreCase("opengl") || norm.equalsIgnoreCase("gl")) {
            return new BackendInfo(BackendKind.OPENGL, rawName, via);
        }
        return new BackendInfo(BackendKind.UNKNOWN, rawName, via);
    }

    /** Быстрая проверка требований GT 650M: только лог, решение принимает RtBoot. */
    public static void logRequirementsReminder() {
        Sunflower.LOGGER.info(
                "[sunflower-rt] требуется Vulkan 1.2 + VK_KHR_dynamic_rendering + VK_KHR_push_descriptor"
                        + " + VK_KHR_synchronization2 + VK_KHR_swapchain. GT 650M на драйвере 475+ проходит, на старом — нет.");
    }
}
