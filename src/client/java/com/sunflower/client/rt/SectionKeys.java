package com.sunflower.client.rt;

/**
 * Упаковка координат секций (16x16x16) в один long для использования в очередях.
 *
 * <p>Вынесено отдельно от {@link VoxelVolume}, чтобы покрыть юнит-тестами БЕЗ
 * Minecraft: {@code VoxelVolume} тянет {@code Level}/{@code BlockPos}, в обычном
 * тесте не поднимается, а именно здесь уже была реальная дыра — маска X была
 * 8 бит вместо 21, и секции за ~2048 блоков схлопывались (мир ехал за игроком).
 *
 * <p>Раскладка: 21 бит на ось, без bias, знак восстанавливается знаковым
 * расширением. Покрывает ±1 048 576 секций = ±16 777 216 блоков — весь мир.
 */
public final class SectionKeys {
    /** Бит на одну ось. */
    public static final int COORD_BITS = 21;
    /** Маска одной оси. */
    public static final long COORD_MASK = 0x1FFFFFL;
    /** Старший бит оси = знак (знаковое расширение в две стороны). */
    public static final int COORD_SIGN = 0x100000;
    /** Максимальная секция по модулю (включительно). */
    public static final int MAX_SECTION = (int) ((1L << (COORD_BITS - 1)) - 1);

    private SectionKeys() {}

    public static long pack(int sx, int sy, int sz) {
        return (((long) sx & COORD_MASK) << (COORD_BITS * 2))
                | (((long) sy & COORD_MASK) << COORD_BITS)
                | ((long) sz & COORD_MASK);
    }

    public static int unpackX(long key) {
        return signExtend((int) ((key >>> (COORD_BITS * 2)) & COORD_MASK));
    }

    public static int unpackY(long key) {
        return signExtend((int) ((key >>> COORD_BITS) & COORD_MASK));
    }

    public static int unpackZ(long key) {
        return signExtend((int) (key & COORD_MASK));
    }

    private static int signExtend(int v) {
        return (v & COORD_SIGN) != 0 ? v | ~((int) COORD_MASK) : v;
    }

    /** true — координата влезает в 21 бит (иначе pack перепутает соседей). */
    public static boolean fits(int sectionCoord) {
        return sectionCoord >= -MAX_SECTION - 1 && sectionCoord <= MAX_SECTION;
    }
}