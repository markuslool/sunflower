package com.sunflower.client.rt;

import com.sunflower.Sunflower;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * CPU-сторона воксельного объема для теневого DDA.
 *
 * <p>Хранение — ПО СЕКЦИЯМ 16x16x16 (как чанки), а не плоским массивом.
 * Это структурная защита от фантомов: раньше origin сдвигался, а байты
 * лежали на месте, и весь объем интерпретировался со сдвигом — старые
 * деревья ехали за игроком. Теперь данные привязаны к координатам секций:
 * рецентровка лишь перекладывает указатели, сдвиг невозможен в принципе.
 *
 * <p>Пустая (null) секция = неизвестна = читается как воздух (fail-open:
 * лучше лишний свет, чем фантомная тень).
 *
 * <p>Размер: 12x6x12 секций = 192x96x192 блока ≈ 3.5МБ + оверхед ссылок.
 */
public final class VoxelVolume {
    /** Дефолт для low-end: 12x6x12 секций = 192x96x192 блока. */
    public static final int DEFAULT_W = 192;
    public static final int DEFAULT_H = 96;
    public static final int DEFAULT_D = 192;
    public static final int SX = DEFAULT_W / 16;
    public static final int SY = DEFAULT_H / 16;
    public static final int SZ = DEFAULT_D / 16;
    public static final int SECTION_SIZE = 4096;

    /** Секций в боксе: 12*6*12 = 864. */
    public static final int SECTIONS_TOTAL = SX * SY * SZ;

    private final int w;
    private final int h;
    private final int d;

    /** Секция бокса: cells[((sy-baseSy)*SZ + (sz-baseSz))*SX + (sx-baseSx)], null = нет данных. */
    private byte[][] cells = new byte[SECTIONS_TOTAL][];
    private int baseSx;
    private int baseSy;
    private int baseSz;
    private boolean initialized;
    private volatile boolean uploadDirty = true;

    /** Плоский стейджинг для аплоада на GPU (собирается из секций). */
    private final byte[] staging;

    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    public VoxelVolume() {
        this(DEFAULT_W, DEFAULT_H, DEFAULT_D);
    }

    public VoxelVolume(int w, int h, int d) {
        this.w = w;
        this.h = h;
        this.d = d;
        this.staging = new byte[w * h * d];
    }

    private static long pack(int sx, int sy, int sz) {
        return (((long) (sx + 128)) << 42) | (((long) (sy + 128)) << 21) | ((long) (sz + 128));
    }

    private static int snapDown(int v) {
        int q = v / 16;
        if (v < 0 && v % 16 != 0) {
            q--;
        }
        return q * 16;
    }

    private synchronized void enqueue(int sx, int sy, int sz) {
        long key = pack(sx, sy, sz);
        if (queued.add(key)) {
            queue.addLast(key);
        }
    }

