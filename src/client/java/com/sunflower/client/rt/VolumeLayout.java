package com.sunflower.client.rt;

/**
 * Раскладка воксельного объема в upload-буфере: {@code [L0 | L2]}.
 *
 * <p>Вынесено отдельно от {@link VoxelVolume}, чтобы можно было юнит-тестами
 * сверить индексы с формулами из шейдера {@code rt_overlay.fsh} — расхождение
 * между CPU и GLSL даёт не «ошибку компиляции», а тихие фантомные тени, поэтому
 * тест на это обязателен.
 *
 * <p>L0: воксели бокса W*H*D, индекс {@code (y*D + z)*W + x} (x быстрее всего).
 * L2: occupancy-mip 4x, сетка (W/4)*(H/4)*(D/4), лежит СРАЗУ за L0, индекс
 * {@code off2 + (my*dimD + mz)*dimW + mx}. Буфер непрерывен, поэтому частичная
 * загрузка = один срез по строкам Y плюс хвост L2.
 */
public final class VolumeLayout {
    public final int w;
    public final int h;
    public final int d;
    /** Секций по осям. */
    public final int sx;
    public final int sy;
    public final int sz;
    /** Смещение L2 в буфере = объём L0. */
    public final int mipOffset;
    /** Сетка L2. */
    public final int mipW;
    public final int mipH;
    public final int mipD;
    public final int mipCells;
    /** Полный размер upload-буфера в байтах. */
    public final int totalBytes;
    /** Байт в одной строке Y (весь срез W*D). */
    public final int rowBytes;

    public VolumeLayout(int w, int h, int d) {
        if (w <= 0 || h <= 0 || d <= 0 || w % 16 != 0 || h % 16 != 0 || d % 16 != 0) {
            throw new IllegalArgumentException(
                    "VolumeLayout dims must be positive multiples of 16: " + w + "x" + h + "x" + d);
        }
        if (w % 4 != 0 || h % 4 != 0 || d % 4 != 0) {
            throw new IllegalArgumentException("L2 (4x) требует кратности 4: " + w + "x" + h + "x" + d);
        }
        this.w = w;
        this.h = h;
        this.d = d;
        this.sx = w / 16;
        this.sy = h / 16;
        this.sz = d / 16;
        this.mipW = w / 4;
        this.mipH = h / 4;
        this.mipD = d / 4;
        this.mipCells = mipW * mipH * mipD;
        this.mipOffset = w * h * d;
        this.totalBytes = mipOffset + mipCells;
        this.rowBytes = w * d;
    }

    /** Индекс вокселя в L0 — идентичен {@code voxelAt()} в rt_overlay.fsh. */
    public int l0Index(int x, int y, int z) {
        return (y * d + z) * w + x;
    }

    /** Индекс секции в массиве cells (локальные координаты секций). */
    public int sectionIndex(int ix, int iy, int iz) {
        return (iy * sz + iz) * sx + ix;
    }

    /** Абсолютный индекс ячейки L2 в upload-буфере. */
    public int mipIndex(int mx, int my, int mz) {
        return mipOffset + (my * mipD + mz) * mipW + mx;
    }

    /** Байтовое смещение строки Y от начала буфера. */
    public int rowOffset(int y) {
        return y * rowBytes;
    }

    /**
     * Секция бокса, которой принадлежит ячейка L2.
     * Ячейка L2 = 4x4x4 вокселя = ровно одна секция 16^3 (т.к. 16/4 = 4).
     */
    public int mipToSectionIndex(int mx, int my, int mz) {
        int ix = (mx * 4) / 16;
        int iy = (my * 4) / 16;
        int iz = (mz * 4) / 16;
        return sectionIndex(ix, iy, iz);
    }

    /** true — ячейка L2 отображается на существующую секцию бокса. */
    public boolean mipInsideSections(int mx, int my, int mz) {
        int ix = (mx * 4) / 16;
        int iy = (my * 4) / 16;
        int iz = (mz * 4) / 16;
        return ix < sx && iy < sy && iz < sz;
    }
}