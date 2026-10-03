package com.sunflower.client.menu;

import com.sunflower.client.RtSettingsScreen;
import com.sunflower.client.rt.RtBoot;
import com.sunflower.client.rt.RtConfig;
import com.sunflower.client.rt.RtOverlay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Главное меню мода. Открывается кнопкой «Sunflower» из меню паузы (ESC),
 * а также командой {@code /sunflower}.
 *
 * <p>Отдельная кнопка «RTX» уводит в {@link RtSettingsScreen} — настройки
 * вынесены в отдельный экран с вкладками RT / Shadow / Свет.
 */
public final class SunflowerMenuScreen extends Screen {
    private static final int BUTTON_W = 220;
    private static final int BUTTON_H = 22;
    private static final int GAP = 6;
    private static final int PAD = 16;

    private final Screen parent;

    private int panelX;
    private int panelY;
    private int panelW;
    private int panelH;

    public SunflowerMenuScreen(Screen parent) {
        super(Component.literal("Sunflower"));
        this.parent = parent;
    }

    /** Открыть главное меню (parent может быть null — вернёмся в игру). */
    public static void open(Screen parent) {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(new SunflowerMenuScreen(parent)));
    }

    @Override
    protected void init() {
        // Панель по высоте контента: заголовок + 4 строки статуса + 3 кнопки.
        int buttonsH = 3 * BUTTON_H + 2 * GAP;
        panelW = BUTTON_W + 2 * PAD;
        panelH = 62 + 4 * 11 + 10 + buttonsH + PAD;
        panelW = Math.min(panelW, Math.max(200, width - 20));
        panelX = (width - panelW) / 2;
        panelY = Math.max(8, (height - panelH) / 2);

        int x = panelX + (panelW - BUTTON_W) / 2;
        int y = panelY + 62 + 4 * 11 + 10;

        addRenderableWidget(Button.builder(Component.literal("RTX — трассировка"),
                        b -> RtSettingsScreen.open(this))
                .bounds(x, y, BUTTON_W, BUTTON_H).build());
        y += BUTTON_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Статус в чат"), b -> sendStatus())
                .bounds(x, y, BUTTON_W, BUTTON_H).build());
        y += BUTTON_H + GAP;

        addRenderableWidget(Button.builder(Component.literal("Готово"), b -> onClose())
                .bounds(x, y, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2;
        MenuChrome.panel(g, panelX, panelY, panelW, panelH);

        MenuChrome.title(g, font, cx, panelY + 12, "SUNFLOWER", MenuChrome.TITLE);
        MenuChrome.subtitle(g, font, cx, panelY + 26, "Voxel ray tracing для слабых видеокарт");

        // Таблица статуса. Значения берём из тех же геттеров, что и /sunflower status,
        // чтобы на экране и в чате не расходились цифры.
        RtConfig cfg = RtBoot.config();
        int keyX = panelX + PAD;
        int valueX = panelX + panelW - PAD - 78;
        int y = panelY + 62;
        int step = 11;

        MenuChrome.kv(g, font, keyX, y, valueX, "Бэкенд",
                RtBoot.isVulkanActive() ? "Vulkan OK" : "НЕ Vulkan",
                RtBoot.isVulkanActive() ? MenuChrome.OK : MenuChrome.BAD);
        y += step;

        MenuChrome.kv(g, font, keyX, y, valueX, "Трассировка",
                cfg.enabled ? "вкл" : "выкл",
                cfg.enabled ? MenuChrome.OK : MenuChrome.TEXT_DIM);
        y += step;

        MenuChrome.kv(g, font, keyX, y, valueX, "Шаг луча",
                cfg.rayStride + "x" + cfg.rayStride, MenuChrome.TEXT);
        y += step;

        MenuChrome.kv(g, font, keyX, y, valueX, "Кадров",
                String.valueOf(RtOverlay.framesDrawn()), MenuChrome.TEXT_DIM);

        super.extractRenderState(g, mouseX, mouseY, partialTick);
    }

    private void sendStatus() {
        Minecraft mc = Minecraft.getInstance();
        // В 26.2 у Gui нет getChat(), а меню паузы живёт только в мире —
        // если игрока нет (открыли извне), просто молча выходим.
        if (mc.player == null) {
            return;
        }
        for (String line : RtBoot.statusLines()) {
            mc.player.sendSystemMessage(Component.literal("§e[Sunflower] §f" + line));
        }
    }

    @Override
    public void onClose() {
        Minecraft mc = Minecraft.getInstance();
        mc.execute(() -> mc.gui.setScreen(parent));
    }
}