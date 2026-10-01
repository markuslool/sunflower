package com.sunflower.client.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Упаковка координат секций. Именно здесь была реальная дыра: маска X была 8 бит
 * вместо 21, и секции дальше ~2048 блоков схлопывались — "мир ехал за игроком".
 * Тесты закрывают именно этот класс ошибок.
 */
class SectionKeysTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, -1, 15, -15, 100, -100, 2048, -2048, 100_000, -100_000})
    @DisplayName("pack/unpack возвращает исходную координату")
    void roundTrip(int v) {
        long key = SectionKeys.pack(v, v, v);
        assertEquals(v, SectionKeys.unpackX(key), "X");
        assertEquals(v, SectionKeys.unpackY(key), "Y");
        assertEquals(v, SectionKeys.unpackZ(key), "Z");
    }

    @Test
    @DisplayName("оси не пересекаются: разные точки дают разные ключи")
    void axesDoNotCollide() {
        // Специально широкие координаты — на грани старой 8-битной маски X.
        assertTrue(SectionKeys.pack(5000, -3000, 700) != SectionKeys.pack(5000, -3000, -700));
        assertTrue(SectionKeys.pack(5000, -3000, 700) != SectionKeys.pack(-5000, -3000, 700));
        assertTrue(SectionKeys.pack(5000, -3000, 700) != SectionKeys.pack(5000, 3000, 700));
    }

    @Test
    @DisplayName("соседние точки не дают одинаковый ключ")
    void neighboursAreDistinct() {
        for (int base = -3000; base <= 3000; base += 7) {
            for (int d = -3; d <= 3; d++) {
                if (d == 0) {
                    continue; // нулевое смещение — ключ совпадает по построению
                }
                assertTrue(SectionKeys.pack(base, base, base) != SectionKeys.pack(base + d, base, base),
                        "коллизия X при " + base + "+" + d);
                assertTrue(SectionKeys.pack(base, base, base) != SectionKeys.pack(base, base + d, base),
                        "коллизия Y при " + base + "+" + d);
                assertTrue(SectionKeys.pack(base, base, base) != SectionKeys.pack(base, base, base + d),
                        "коллизия Z при " + base + "+" + d);
            }
        }
    }

    @Test
    @DisplayName("пределы 21 бита со знаком")
    void limits() {
        int max = SectionKeys.MAX_SECTION;
        assertEquals(max, SectionKeys.unpackX(SectionKeys.pack(max, max, max)));
        assertEquals(-max - 1, SectionKeys.unpackX(SectionKeys.pack(-max - 1, -max - 1, -max - 1)));
        assertTrue(SectionKeys.fits(max));
        assertTrue(SectionKeys.fits(-max - 1));
        assertTrue(!SectionKeys.fits(max + 1), "вне диапазона must not fit");
        assertTrue(!SectionKeys.fits(-max - 2), "вне диапазона must not fit");
    }
}