    private int cellIndex(int sx, int sy, int sz) {
        int ix = sx - baseSx;
        int iy = sy - baseSy;
        int iz = sz - baseSz;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= SX || iy >= SY || iz >= SZ) {
            return -1;
        }
        return (iy * SZ + iz) * SX + ix;
    }

    /**
     * Сдвинуть бокс к игроку. Данные едут ВМЕСТЕ с боксом (перекладка указателей
     * по координатам секций), в очередь встают только секции, которых не было.
     */
    public synchronized void recenterSmart(int playerBlockX, int playerBlockY, int playerBlockZ, int minY, int maxY) {
        int nx = snapDown(playerBlockX - w / 2);
        int nz = snapDown(playerBlockZ - d / 2);
        int ny = snapDown(playerBlockY - h / 2);
        ny = Math.max(snapDown(minY), Math.min(snapDown(maxY - h), ny));
        int nbx = nx / 16;
        int nby = ny / 16;
        int nbz = nz / 16;
        if (initialized && nbx == baseSx && nby == baseSy && nbz == baseSz) {
            return;
        }
        byte[][] moved = new byte[SECTIONS_TOTAL][];
        int added = 0;
        for (int sy = nby; sy < nby + SY; sy++) {
            for (int sz = nbz; sz < nbz + SZ; sz++) {
                for (int sx = nbx; sx < nbx + SX; sx++) {
                    int dst = ((sy - nby) * SZ + (sz - nbz)) * SX + (sx - nbx);
                    int src = initialized ? cellIndex(sx, sy, sz) : -1;
                    if (src >= 0) {
                        moved[dst] = cells[src]; // секция переехала вместе с боксом
                    } else {
                        enqueue(sx, sy, sz);
                        added++;
                    }
                }
            }
        }
        cells = moved;
        baseSx = nbx;
        baseSy = nby;
        baseSz = nbz;
        initialized = true;
        uploadDirty = true;
        Sunflower.LOGGER.debug("[sunflower-rt] volume base {},{},{} +{} sections (queue={})",
                nbx, nby, nbz, added, queue.size());
    }

    /** Пометить секцию грязной (блок поставлен/сломан). Данные лежат до перепёка — ок. */
    public synchronized void markSectionDirty(int sx, int sy, int sz) {
        if (!initialized || cellIndex(sx, sy, sz) < 0) {
            return;
        }
        enqueue(sx, sy, sz);
        uploadDirty = true;
    }

    /**
     * Обработать до budget секций из очереди. Вызывать на render-потоке.
     * Возвращает число обработанных.
     */
    public synchronized int drain(Level level, int budget) {
        int done = 0;
        while (done < budget && !queue.isEmpty()) {
            long key = queue.removeFirst();
            queued.remove(key);
            int sx = (int) ((key >>> 42) & 0xFF) - 128;
            int sy = (int) ((key >>> 21) & 0x1FFFFF) - 128;
            int sz = (int) (key & 0x1FFFFF) - 128;
            fillSection(level, sx, sy, sz);
            done++;
        }
        if (done > 0) {
            uploadDirty = true;
        }
        return done;
    }

    private void fillSection(Level level, int sx, int sy, int sz) {
        int idx = cellIndex(sx, sy, sz);
        if (idx < 0) {
            return; // секция уже вне бокса (бокс уехал пока ждала) — пропускаем
        }
        byte[] arr = cells[idx];
        if (arr == null) {
            arr = new byte[SECTION_SIZE];
            cells[idx] = arr;
        }
        int p = 0;
        for (int ly = 0; ly < 16; ly++) {
            int wy = sy * 16 + ly;
            boolean outY = level.isOutsideBuildHeight(wy);
            for (int lz = 0; lz < 16; lz++) {
                int wz = sz * 16 + lz;
                for (int lx = 0; lx < 16; lx++) {
                    byte mat;
                    if (outY) {
                        mat = MaterialTable.AIR;
                    } else {
                        try {
                            scratch.set(sx * 16 + lx, wy, wz);
                            mat = MaterialTable.classify(level.getBlockState(scratch));
                        } catch (Exception e) {
                            mat = MaterialTable.AIR;
                        }
                    }
                    arr[p++] = mat;
                }
            }
        }
    }

    /** Полный сброс (смена мира/измерения). */
    public synchronized void resetForNewLevel() {
        initialized = false;
        queue.clear();
        queued.clear();
        cells = new byte[SECTIONS_TOTAL][];
        uploadDirty = true;
    }

    public synchronized void setVoxel(int wx, int wy, int wz, byte mat) {
        int sx = Math.floorDiv(wx, 16);
        int sy = Math.floorDiv(wy, 16);
        int sz = Math.floorDiv(wz, 16);
        int idx = cellIndex(sx, sy, sz);
        if (idx < 0) {
            return;
        }
        byte[] arr = cells[idx];
        if (arr == null) {
            arr = new byte[SECTION_SIZE];
            cells[idx] = arr;
        }
        arr[((wy - sy * 16) * 16 + (wz - sz * 16)) * 16 + (wx - sx * 16)] = mat;
    }

    public synchronized byte getVoxelLocal(int lx, int ly, int lz) {
        return getVoxelWorld(originX() + lx, originY() + ly, originZ() + lz);
    }

    public synchronized byte getVoxelWorld(int wx, int wy, int wz) {
        int sx = Math.floorDiv(wx, 16);
        int sy = Math.floorDiv(wy, 16);
        int sz = Math.floorDiv(wz, 16);
        int idx = cellIndex(sx, sy, sz);
        if (idx < 0) {
            return MaterialTable.AIR;
        }
        byte[] arr = cells[idx];
        if (arr == null) {
            return MaterialTable.AIR;
        }
        return arr[((wy - sy * 16) * 16 + (wz - sz * 16)) * 16 + (wx - sx * 16)];
    }

    /**
     * Собрать плоский снимок для GPU: секции по порядку, пустые — нули.
     * Возвращает внутренний стейджинг (не хранить ссылку, копируется сразу).
     */
    public synchronized byte[] snapshotFlat() {
        int p = 0;
        // Порядок обязан совпадать с шейдером: ((y*D + z)*W + x), секции 16³.
        for (int sy = baseSy; sy < baseSy + SY; sy++) {
            for (int ly = 0; ly < 16; ly++) {
                for (int sz = baseSz; sz < baseSz + SZ; sz++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int sx = baseSx; sx < baseSx + SX; sx++) {
                            int idx = cellIndex(sx, sy, sz);
                            byte[] arr = idx >= 0 ? cells[idx] : null;
                            if (arr == null) {
                                Arrays.fill(staging, p, p + 16, (byte) 0);
                            } else {
                                int src = (ly * 16 + lz) * 16;
                                System.arraycopy(arr, src, staging, p, 16);
                            }
                            p += 16;
                        }
                    }
                }
            }
        }
        return staging;
    }

    /** Забрать флаг "нужен аплоад на GPU" (полная перезаливка, v1). */
    public boolean consumeUploadDirty() {
        boolean was = uploadDirty;
        uploadDirty = false;
        return was;
    }

    public synchronized double fillFraction() {
        return 1.0 - Math.min(1.0, (double) queue.size() / Math.max(1, SECTIONS_TOTAL));
    }

    public synchronized int queuedSections() {
        return queue.size();
    }

    public synchronized boolean isInitialized() {
        return initialized;
    }

    public int width() {
        return w;
    }

    public int height() {
        return h;
    }

    public int depth() {
        return d;
    }

    public int originX() {
        return baseSx * 16;
    }

    public int originY() {
        return baseSy * 16;
    }

    public int originZ() {
        return baseSz * 16;
    }

    public long bytesSize() {
        return (long) staging.length;
    }
}
