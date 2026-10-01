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

    // Shadow march toward the sun.
    // Surfaces facing away from the sun are shadowed by definition (no march needed).
    float facing = dot(nrm, SunDir);
    if (facing <= 0.0) {
        float fb = 1.0 - Params.z;
        fragColor = vec4(fb, fb, fb, 1.0);
        return;
    }
    // Старт чуть над поверхностью; клетка самой поверхности (originCell) из сэмплирования
    // исключается явно — иначе вечное самозатенение (был такой баг "всё темно").
    vec3 start = ro + rd * hitT + nrm * 0.05 + SunDir * 0.1;
    ivec3 originCell = ivec3(floor(start - vec3(Origin)));
    float shadowDist = Params.x;
    int maxSteps = int(Params.y);
    float occl = 0.0;
    int steps = 0;
    float sCur = 0.0;
    for (int outer = 0; outer < 24 && sCur <= shadowDist && occl < 1.0; outer++) {
        float sC = coarseFind(start, SunDir, sCur, shadowDist, dims2, off2);
        if (sC < 0.0) {
            break;
        }
        float sF = max(max(sC - 8.01, sCur), 0.0);
        ddaInit(start + SunDir * sF, SunDir, cell, stepI, tMax, tDelta);
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
                occl = 1.0;
                break;
            } else if (m == 2u) {
                // Стекло/вода/трава: полутень.
                occl += 0.5;
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

    float f = 1.0 - Params.z * clamp(occl, 0.0, 1.0);
    fragColor = vec4(f, f, f, 1.0);
}
