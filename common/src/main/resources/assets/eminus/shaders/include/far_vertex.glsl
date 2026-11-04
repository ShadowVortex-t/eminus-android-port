#ifndef EMINUS_FAR_VERTEX_GLSL
#define EMINUS_FAR_VERTEX_GLSL

struct EminusQuadVertex {
    vec3 position;
    vec3 facePoint;
    int face;
    int blockLight;
    int skyLight;
    ivec2 atlasCell;
    vec2 faceUV;
    vec3 tint;
    ivec3 cellOrigin;
    int level;
    vec3 voxelPoint;
    int faceSlot;
    int variantStart;
    int variantCount;
    int emission;
    int modelId;
    bool culled;
    uint occlusion;
    bool mainDiagonal;
    vec2 occlusionPoint;
    vec2 occlusionSize;
    float occlusionInner;
};

const int EMINUS_CORNERS_PER_QUAD = 4;
const int EMINUS_WORD_BITS = 32;
const float EMINUS_HALF_VOXEL = 0.5;

uint eminus_field(uint value, int shift, int bits) {
    return (value >> uint(shift)) & ((1u << uint(bits)) - 1u);
}

uint eminus_field(uvec2 value, int shift, int bits) {
    if (shift >= EMINUS_WORD_BITS) {
        return eminus_field(value.y, shift - EMINUS_WORD_BITS, bits);
    }

    uint low = value.x >> uint(shift);
    if (shift + bits > EMINUS_WORD_BITS) {
        low |= value.y << uint(EMINUS_WORD_BITS - shift);
    }
    return low & ((1u << uint(bits)) - 1u);
}

float eminus_low(float model, float lowGap, float blocks) {
    return (lowGap + model) / blocks;
}

float eminus_high(float model, float highGap, float blocks) {
    return (blocks - 1.0 - highGap + model) / blocks;
}

float eminus_offset_axis(uint placement, int shift) {
    int steps = int(eminus_field(placement, shift, OFFSET_AXIS_BITS));
    int signBit = 1 << (OFFSET_AXIS_BITS - 1);
    return float(steps >= signBit ? steps - 2 * signBit : steps) / OFFSET_STEPS_PER_BLOCK;
}

// The corner arithmetic below reads and writes every axis by comparison, never by a varying index: a translated shader
// must not depend on how the translator lowers a dynamic index into a vector or a local array.
float eminus_axis(vec3 value, int axis) {
    return axis == 0 ? value.x : (axis == 1 ? value.y : value.z);
}

int eminus_axis_int(ivec3 value, int axis) {
    return axis == 0 ? value.x : (axis == 1 ? value.y : value.z);
}

vec3 eminus_axis_add(vec3 value, int axis, float amount) {
    return value + vec3(axis == 0 ? amount : 0.0, axis == 1 ? amount : 0.0, axis == 2 ? amount : 0.0);
}

vec3 eminus_axis_set(vec3 value, int axis, float amount) {
    return vec3(axis == 0 ? amount : value.x, axis == 1 ? amount : value.y, axis == 2 ? amount : value.z);
}

float eminus_inset(vec4 first, vec4 second, int face) {
    if (face == 0) {
        return first.x;
    }
    if (face == 1) {
        return first.y;
    }
    if (face == 2) {
        return first.z;
    }
    if (face == 3) {
        return first.w;
    }
    if (face == 4) {
        return second.x;
    }

    return second.y;
}

vec4 eminus_fluid_corners(uint corners, float flatHeight) {
    float steps = float(CORNER_STEPS);
    if (corners == 0u) {
        return vec4(round(flatHeight * steps) / steps);
    }

    return vec4(float(eminus_field(corners, CORNER_NORTH_WEST_SHIFT, CORNER_BITS)),
                float(eminus_field(corners, CORNER_NORTH_EAST_SHIFT, CORNER_BITS)),
                float(eminus_field(corners, CORNER_SOUTH_WEST_SHIFT, CORNER_BITS)),
                float(eminus_field(corners, CORNER_SOUTH_EAST_SHIFT, CORNER_BITS))) / steps;
}

float eminus_fluid_corner(vec4 surface, int face, vec2 unit) {
    bool widthEnd = unit.x > EMINUS_HALF_VOXEL;
    if (face == 1) {
        return unit.y > EMINUS_HALF_VOXEL ? (widthEnd ? surface.w : surface.z) : (widthEnd ? surface.y : surface.x);
    }
    if (face == 2) {
        return widthEnd ? surface.y : surface.x;
    }
    if (face == 3) {
        return widthEnd ? surface.w : surface.z;
    }
    if (face == 4) {
        return widthEnd ? surface.z : surface.x;
    }

    return widthEnd ? surface.w : surface.y;
}

