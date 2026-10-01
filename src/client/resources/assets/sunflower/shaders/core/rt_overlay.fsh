#version 330

// Sunflower RT overlay: hierarchical DDA (Hi-DDA) + sun shadow DDA in fragment.
// L0 — полный воксельный объем (MaterialTable): 0 air, 1 opaque, 2 translucent,
//   3 emissive/pass, 4 leaf (для primary останавливает как крона, для тени непрозрачна).
// L2 (4x) в ТОМ ЖЕ буфере за L0 — консервативный occupancy-mipmap:
//   0 = все 64 ребенка 0/3 (точно пусто — пропуск безопасен везде),
//   1 = есть полупрозрачные (2), но нет 1/4,
//   2 = есть непрозрачные (1) или листва (4).
// Пустые 4x-клетки шагаются целиком + ранний выход по AABB (небо — ноль шагов).
// Output multiplies the framebuffer (blend ZERO, SRC_COLOR): 1.0 = no change.

layout(std140) uniform RtFrame {
    mat4 InvViewProj;
    vec3 CamPos;
    vec3 SunDir;
    ivec3 Origin;
    ivec3 Dims;
    vec4 Params; // x: shadowDist, y: maxSteps, z: strength, w: primaryMax
    vec4 ResPad; // xy: framebuffer size, z: debugMode, w: rayStride (1/2/4)
    vec4 Misc; // x: time sec, y: sun angular radius, z: soft taps (1/4/8), w: cloudShadows
};

uniform usamplerBuffer Voxels;

out vec4 fragColor;

uint voxelAt(ivec3 c) {
    if (c.x < 0 || c.y < 0 || c.z < 0 || c.x >= Dims.x || c.y >= Dims.y || c.z >= Dims.z) {
        return 0u;
    }
    int idx = (c.y * Dims.z + c.z) * Dims.x + c.x;
    return texelFetch(Voxels, idx).r;
}

uint mipAt(ivec3 c, ivec3 dims, int off) {
    if (c.x < 0 || c.y < 0 || c.z < 0 || c.x >= dims.x || c.y >= dims.y || c.z >= dims.z) {
        return 0u;
    }
    return texelFetch(Voxels, off + (c.y * dims.z + c.z) * dims.x + c.x).r;
}

void ddaInit(vec3 ro, vec3 rd, out ivec3 cell, out ivec3 stepI, out vec3 tMax, out vec3 tDelta) {
    vec3 local = ro - vec3(Origin);
    cell = ivec3(floor(local));
    stepI = ivec3(rd.x > 0.0 ? 1 : -1, rd.y > 0.0 ? 1 : -1, rd.z > 0.0 ? 1 : -1);
    tDelta = vec3(
        rd.x != 0.0 ? abs(1.0 / rd.x) : 1e30,
        rd.y != 0.0 ? abs(1.0 / rd.y) : 1e30,
        rd.z != 0.0 ? abs(1.0 / rd.z) : 1e30);
    vec3 f = fract(local);
    tMax = vec3(
        rd.x > 0.0 ? (1.0 - f.x) * tDelta.x : f.x * tDelta.x,
        rd.y > 0.0 ? (1.0 - f.y) * tDelta.y : f.y * tDelta.y,
        rd.z > 0.0 ? (1.0 - f.z) * tDelta.z : f.z * tDelta.z);
}

// Тот же DDA, но на сетке с шагом cs и началом org (для coarse-клеток 4x).
void ddaInitScaled(vec3 ro, vec3 rd, float cs, vec3 org, out ivec3 cell, out ivec3 stepI, out vec3 tMax, out vec3 tDelta) {
    vec3 local = (ro - org) / cs;
    cell = ivec3(floor(local));
    stepI = ivec3(rd.x > 0.0 ? 1 : -1, rd.y > 0.0 ? 1 : -1, rd.z > 0.0 ? 1 : -1);
    tDelta = vec3(
        rd.x != 0.0 ? abs(cs / rd.x) : 1e30,
        rd.y != 0.0 ? abs(cs / rd.y) : 1e30,
        rd.z != 0.0 ? abs(cs / rd.z) : 1e30);
    vec3 f = fract(local);
    tMax = vec3(
        rd.x > 0.0 ? (1.0 - f.x) * tDelta.x : f.x * tDelta.x,
        rd.y > 0.0 ? (1.0 - f.y) * tDelta.y : f.y * tDelta.y,
        rd.z > 0.0 ? (1.0 - f.z) * tDelta.z : f.z * tDelta.z);
}

