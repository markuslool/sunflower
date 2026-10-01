package com.sunflower.client.rt;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.sunflower.Sunflower;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
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
 *
 * <p>Поверх L0 строится консервативный occupancy-mipmap L2 (4x, 48x24x48) для Hi-DDA
 * в шейдере. L2 лежит в ТОМ ЖЕ upload-буфере сразу за L0 ([L0 | L2], офсет выводится
 * в шейдере из Dims — формат менять не надо).
 * Кодировка mip: 0 = все 64 ребенка 0/3 (точно пусто — пропуск безопасен и для primary,
 * и для тени), 1 = есть полупрозрачные (2), но нет 1/4, 2 = есть непрозрачные (1)
 * или листва (4). Неизвестные коды маппятся в 2 (консервативно).
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
    /** Число секций по осям для ЭТОГО экземпляра (дефолт = SX/SY/SZ). */
    private final int sxCount;
    private final int syCount;
    private final int szCount;
    private final int sectionsTotal;
    /** Вся раскладка буфера (индексы L0/L2, офсеты строк) — единый источник правды. */
    private final VolumeLayout layout;

    /** Секция бокса: cells[((sy-baseSy)*szCount + (sz-baseSz))*sxCount + (sx-baseSx)], null = нет данных. */
    private SectionData[] cells;

    /**
     * Секция 16^3 + её кэшированный occupancy-класс (0 пусто / 1 полу / 2 непрозрачно).
     *
     * <p>Класс считается один раз при заливке. Без этого кэша пересчёт L2 после
     * recenter стоил 3.5M чтений (сканирование всех вокселей заново) — при прыжке
     * или падении, когда бокс сдвигается по Y, это давало всплеск кадра прямо в
     * середине движения. С кэшем полный пересчёт L2 стоит ~55K max-операций.
     */
    private static final class SectionData {
        final byte[] voxels;
        /** Пересчитывается после setVoxel, поэтому не final. */
        byte cls;

        SectionData(byte[] voxels, byte cls) {
            this.voxels = voxels;
            this.cls = cls;
        }
    }

    /** Пересчитать occupancy-класс секции после точечной правки вокселя. */
    private static byte recomputeClass(byte[] voxels) {
        byte cls = 0;
        for (byte v : voxels) {
            byte mapped = (byte) mipMap(v);
            if (mapped > cls) {
                cls = mapped;
            }
            if (cls >= 2) {
                break;
            }
        }
        return cls;
    }
    private volatile int baseSx;
    private volatile int baseSy;
    private volatile int baseSz;
    private volatile boolean initialized;
    private volatile boolean uploadDirty = true;

    /** Диагностика: сколько вокселей обновлено мгновенно через setVoxel (правки блоков). */
    private long liveUpdates;
    /** Диагностика: сколько ячеек L2 пересчитано в последнем снапшоте. */
    private int mipRecomputed;

    /** Счётчик живых обновлений — для /sunflower status (0 = хук правок не работает). */
    public synchronized long liveUpdateCount() {
        return liveUpdates;
    }

    /** Сколько ячеек L2 пересчитано в последнем снапшоте (диагностика инкрементальности). */
    public synchronized int mipRecomputedCount() {
        return mipRecomputed;
    }

    /** Секций в предзаливке (диагностика: 0 = кольцо не набирается). */
    public synchronized int prefetchReady() {
        return prefetch.size();
    }

    /** Плоский стейджинг для аплоада на GPU: единый буфер [L0 | L2(4x)]. */
    private final byte[] staging;
    /** Офсет L2 (4x) в staging — равен объему L0. */
    private final int mipOff2;
    /** Размеры сетки L2 (одна ячейка = 4x4x4 вокселя = четверть секции). */
    private final int mipW;
    private final int mipH;
    private final int mipD;
    private final int mipCells;
    /**
     * Грязные ячейки L2: пересчитываем ТОЛЬКО их. Без этого маска rebuild смотрел
     * весь объем (3.5M чтений) каждый кадр и на GT 650M ронял кадр — отсюда
     * было "мерцает при беге". Секция 16^3 перекрывает 4x4x4 = 64 ячеек L2.
     */
    private final boolean[] mipDirty;
    /** L2 пересчитан и ждёт загрузки на GPU. */
    private boolean mipUploadPending;
    /**
     * Грязные строки Y в staging. Сдвиг бокса переставляет ВСЕ секции, поэтому
     * после него помечаем всё — но благодаря гистерезису это случается редко.
     */
    private final long[] dirtyRows;

    /** Максимум строк, ради которых ещё есть смысл возиться со срезом. */
    private static final int FULL_UPLOAD_ROW_FRACTION = 2;

    /** Пометить строку Y (локальную) как требующую пересборки в staging. */
    private void markRowDirty(int localY) {
        if (localY < 0 || localY >= h) {
            return;
        }
        dirtyRows[localY >> 6] |= 1L << (localY & 63);
    }

    /** Диапазон грязных строк включительно; null = грязных нет. */
    private synchronized int[] dirtyRowRange() {
        int lo = -1;
        int hi = -1;
        for (int i = 0; i < h; i++) {
            if ((dirtyRows[i >> 6] & (1L << (i & 63))) != 0) {
                if (lo < 0) {
                    lo = i;
                }
                hi = i;
            }
        }
        return lo < 0 ? null : new int[] {lo, hi};
    }

    /** Сбросить бит строки (после успешной её загрузки). */
    private void clearRowDirty(int localY) {
        dirtyRows[localY >> 6] &= ~(1L << (localY & 63));
    }

    private void clearAllRowsDirty() {
        Arrays.fill(dirtyRows, 0L);
    }

    /** Байт последней успешной частичной загрузки (диагностика: 0 = аплоад не нужен). */
    private int lastUploadedBytes;

    /** Сколько строк Y ждут загрузки на GPU (должно быть 0 в устоявшемся кадре). */
    public synchronized int pendingDirtyRows() {
        int n = 0;
        for (int i = 0; i < h; i++) {
            if ((dirtyRows[i >> 6] & (1L << (i & 63))) != 0) {
                n++;
            }
        }
        return n;
    }

    /** Сколько байт ушло в GPU в последнем аплоаде. */
    public synchronized int lastUploadedBytes() {
        return lastUploadedBytes;
    }

    /**
     * Диагностика coarse-уровня: сколько ячеек L2 в staging считает "не пусто".
     *
     * <p>Если 0 — Hi-DDA выключен по факту: coarseFind() сразу возвращает -1,
     * primary-марш не находит поверхность и тени не рисуются вовсе. Именно так
     * выглядел баг с взаимной блокировкой флага mipUploadPending.
     */
    public synchronized int nonEmptyMipCells() {
        int n = 0;
        for (int i = 0; i < mipCells; i++) {
            if (staging[layout.mipOffset + i] != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * true — следующий {@link #uploadDirtyRange} перезапишет буфер целиком,
     * поэтому device-copy предыдущего слота можно пропустить.
     *
     * <p>Это важно для инварианта кольца: каждый слот хранит полную актуальную
     * картинку, а копирование вперёд переносит накопленные патчи. Если патч
     * перезаписывает всё, копирование просто лишнее.
     */
    public synchronized boolean nextUploadIsFull() {
        int[] r = dirtyRowRange();
        if (r != null && (r[1] - r[0] + 1) > h / FULL_UPLOAD_ROW_FRACTION) {
            return true;
        }
        return r != null && mipUploadPending && r[0] == 0;
    }

    /**
     * Частичная загрузка на GPU. Возвращает true, если данные залиты.
     *
     * <p>Заливается ОДИН непрерывный срез: строки [lo..hi], а если пересобрался L2 —
     * то и его хвост сразу (в буфере он идёт вплотную за L0).
     *
     * <p>ВАЖНО: L2 пересобирается ЗДЕСЬ ВСЕГДА, а не по флагу. Раньше флаг ставился
     * внутри buildMips(), а buildMips() вызывался только если флаг уже стоял, — mutual
     * deadlock. В итоге coarse-уровень навсегда оставался нулевым ("всё пусто"),
     * coarseFind() сразу возвращал -1, и тени то пропадали, то мигали при подгрузке
     * новых территорий.
     */
    public boolean uploadDirtyRange(com.mojang.blaze3d.buffers.GpuBuffer target) {
        int[] range = dirtyRowRange();
        int lo = 0;
        int hi = -1;
        boolean hasRows = false;
        if (range != null) {
            if ((range[1] - range[0] + 1) <= h / FULL_UPLOAD_ROW_FRACTION) {
                lo = range[0];
                hi = range[1];
            } else {
                // Грязного слишком много (или это первый кадр) — дешевле один большой
                // непрерывный срез, чем раздутый диапазон строк.
                lo = 0;
                hi = h - 1;
            }
            snapshotRows(lo, hi);
            hasRows = true;
        }

        int mipCount = buildMips();
        if (mipCount > 0) {
            mipUploadPending = true;
        }
        if (!hasRows && !mipUploadPending) {
            lastUploadedBytes = 0;
            return true;
        }

        final int offset;
        final int length;
        if (!hasRows) {
            offset = layout.mipOffset;
            length = layout.mipCells;
        } else {
            offset = layout.rowOffset(lo);
            length = mipUploadPending ? layout.totalBytes - offset : layout.rowOffset(hi) + layout.rowBytes - offset;
        }
        try (GpuBufferSlice.MappedView view = new GpuBufferSlice(target, 0, target.size())
                .slice(offset, length)
                .map(false, true)) {
            ByteBuffer buf = view.data();
            buf.position(0);
            if (buf.remaining() < length) {
                markAllMipsDirty();
                return false;
            }
            buf.put(staging, offset, length);
        } catch (Exception e) {
            // Пересборка уже сбросила флаги mipDirty — возвращаем их, иначе
            // сорванная загрузка молча оставила бы шейдер со старым L2.
            markAllMipsDirty();
            Sunflower.LOGGER.warn("[sunflower-rt] partial voxel upload failed: {}", e.toString());
            return false;
        }
        if (hasRows) {
            for (int y = lo; y <= hi; y++) {
                clearRowDirty(y);
            }
        }
        mipUploadPending = false;
        lastUploadedBytes = length;
        return true;
    }

    /**
     * Предзаливка секций ЗА пределами бокса (кольцо в 1 секцию).
     * Ключ — упакованные координаты секции, значение — готовая 16^3 секция.
     * Нужна, чтобы при сдвиге бокса не появлялась "полоса" без данных: новая
     * секция уже залита заранее, теням не нужно догонять (иначе они мигают).
     */
    private final HashMap<Long, SectionData> prefetch = new HashMap<>();

    private final ArrayDeque<Long> queue = new ArrayDeque<>();
    private final HashSet<Long> queued = new HashSet<>();
    /** Очередь предзаливки (секции вне бокса) — обрабатывается только при простое основной. */
    private final ArrayDeque<Long> prefetchQueue = new ArrayDeque<>();
    private final HashSet<Long> prefetchQueued = new HashSet<>();
    private final BlockPos.MutableBlockPos scratch = new BlockPos.MutableBlockPos();

    /** Секций в кольце предзаливки = (sx+2m)(sy+2m)(sz+2m) - sx*sy*sz. */
    private final int prefetchRingSize;

    public VoxelVolume() {
        this(DEFAULT_W, DEFAULT_H, DEFAULT_D);
    }

    public VoxelVolume(int w, int h, int d) {
        this.layout = new VolumeLayout(w, h, d);
        this.w = w;
        this.h = h;
        this.d = d;
        this.sxCount = layout.sx;
        this.syCount = layout.sy;
        this.szCount = layout.sz;
        this.sectionsTotal = sxCount * syCount * szCount;
        this.cells = new SectionData[sectionsTotal];
        this.mipW = layout.mipW;
        this.mipH = layout.mipH;
        this.mipD = layout.mipD;
        this.mipCells = layout.mipCells;
        this.mipOff2 = layout.mipOffset;
        this.mipDirty = new boolean[mipCells];
        this.dirtyRows = new long[(h + 63) / 64];
        this.staging = new byte[layout.totalBytes];
        this.prefetchRingSize = (sxCount + 2 * PREFETCH_MARGIN) * (syCount + 2 * PREFETCH_MARGIN)
                * (szCount + 2 * PREFETCH_MARGIN) - sectionsTotal;
    }

    private static long pack(int sx, int sy, int sz) {
        return SectionKeys.pack(sx, sy, sz);
    }

    private static int unpackX(long key) {
        return SectionKeys.unpackX(key);
    }

    private static int unpackY(long key) {
        return SectionKeys.unpackY(key);
    }

    private static int unpackZ(long key) {
        return SectionKeys.unpackZ(key);
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
        if (ix < 0 || iy < 0 || iz < 0 || ix >= sxCount || iy >= syCount || iz >= szCount) {
            return -1;
        }
        return layout.sectionIndex(ix, iy, iz);
    }

    /**
     * Сдвинуть бокс к игроку. Данные едут ВМЕСТЕ с боксом (перекладка указателей
     * по координатам секций), в очередь встают только секции, которых не было.
     */
    public synchronized void recenterSmart(int playerBlockX, int playerBlockY, int playerBlockZ, int minY, int maxY) {
        int cx = baseSx * 16;
        int cy = baseSy * 16;
        int cz = baseSz * 16;
        if (!BoxHysteresis.shouldRecenter(initialized, cx, cy, cz, w, h, d,
                playerBlockX, playerBlockY, playerBlockZ)) {
            return;
        }
        int nx = BoxHysteresis.centeredBase(playerBlockX, w);
        int nz = BoxHysteresis.centeredBase(playerBlockZ, d);
        int ny = BoxHysteresis.clampBaseY(BoxHysteresis.snapDownSection(playerBlockY - h / 2), h, minY, maxY);
        int nbx = nx / 16;
        int nby = ny / 16;
        int nbz = nz / 16;
        if (initialized && nbx == baseSx && nby == baseSy && nbz == baseSz) {
            return;
        }
        SectionData[] moved = new SectionData[sectionsTotal];
        int added = 0;
        int adopted = 0;
        for (int sy = nby; sy < nby + syCount; sy++) {
            for (int sz = nbz; sz < nbz + szCount; sz++) {
                for (int sx = nbx; sx < nbx + sxCount; sx++) {
                    int dst = layout.sectionIndex(sx - nbx, sy - nby, sz - nbz);
                    int src = initialized ? cellIndex(sx, sy, sz) : -1;
                    if (src >= 0) {
                        moved[dst] = cells[src]; // секция переехала вместе с боксом
                        continue;
                    }
                    // Новая секция: сначала ищем в предзаливке — если она уже готова,
                    // теням не нужно ждать (иначе при беге мигает "полоса" без теней).
                    SectionData pre = prefetch.remove(pack(sx, sy, sz));
                    if (pre != null) {
                        prefetchQueued.remove(pack(sx, sy, sz));
                        moved[dst] = pre;
                        adopted++;
                        continue;
                    }
                    enqueue(sx, sy, sz);
                    added++;
                }
            }
        }
        cells = moved;
        baseSx = nbx;
        baseSy = nby;
        baseSz = nbz;
        initialized = true;
        uploadDirty = true;
        markAllMipsDirty();
        clearAllRowsDirty();
        for (int y = 0; y < h; y++) {
            markRowDirty(y); // сдвиг переставил все секции — весь L0 требует пересборки
        }
        enqueuePrefetchRing();
        Sunflower.LOGGER.debug("[sunflower-rt] volume base {},{},{} +{} sections (adopted={}, queue={}, prequeue={})",
                nbx, nby, nbz, added, adopted, queue.size(), prefetchQueue.size());
    }

    /**
     * Кольцо предзаливки: секции в 1 секцию за границей бокса. Их данные не нужны
     * сейчас, но понадобятся при следующем сдвиге — заливаем на простое.
     */
    /** Радиус кольца предзаливки в секциях: перекрывает часть сдвига при гистерезисе. */
    private static final int PREFETCH_MARGIN = 2;

    private void enqueuePrefetchRing() {
        for (int sy = baseSy - PREFETCH_MARGIN; sy <= baseSy + syCount + PREFETCH_MARGIN; sy++) {
            for (int sz = baseSz - PREFETCH_MARGIN; sz <= baseSz + szCount + PREFETCH_MARGIN; sz++) {
                for (int sx = baseSx - PREFETCH_MARGIN; sx <= baseSx + sxCount + PREFETCH_MARGIN; sx++) {
                    boolean inBox = sx >= baseSx && sx < baseSx + sxCount
                            && sy >= baseSy && sy < baseSy + syCount
                            && sz >= baseSz && sz < baseSz + szCount;
                    if (inBox) {
                        continue;
                    }
                    long key = pack(sx, sy, sz);
                    if (prefetch.containsKey(key) || prefetchQueued.contains(key)) {
                        continue;
                    }
                    prefetchQueued.add(key);
                    prefetchQueue.addLast(key);
                }
            }
        }
    }

    /** Пометить секцию грязной (блок поставлен/сломан). Данные лежат до перепёка — ок. */
    public synchronized void markSectionDirty(int sx, int sy, int sz) {
        if (!initialized || cellIndex(sx, sy, sz) < 0) {
            return;
        }
        enqueue(sx, sy, sz);
        uploadDirty = true;
    }

    /** Весь столбец секций чанка (по всей высоте бокса) в очередь на перепек. */
    public synchronized void markColumnDirty(int sx, int sz) {
        if (!initialized) {
            return;
        }
        for (int sy = baseSy; sy < baseSy + syCount; sy++) {
            enqueue(sx, sy, sz);
        }
        uploadDirty = true;
    }

    /**
     * Обработать до budget секций из очереди. Вызывать на render-потоке.
     *
     * <p>Возвращает число обработанных; -1 = очередь была, но не тронута (throttled):
     * {@code playerSec} кладёт этот кадр под догонку свежих данных, чтобы воксели
     * вокруг игрока (где RT реально виден) не отставали.
     */
    public synchronized int drain(Level level, int budget, int playerSec) {
        int done = 0;
        if (!queue.isEmpty()) {
            if (budget <= 0) {
                return -1; // throttled: рендер--hook снимет throttle и догонит на след. кадре
            }
            // Ближние к игроку секции первыми: иначе после /fill или загрузки чанка
            // бюджет съедают дальние секции, а под ногами воксели остаются воздухом
            // (симптом "RT пропадает рядом со мной").
            long playerKey = pack(playerSec, playerSec, playerSec);
            while (done < budget && !queue.isEmpty()) {
                long key;
                if (playerSec != Integer.MIN_VALUE && queued.contains(playerKey)) {
                    key = playerKey;
                } else {
                    key = queue.peekFirst();
                    if (sectionDistanceSq(key, playerSec) > sectionDistanceSq(queue.peekLast(), playerSec)) {
                        key = queue.pollLast();
                    } else {
                        queue.pollFirst();
                    }
                }
                queued.remove(key);
                fillSection(level, unpackX(key), unpackY(key), unpackZ(key));
                done++;
            }
            if (done > 0) {
                uploadDirty = true;
            }
        }
        // Предзаливка кольца — только на простое (основная очередь пуста) и малым
        // бюджетом, чтобы никогда не конкурировать с заливкой бокса за кадр.
        if (budget > 0 && queue.isEmpty() && !prefetchQueue.isEmpty()) {
            int preBudget = Math.max(1, Math.min(4, budget));
            for (int i = 0; i < preBudget && !prefetchQueue.isEmpty(); i++) {
                long key = prefetchQueue.pollFirst();
                prefetchQueued.remove(key);
                int idx = cellIndex(unpackX(key), unpackY(key), unpackZ(key));
                if (idx < 0) {
                    fillPrefetch(level, unpackX(key), unpackY(key), unpackZ(key));
                    done++;
                }
            }
        }
        return done;
    }

    /** Квадрат расстояния (в секциях) от точки до центра секции ключа. */
    private static long sectionDistanceSq(long key, int playerSec) {
        long dx = (long) unpackX(key) - playerSec;
        long dy = (long) unpackY(key) - playerSec;
        long dz = (long) unpackZ(key) - playerSec;
        return dx * dx + dy * dy + dz * dz;
    }

    /** Заливка секции в кольцо предзаливки (вне бокса — в стороннюю карту). */
    private void fillPrefetch(Level level, int sx, int sy, int sz) {
        prefetch.put(pack(sx, sy, sz), buildSection(level, sx, sy, sz));
        // При беге кольцо уезжает, и старые записи осиротевают. Держим карту в
        // границах ~2 колец, иначе она растет и подъедает память на длинной прогулке.
        if (prefetch.size() > prefetchRingSize * 2) {
            trimPrefetch();
        }
    }

    /** Выбросить из предзаливки всё, что дальше 1 секции от текущего бокса. */
    private void trimPrefetch() {
        prefetch.entrySet().removeIf(e -> {
            int sx = unpackX(e.getKey());
            int sy = unpackY(e.getKey());
            int sz = unpackZ(e.getKey());
            boolean near = sx >= baseSx - 1 && sx <= baseSx + sxCount
                    && sy >= baseSy - 1 && sy <= baseSy + syCount
                    && sz >= baseSz - 1 && sz <= baseSz + szCount;
            return !near;
        });
    }

    private void fillSection(Level level, int sx, int sy, int sz) {
        int idx = cellIndex(sx, sy, sz);
        if (idx < 0) {
            return; // секция уже вне бокса (бокс уехал пока ждала) — пропускаем
        }
        cells[idx] = buildSection(level, sx, sy, sz);
        // Секция высотой 16 блоков = 16 строк staging. Пометить только одну строку
        // нельзя: 15 строк остались бы в GPU старыми и давали бы пятнистые
        // фантомные тени (проверено на скриншоте — «леопардовые» чёрные пятна).
        markSectionRowsDirty(sy - baseSy);
        markMipsDirtyForSection(sx, sy, sz);
    }

    /** Пометить грязными все 16 строк секции по локальной координате секции. */
    private void markSectionRowsDirty(int syLocal) {
        int first = layout.sectionFirstRow(syLocal);
        for (int i = 0; i < 16; i++) {
            markRowDirty(first + i);
        }
    }

    /** Прочитать 16^3 секцию из мира и сразу посчитать её occupancy-класс. */
    private SectionData buildSection(Level level, int sx, int sy, int sz) {
        byte[] arr = new byte[SECTION_SIZE];
        byte cls = 0;
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
                    arr[p] = mat;
                    byte mapped = (byte) mipMap(mat);
                    if (mapped > cls) {
                        cls = mapped;
                    }
                    p++;
                }
            }
        }
        return new SectionData(arr, cls);
    }

    /** Полный сброс (смена мира/измерения). */
    public synchronized void resetForNewLevel() {
        initialized = false;
        queue.clear();
        queued.clear();
        prefetch.clear();
        prefetchQueue.clear();
        prefetchQueued.clear();
        cells = new SectionData[sectionsTotal];
        markAllMipsDirty();
        uploadDirty = true;
        clearAllRowsDirty();
        for (int y = 0; y < h; y++) {
            markRowDirty(y);
        }
        uploadedOriginValid = false; // старые данные GPU больше не соответствуют миру
    }

    public synchronized void setVoxel(int wx, int wy, int wz, byte mat) {
        int sx = Math.floorDiv(wx, 16);
        int sy = Math.floorDiv(wy, 16);
        int sz = Math.floorDiv(wz, 16);
        int idx = cellIndex(sx, sy, sz);
        if (idx < 0) {
            return;
        }
        SectionData sec = cells[idx];
        if (sec == null) {
            sec = new SectionData(new byte[SECTION_SIZE], (byte) 0);
            cells[idx] = sec;
        }
        sec.voxels[((wy - sy * 16) * 16 + (wz - sz * 16)) * 16 + (wx - sx * 16)] = mat;
        sec.cls = recomputeClass(sec.voxels);
        liveUpdates++;
        uploadDirty = true;
        markRowDirty(wy - baseSy * 16);
        markMipsDirtyForSection(sx, sy, sz);
    }

    /** Пометить все ячейки L2 (после сдвига бокса прежняя раскладка недействительна). */
    private void markAllMipsDirty() {
        java.util.Arrays.fill(mipDirty, true);
    }

    /**
     * Секция 16x16x16 перекрывает блок 4x4x4 ячеек L2 — помечаем только их.
     * Это и есть экономия: пересчет 64 ячеек вместо всех 55296.
     */
    private void markMipsDirtyForSection(int sx, int sy, int sz) {
        int ix = sx - baseSx;
        int iy = sy - baseSy;
        int iz = sz - baseSz;
        if (ix < 0 || iy < 0 || iz < 0 || ix >= sxCount || iy >= syCount || iz >= szCount) {
            return;
        }
        int bx0 = ix >> 2;
        int by0 = iy >> 2;
        int bz0 = iz >> 2;
        int bw = Math.max(1, sxCount / 4);
        int bh = Math.max(1, syCount / 4);
        int bd = Math.max(1, szCount / 4);
        for (int by = by0; by < by0 + 4 && by < bh; by++) {
            for (int bz = bz0; bz < bz0 + 4 && bz < bd; bz++) {
                for (int bx = bx0; bx < bx0 + 4 && bx < bw; bx++) {
                    mipDirty[(by * bd + bz) * bw + bx] = true;
                }
            }
        }
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
        SectionData sec = cells[idx];
        if (sec == null) {
            return MaterialTable.AIR;
        }
        return sec.voxels[((wy - sy * 16) * 16 + (wz - sz * 16)) * 16 + (wx - sx * 16)];
    }

    /**
     * Полный снимок (все строки) —kept для совместимости и диагностики.
     * Основной путь теперь частичный: {@link #snapshotRows(int, int)}.
     */
    public synchronized byte[] snapshotFlat() {
        snapshotRows(0, h - 1);
        buildMips();
        return staging;
    }

    /**
     * Пересобрать в staging только строки Y включительно [loY..hiY] + грязные ячейки L2.
     *
     * <p>Раскладка буфера y-старшая ({@code (y*D + z)*W + x}), поэтому диапазон
     * строк — НЕПРЕРЫВНЫЙ кусок, который заливается ОДНИМ срезом: одна map и один
     * fence на кадр вместо 3.5 МБ целиком. Типичный кадр — единицы строк (десятки КБ).
     */
    public synchronized void snapshotRows(int loY, int hiY) {
        int from = Math.max(0, loY);
        int to = Math.min(h - 1, hiY);
        if (from > to) {
            return;
        }
        final int rowBytes = layout.rowBytes;
        final int sx = layout.sx;
        final int sz = layout.sz;
        for (int y = from; y <= to; y++) {
            final int ly = y & 15;
            final int syLocal = y >> 4;
            final int rowBase = y * rowBytes;
            for (int szLocal = 0; szLocal < sz; szLocal++) {
                for (int sxLocal = 0; sxLocal < sx; sxLocal++) {
                    SectionData sec = cells[layout.sectionIndex(sxLocal, syLocal, szLocal)];
                    final int colBase = rowBase + szLocal * 16 * layout.w + sxLocal * 16;
                    if (sec == null) {
                        for (int lz = 0; lz < 16; lz++) {
                            Arrays.fill(staging, colBase + lz * layout.w, colBase + lz * layout.w + 16, (byte) 0);
                        }
                        continue;
                    }
                    final byte[] vox = sec.voxels;
                    for (int lz = 0; lz < 16; lz++) {
                        System.arraycopy(vox, (ly * 16 + lz) * 16, staging, colBase + lz * layout.w, 16);
                    }
                }
            }
        }
    }

    /** Консервативный occupancy-код mip-пирамиды: пусто только если все дети 0/3. */
    private static int mipMap(byte m) {
        if (m == MaterialTable.AIR || m == MaterialTable.EMISSIVE_PASS) {
            return 0;
        }
        if (m == MaterialTable.TRANSLUCENT) {
            return 1;
        }
        return 2; // OPAQUE, LEAF и любые неизвестные — непрозрачно
    }

    /**
     * Построить L2 (4x) прямой редукцией L0 4x4x4 -> 1 прямо в staging.
     * Раскладка обязана совпадать с шейдером: off2 = W*H*D,
     * порядок ((y*D + z)*W + x) на каждом уровне.
     *
     * <p>Пересчитываются ТОЛЬКО грязные ячейки (маска mipDirty). Полный проход —
     * 3.5M чтений на каждый снапшот, что на GT 650M съедало кадр и давало
     * "мерцание при беге"; теперь обычно пересчитываются десятки ячеек.
     */
    /** Пересобрать грязные ячейки L2, вернуть число пересчитанных. */
    private int buildMips() {
        int anyDirty = 0;
        for (int my = 0; my < mipH; my++) {
            for (int mz = 0; mz < mipD; mz++) {
                for (int mx = 0; mx < mipW; mx++) {
                    int mi = (my * mipD + mz) * mipW + mx;
                    if (!mipDirty[mi]) {
                        continue;
                    }
                    mipDirty[mi] = false;
                    anyDirty++;
                    // Ячейка L2 = 4x4x4 ВОКСЕЛЯ = ровно одна секция 16^3.
                    // Консервативный код — из кэш-класса секции, без чтения вокселей.
                    SectionData sec = null;
                    if (layout.mipInsideSections(mx, my, mz)) {
                        sec = cells[layout.mipToSectionIndex(mx, my, mz)];
                    }
                    staging[layout.mipIndex(mx, my, mz)] = sec != null ? sec.cls : (byte) 0;
                }
            }
        }
        mipRecomputed = anyDirty;
        return anyDirty;
    }

    /**
     * База бокса, ДЕЙСТВИТЕЛЬНО лежащая в GPU-буфере (обновляется только после аплоада).
     *
     * <p>Раньше в uniform писались "живые" originX/Y/Z, а воксельные данные грузились
     * с троттлингом. При сдвиге бокса по Y (прыжок, падение) рамка уезжала на кадр
     * раньше данных — шейдер читал объем, сдвинутый на 16 блоков, и картинка
     * "мерцала" каждый прыжок. Теперь шейдер всегда получает ту базу, которая
     * реально залита, и кадр физически не может быть несогласованным.
     */
    private volatile int uploadedBaseSx;
    private volatile int uploadedBaseSy;
    private volatile int uploadedBaseSz;
    private volatile boolean uploadedOriginValid;

    /** База бокса в GPU-буфере — её и надо писать в uniform Origin. */
    public int uploadedOriginX() {
        return uploadedBaseSx * 16;
    }

    public int uploadedOriginY() {
        return uploadedBaseSy * 16;
    }

    public int uploadedOriginZ() {
        return uploadedBaseSz * 16;
    }

    /** true = данные в GPU соответствуют uploadedOrigin* (до первого аплоада — false). */
    public boolean uploadedOriginValid() {
        return uploadedOriginValid;
    }

    /** База сдвинулась и ждёт аплоада — рендер-хук обязан грузить без троттлинга. */
    public boolean originShiftPending() {
        return !uploadedOriginValid
                || uploadedBaseSx != baseSx
                || uploadedBaseSy != baseSy
                || uploadedBaseSz != baseSz;
    }

    /**
     * Забрать флаг "нужен аплоад на GPU".
     *
     * <p>ВАЖНО: флаг НЕ сбрасывается, если аплоад не состоялся — рендер-хук может
     * отложить загрузку из-за троттлинга. Раньше здесь стоял безусловный сброс,
     * и обновления терялись навсегда: при прыжке/падении (сдвиг бокса по Y) GPU
     * оставался с устаревшим буфером, и тени мерцали во весь экран.
     */
    public boolean consumeUploadDirty() {
        return uploadDirty;
    }

    /** Сбросить флаг и запомнить залитую базу — только ПОСЛЕ успешного аплоада. */
    public synchronized void markUploaded() {
        uploadDirty = false;
        uploadedBaseSx = baseSx;
        uploadedBaseSy = baseSy;
        uploadedBaseSz = baseSz;
        uploadedOriginValid = true;
    }

    /** Диагностика: ждёт ли данных аплоад (должно быть false в устоявшемся кадре). */
    public synchronized boolean uploadPending() {
        return uploadDirty;
    }

    public synchronized double fillFraction() {
        return 1.0 - Math.min(1.0, (double) queue.size() / Math.max(1, sectionsTotal));
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
