package com.sunflower.client.rt;

/**
 * Математика перемещения бокса объема вокруг игрока: снап до сетки секций,
 * кламп по высоте мира и решение «сдвигать ли бокс» (гистерезис).
 *
 * <p>Вынесено отдельно от {@link VoxelVolume} ради тестируемости без Minecraft.
 *
 * <p>Гистерезис нужен потому, что каждый сдвиг бокса = полная перезаливка
 * upload-буфера (~3.5 МБ) с fence, а без гистерезиса бокс центрировался на
 * игроке и уезжал каждые 16 блоков. На GT 650M это давало жёсткую просадку
 * («всё дёргается») при беге и падении. С мягкой зоной в 1/6 размера бокса
 * сдвиг случается примерно в 6 раз реже.
 */
public final class BoxHysteresis {
    /** Доля от размера бокса, отводимая под мягкую зону по каждой оси. */
    public static final int MARGIN_DIVISOR = 6;

    private BoxHysteresis() {}

    /** Округление вниз до кратного step. Корректно для отрицательных значений. */
    public static int snapDown(int v, int step) {
        int q = v / step;
        if (v < 0 && v % step != 0) {
            q--;
        }
        return q * step;
    }

    public static int snapDownSection(int blockCoord) {
        return snapDown(blockCoord, 16);
    }

    /** Мягкая зона по оси. */
    public static int margin(int boxSize) {
        return boxSize / MARGIN_DIVISOR;
    }

    /**
     * Кламп базы бокса по вертикали в пределы мира.
     * Если мир ниже бокса (высота мира меньше размера бокса) — прижимаем к нижней
     * границе: Math.min/max здесь инвертировал бы кламп.
     */
    public static int clampBaseY(int desiredBase, int boxHeight, int worldMinY, int worldMaxY) {
        int lo = snapDown(worldMinY, 16);
        int hi = snapDown(worldMaxY - boxHeight, 16);
        return hi < lo ? lo : Math.max(lo, Math.min(hi, desiredBase));
    }

    /**
     * Нужно ли двигать бокс. true — игрок покинул мягкую зону текущего бокса
     * (или бокс ещё не инициализирован).
     */
    public static boolean shouldRecenter(
            boolean initialized,
            int baseBlockX, int baseBlockY, int baseBlockZ,
            int boxW, int boxH, int boxD,
            int playerX, int playerY, int playerZ) {
        if (!initialized) {
            return true;
        }
        int mx = margin(boxW);
        int my = margin(boxH);
        int mz = margin(boxD);
        boolean insideSoftX = playerX >= baseBlockX + mx && playerX < baseBlockX + boxW - mx;
        boolean insideSoftY = playerY >= baseBlockY + my && playerY < baseBlockY + boxH - my;
        boolean insideSoftZ = playerZ >= baseBlockZ + mz && playerZ < baseBlockZ + boxD - mz;
        return !(insideSoftX && insideSoftY && insideSoftZ);
    }

    /** База бокса, центрированная на игрока (снап по секциям). */
    public static int centeredBase(int playerBlock, int boxSize) {
        return snapDown(playerBlock - boxSize / 2, 16);
    }
}