int eminus_normal_axis(int face) {
    return face < 2 ? 1 : (face < 4 ? 2 : 0);
}

vec3 eminus_face_normal(int face) {
    return eminus_axis_set(vec3(0.0), eminus_normal_axis(face), (face & 1) == 1 ? 1.0 : -1.0);
}

vec2 eminus_slope(vec4 fifth, vec4 sixth, vec4 seventh, int face) {
    if (face == 0) {
        return fifth.xy;
    }
    if (face == 1) {
        return fifth.zw;
    }
    if (face == 2) {
        return sixth.xy;
    }
    if (face == 3) {
        return sixth.zw;
    }
    if (face == 4) {
        return seventh.xy;
    }

    return seventh.zw;
}

bool eminus_front_only(int flags, int face) {
    if ((flags & ONE_SIDED_FLAG) != 0) {
        return true;
    }
#ifdef SHADOW_PASS
    // A light sees the faces the camera's plane test drops.
    return false;
#else
    if ((flags & TRANSLUCENT_FLAG) == 0) {
        // A face whose model lacks the opposite face stands for both of the game's faces.
        return (flags >> PRESENT_SHIFT & 1 << (face ^ 1)) != 0;
    }
    // The game draws a fluid's top and sides from both sides and its bottom from the front alone.
    return (flags & FLUID_FLAG) == 0 || face == 0;
#endif
}

