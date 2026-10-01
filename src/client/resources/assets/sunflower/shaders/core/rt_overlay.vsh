#version 330

// Sunflower RT overlay: fullscreen triangle, no vertex buffer data needed
// (positions come from the POSITION vertex buffer, 3 verts).
in vec3 Position;

void main() {
    gl_Position = vec4(Position, 1.0);
}
