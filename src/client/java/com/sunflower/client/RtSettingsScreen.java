package com.sunflower.client;

import com.sunflower.Sunflower;
import com.sunflower.client.rt.RtBoot;
import com.sunflower.client.rt.RtConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Экран настроек RT.
 *
 * <p>Открыть: командой {@code /sunflower rt} (см. SunflowerClient).
 * TODO тебе: добавить кнопку в ванильный Video Settings когда уточнишь имя
 * класса экрана в 26.2 (в 26.2 Gui/Hud реорганизация — имя могло смениться).
 *
 * <p>Переключение экранов — напрямую через {@code mc.gui.setScreen()}
 * (в 26.2 setScreen живет в Gui).
 */
public final class RtSettingsScreen extends Screen {
    private final Screen parent;

    public RtSettingsScreen(Screen parent) {
        super(Component.literal("Sunflower RT v1 full-res (stride 2x2/4x4 — в v1.1)"));
        this.parent = parent;
    }

    /** Открыть экран настроек. */
    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new RtSettingsScreen(parent)));
    }

    /** Закрыть обратно на parent. */
    public static void closeTo(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(parent));
    }

    @Override
    protected void init() {
        RtConfig cfg = RtBoot.config();
        int cx = this.width / 2 - 130;
        int y = 36;
        int step = 24;

        addRenderableWidget(Button.builder(
                Component.literal("RT: " + (cfg.enabled ? "ВКЛ" : "ВЫКЛ")),
                b -> {
                    cfg.enabled = !cfg.enabled;
                    RtBoot.saveConfig();
                    rebuildWidgets();
                }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(
                Component.literal("Шаг луча: " + cfg.rayStride + "x" + cfg.rayStride
                        + (cfg.rayStride == 1 ? " (1x1 натив)" : cfg.rayStride == 2 ? " (1 луч на 2x2)" : " (1 луч на 4x4)")),
                b -> {
                    cfg.setRayStride(cfg.rayStride == 1 ? 2 : cfg.rayStride == 2 ? 4 : 1);
                    RtBoot.saveConfig();
                    rebuildWidgets();
                }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(
                Component.literal("Дальность тени: " + cfg.shadowDistance + " блоков [32/64/96/128]"),
                b -> {
                    if (cfg.shadowDistance < 64) {
                        cfg.shadowDistance = 64;
                    } else if (cfg.shadowDistance < 96) {
                        cfg.shadowDistance = 96;
                    } else if (cfg.shadowDistance < 128) {
                        cfg.shadowDistance = 128;
                    } else {
                        cfg.shadowDistance = 32;
                    }
                    RtBoot.saveConfig();
                    rebuildWidgets();
                }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(
                Component.literal("Макс. шагов DDA: " + cfg.maxSteps + " [64/128/192]"),
                b -> {
                    if (cfg.maxSteps < 128) {
                        cfg.maxSteps = 128;
                    } else if (cfg.maxSteps < 192) {
                        cfg.maxSteps = 192;
                    } else {
                        cfg.maxSteps = 64;
                    }
                    RtBoot.saveConfig();
                    rebuildWidgets();
                }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Пресет Potato GT 650M (4x4, 32, 32)"), b -> {
            cfg.applyPotatoPreset();
            RtBoot.saveConfig();
            rebuildWidgets();
        }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Пресет Low (2x2, 64, 64)"), b -> {
            cfg.applyLowPreset();
            RtBoot.saveConfig();
            rebuildWidgets();
        }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Пресет Medium (1x1, 96, 96)"), b -> {
            cfg.applyMediumPreset();
            RtBoot.saveConfig();
            rebuildWidgets();
        }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal(
                RtBoot.isVulkanActive() ? "Бэкенд: Vulkan OK (нажми чтобы перепроверить)" : "Бэкенд: НЕ Vulkan — RT выкл (нажми чтобы перепроверить)"),
                b -> {
                    RtBoot.refreshBackendState();
                    rebuildWidgets();
                }).pos(cx, y).size(260, 20).build());
        y += step;

        addRenderableWidget(Button.builder(Component.literal("Готово"), b ->
                RtSettingsScreen.closeTo(parent)).pos(cx, y).size(260, 20).build());
    }

    @Override
    public void onClose() {
        RtBoot.saveConfig();
        Minecraft.getInstance().gui.setScreen(parent);
    }
}
