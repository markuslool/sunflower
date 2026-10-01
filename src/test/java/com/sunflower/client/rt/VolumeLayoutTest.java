package com.sunflower.client.rt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Раскладка upload-буфера {@code [L0 | L2]}.
 *
 * <p>Ключевой тест — согласие с формулами из {@code rt_overlay.fsh}. Расхождение
 * между CPU и GLSL не даёт ошибки компиляции: шейдер молча читает не то и рисует
 * фантомные тени, поэтому сверка обязана быть автоматической.
 */
class VolumeLayoutTest {
    private static final int W = 192;
    private static final int H = 96;
    private static final int D = 192;

    @Test
    @DisplayName("индекс L0 совпадает с формулой шейдера (y*D + z)*W + x")
    void l0MatchesShader() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        for (int x = 0; x < W; x += 7) {
            for (int y = 0; y < H; y += 5) {
                for (int z = 0; z < D; z += 11) {
                    assertEquals((y * D + z) * W + x, l.l0Index(x, y, z));
                }
            }
        }
    }

    @Test
    @DisplayName("L2 начинается ровно за L0 и не перекрывается с ним")
    void mipStartsAfterL0() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        assertEquals(W * H * D, l.mipOffset);
        int lastL0 = l.l0Index(W - 1, H - 1, D - 1);
        assertTrue(l.mipIndex(0, 0, 0) > lastL0, "L2 должен идти после последнего L0");
        assertEquals(l.mipOffset + l.mipCells, l.totalBytes);
    }

    @Test
    @DisplayName("индекс L2 совпадает с формулой шейдера off2 + (my*dimD + mz)*dimW + mx")
    void mipMatchesShader() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        for (int mx = 0; mx < l.mipW; mx += 3) {
            for (int my = 0; my < l.mipH; my += 2) {
                for (int mz = 0; mz < l.mipD; mz += 5) {
                    int expected = l.mipOffset + (my * l.mipD + mz) * l.mipW + mx;
                    assertEquals(expected, l.mipIndex(mx, my, mz));
                }
            }
        }
    }

    @Test
    @DisplayName("каждая ячейка L2 отображается на существующую секцию бокса")
    void everyMipCellMapsToSection() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        int inside = 0;
        for (int mx = 0; mx < l.mipW; mx++) {
            for (int my = 0; my < l.mipH; my++) {
                for (int mz = 0; mz < l.mipD; mz++) {
                    assertTrue(l.mipInsideSections(mx, my, mz),
                            "ячейка вне сетки секций: " + mx + "," + my + "," + mz);
                    int sec = l.mipToSectionIndex(mx, my, mz);
                    assertTrue(sec >= 0 && sec < l.sx * l.sy * l.sz, "индекс секции вне массива");
                    inside++;
                }
            }
        }
        assertEquals(l.mipCells, inside);
    }

    @Test
    @DisplayName("мэппинг ячейка->секция совпадает с формулой шейдера floor((mx*4)/16)")
    void mipToSectionMatchesShader() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        for (int mx = 0; mx < l.mipW; mx++) {
            for (int my = 0; my < l.mipH; my++) {
                for (int mz = 0; mz < l.mipD; mz++) {
                    int ix = (mx * 4) / 16;
                    int iy = (my * 4) / 16;
                    int iz = (mz * 4) / 16;
                    assertEquals((iy * l.sz + iz) * l.sx + ix, l.mipToSectionIndex(mx, my, mz));
                }
            }
        }
    }

    @Test
    @DisplayName("смещение строки Y = y*W*D, строки идут подряд и покрывают весь L0")
    void rowsAreContiguous() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        assertEquals(W * D, l.rowBytes);
        for (int y = 0; y < H; y++) {
            assertEquals(y * l.rowBytes, l.rowOffset(y));
        }
        assertEquals(0, l.rowOffset(0));
        // Последняя строка заканчивается ровно там, где начинается L2.
        assertEquals(l.mipOffset, l.rowOffset(H - 1) + l.rowBytes);
    }

    @Test
    @DisplayName("буфер покрывает все секции и индекс секции в границах")
    void sectionIndexInRange() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        int total = l.sx * l.sy * l.sz;
        assertEquals(total, 12 * 6 * 12);
        assertEquals(0, l.sectionIndex(0, 0, 0));
        assertEquals(total - 1, l.sectionIndex(l.sx - 1, l.sy - 1, l.sz - 1));
    }

    @Test
    @DisplayName("секция занимает ровно 16 строк Y, и все они внутри бокса")
    void sectionRowRange() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        for (int syLocal = 0; syLocal < l.sy; syLocal++) {
            int first = l.sectionFirstRow(syLocal);
            int last = l.sectionLastRow(syLocal);
            // Ровно 16 строк — если пометить только первую, 15 строк секции
            // останутся в GPU старыми (был баг с пятнистыми фантомными тенями).
            assertEquals(16, last - first + 1, "секция должна занимать 16 строк");
            assertEquals(syLocal * 16, first);
            assertTrue(first >= 0 && last < l.h, "строки секции внутри бокса");
            assertTrue(l.sectionRowsInside(syLocal));
        }
        assertTrue(!l.sectionRowsInside(-1));
        assertTrue(!l.sectionRowsInside(l.sy));
    }

    @Test
    @DisplayName("соседние секции по Y не делят строки")
    void sectionRowsDoNotOverlap() {
        VolumeLayout l = new VolumeLayout(W, H, D);
        for (int syLocal = 0; syLocal + 1 < l.sy; syLocal++) {
            assertTrue(l.sectionLastRow(syLocal) < l.sectionFirstRow(syLocal + 1),
                    "секции пересекаются на " + syLocal);
        }
        assertEquals(0, l.sectionFirstRow(0));
        assertEquals(l.h - 1, l.sectionLastRow(l.sy - 1));
    }

    @Test
    @DisplayName("некорректные размеры отвергаются")
    void rejectsBadSizes() {
        for (int[] bad : new int[][] {{0, 96, 192}, {192, 96, 0}, {100, 96, 192}, {192, 33, 192}}) {
            boolean thrown = false;
            try {
                new VolumeLayout(bad[0], bad[1], bad[2]);
            } catch (IllegalArgumentException e) {
                thrown = true;
            }
            assertTrue(thrown, "ожидался отказ для " + bad[0] + "x" + bad[1] + "x" + bad[2]);
        }
    }
}