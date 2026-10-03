package com.sunflower.client;

import com.sunflower.client.menu.MenuChrome;
import com.sunflower.client.rt.RtBoot;
import com.sunflower.client.rt.RtClipmap;
import com.sunflower.client.rt.RtConfig;
import com.sunflower.client.rt.RtOverlay;
import com.sunflower.client.rt.ShadowClipmap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Экран настроек RT с вкладками.
 *
 * <ul>
 *   <li><b>RT</b> — трассировка: включение, шаг луча, шаги DDA, пресеты, view-bob, бэкенд.</li>
 *   <li><b>Shadow</b> — тени: дальность, сила, мягкость, режим отладки, диагностика.</li>
 *   <li><b>Свет</b> — свет: экспозиция, размер солнца, ночной свет, облачные тени.</li>
 * </ul>
 *
 * <p>Открывается из {@code SunflowerMenuScreen} кнопкой «RTX» и командой {@code /sunflower rt}.
 * Переключение экранов — напрямую через {@code mc.gui.setScreen()} (в 26.2 setScreen живёт в Gui).
 */
public final class RtSettingsScreen extends Screen {
    /** Вкладки. Порядок = порядок кнопок в таб-баре. */
    private enum Tab {
        RT("RT", "Трассировка"),
        SHADOW("Shadow", "Тени"),
        LIGHT("Свет", "Свет и солнце"),
        TRACE("Трассировка", "Клипмап теней CSM");

        private final String label;
        private final String hint;

        Tab(String label, String hint) {
            this.label = label;
            this.hint = hint;
        }
    }

    private static final int BUTTON_W = 230;
    private static final int BUTTON_H = 20;
    private static final int STEP = 24;
    private static final int PAD = 14;
    private static final int TAB_H = 20;

    private final Screen parent;
    private Tab tab = Tab.RT;

    // Раскладка панели. Считается в init(), нужна и в init(), и в рендере.
    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;
    private int contentX;
    private int contentY;

    public RtSettingsScreen(Screen parent) {
        super(Component.literal("Sunflower"));
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

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        int rows = rowsInTab();
        // Раскладка: шапка 76px, контент rows*STEP, потом футер и рамка.
        // Считаем от начала панели, чтобы футер гарантированно не наезжал на контент.
        panelW = BUTTON_W + 2 * PAD;
        panelH = Math.min(height - 12, 76 + rows * STEP + 8 + BUTTON_H + PAD);
        panelW = Math.min(panelW, Math.max(180, width - 16));
        panelX = (width - panelW) / 2;
        panelY = Math.max(6, (height - panelH) / 2);

        contentX = panelX + (panelW - BUTTON_W) / 2;
        contentY = panelY + 76;

        buildTabs();
        switch (tab) {
            case RT -> buildRtTab();
            case SHADOW -> buildShadowTab();
            case LIGHT -> buildLightTab();
            case TRACE -> buildTraceTab();
        }
        buildFooter();
    }

    /** Сколько строк контента в текущей вкладке — от этого зависит высота панели. */
    private int rowsInTab() {
        return switch (tab) {
            case RT -> 10;
            case SHADOW -> 6;
            case LIGHT -> 7;
            case TRACE -> 9;
        };
    }

    private void buildTabs() {
        int gap = 4;
        int tabW = (panelW - 2 * PAD - 2 * gap) / Tab.values().length;
        int x = panelX + PAD;
        int y = panelY + 40;
        for (Tab t : Tab.values()) {
            Button b = Button.builder(Component.literal(t.label), btn -> {
                        tab = t;
                        rebuildWidgets();
                    })
                    .bounds(x, y, tabW, TAB_H).build();
            // Активная вкладка гасится — читается как «здесь мы уже».
            if (t == tab) {
                b.active = false;
            }
            addRenderableWidget(b);
            x += tabW + gap;
        }
    }

