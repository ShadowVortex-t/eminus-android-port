#version 330

#include <eminus:far_frame.glsl>

#ifdef TEXTURE_QUADS
// The device's texel buffers are too small for the geometry arena, so its quads are read from a 2D texture.
uniform usampler2D EminusQuads;
#define EMINUS_QUAD(index) texelFetch(EminusQuads, ivec2((index) & QUAD_TEXTURE_MASK, (index) >> QUAD_TEXTURE_SHIFT), 0).xy
#else
uniform usamplerBuffer EminusQuads;
#define EMINUS_QUAD(index) texelFetch(EminusQuads, index).xy
#endif
uniform usamplerBuffer EminusMeshRecords;
uniform samplerBuffer EminusModelRecords;

#include <eminus:far_vertex.glsl>

out vec2 eminus_faceUV;
out vec3 eminus_nearPoint;
out vec3 eminus_voxelPoint;
out vec3 eminus_viewPos;
out vec3 eminus_playerPos;
out vec2 eminus_lmcoord;
flat out vec3 eminus_tint;
flat out vec3 eminus_normal;
flat out ivec2 eminus_atlasCell;
flat out ivec4 eminus_variantInfo;
flat out ivec3 eminus_cellOrigin;
flat out int eminus_face;
flat out float eminus_emission;
flat out int eminus_blockId;
#ifndef DH_PROGRAM
out vec3 eminus_occlusionPoint;
flat out vec2 eminus_occlusionSize;
flat out uvec2 eminus_occlusion;
#endif
#ifdef PACK_VERTEX
#include <eminus:far_pack_hook.glsl>
#elif defined PACK_SHADOW_VERTEX
vec4 eminus_shadowPosition(vec4 shadowClipPosition);
#elif defined DH_PROGRAM
uniform sampler2D EminusAtlas;
uniform sampler2D EminusTintMask;
#endif
#ifdef PACK_SHADOW_PROGRAM
#include <eminus:far_pack_inputs.glsl>

vec4 eminus_multiTexCoord0;
vec4 eminus_multiTexCoord1;
#endif
#ifndef DH_PROGRAM
out vec3 iris_vBlockPos;
flat out uvec2 iris_TexId;
#endif

const vec4 EMINUS_CULLED_POSITION = vec4(2.0, 2.0, 2.0, 1.0);
const vec3 EMINUS_BLADE_NORMAL = vec3(0.0, 1.0, 0.0);
const float EMINUS_LIGHT_LEVELS = 16.0;
const float EMINUS_LIGHT_CENTER = 0.5;
const int EMINUS_LAST_AXIS_FACE = 5;
const int EMINUS_FIRST_SIDE_FACE = 2;
const int EMINUS_FIRST_TOP_CORNER = 2;
#ifdef SHADOW_PASS
const bool EMINUS_SHADOW_PASS = true;
#else
const bool EMINUS_SHADOW_PASS = false;
#endif
#ifdef STILL
const bool EMINUS_ANIMATED = false;
#else
const bool EMINUS_ANIMATED = true;
#endif
const int EMINUS_NO_BLOCK_ID = -1;
const int EMINUS_ANIMATED_LEVEL = 0;

#ifdef DH_PROGRAM
bool eminus_culled;

vec4 eminus_faceColor(ivec2 atlasCell, vec3 tint) {
    vec4 color = texelFetch(EminusAtlas, atlasCell, EminusAtlasMeanLevel);
    color.rgb *= mix(vec3(1.0), tint, texelFetch(EminusTintMask, atlasCell, EminusAtlasMeanLevel).r);
#ifdef FULL_COVERAGE
    color.a = 1.0;
#endif
    return color;
}

void eminus_cull() {
    if (eminus_culled) {
        gl_Position = EMINUS_CULLED_POSITION;
    }
}
#endif