// Slab-тест луча против бокса объема. Мимо (небо) — выходим до единого шага DDA.
bool rayBox(vec3 ro, vec3 rd, vec3 bmin, vec3 bmax, float maxDist, out float tEnter, out float tExit) {
    vec3 inv = vec3(
        rd.x != 0.0 ? 1.0 / rd.x : 1e30,
        rd.y != 0.0 ? 1.0 / rd.y : 1e30,
        rd.z != 0.0 ? 1.0 / rd.z : 1e30);
    vec3 t0 = (bmin - ro) * inv;
    vec3 t1 = (bmax - ro) * inv;
    vec3 tsm = min(t0, t1);
    vec3 tbg = max(t0, t1);
    tEnter = max(max(tsm.x, tsm.y), tsm.z);
    tExit = min(min(tbg.x, tbg.y), tbg.z);
    return tExit > max(tEnter, 0.0) && tEnter < maxDist;
}

// Первая НЕПУСТАЯ 4x-клетка на [t0, t1] (абсолютное t), или -1.
// Стартовую клетку проверяет первой — старт внутри непустого дает t0.
float coarseFind(vec3 ro, vec3 rd, float t0, float t1, ivec3 dims2, int off2) {
    ivec3 cell;
    ivec3 stepI;
    vec3 tMax;
    vec3 tDelta;
    ddaInitScaled(ro + rd * t0, rd, 4.0, vec3(Origin), cell, stepI, tMax, tDelta);
    float tr = 0.0;
    for (int i = 0; i < 96 && t0 + tr <= t1; i++) {
        if (mipAt(cell, dims2, off2) != 0u) {
            return t0 + tr;
        }
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            cell.x += stepI.x;
            tr = tMax.x;
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            cell.y += stepI.y;
            tr = tMax.y;
            tMax.y += tDelta.y;
        } else {
            cell.z += stepI.z;
            tr = tMax.z;
            tMax.z += tDelta.z;
        }
    }
    return -1.0;
}

// Нормаль для вырожденного случая (луч стартовал точно на границе твердого вокселя):
// грань, в которую смотрит луч, по доминирующей оси.
vec3 dominantNormal(vec3 rd) {
    vec3 a = abs(rd);
    if (a.x > a.y && a.x > a.z) {
        return vec3(rd.x > 0.0 ? -1.0 : 1.0, 0.0, 0.0);
    }
    if (a.y > a.z) {
        return vec3(0.0, rd.y > 0.0 ? -1.0 : 1.0, 0.0);
    }
    return vec3(0.0, 0.0, rd.z > 0.0 ? -1.0 : 1.0);
}

// --- Мягкие тени: один теневой марш по произвольному направлению на солнце ---
// Вынесено в функцию, чтобы можно было усреднить несколько тапов по диску солнца
// (жёсткие тени = 1 тап, мягкие = 4/8). Каждый тап — полноценный Hi-DDA марш.
float shadowMarch(vec3 start, ivec3 originCell, vec3 sunDir, float shadowDist, int maxSteps,
        ivec3 dims2, int off2) {
    ivec3 cell;
    ivec3 stepI;
    vec3 tMax;
    vec3 tDelta;
    float occl = 0.0;
    int steps = 0;
    float sCur = 0.0;
    // Мягкий срез по дистанции: окклюдер на самой границе shadowDist давал полную
    // тень, а на сантиметр дальше — ноль. Получался жёсткий круг ("разрез") по
    // всей картинке. Гасим окклюдер, найденный у самого края дальности.
    float fadeStart = shadowDist * 0.72;
    for (int outer = 0; outer < 24 && sCur <= shadowDist && occl < 1.0; outer++) {
        float sC = coarseFind(start, sunDir, sCur, shadowDist, dims2, off2);
        if (sC < 0.0) {
            break;
        }
        float sF = max(max(sC - 8.01, sCur), 0.0);
        ddaInit(start + sunDir * sF, sunDir, cell, stepI, tMax, tDelta);
        float st = sF;
        float sFineEnd = min(sC + 8.0, shadowDist);
        for (int i = 0; i < 64 && st <= sFineEnd; i++) {
            if (steps >= maxSteps) {
                break;
            }
            if (tMax.x < tMax.y && tMax.x < tMax.z) {
                cell.x += stepI.x;
                st = sF + tMax.x;
                tMax.x += tDelta.x;
            } else if (tMax.y < tMax.z) {
                cell.y += stepI.y;
                st = sF + tMax.y;
                tMax.y += tDelta.y;
            } else {
                cell.z += stepI.z;
                st = sF + tMax.z;
                tMax.z += tDelta.z;
            }
            if (cell == originCell) {
                continue; // сама поверхность — не окклюдер
            }
            steps++;
            uint m = voxelAt(cell);
            if (m == 1u || m == 4u) {
                // Непрозрачный блок или листва: солнцу не просвечивает.
                occl = 1.0 - smoothstep(fadeStart, shadowDist, st);
                break;
            } else if (m == 2u) {
                occl += 0.5 * (1.0 - smoothstep(fadeStart, shadowDist, st)); // стекло/вода: полутень
                if (occl >= 1.0) {
                    occl = 1.0;
                    break;
                }
            }
        }
        if (occl >= 1.0 || steps >= maxSteps) {
            break;
        }
        sCur = sFineEnd + 0.01;
    }
    return clamp(occl, 0.0, 1.0);
}