    private void buildFooter() {
        int y = panelY + 76 + rowsInTab() * STEP + 8;
        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
                .bounds(contentX, y, BUTTON_W, BUTTON_H).build());
    }

    /** Кнопка на всю ширину контента. */
    private void row(String label, Runnable action) {
        addRenderableWidget(Button.builder(Component.literal(label), b -> {
                    action.run();
                    rebuildWidgets();
                })
                .bounds(contentX, contentY, BUTTON_W, BUTTON_H).build());
        contentY += STEP;
    }

    // ------------------------------------------------------------------ вкладка RT

    private void buildRtTab() {
        RtConfig cfg = RtBoot.config();

        row("Трассировка: " + (cfg.enabled ? "ВКЛ" : "ВЫКЛ"), () -> {
            cfg.enabled = !cfg.enabled;
            RtBoot.saveConfig();
        });
        row("Шаг луча: " + cfg.rayStride + "x" + cfg.rayStride + " " + strideHint(cfg.rayStride), () -> {
            cfg.setRayStride(cfg.rayStride == 1 ? 2 : cfg.rayStride == 2 ? 4 : 1);
            RtBoot.saveConfig();
        });
        row("Макс. шагов DDA: " + cfg.maxSteps + " [64/128/192]", () -> {
            if (cfg.maxSteps < 128) {
                cfg.maxSteps = 128;
            } else if (cfg.maxSteps < 192) {
                cfg.maxSteps = 192;
            } else {
                cfg.maxSteps = 64;
            }
            RtBoot.saveConfig();
        });
        row("Секций за кадр: " + cfg.sectionsPerFrame + " (1..24)", () -> {
            cfg.sectionsPerFrame = cfg.sectionsPerFrame >= 24 ? 1 : cfg.sectionsPerFrame + 1;
            RtBoot.saveConfig();
        });
        row("Пресет Potato GT 650M (4x4, 32, 32)", () -> {
            cfg.applyPotatoPreset();
            RtBoot.saveConfig();
        });
        row("Пресет Low (2x2, 64, 64)", () -> {
            cfg.applyLowPreset();
            RtBoot.saveConfig();
        });
        row("Пресет Medium (1x1, 96, 96)", () -> {
            cfg.applyMediumPreset();
            RtBoot.saveConfig();
        });
        row(RtOverlay.useBob() != 0 ? "Лучи: с view-bob (как террейн)" : "Лучи: базовая проекция (Sodium)", () ->
                RtOverlay.setUseBob(RtOverlay.useBob() != 0 ? 0 : 1));
        row(RtBoot.isVulkanActive() ? "Бэкенд: Vulkan OK (перепроверить)" : "Бэкенд: НЕ Vulkan — RT выкл", () -> {
            RtBoot.refreshBackendState();
            rebuildWidgets();
        });
        row("Сбросить вкладку RT", () -> {
            cfg.enabled = true;
            cfg.setRayStride(2);
            cfg.maxSteps = 128;
            cfg.sectionsPerFrame = 6;
            RtOverlay.setUseBob(1);
            RtBoot.saveConfig();
        });
    }

    // ------------------------------------------------------------------ вкладка Shadow

    private void buildShadowTab() {
        RtConfig cfg = RtBoot.config();

        row("Дальность тени: " + cfg.shadowDistance + " блоков", () -> {
            cfg.shadowDistance = cfg.shadowDistance < 64 ? 64 : cfg.shadowDistance < 96 ? 96
                    : cfg.shadowDistance < 128 ? 128 : 32;
            RtBoot.saveConfig();
        });
        row("Сила теней: " + Math.round(cfg.shadowStrength * 100.0F) + "%", () -> {
            cfg.shadowStrength = cycle(cfg.shadowStrength,
                    new float[] {0.20F, 0.35F, 0.50F, 0.65F, 0.80F, 1.00F});
            RtBoot.saveConfig();
        });
        row("Мягкость: " + softLabel(cfg.softShadows), () -> {
            cfg.softShadows = cfg.softShadows >= 2 ? 0 : cfg.softShadows + 1;
            RtBoot.saveConfig();
        });
        row("Режим отладки: " + debugLabel(RtOverlay.debugMode()), () ->
                RtOverlay.setDebugMode((RtOverlay.debugMode() + 1) % 3));
        row("Диагностика в чат", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§e[Sunflower] §fsun=("
                        + String.format("%.2f", RtOverlay.lastSunX()) + ","
                        + String.format("%.2f", RtOverlay.lastSunY()) + ") strength="
                        + String.format("%.2f", RtOverlay.lastStrength())
                        + " frames=" + RtOverlay.framesDrawn()
                        + " fill=" + RtOverlay.lastFilled()
                        + " upload=" + RtOverlay.lastUploadedBytes() + "B"
                        + " state=" + RtOverlay.skipReason()));
            }
        });
        row("Сбросить вкладку Shadow", () -> {
            cfg.resetShadowSettings();
            RtOverlay.setDebugMode(0);
            RtBoot.saveConfig();
        });
    }

    // ------------------------------------------------------------------ вкладка Свет

    private void buildLightTab() {
        RtConfig cfg = RtBoot.config();

        row("Экспозиция: x" + String.format("%.2f", cfg.lightExposure), () -> {
            cfg.lightExposure = cycle(cfg.lightExposure,
                    new float[] {0.25F, 0.50F, 0.65F, 0.80F, 1.00F, 1.25F, 1.50F});
            RtBoot.saveConfig();
        });
        row("Размер солнца: " + String.format("%.3f", cfg.sunSize) + " рад", () -> {
            cfg.sunSize = cycle(cfg.sunSize,
                    new float[] {0.010F, 0.020F, 0.035F, 0.060F, 0.100F});
            RtBoot.saveConfig();
        });
        row(cfg.nightLight ? "Ночной свет: ВКЛ" : "Ночной свет: ВЫКЛ", () -> {
            cfg.nightLight = !cfg.nightLight;
            RtBoot.saveConfig();
        });
        row(cfg.cloudShadows ? "Облачные тени: ВКЛ" : "Облачные тени: ВЫКЛ", () -> {
            cfg.cloudShadows = !cfg.cloudShadows;
            RtBoot.saveConfig();
        });
        row("Свет сейчас: " + sunStateLabel(), () -> RtBoot.refreshBackendState());
        row("Диагностика в чат", () -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) {
                mc.player.sendSystemMessage(Component.literal("§e[Sunflower] §fexposure="
                        + String.format("%.2f", cfg.lightExposure)
                        + " sunSize=" + String.format("%.3f", cfg.sunSize)
                        + " night=" + cfg.nightLight
                        + " clouds=" + cfg.cloudShadows
                        + " sun=(" + String.format("%.2f", RtOverlay.lastSunX()) + ","
                        + String.format("%.2f", RtOverlay.lastSunY()) + ")"));
            }
        });
        row("Сбросить вкладку Свет", () -> {
            cfg.resetLightSettings();
            RtBoot.saveConfig();
        });
    }

    // ------------------------------------------------------------------ вкладка Трассировка

    private void buildTraceTab() {
        RtConfig cfg = RtBoot.config();

        row(cfg.clipmap ? "Клипмап теней: ВКЛ" : "Клипмап теней: ВЫКЛ (одиночный марч)", () -> {
            cfg.clipmap = !cfg.clipmap;
            RtBoot.saveConfig();
        });
        row("Каскадов: " + cfg.cascades + " (1.." + ShadowClipmap.MAX_CASCADES + ")", () -> {
            cfg.cascades = cfg.cascades >= ShadowClipmap.MAX_CASCADES ? 1 : cfg.cascades + 1;
            RtBoot.saveConfig();
        });
        row("Разрешение карты: " + cfg.clipmapResolution + "px", () -> {
            cfg.clipmapResolution = cfg.clipmapResolution >= 2048 ? 512 : cfg.clipmapResolution * 2;
            RtBoot.saveConfig();
        });
        row("Дальность клипмапа: " + cfg.clipmapDistance + " блоков", () -> {
            cfg.clipmapDistance = cfg.clipmapDistance >= 256 ? 64 : cfg.clipmapDistance * 2;
            RtBoot.saveConfig();
        });
        row("Перекрытие каскадов: " + Math.round(cfg.cascadeBlend * 100.0F) + "%", () -> {
            cfg.cascadeBlend = cycle(cfg.cascadeBlend, new float[] {0.0F, 0.05F, 0.1F, 0.2F, 0.35F});
            RtBoot.saveConfig();
        });
        row("Сглаживание (PCF): " + cfg.clipmapPcf + " тап(ов)", () -> {
            cfg.clipmapPcf = switch (cfg.clipmapPcf) {
                case 1 -> 3;
                case 3 -> 5;
                case 5 -> 9;
                default -> 1;
            };
            RtBoot.saveConfig();
        });
        row("Смещение тени: " + String.format("%.1f", cfg.shadowBias) + " текселя", () -> {
            cfg.shadowBias = cycle(cfg.shadowBias, new float[] {0.0F, 0.5F, 1.0F, 1.5F, 2.0F, 3.0F});
            RtBoot.saveConfig();
        });
        row("Раскладка каскадов", () -> sendClipmapInfo());
        row("Сбросить вкладку Трассировка", () -> {
            cfg.resetClipmapSettings();
            RtBoot.saveConfig();
        });
    }

    private void sendClipmapInfo() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        RtConfig cfg = RtBoot.config();
        StringBuilder sb = new StringBuilder("clipmap=").append(cfg.clipmap)
                .append(" cascades=").append(cfg.cascades)
                .append(" res=").append(cfg.clipmapResolution)
                .append(" dist=").append(cfg.clipmapDistance)
                .append(" pcf=").append(cfg.clipmapPcf);
        mc.player.sendSystemMessage(Component.literal("§e[Sunflower] §f" + sb));

        // Живая раскладка каскадов от реальной геометрии — полезнее, чем просто
        // число из конфига: сразу видно, не сходится ли extent с дальностью.
        try {
            ShadowClipmap.Cascade[] cs = RtClipmap.layout(cfg, camX(), camY(), camZ());
            for (ShadowClipmap.Cascade c : cs) {
                mc.player.sendSystemMessage(Component.literal("§7  каскад " + c.index
                        + ": " + Math.round(c.nearDist) + ".." + Math.round(c.farDist) + " блоков"
                        + ", extent " + Math.round(c.orthoWidth) + " блоков"
                        + ", тексель " + String.format("%.3f", c.texelWorldSize) + " блока"
                        + ", глубина " + Math.round(c.depthRange)));
            }
        } catch (Exception e) {
            mc.player.sendSystemMessage(Component.literal("§c  раскладка недоступна: " + e));
        }
    }

    private static float camX() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? (float) mc.player.getX() : 0.0F;
    }

    private static float camY() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? (float) mc.player.getY() : 0.0F;
    }

    private static float camZ() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null ? (float) mc.player.getZ() : 0.0F;
    }

    // ------------------------------------------------------------------ рендер

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2;
        MenuChrome.panel(g, panelX, panelY, panelW, panelH);

        MenuChrome.title(g, font, cx, panelY + 12, "SUNFLOWER RT", MenuChrome.TITLE);
        MenuChrome.subtitle(g, font, cx, panelY + 26, "Esc — меню паузы, /sunflower rt — сюда");

        // Подсказка активной вкладки — сразу под таб-баром.
        MenuChrome.subtitle(g, font, cx, panelY + 64, tab.label + " — " + tab.hint);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void onClose() {
        RtBoot.saveConfig();
        RtSettingsScreen.closeTo(parent);
    }

    // ------------------------------------------------------------------ подписи

    /** Следующее значение по списку шагов (с зацикливание на последнем). */
    private static float cycle(float current, float[] steps) {
        for (int i = 0; i < steps.length; i++) {
            if (current < steps[i] - 1e-4F) {
                return steps[i];
            }
        }
        return steps[0];
    }

    private static String strideHint(int stride) {
        return switch (stride) {
            case 1 -> "(1x1 натив)";
            case 2 -> "(1 луч на 2x2)";
            default -> "(1 луч на 4x4)";
        };
    }

    /** Подпись режима мягкости: 1/4/8 тапов по диску солнца. */
    private static String softLabel(int mode) {
        return switch (mode) {
            case 0 -> "жесткие (1 тап, быстро)";
            case 1 -> "мягкие (4 тапа)";
            default -> "мягкие (8 тапов, тяжело)";
        };
    }

    private static String debugLabel(int mode) {
        return switch (mode) {
            case 0 -> "тени (обычный)";
            case 1 -> "чернить поверхности";
            default -> "весь экран -50%";
        };
    }

    /** Живое состояние солнца из последнего отрисованного кадра. */
    private static String sunStateLabel() {
        double y = RtOverlay.lastSunY();
        if (RtOverlay.framesDrawn() == 0) {
            return "пасс ещё не рисовался";
        }
        if (y <= 0.02) {
            return "ночь" + (RtBoot.config().nightLight ? " (луна)" : " — свет выключен");
        }
        return "день, высота " + String.format("%.2f", y);
    }
}