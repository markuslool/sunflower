package com.sunflower.client.rt;

import com.sunflower.Sunflower;
import java.util.ArrayDeque;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/**
 * CPU-сторона воксельного объема для теневого DDA.
 *
 * <p>Раскладка: плотный byte[W*H*D], 1 байт = {@link MaterialTable} код.
 * Начало координат (originX/Y/Z) — минимальный угол бокса в мировых блоках,
 * всегда кратно 16 (выровнено по секциям — так очередь проще).
 * За пределы бокса луч считается ушедшим в небо (тени нет).
 *
 * <p>Почему так для GT 650M: 192x96x192 = ~3.5МБ — влезает даже в 1GB VRAM.
 *
 * <p>Обновление инкрементальное: очередь секций 16x16x16 + бюджет в drain().
 * При сдвиге бокса (recenterSmart) в очередь встают ТОЛЬКО новые секции,
 * перекрытие сохраняется — нет шторма перезаливки на бегу.
 */
public final class VoxelVolume {
    /** Дефолт для low-end: 12x6x12 чанков = 192x96x192 блока. */
    public static final int DEFAULT_W = 192;
    public static final int DEFAULT_H = 96;
    public static final int DEFAULT_D = 192;

    /** Секций в боксе: 12*6*12 = 864. */
    public static final int SECTIONS_TOTAL = (DEFAULT_W / 16) * (DEFAULT_H / 16) * (DEFAULT_D / 16);

    private final int w;
    private final int h;
    private final int d;
    private final byte[] voxels;

    private int originX;
    private int originY;
    private int originZ;
    private boolean initialized;
    private volatile boolean uploadDirty = true;

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
        this.voxels = new byte[w * h * d];
    }

    private static long pack(int sx, int sy, int sz) {
        return (((long) (sx + 128)) << 42) | (((long) (sy + 128)) << 21) | ((long) (sz + 128));
    }

    private static int snapDown(int v) {
        return (v / 16) * 16 - (v < 0 && v % 16 != 0 ? 16 : 0);
    }

    private synchronized void enqueue(int sx, int sy, int sz) {
        long key = pack(sx, sy, sz);
        if (queued.add(key)) {
            queue.addLast(key);
        }
    }

    private boolean sectionInside(int sx, int sy, int sz, int ox, int oy, int oz) {
        int x0 = sx * 16;
        int y0 = sy * 16;
        int z0 = sz * 16;
        return x0 + 16 > ox && x0 < ox + w
                && y0 + 16 > oy && y0 < oy + h
                && z0 + 16 > oz && z0 < oz + d;
    }

    private boolean sectionInside(int sx, int sy, int sz) {
        return sectionInside(sx, sy, sz, originX, originY, originZ);
    }

    /**
     * Сдвинуть бокс к игроку. Начало всегда кратно 16.
     * В очередь встают только секции, которых не было в старом боксе.
     */
    public synchronized void recenterSmart(int playerBlockX, int playerBlockY, int playerBlockZ, int minY, int maxY) {
        int nx = snapDown(playerBlockX - w / 2);
        int nz = snapDown(playerBlockZ - d / 2);
        int ny = snapDown(playerBlockY - h / 2);
        ny = Math.max(snapDown(minY), Math.min(snapDown(maxY - h), ny));
        if (initialized && nx == originX && ny == originY && nz == originZ) {
            return;
        }
        int ox = originX;
        int oy = originY;
        int oz = originZ;
        boolean hadBox = initialized;
        originX = nx;
        originY = ny;
        originZ = nz;
        initialized = true;
        int added = 0;
        for (int sy = ny / 16; sy * 16 < ny + h; sy++) {
            for (int sz = nz / 16; sz * 16 < nz + d; sz++) {
                for (int sx = nx / 16; sx * 16 < nx + w; sx++) {
                    if (!hadBox || !sectionInside(sx, sy, sz, ox, oy, oz)) {
                        enqueue(sx, sy, sz);
                        added++;
                    }
                }
            }
        }
        uploadDirty = true;
        Sunflower.LOGGER.debug("[sunflower-rt] volume box {},{},{} +{} sections (queue={})", nx, ny, nz, added, queue.size());
    }

    /** Пометить секцию грязной (блок поставлен/сломан). */
    public synchronized void markSectionDirty(int sx, int sy, int sz) {
        if (!initialized || !sectionInside(sx, sy, sz)) {
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
        for (int ly = 0; ly < 16; ly++) {
            int wy = sy * 16 + ly;
            for (int lz = 0; lz < 16; lz++) {
                int wz = sz * 16 + lz;
                for (int lx = 0; lx < 16; lx++) {
                    int wx = sx * 16 + lx;
                    byte mat;
                    if (level.isOutsideBuildHeight(wy)) {
                        mat = MaterialTable.AIR;
                    } else {
                        try {
                            scratch.set(wx, wy, wz);
                            mat = MaterialTable.classify(level.getBlockState(scratch));
                        } catch (Exception e) {
                            mat = MaterialTable.AIR;
                        }
                    }
                    setVoxel(wx, wy, wz, mat);
                }
            }
        }
    }

    /** Полный сброс (смена мира/измерения). */
    public synchronized void resetForNewLevel() {
        initialized = false;
        queue.clear();
        queued.clear();
        java.util.Arrays.fill(voxels, (byte) 0);
        uploadDirty = true;
    }

    public synchronized void setVoxel(int wx, int wy, int wz, byte mat) {
        int lx = wx - originX;
        int ly = wy - originY;
        int lz = wz - originZ;
        if (lx < 0 || ly < 0 || lz < 0 || lx >= w || ly >= h || lz >= d) {
            return;
        }
        voxels[(ly * d + lz) * w + lx] = mat;
    }

    public synchronized byte getVoxelLocal(int lx, int ly, int lz) {
        if (lx < 0 || ly < 0 || lz < 0 || lx >= w || ly >= h || lz >= d) {
            return MaterialTable.AIR;
        }
        return voxels[(ly * d + lz) * w + lx];
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

    public byte[] rawBytes() {
        return voxels;
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
        return originX;
    }

    public int originY() {
        return originY;
    }

    public int originZ() {
        return originZ;
    }

    public long bytesSize() {
        return (long) voxels.length;
    }
}