void eminus_vertex() {
    EminusQuadVertex vertex = eminus_quad_vertex(gl_VertexIndex);
    vec3 position = vertex.position;
    vec2 lmcoord = (vec2(vertex.blockLight, vertex.skyLight) + EMINUS_LIGHT_CENTER) / EMINUS_LIGHT_LEVELS;
    int blockId = int(texelFetch(EminusModelRecords,vertex.modelId * MODEL_TEXELS + PACK_ID_TEXEL).x);
#ifdef PACK_VERTEX
    bool animated = EMINUS_ANIMATED && vertex.level == EMINUS_ANIMATED_LEVEL;
    bool top = animated && vertex.face >= EMINUS_FIRST_SIDE_FACE
            && gl_VertexIndex % EMINUS_CORNERS_PER_QUAD >= EMINUS_FIRST_TOP_CORNER;
    EminusVertex hooked = EminusVertex(position, animated ? blockId : EMINUS_NO_BLOCK_ID, vertex.face,
            animated && vertex.face >= FIRST_BLADE_FACE, top, lmcoord, vertex.level, EMINUS_SHADOW_PASS);
    vec4 clipPosition = eminus_vertexPosition(hooked);
    position = hooked.playerPos;
    gl_Position = vertex.culled ? EMINUS_CULLED_POSITION : clipPosition;
#elif defined PACK_SHADOW_VERTEX
    gl_Position = vertex.culled ? EMINUS_CULLED_POSITION : eminus_shadowPosition(EminusProjView * vec4(position, 1.0));
#elif !defined DH_PROGRAM
    gl_Position = vertex.culled ? EMINUS_CULLED_POSITION : EminusProjView * vec4(position, 1.0);
#endif
#ifdef SHADOW_ZERO_TO_ONE
    // The remap Iris gives a patched shadow program, so a pack's -1..1 shadow clip lands where its lookups read.
    gl_Position.z = 0.5 * (gl_Position.z + gl_Position.w);
#endif

    eminus_lmcoord = lmcoord;
    eminus_blockId = blockId;
    eminus_faceUV = vertex.faceUV;
    eminus_nearPoint = vertex.facePoint;
    eminus_voxelPoint = vertex.voxelPoint;
    eminus_viewPos = (EminusView * vec4(position, 1.0)).xyz;
    eminus_playerPos = position;
    eminus_tint = vertex.tint;
    vec3 normal = vertex.face < FIRST_BLADE_FACE ? eminus_face_normal(vertex.face) : EMINUS_BLADE_NORMAL;
    eminus_normal = mat3(EminusView) * normal;
    eminus_atlasCell = vertex.atlasCell;
    eminus_variantInfo = ivec4(vertex.variantStart, vertex.variantCount, vertex.faceSlot, vertex.level);
    eminus_cellOrigin = vertex.cellOrigin;
    eminus_face = vertex.face;
    eminus_emission = float(vertex.emission) / float(MAX_EMISSION);
#ifndef DH_PROGRAM
    eminus_occlusionPoint = vec3(vertex.occlusionPoint, vertex.occlusionInner);
    eminus_occlusionSize = vertex.occlusionSize;
    eminus_occlusion = uvec2(vertex.occlusion, vertex.mainDiagonal ? 1u : 0u);
#endif

#ifdef DH_PROGRAM
    eminus_culled = vertex.culled;
    _vert_position = vertex.position;
    _vert_normal = normal;
    _vert_tex_light_coord = lmcoord;
    dhMaterialId = blockId;
#ifdef PACK_SHADOW_PROGRAM
    _vert_color = vec4(1.0);
    eminus_multiTexCoord0 = vec4(vertex.faceUV, 0.0, 1.0);
    eminus_multiTexCoord1 = vec4(vec2(vertex.blockLight, vertex.skyLight) * EMINUS_LIGHT_LEVELS, 0.0, 1.0);
    eminus_packInputs(blockId);
#else
    _vert_color = eminus_faceColor(vertex.atlasCell, vertex.tint);
#endif
#else
    iris_vBlockPos = vertex.voxelPoint;
    iris_TexId = uvec2(0u, uint(min(vertex.face, EMINUS_LAST_AXIS_FACE)));
#endif
}

#if !defined DH_PROGRAM
void main() {
    eminus_vertex();
}
#endif