// --- Облачные тени: пятна от облаков, едущие по земле ---
float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    float a = hash21(i);
    float b = hash21(i + vec2(1.0, 0.0));
    float c = hash21(i + vec2(0.0, 1.0));
    float d = hash21(i + vec2(1.0, 1.0));
    return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// 1 = облако закрывает солнце (темно), 0 = просвет. Точка проецируется на плоскость
// облаков вдоль направления на солнце — поэтому пятна едут вместе с облаками.
float cloudShadow(vec3 wp, vec3 sunDir) {
    const float cloudY = 192.0;
    if (sunDir.y <= 0.03) {
        return 0.0;
    }
    float t = (cloudY - wp.y) / sunDir.y;
    if (t < 0.0) {
        return 0.0;
    }
    vec2 p = (wp + sunDir * t).xz;
    p += vec2(Misc.x * 0.7, Misc.x * 0.3); // ветер облаков
    float n = vnoise(p * 0.018) * 0.65 + vnoise(p * 0.045) * 0.35;
    return smoothstep(0.50, 0.62, n);
}

void main() {
    vec2 res = ResPad.xy;
    float dbg = ResPad.z;
    if (dbg > 1.5) {
        fragColor = vec4(0.5); // debug 2: проверка пасса+бленда (весь экран -50%)
        return;
    }
    // Coarse-шаг 2x2/4x4: квантуем центр пикселя к центру блока stride×stride,
    // чтобы соседние пиксели делили один луч (когерентность кэша).
    float stride = max(ResPad.w, 1.0);
    vec2 px = (floor(gl_FragCoord.xy / stride) + 0.5) * stride;
    vec3 ndc = vec3(px / res * 2.0 - 1.0, 1.0);
    vec4 wp4 = InvViewProj * vec4(ndc, 1.0);
    // ВАЖНО: InvViewProj инвертирует P*Vrot, которая отображает СМЕЩЕНИЕ
    // относительно камеры (см. Camera.projectPointToScreen), поэтому wp4/w
    // уже есть направление луча — CamPos вычитать НЕЛЬЗЯ (был такой баг:
    // все лучи летели мимо, теней не было).
    vec3 ro = CamPos;
    vec3 rd = normalize(wp4.xyz / wp4.w);

    // Раскладка пирамиды — та же, что строит VoxelVolume.buildMips().
    ivec3 dims2 = Dims / 4;
    int off2 = Dims.x * Dims.y * Dims.z;

    float primaryMax = Params.w;
    vec3 bmin = vec3(Origin);
    vec3 bmax = vec3(Origin + Dims);
    float tEnter;
    float tExit;
    if (!rayBox(ro, rd, bmin, bmax, primaryMax, tEnter, tExit)) {
        fragColor = vec4(1.0); // мимо объема (небо): ноль шагов DDA
        return;
    }

    // Primary march: first OPAQUE (1) or LEAF (4) voxel. Codes 0/2/3 are see-through.
    // Иерархия: coarse находит клетку-кандидата, fine подтверждает с откатом.
    // Откат 8.01 > диагонали 4-куба (4*sqrt(3) ~= 6.93): старт fine-марша
    // гарантированно ВНЕ найденной клетки, а всё до неё — пустые coarse-клетки,
    // т.е. пустые fine-воксели. Поэтому пропуски структурно невозможны,
    // а нормали считаются обычным шаганием.
    ivec3 cell;
    ivec3 stepI;
    vec3 tMax;
    vec3 tDelta;
    uint camM = voxelAt(ivec3(floor(ro - vec3(Origin))));
    if (camM == 1u || camM == 4u) {
        fragColor = vec4(1.0); // камера внутри твердого, fail open
        return;
    }
    float t0 = max(tEnter, 0.0);
    float t1 = min(tExit, primaryMax);
    float hitT = -1.0;
    vec3 nrm = vec3(0.0);
    float tCur = t0;
    for (int outer = 0; outer < 24 && tCur <= t1; outer++) {
        float tC = coarseFind(ro, rd, tCur, t1, dims2, off2);
        if (tC < 0.0) {
            break;
        }
        float tF = max(max(tC - 8.01, tCur), 0.0);
        ddaInit(ro + rd * tF, rd, cell, stepI, tMax, tDelta);
        float t = tF;
        float tFineEnd = min(tC + 8.0, t1);
        uint m0 = voxelAt(cell);
        if (m0 == 1u || m0 == 4u) {
            hitT = tF;
            nrm = dominantNormal(rd);
            break;
        }
        bool done = false;
        for (int i = 0; i < 64 && t <= tFineEnd; i++) {
            if (tMax.x < tMax.y && tMax.x < tMax.z) {
                cell.x += stepI.x;
                t = tF + tMax.x;
                tMax.x += tDelta.x;
                nrm = vec3(float(-stepI.x), 0.0, 0.0);
            } else if (tMax.y < tMax.z) {
                cell.y += stepI.y;
                t = tF + tMax.y;
                tMax.y += tDelta.y;
                nrm = vec3(0.0, float(-stepI.y), 0.0);
            } else {
                cell.z += stepI.z;
                t = tF + tMax.z;
                tMax.z += tDelta.z;
                nrm = vec3(0.0, 0.0, float(-stepI.z));
            }
            uint m = voxelAt(cell);
            if (m == 1u || m == 4u) {
                hitT = t;
                done = true;
                break;
            }
        }
        if (done) {
            break;
        }
        tCur = tFineEnd + 0.01; // строго вперед: coarseFind заново якорится и ничего не пропустит
    }
    if (hitT < 0.0) {
        fragColor = vec4(1.0); // sky / out of volume: no darkening
        return;
    }
    if (dbg > 0.5) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0); // debug 1: черним все найденные поверхности
        return;
    }

    // Затухание у границы рабочей области луча. За пределами бокса (или дальше
    // primaryMax) rayBox() не попал и луч вернул 1.0, а внутри рисовалась тень —
    // получалась прямая черта поперёк кадра ("разрез"). Гасим силу тени по мере
    // приближения к краю. t1 = min(tExit, primaryMax) — реальный конец луча.
    float edgeFade = smoothstep(0.0, 28.0, t1 - hitT);

    // Shadow march toward the sun.
    // Surfaces facing away from the sun are shadowed by definition (no march needed).
    float facing = dot(nrm, SunDir);
    if (facing <= 0.0) {
        float fb = 1.0 - Params.z * edgeFade;
        fragColor = vec4(fb, fb, fb, 1.0);
        return;
    }
    // Старт чуть над поверхностью; клетка самой поверхности (originCell) из сэмплирования
    // исключается явно — иначе вечное самозатенение (был такой баг "всё темно").
    vec3 start = ro + rd * hitT + nrm * 0.05 + SunDir * 0.1;
    ivec3 originCell = ivec3(floor(start - vec3(Origin)));
    float shadowDist = Params.x;
    int maxSteps = int(Params.y);

    // Мягкость: 1 тап = жёсткие тени, 4/8 = усреднение по диску солнца.
    // Тапы идут по золотому углу, чтобы не было полос от регулярной сетки.
    int taps = int(Misc.z);
    float sunR = Misc.y;
    float acc = 0.0;
    if (taps <= 1) {
        acc = shadowMarch(start, originCell, SunDir, shadowDist, maxSteps, dims2, off2);
    } else {
        // Базис в плоскости, перпендикулярной солнцу.
        vec3 up = abs(SunDir.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
        vec3 tang = normalize(cross(up, SunDir));
        vec3 bitan = cross(SunDir, tang);
        for (int i = 0; i < 8; i++) {
            if (i >= taps) {
                break;
            }
            float ang = float(i) * 2.39996323; // золотой угол
            float rad = sqrt((float(i) + 0.5) / float(taps)) * sunR;
            vec3 d = normalize(SunDir + (tang * cos(ang) + bitan * sin(ang)) * rad);
            acc += shadowMarch(start, originCell, d, shadowDist, maxSteps, dims2, off2);
        }
        acc /= float(taps);
    }

    float occl = acc;
    if (Misc.w > 0.5) {
        // Тени от облаков считаем по точке попадания, а не по лучу — иначе пятна
        // «плыли» бы на границах блоков. 0.75 — облако чуть светлее полной тени,
        // как в ванилле (там тень от облаков полупрозрачная, а не чёрная).
        float cs = cloudShadow(ro + rd * hitT, SunDir) * 0.75;
        occl = max(occl, cs);
    }

    float f = 1.0 - Params.z * clamp(occl, 0.0, 1.0) * edgeFade;
    fragColor = vec4(f, f, f, 1.0);
}
