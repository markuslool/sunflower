#version 330

// Sunflower shadow raster: depth-only проход одного каскада клипмапа.
// Вершины секций чанков приходят в МИРОВЫХ координатах (SectionCompiler тесселирует
// абсолютные BlockPos), поэтому ModelOffset не нужен — в отличие от сущностей.

layout(std140) uniform RtShadowFrame {
    mat4 LightViewProj;
};

in vec3 Position;
// Остальные атрибуты формата DefaultVertexFormat.BLOCK объявляем, но не читаем:
// Vulkan допускает неиспользуемые вершинные входы, а объявлять их надо, чтобы
// VertexInput пайплайна совпадал с форматом, которым реально забиндин геометрию.
in vec4 Color;
in vec2 UV0;
in ivec2 UV2;

void main() {
    gl_Position = LightViewProj * vec4(Position, 1.0);
}