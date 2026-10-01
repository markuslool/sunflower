package com.sunflower.client.rt;

import com.sunflower.Sunflower;

/**
 * Таблица материалов для DDA.
 *
 * <p>DDA-шейдеру не нужны полные BlockState — только 1 байт на воксель:
 * <ul>
 *   <li>0 — воздух (луч летит дальше)</li>
 *   <li>1 — непрозрачный окклюдер (тень = 1, обход останавливается)</li>
 *   <li>2 — полупрозрачный (листва, вода, стекло): ослабляет свет, но не стопает луч.
 *       В v1 трактуем как окклюдер с весом 0.5 — уточнишь позже.</li>
 *   <li>3 — источник света / не блокирует (факелы, лава): пропускаем.</li>
 * </ul>
 *
 * <p>TODO для тебя при доводке: заполнить classify() реальными тегами 26.2
 * (leaves, glass, water). Сейчас — эвристика по id, чтобы VoxelVolume уже
 * работал без привязки к маппингам.
 */
public final class MaterialTable {
    public static final byte AIR = 0;
    public static final byte OPAQUE = 1;
    public static final byte TRANSLUCENT = 2;
    public static final byte EMISSIVE_PASS = 3;
    /**
     * Листва: для primary-луча прозрачна (видно что за кроной),
     * для теневого — непрозрачна (крона солнцу почти не просвечивает,
     * как и в ванильном освещении). Отдельный код, чтобы стекло/вода
     * остались полутенью 0.5.
     */
    public static final byte LEAF = 4;

    private MaterialTable() {}

    /**
     * Основная классификация по BlockState (без эвристик по id).
     * Вызывается из VoxelVolume.drain() на render-потоке.
     */
    public static byte classify(net.minecraft.world.level.block.state.BlockState st) {
        if (st.isAir()) {
            return AIR;
        }
        if (st.getLightEmission() > 0) {
            return EMISSIVE_PASS;
        }
        if (st.liquid()) {
            return TRANSLUCENT;
        }
        // Листва раньше стекла/травы: у нее свой код тени.
        if (st.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) {
            return LEAF;
        }
        if (!st.isSolidRender()) {
            // Торчащие/плоские блоки: трава, цветы, снег-слой, факелы без света и т.п.
            return TRANSLUCENT;
        }
        if (st.propagatesSkylightDown()) {
            return TRANSLUCENT;
        }
        if (!st.canOcclude()) {
            // Ступени/плиты/заборы: мягкая полутень вместо черных столбов.
            return TRANSLUCENT;
        }
        return OPAQUE;
    }

    /** Старая эвристика по id — оставлена для тестов, в проде не используется. */
    public static byte classify(String blockId, boolean propagatesSkylight, boolean isSolid) {
        if (blockId == null) {
            return AIR;
        }
        // Эвристика по id — переживет переименования маппингов 26.2.
        if (blockId.contains("air") || blockId.contains("cave") || blockId.contains("void")) {
            return AIR;
        }
        if (blockId.contains("torch") || blockId.contains("lantern") || blockId.contains("lava")
                || blockId.contains("glow") || blockId.contains("fire")) {
            return EMISSIVE_PASS;
        }
        if (blockId.contains("leaves") || blockId.contains("glass") || blockId.contains("water")
                || blockId.contains("ice") || blockId.contains("slime") || blockId.contains("honey")) {
            return TRANSLUCENT;
        }
        if (!isSolid || propagatesSkylight) {
            // Низкие кусты, трава, снег-слой: тень почти не дают, чтобы не было "черных столбов".
            return TRANSLUCENT;
        }
        return OPAQUE;
    }

    public static void logSelfCheck() {
        Sunflower.LOGGER.info("[sunflower-rt] material table: air=0 opaque=1 translucent=2 emissive=3 leaf=4");
    }
}
