#version 450 core

// The full-screen quad of the upscale passes: Position over [0,1]^2, UV0 the same corner.
layout(location = 0) in vec3 Position;
layout(location = 1) in vec2 UV0;

layout(location = 0) out vec2 aeth_uv;

void main() {
    aeth_uv = UV0;
    gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
}
