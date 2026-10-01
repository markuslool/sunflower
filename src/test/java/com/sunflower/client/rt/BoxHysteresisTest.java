package com.sunflower.client.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Математика бокса: снап по секциям, кламп по высоте мира, гистерезис.
 *
 * <p>Гистерезис — не оптимизация, а защита от просадки кадра: каждый сдвиг бокса
 * означает перезаливку буфера, а без гистерезиса бокс уезжал каждые 16 блоков.
 */
class BoxHysteresisTest {
    private static final int W = 192;
    private static final int H = 96;
    private static final int D = 192;

    @ParameterizedTest
    @CsvSource({
            "0, 0", "1, 0", "15, 0", "16, 16", "17, 16", "-1, -16", "-16, -16", "-17, -32", "31, 16",
            "-31, -32", "4095, 4080", "-4095, -4096"
    })
    @DisplayName("snapDown корректен и для отрицательных значений")
    void snapDown(int input, int expected) {
        assertEquals(expected, BoxHysteresis.snapDown(input, 16));
        assertTrue(BoxHysteresis.snapDown(input, 16) <= input, "снап только вниз");
    }

    @Test
    @DisplayName("мягкая зона = 1/6 размера бокса")
    void marginIsSixth() {
        assertEquals(32, BoxHysteresis.margin(192));
        assertEquals(16, BoxHysteresis.margin(96));
    }

    @Test
    @DisplayName("неинициализированный бокс всегда требует сдвига")
    void uninitialisedAlwaysMoves() {
        assertTrue(BoxHysteresis.shouldRecenter(false, 0, 0, 0, W, H, D, 0, 0, 0));
    }

    @Test
    @DisplayName("центр бокса внутри мягкой зоны — сдвига нет")
    void centreDoesNotMove() {
        int bx = -96;
        int by = 64;
        int bz = -96;
        assertFalse(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D,
                bx + W / 2, by + H / 2, bz + D / 2));
    }

    @Test
    @DisplayName("бокс уезжает реже, чем раз в 16 блоков (проверка гистерезиса)")
    void hysteresisActuallyDelays() {
        int bx = -96;
        int by = 64;
        int bz = -96;
        int startX = bx + BoxHysteresis.margin(W);
        int moves = 0;
        for (int x = startX; x < startX + W - 2 * BoxHysteresis.margin(W); x++) {
            if (BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D, x, by + H / 2, bz + D / 2)) {
                moves++;
            }
        }
        assertEquals(0, moves, "в пределах мягкой зоны сдвигов быть не должно");
        // За её пределами — сдвиг есть.
        assertTrue(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D,
                bx + BoxHysteresis.margin(W) - 1, by + H / 2, bz + D / 2));
    }

    @Test
    @DisplayName("границы мягкой зоны включительно-строгие")
    void softZoneBoundaries() {
        int bx = -96;
        int by = 64;
        int bz = -96;
        int m = BoxHysteresis.margin(W);
        assertFalse(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D, bx + m, by + H / 2, bz + D / 2),
                "левый край мягкой зоны — еще внутри");
        assertTrue(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D, bx + m - 1, by + H / 2, bz + D / 2),
                "на шаг левее — уже сдвиг");
        assertFalse(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D, bx + W - m - 1, by + H / 2, bz + D / 2),
                "правый край мягкой зоны — еще внутри");
        assertTrue(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D, bx + W - m, by + H / 2, bz + D / 2),
                "на шаг правее — уже сдвиг");
    }

    @Test
    @DisplayName("игрок вне бокса по Y тоже вызывает сдвиг")
    void verticalLeavesZone() {
        int bx = -96;
        int by = 64;
        int bz = -96;
        int my = BoxHysteresis.margin(H);
        assertTrue(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D,
                bx + W / 2, by + my - 1, bz + D / 2));
        assertTrue(BoxHysteresis.shouldRecenter(true, bx, by, bz, W, H, D,
                bx + W / 2, by + H - my, bz + D / 2));
    }

    @Test
    @DisplayName("кламп по высоте мира держит бокс внутри границ")
    void clampInsideWorld() {
        // Оверворлд -64..320, бокс 96: база в [-64..224].
        assertEquals(-64, BoxHysteresis.clampBaseY(-1000, H, -64, 320));
        assertEquals(224, BoxHysteresis.clampBaseY(1000, H, -64, 320));
        assertEquals(64, BoxHysteresis.clampBaseY(64, H, -64, 320), "внутри не трогаем");
        // Нижний мир 0..128: бокс 96 => база в [0..32].
        assertEquals(0, BoxHysteresis.clampBaseY(-1000, H, 0, 128));
        assertEquals(32, BoxHysteresis.clampBaseY(1000, H, 0, 128));
    }

    @Test
    @DisplayName("мир ниже бокса не инвертирует кламп")
    void tinyWorldDoesNotInvertClamp() {
        // Мир 0..64, бокс 96 — больше мира. Должно быть 0, а не отрицательная база.
        assertEquals(0, BoxHysteresis.clampBaseY(-1000, H, 0, 64));
        assertEquals(0, BoxHysteresis.clampBaseY(1000, H, 0, 64));
    }

    @Test
    @DisplayName("база центрируется на игроке и кратна 16")
    void centeredBase() {
        assertEquals(-96, BoxHysteresis.centeredBase(0, W));
        assertEquals(0, BoxHysteresis.centeredBase(96, W));
        // -40 - 96 = -136 -> snap вниз до кратного 16 = -144
        assertEquals(-144, BoxHysteresis.centeredBase(-40, W));
        assertEquals(0, BoxHysteresis.centeredBase(0, W) % 16, "база кратна секции");
    }
}