EminusQuadVertex eminus_quad_vertex(int vertexId) {
    EminusQuadVertex vertex;

    int quadIndex = vertexId / EMINUS_CORNERS_PER_QUAD;
    int corner = vertexId % EMINUS_CORNERS_PER_QUAD;
    vec2 unit = vec2(corner == 1 || corner == 2 ? 1.0 : 0.0, corner >= 2 ? 1.0 : 0.0);

    uvec4 mesh = texelFetch(EminusMeshRecords,quadIndex / QUADS_PER_SEGMENT);
    int level = int(eminus_field(mesh.xy, CELL_LEVEL_SHIFT, CELL_LEVEL_BITS));
    int cellX = int(eminus_field(mesh.xy, CELL_X_SHIFT, CELL_HORIZONTAL_BITS)) + MIN_HORIZONTAL;
    int cellZ = int(eminus_field(mesh.xy, CELL_Z_SHIFT, CELL_HORIZONTAL_BITS)) + MIN_HORIZONTAL;
    int cellY = int(eminus_field(mesh.xy, CELL_Y_SHIFT, CELL_VERTICAL_BITS)) + MIN_VERTICAL;

    uvec2 quad = EMINUS_QUAD(quadIndex);
    int face = int(eminus_field(quad, QUAD_FACE_SHIFT, QUAD_FACE_BITS));
    ivec3 voxel = ivec3(eminus_field(quad, QUAD_X_SHIFT, QUAD_COORDINATE_BITS),
                        eminus_field(quad, QUAD_Y_SHIFT, QUAD_COORDINATE_BITS),
                        eminus_field(quad, QUAD_Z_SHIFT, QUAD_COORDINATE_BITS));
    int width = int(eminus_field(quad, QUAD_WIDTH_SHIFT, QUAD_SIDE_BITS)) + 1;
    int height = int(eminus_field(quad, QUAD_HEIGHT_SHIFT, QUAD_SIDE_BITS)) + 1;
    uint light = eminus_field(quad, QUAD_LIGHT_SHIFT, QUAD_LIGHT_BITS);
    int modelId = int(eminus_field(quad, QUAD_MODEL_SHIFT, QUAD_MODEL_BITS));
    int colorIndex = int(eminus_field(quad, QUAD_COLOR_SHIFT, QUAD_COLOR_BITS));
    int quadInMesh = quadIndex - int(mesh.z);
    bool occluded = (mesh.w >> MESH_UNOCCLUDED_SHIFT) == 0u;
    bool innerOccluded = ((mesh.w >> MESH_INNER_OCCLUDED_SHIFT) & 1u) != 0u;
    int meshQuads = int(mesh.w & ((1u << MESH_INNER_OCCLUDED_SHIFT) - 1u));
    int streamStart = int(mesh.z) + meshQuads;
    int outerSlots = occluded ? (meshQuads + OCCLUSION_PER_SLOT - 1) / OCCLUSION_PER_SLOT : 0;
    int occlusionSlots = innerOccluded ? 2 * outerSlots : outerSlots;
    vertex.occlusion = 0u;
    vertex.occlusionInner = 0.0;
    if (occluded) {
        int wordShift = quadInMesh % OCCLUSION_PER_SLOT * OCCLUSION_BITS;
        int wordSlot = quadInMesh / OCCLUSION_PER_SLOT;
        vertex.occlusion = eminus_field(EMINUS_QUAD(streamStart + wordSlot), wordShift, OCCLUSION_BITS);
        if (innerOccluded) {
            uint inner = eminus_field(EMINUS_QUAD(streamStart + outerSlots + wordSlot), wordShift,
                                      OCCLUSION_BITS);
            vertex.occlusion |= inner << uint(OCCLUSION_INNER_SHIFT);
        }
    }
    vertex.mainDiagonal = face >= FIRST_BLADE_FACE || face == 1 || face == 2 || face == 5;

    vec4 first = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS);
    vec4 second = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 1);
    vec4 third = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 2);
    vec4 fourth = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 3);
    vec4 fifth = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 4);
    vec4 sixth = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 5);
    vec4 seventh = texelFetch(EminusModelRecords,modelId * MODEL_TEXELS + 6);
    vec3 boundsMin = vec3(second.z, second.w, third.x);
    vec3 boundsMax = vec3(third.y, third.z, third.w);

    int flags = floatBitsToInt(fourth.x);
    bool fluid = (flags & FLUID_FLAG) != 0;
    bool inward = (flags & INWARD_FLAG) != 0;
    uint placement = 0u;
    vertex.tint = vec3(1.0);
    if (colorIndex != 0) {
        uvec2 entry = EMINUS_QUAD(streamStart + occlusionSlots + colorIndex);
        vertex.tint = vec3(eminus_field(entry.r, TINT_RED_SHIFT, TINT_CHANNEL_BITS),
                           eminus_field(entry.r, TINT_GREEN_SHIFT, TINT_CHANNEL_BITS),
                           eminus_field(entry.r, TINT_BLUE_SHIFT, TINT_CHANNEL_BITS))
                / float((1 << TINT_CHANNEL_BITS) - 1);
        placement = entry.g;
    }

    uint corners = 0u;
    vec3 offset = vec3(0.0);
    float lowGap = 0.0;
    float highGap = 0.0;
    if (level != 0) {
        lowGap = float(eminus_field(placement, LOW_GAP_SHIFT, NIBBLE_BITS));
        highGap = float(eminus_field(placement, HIGH_GAP_SHIFT, NIBBLE_BITS));
    } else if (fluid) {
        corners = placement;
    } else {
        offset = vec3(eminus_offset_axis(placement, OFFSET_X_SHIFT), eminus_offset_axis(placement, OFFSET_Y_SHIFT),
                      eminus_offset_axis(placement, OFFSET_Z_SHIFT));
    }

    float blocks = float(1 << level);
    float drawnBottom = eminus_low(boundsMin.y, lowGap, blocks);
    float drawnTop = eminus_high(boundsMax.y, highGap, blocks);
    vec3 local = vec3(voxel) + offset;
    vec2 extent;
    vec3 planeNormal = vec3(0.0);

    if (face >= FIRST_BLADE_FACE) {
        float slide = mix(boundsMin.x, boundsMax.x, unit.x);
        float across = face == FIRST_BLADE_FACE ? slide : boundsMin.x + boundsMax.x - slide;
        float up = unit.y * float(height - 1) + mix(drawnBottom, drawnTop, unit.y);

        local.x += across;
        local.y += up;
        local.z += mix(boundsMin.z, boundsMax.z, unit.x);
        extent = vec2(face == FIRST_BLADE_FACE ? unit.x : 1.0 - unit.x, unit.y * float(height));
        vertex.occlusionPoint = vec2(across, up) * blocks;
        vertex.occlusionSize = vec2(1.0, float(height)) * blocks;
    } else {
        int normalAxis = eminus_normal_axis(face);
        int widthAxis = face < 4 ? 0 : 2;
        int heightAxis = face < 2 ? 2 : 1;
        bool vertical = heightAxis == 1;
        extent = vec2(unit.x * float(width - 1)
                          + mix(eminus_axis(boundsMin, widthAxis), eminus_axis(boundsMax, widthAxis), unit.x),
                      unit.y * float(height - 1)
                          + mix(eminus_axis(boundsMin, heightAxis), eminus_axis(boundsMax, heightAxis), unit.y));
        vertex.occlusionPoint = extent * blocks;
        vertex.occlusionSize = vec2(width, height) * blocks;
        vec2 slope = eminus_slope(fifth, sixth, seventh, face);
        float inset = eminus_inset(first, second, face) + dot(slope, extent);
        float placedHeight = vertical ? unit.y * float(height - 1) + mix(drawnBottom, drawnTop, unit.y) : extent.y;
        if (fluid && face != 0) {
            float surface = eminus_fluid_corner(eminus_fluid_corners(corners, boundsMax.y), inward ? face ^ 1 : face, unit);
            if (face == 1) {
                inset = 1.0 - surface;
                // Sodium lights a water top between its own layer and the one above, by the corner's height.
                vertex.occlusionInner = 1.0 - surface;
            } else {
                vertex.occlusionPoint.y = unit.y * float(height) * blocks;
                extent.y = unit.y * float(height - 1) + mix(boundsMin.y, surface, unit.y);
                placedHeight = unit.y * float(height - 1) + mix(drawnBottom, eminus_high(surface, highGap, blocks), unit.y);
            }
        }

        float depth = (face & 1) == 1 ? 1.0 - inset : inset;
        if (face == 0) {
            depth = eminus_low(inset, lowGap, blocks);
        } else if (face == 1) {
            depth = eminus_high(1.0 - inset, highGap, blocks);
        } else if (inward) {
            depth = 1.0 - depth;
        }

        local = eminus_axis_add(local, normalAxis, depth);
        local = eminus_axis_add(local, widthAxis, extent.x);
        local = eminus_axis_add(local, heightAxis, placedHeight);

        // The plane's own normal, not the axis: the corners of a sloped face lie at different depths along the axis.
        float depthStep = (face & 1) == 1 ? -1.0 : 1.0;
        if (face < 2) {
            depthStep /= blocks;
        } else if (inward) {
            depthStep = -depthStep;
        }
        float placedStep = vertical ? float(height - 1) + drawnTop - drawnBottom : 1.0;
        float extentStep = vertical ? float(height - 1) + boundsMax.y - boundsMin.y : 1.0;
        planeNormal = eminus_axis_set(vec3(0.0), normalAxis, placedStep);
        planeNormal = eminus_axis_add(planeNormal, widthAxis, -depthStep * slope.x * placedStep);
        planeNormal = eminus_axis_add(planeNormal, heightAxis, -depthStep * slope.y * extentStep);
        planeNormal = (face & 1) == 1 ? planeNormal : -planeNormal;
    }

    int cellBlocks = VOXELS_PER_SIDE << level;
    ivec3 origin = ivec3(cellX * cellBlocks, cellY * cellBlocks + EminusMinBlockY, cellZ * cellBlocks);
    vertex.position = vec3(origin - EminusCameraBlockPos) + EminusCameraOffset + local * float(1 << level);
    vertex.culled = face < FIRST_BLADE_FACE && eminus_front_only(flags, face)
            && dot(planeNormal, vertex.position) >= 0.0;

    vec3 faceLocal = local;
    if (face < FIRST_BLADE_FACE) {
        int faceAxis = eminus_normal_axis(face);
        faceLocal = eminus_axis_set(faceLocal, faceAxis,
                float(eminus_axis_int(voxel, faceAxis)) + EMINUS_HALF_VOXEL + eminus_axis(offset, faceAxis));
    }
    vertex.facePoint = vec3(origin - EminusCameraBlockPos) + faceLocal * float(1 << level);
    vertex.voxelPoint = faceLocal - offset;
    vertex.cellOrigin = origin;
    vertex.level = level;

    if (face >= FIRST_BLADE_FACE) {
        vec2 span = vec2(boundsMax.x - boundsMin.x, boundsMax.z - boundsMin.z);
        vec3 painted = vec3(span.y, 0.0, face == FIRST_BLADE_FACE ? -span.x : span.x);
        vertex.faceUV = vec2(dot(vertex.position, painted) > 0.0 ? 1.0 - extent.x : extent.x, extent.y);
    } else {
        vertex.faceUV = vec2(face == 2 || face == 5 ? float(width) - extent.x : extent.x,
                             face == 1 ? float(height) - extent.y : extent.y);
    }

    vertex.faceSlot = face >= FIRST_BLADE_FACE ? face - FIRST_BLADE_FACE : face;
    int slot = modelId * MODEL_FACES + vertex.faceSlot;
    vertex.atlasCell = ivec2(slot % EminusAtlasCells, slot / EminusAtlasCells);
    vertex.variantStart = int(fourth.z);
    vertex.variantCount = int(fourth.w);

    vertex.emission = int(eminus_field(uint(flags), EMISSION_SHIFT, EMISSION_BITS));
    vertex.modelId = modelId;
    vertex.face = face;
    vertex.blockLight = int(eminus_field(light, BLOCK_LIGHT_SHIFT, NIBBLE_BITS));
    vertex.skyLight = int(eminus_field(light, SKY_LIGHT_SHIFT, NIBBLE_BITS));

    return vertex;
}

#endif
