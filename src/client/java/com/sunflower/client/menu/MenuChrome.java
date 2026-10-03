package com.sunflower.client.menu;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * Общая отрисовка меню: тёмная панель с рамкой, заголовок, подпись и строки «ключ: значение».
 *
 * <p>Порядок слоёв в 26.2 важен. {@code Screen.extractRenderStateWithTooltipAndSubtitles}
 * делает ровно это:
 * <pre>
 *   nextStratum(); extractBackground();   // фон мира + размытие
 *   nextStratum(); extractRenderState();  // наш код + виджеты
 * </pre>
 * Значит панель можно рисовать первым вызовом в {@code extractRenderState} — она попадёт
 * в слой контента, но раньше виджетов, и кнопки окажутся поверх.
 *
 * <p>Цвета — ARGB int, как во всех ванильных {@code fill}.
 */
public final class MenuChrome {
    /** Фон панели: почти чёрный, слегка синий, 80% непрозрачности. */
    public static final int PANEL_BG = 0xCC0D1117;
    /** Рамка панели — приглушённый янтарный (цвет подсолнуха). */
    public static final int PANEL_BORDER = 0xFF6B5A2E;
    /** Заголовок экрана. */
    public static final int TITLE = 0xFFFFFFFF;
    /** Обычный текст. */
    public static final int TEXT = 0xFFD6D6D6;
    /** Приглушённый текст (диагностика, подсказки). */
    public static final int TEXT_DIM = 0xFF8A8F98;
    /** Зелёный — «в порядке». */
    public static final int OK = 0xFF7BD88F;
    /** Красный — «сломано». */
    public static final int BAD = 0xFFFF6B6B;

    private MenuChrome() {}

    /** Панель: заливка + рамка в 1px (двумя fill'ами, чтобы не зависеть от толщины outline). */
    public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, PANEL_BORDER);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, PANEL_BG);
    }

    /** Заголовок по центру экрана. */
    public static void title(GuiGraphicsExtractor g, Font font, int cx, int y, String text, int color) {
        g.centeredText(font, Component.literal(text), cx, y, color);
    }

    /** Приглушённая подпись по центру экрана. */
    public static void subtitle(GuiGraphicsExtractor g, Font font, int cx, int y, String text) {
        g.centeredText(font, Component.literal(text), cx, y, TEXT_DIM);
    }

    /** Строка «ключ: значение» — ключ серый, значение своим цветом. */
    public static void kv(GuiGraphicsExtractor g, Font font, int keyX, int valueX, int y,
            String key, String value, int valueColor) {
        g.text(font, Component.literal(key), keyX, y, TEXT_DIM);
        g.text(font, Component.literal(value), valueX, y, valueColor);
    }
}