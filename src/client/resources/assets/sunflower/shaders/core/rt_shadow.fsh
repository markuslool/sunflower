#version 330

// Sunflower shadow raster: пустой фрагментный шейдер.
// Целевого цвета у прохода нет (только depth attachment), но Blaze3D требует
// фрагментный шейдер у RenderPipeline, иначе нечем рисовать в пустоту.
out vec4 fragColor;

void main() {
    fragColor = vec4(1.0);
}