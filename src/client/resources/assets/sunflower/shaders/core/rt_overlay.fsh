#version 330

// Sunflower RT overlay v1: primary DDA + sun shadow DDA in fragment.
// Voxel codes (MaterialTable): 0 air, 1 opaque, 2 translucent, 3 emissive/pass.
// Output multiplies the framebuffer (blend ZERO, SRC_COLOR): 1.0 = no change.

layout(std140) uniform RtFrame {
    mat4 InvViewProj;
    vec3 CamPos;
    vec3 SunDir;
    ivec3 Origin;
    ivec3 Dims;
    vec4 Params; // x: shadowDist, y: maxSteps, z: strength, w: primaryMax
    vec4 ResPad; // xy: framebuffer size
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

void main() {
    vec2 res = ResPad.xy;
    float dbg = ResPad.z;
    if (dbg > 1.5) {
        fragColor = vec4(0.5); // debug 2: проверка пасса+бленда (весь экран -50%)
        return;
    }
    vec3 ndc = vec3(gl_FragCoord.xy / res * 2.0 - 1.0, 1.0);
    vec4 wp4 = InvViewProj * vec4(ndc, 1.0);
    // ВАЖНО: InvViewProj инвертирует P*Vrot, которая отображает СМЕЩЕНИЕ
    // относительно камеры (см. Camera.projectPointToScreen), поэтому wp4/w
    // уже есть направление луча — CamPos вычитать НЕЛЬЗЯ (был такой баг:
    // все лучи летели мимо, теней не было).
    vec3 ro = CamPos;
    vec3 rd = normalize(wp4.xyz / wp4.w);

    // Primary march: first OPAQUE (1) or LEAF (4) voxel. Codes 0/2/3 are see-through.
    ivec3 cell;
    ivec3 stepI;
    vec3 tMax;
    vec3 tDelta;
    ddaInit(ro, rd, cell, stepI, tMax, tDelta);
    float primaryMax = Params.w;
    float t = 0.0;
    vec3 nrm = vec3(0.0);
    bool hit = false;
    // Primary останавливается на непрозрачном (1) И на листве (4):
    // пиксель кроны должен освещаться как крона, а не как земля за ней.
    // Иначе свет «протекает сквозь блоки». Прозрачные 2/3 — насквозь.
    uint startM = voxelAt(cell);
    if (startM == 1u || startM == 4u) {
        fragColor = vec4(1.0); // камера внутри твердого, fail open
        return;
    }
    for (int i = 0; i < 320 && t <= primaryMax; i++) {
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            cell.x += stepI.x;
            t = tMax.x;
            tMax.x += tDelta.x;
            nrm = vec3(float(-stepI.x), 0.0, 0.0);
        } else if (tMax.y < tMax.z) {
            cell.y += stepI.y;
            t = tMax.y;
            tMax.y += tDelta.y;
            nrm = vec3(0.0, float(-stepI.y), 0.0);
        } else {
            cell.z += stepI.z;
            t = tMax.z;
            tMax.z += tDelta.z;
            nrm = vec3(0.0, 0.0, float(-stepI.z));
        }
        if (voxelAt(cell) == 1u || voxelAt(cell) == 4u) {
            hit = true;
            break;
        }
    }
    if (!hit) {
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
    // Start outside the hit voxel: bias + unconditionally step once, WITHOUT
    // sampling the starting cell. Sampling it would read the very voxel we hit
    // (bias 0.05 < voxel size 1.0 usually stays inside) => eternal self-shadow.
    // That was the "everything is dark" bug.
    vec3 start = ro + rd * t + nrm * 0.05 + SunDir * 0.1;
    ddaInit(start, SunDir, cell, stepI, tMax, tDelta);
    // First step: leave the starting voxel before any sampling.
    if (tMax.x < tMax.y && tMax.x < tMax.z) {
        cell.x += stepI.x;
        tMax.x += tDelta.x;
    } else if (tMax.y < tMax.z) {
        cell.y += stepI.y;
        tMax.y += tDelta.y;
    } else {
        cell.z += stepI.z;
        tMax.z += tDelta.z;
    }
    float shadowDist = Params.x;
    int maxSteps = int(Params.y);
    float st = 0.0;
    float occl = 0.0;
    for (int i = 0; i < 256 && st < shadowDist; i++) {
        if (i >= maxSteps) {
            break;
        }
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
        if (tMax.x < tMax.y && tMax.x < tMax.z) {
            cell.x += stepI.x;
            st = tMax.x;
            tMax.x += tDelta.x;
        } else if (tMax.y < tMax.z) {
            cell.y += stepI.y;
            st = tMax.y;
            tMax.y += tDelta.y;
        } else {
            cell.z += stepI.z;
            st = tMax.z;
            tMax.z += tDelta.z;
        }
    }

    float f = 1.0 - Params.z * clamp(occl, 0.0, 1.0);
    fragColor = vec4(f, f, f, 1.0);
}
