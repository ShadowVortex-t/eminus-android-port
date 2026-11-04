#version 330
#extension GL_ARB_separate_shader_objects : require

#include <eminus:far_lightmap.glsl>
#include <eminus:far_frame.glsl>

#ifdef STORAGE_QUADS
// The device's texel buffers are too small for the geometry arena, so its quads are read as a storage buffer.
layout(std430, binding = 0) readonly buffer EminusQuads {
    uvec2 EminusQuadWords[];
};
#define EMINUS_QUAD(index) EminusQuadWords[index]
#else
uniform usamplerBuffer EminusQuads;
#define EMINUS_QUAD(index) texelFetch(EminusQuads, index).xy
#endif
uniform usamplerBuffer EminusMeshRecords;
uniform samplerBuffer EminusModelRecords;
uniform sampler2D Lightmap;

#include <eminus:far_vertex.glsl>

layout(location = 0) out vec2 faceUV;
layout(location = 1) out vec4 vertexColor;
layout(location = 2) flat out vec3 tintColor;
layout(location = 3) flat out ivec2 atlasCell;
layout(location = 5) flat out ivec4 variantInfo;
layout(location = 6) flat out ivec3 cellOrigin;
layout(location = 7) out vec3 voxelPoint;
layout(location = 8) out vec3 occlusionPoint;
layout(location = 9) flat out vec2 occlusionSize;
layout(location = 10) flat out uvec2 occlusionWord;

#ifdef NEAR_SECTIONS
layout(location = 4) out vec3 nearPoint;
#endif

invariant gl_Position;

const int LIGHT_STEP = 16;
const vec4 CULLED_POSITION = vec4(2.0, 2.0, 2.0, 1.0);

void main() {
    EminusQuadVertex vertex = eminus_quad_vertex(gl_VertexIndex);
    gl_Position = vertex.culled ? CULLED_POSITION : EminusProjView * vec4(vertex.position, 1.0);

#ifdef NEAR_SECTIONS
    nearPoint = vertex.facePoint;
#endif

    faceUV = vertex.faceUV;
    atlasCell = vertex.atlasCell;
    variantInfo = ivec4(vertex.variantStart, vertex.variantCount, vertex.faceSlot, vertex.level);
    cellOrigin = vertex.cellOrigin;
    voxelPoint = vertex.voxelPoint;
    tintColor = vertex.tint;
    occlusionPoint = vec3(vertex.occlusionPoint, vertex.occlusionInner);
    occlusionSize = vertex.occlusionSize;
    occlusionWord = uvec2(vertex.occlusion, vertex.mainDiagonal ? 1u : 0u);

    vec4 color = far_lightmap(Lightmap, ivec2(vertex.blockLight * LIGHT_STEP, vertex.skyLight * LIGHT_STEP));
    uint shades = uint(texelFetch(EminusModelRecords, vertex.modelId * MODEL_TEXELS + SHADES_TEXEL).y);
    uint shade = eminus_field(shades, vertex.faceSlot * SHADE_BITS, SHADE_BITS);
    float faceShade = shade == 0u ? EminusShadeDown
        : shade == 1u ? EminusShadeUp
        : shade == 2u ? EminusShadeNorth
        : shade == 3u ? EminusShadeSouth
        : shade == 4u ? EminusShadeWest
        : EminusShadeEast;
    color.rgb *= faceShade;
    vertexColor = color;
}
