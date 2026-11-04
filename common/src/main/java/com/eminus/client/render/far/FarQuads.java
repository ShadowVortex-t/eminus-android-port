package com.eminus.client.render.far;

import com.eminus.Eminus;
import com.eminus.cell.CellKey;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.VoxelEntry;
import com.eminus.handoff.NearSections;
import com.eminus.mesh.FluidCorners;
import com.eminus.mesh.MeshBuffer;
import com.eminus.mesh.OcclusionWord;
import com.eminus.mesh.Quad;
import com.eminus.mesh.QuadOffset;
import com.eminus.model.BakedModel;
import com.eminus.model.ModelMetadata;
import com.eminus.model.port.VariantDraw;
import com.eminus.render.arena.ArenaAllocator;
import com.eminus.render.far.DrawCommands;
import com.eminus.client.handoff.NearSectionTable;
import com.eminus.client.model.ModelPublisher;
import com.eminus.client.model.ModelRecords;
import com.eminus.client.model.ModelVariants;
import com.eminus.client.render.arena.GeometryArena;
import com.eminus.client.render.arena.MeshRecords;
import com.eminus.client.gpu.DeviceQueries;
import com.eminus.gpu.Capabilities;
import com.eminus.gpu.Location;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.TexelView;
import com.eminus.gpu.pass.Pass;
import com.eminus.gpu.pipeline.Binding;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.client.frame.TerrainFilter;
import com.eminus.gpu.texture.Sampler;
import com.eminus.gpu.texture.Texture;

import org.jspecify.annotations.Nullable;

final class FarQuads {
    static final int MAX_SAMPLES = 8;

    private static final int MAX_GROUP_INDICES = MeshBuffer.MAX_QUADS_PER_GROUP * DrawCommands.INDICES_PER_QUAD;
    private static final Location SHADER = new Location(Eminus.MODID, "core/far_quads");
    private static final String QUADS = "EminusQuads";
    private static final String MESH_RECORDS = "EminusMeshRecords";
    private static final String MODEL_RECORDS = "EminusModelRecords";
    private static final String MODEL_VARIANTS = "EminusModelVariants";
    private static final String NEAR_SECTIONS = "EminusNearSections";
    private static final String ATLAS = "EminusAtlas";
    private static final String TINT_MASK = "EminusTintMask";
    private static final String LIGHTMAP = "Lightmap";
    private static final String FRONT_DEPTH = "EminusFrontDepth";
    private static final String OPAQUE_DEPTH = "EminusOpaqueDepth";
    private static final String NEXT_LONG_MODULO = "VARIANT_NEXT_LONG_MODULO";

    static PipelineSpec.Builder pipeline(Location location, float alphaCutout, Capabilities capabilities,
            VariantDraw variantDraw) {
        return pipeline(location, SHADER, alphaCutout, capabilities, variantDraw, true)
                .withBinding(Binding.sampled(LIGHTMAP));
    }

    static PipelineSpec.Builder pipeline(Location location, Location shader, float alphaCutout,
            Capabilities capabilities, VariantDraw variantDraw) {
        return pipeline(location, shader, alphaCutout, capabilities, variantDraw, true);
    }

    static PipelineSpec.Builder solidPipeline(Location location, Capabilities capabilities, VariantDraw variantDraw) {
        return pipeline(location, SHADER, OpaquePass.ALPHA_CUTOUT, capabilities, variantDraw, false)
                .withBinding(Binding.sampled(LIGHTMAP))
                .withDefine("SOLID");
    }

    private static PipelineSpec.Builder pipeline(Location location, Location shader, float alphaCutout,
            Capabilities capabilities, VariantDraw variantDraw, boolean nearSections) {
        PipelineSpec.Builder builder = PipelineSpec.builder(location, shader, shader)
                .withBinding(Binding.uniform(FarFrame.BLOCK.name()))
                .withBinding(Binding.texel(QUADS, GeometryArena.QUAD_FORMAT))
                .withBinding(Binding.texel(MESH_RECORDS, MeshRecords.TEXEL_FORMAT))
                .withBinding(Binding.texel(MODEL_RECORDS, ModelRecords.TEXEL_FORMAT))
                .withBinding(Binding.texel(MODEL_VARIANTS, ModelVariants.TEXEL_FORMAT));
        if (nearSections) {
            builder.withBinding(Binding.texel(NEAR_SECTIONS, NearSectionTable.TEXEL_FORMAT));
        }

        builder.withBinding(Binding.sampled(ATLAS))
                .withBinding(Binding.sampled(TINT_MASK))
                .withDefine("QUADS_PER_SEGMENT", ArenaAllocator.QUADS_PER_SEGMENT)
                .withDefine("VOXELS_PER_SIDE", DetailLevel.VOXELS_PER_SIDE)
                .withDefine("MIN_HORIZONTAL", CellKey.MIN_HORIZONTAL)
                .withDefine("MIN_VERTICAL", CellKey.MIN_VERTICAL)
                .withDefine("MODEL_FACES", BakedModel.FACE_COUNT)
                .withDefine("FIRST_BLADE_FACE", Quad.FIRST_BLADE_FACE)
                .withDefine("FLUID_FLAG", ModelMetadata.FLUID)
                .withDefine("TRANSLUCENT_FLAG", ModelMetadata.TRANSLUCENT)
                .withDefine("ONE_SIDED_FLAG", ModelMetadata.ONE_SIDED)
                .withDefine("PRESENT_SHIFT", ModelMetadata.PRESENT_SHIFT)
                .withDefine("INWARD_FLAG", ModelMetadata.INWARD)
                .withDefine("EMISSION_SHIFT", ModelMetadata.EMISSION_SHIFT)
                .withDefine("EMISSION_BITS", Integer.bitCount(ModelMetadata.MAX_EMISSION))
                .withDefine("MAX_EMISSION", ModelMetadata.MAX_EMISSION)
                .withDefine("CORNER_STEPS", FluidCorners.STEPS)
                .withDefine("MAX_VARIANT_REJECTIONS", BakedModel.MAX_VARIANT_REJECTIONS)
                .withDefine("ALPHA_CUTOUT", alphaCutout)
                .withDefine("SHADE_BITS", BakedModel.SHADE_BITS)
                .withDefine("MAX_SAMPLES", MAX_SAMPLES)
                .withDefine("NEAR_SECTION_BLOCKS", NearSections.SECTION_BLOCKS)
                .withDefine("NEAR_TEXEL_BITS", NearSections.BITS_PER_TEXEL)
                .withDefine("NEAR_TEXEL_SHIFT", NearSections.TEXEL_SHIFT)
                .withDefine("MODEL_TEXELS", ModelRecords.TEXELS)
                .withDefine("PACK_ID_TEXEL", ModelRecords.PACK_ID_TEXEL)
                .withDefine("SHADES_TEXEL", ModelRecords.SHADES_TEXEL);
        if (capabilities.textureQuads()) {
            builder.withDefine("TEXTURE_QUADS")
                    .withDefine("QUAD_TEXTURE_SHIFT", DeviceQueries.QUAD_TEXTURE_SHIFT)
                    .withDefine("QUAD_TEXTURE_MASK", DeviceQueries.QUAD_TEXTURE_WIDTH - 1);
        }

        cellKeyLayout(builder);
        quadLayout(builder);
        placementLayout(builder);
        occlusionLayout(builder);
        if (variantDraw == VariantDraw.NEXT_LONG_MODULO) {
            builder.withDefine(NEXT_LONG_MODULO);
        }

        return capabilities.lightmapHalfTexel() ? builder.withDefine("LIGHTMAP_HALF_TEXEL") : builder;
    }

    private static void cellKeyLayout(PipelineSpec.Builder builder) {
        builder.withDefine("CELL_LEVEL_SHIFT", CellKey.LEVEL_SHIFT)
                .withDefine("CELL_LEVEL_BITS", CellKey.LEVEL_BITS)
                .withDefine("CELL_X_SHIFT", CellKey.X_SHIFT)
                .withDefine("CELL_Y_SHIFT", CellKey.Y_SHIFT)
                .withDefine("CELL_Z_SHIFT", CellKey.Z_SHIFT)
                .withDefine("CELL_HORIZONTAL_BITS", CellKey.HORIZONTAL_BITS)
                .withDefine("CELL_VERTICAL_BITS", CellKey.VERTICAL_BITS);
    }

    private static void quadLayout(PipelineSpec.Builder builder) {
        builder.withDefine("QUAD_FACE_SHIFT", Quad.FACE_SHIFT)
                .withDefine("QUAD_FACE_BITS", Quad.FACE_BITS)
                .withDefine("QUAD_X_SHIFT", Quad.X_SHIFT)
                .withDefine("QUAD_Y_SHIFT", Quad.Y_SHIFT)
                .withDefine("QUAD_Z_SHIFT", Quad.Z_SHIFT)
                .withDefine("QUAD_COORDINATE_BITS", Quad.COORDINATE_BITS)
                .withDefine("QUAD_WIDTH_SHIFT", Quad.WIDTH_SHIFT)
                .withDefine("QUAD_HEIGHT_SHIFT", Quad.HEIGHT_SHIFT)
                .withDefine("QUAD_SIDE_BITS", Quad.SIDE_BITS)
                .withDefine("QUAD_LIGHT_SHIFT", Quad.LIGHT_SHIFT)
                .withDefine("QUAD_LIGHT_BITS", Quad.LIGHT_BITS)
                .withDefine("QUAD_MODEL_SHIFT", Quad.MODEL_SHIFT)
                .withDefine("QUAD_MODEL_BITS", Quad.MODEL_BITS)
                .withDefine("QUAD_COLOR_SHIFT", Quad.COLOR_SHIFT)
                .withDefine("QUAD_COLOR_BITS", Quad.COLOR_BITS);
    }

    private static void placementLayout(PipelineSpec.Builder builder) {
        builder.withDefine("NIBBLE_BITS", VoxelEntry.NIBBLE_BITS)
                .withDefine("BLOCK_LIGHT_SHIFT", VoxelEntry.BLOCK_LIGHT_SHIFT)
                .withDefine("SKY_LIGHT_SHIFT", VoxelEntry.SKY_LIGHT_SHIFT)
                .withDefine("LOW_GAP_SHIFT", VoxelEntry.LOW_GAP_SHIFT)
                .withDefine("HIGH_GAP_SHIFT", VoxelEntry.HIGH_GAP_SHIFT)
                .withDefine("OFFSET_AXIS_BITS", QuadOffset.AXIS_BITS)
                .withDefine("OFFSET_X_SHIFT", QuadOffset.X_SHIFT)
                .withDefine("OFFSET_Y_SHIFT", QuadOffset.Y_SHIFT)
                .withDefine("OFFSET_Z_SHIFT", QuadOffset.Z_SHIFT)
                .withDefine("OFFSET_STEPS_PER_BLOCK", QuadOffset.STEPS_PER_BLOCK)
                .withDefine("TINT_CHANNEL_BITS", MeshBuffer.CHANNEL_BITS)
                .withDefine("TINT_RED_SHIFT", MeshBuffer.RED_SHIFT)
                .withDefine("TINT_GREEN_SHIFT", MeshBuffer.GREEN_SHIFT)
                .withDefine("TINT_BLUE_SHIFT", MeshBuffer.BLUE_SHIFT)
                .withDefine("CORNER_BITS", FluidCorners.BITS)
                .withDefine("CORNER_NORTH_WEST_SHIFT", FluidCorners.NORTH_WEST_SHIFT)
                .withDefine("CORNER_NORTH_EAST_SHIFT", FluidCorners.NORTH_EAST_SHIFT)
                .withDefine("CORNER_SOUTH_WEST_SHIFT", FluidCorners.SOUTH_WEST_SHIFT)
                .withDefine("CORNER_SOUTH_EAST_SHIFT", FluidCorners.SOUTH_EAST_SHIFT);
    }

    private static void occlusionLayout(PipelineSpec.Builder builder) {
        builder.withDefine("MESH_UNOCCLUDED_SHIFT", MeshRecords.UNOCCLUDED_SHIFT)
                .withDefine("MESH_INNER_OCCLUDED_SHIFT", MeshRecords.INNER_OCCLUDED_SHIFT)
                .withDefine("OCCLUSION_BITS", OcclusionWord.BITS)
                .withDefine("OCCLUSION_INNER_SHIFT", OcclusionWord.INNER_SHIFT)
                .withDefine("OCCLUSION_PER_SLOT", OcclusionWord.PER_SLOT)
                .withDefine("OCCLUSION_FRONT", OcclusionWord.FRONT)
                .withDefine("OCCLUSION_LOW_U", OcclusionWord.LOW_U)
                .withDefine("OCCLUSION_HIGH_U", OcclusionWord.HIGH_U)
                .withDefine("OCCLUSION_LOW_V", OcclusionWord.LOW_V)
                .withDefine("OCCLUSION_HIGH_V", OcclusionWord.HIGH_V)
                .withDefine("OCCLUSION_LOW_U_LOW_V", OcclusionWord.LOW_U_LOW_V)
                .withDefine("OCCLUSION_HIGH_U_LOW_V", OcclusionWord.HIGH_U_LOW_V)
                .withDefine("OCCLUSION_LOW_U_HIGH_V", OcclusionWord.LOW_U_HIGH_V)
                .withDefine("OCCLUSION_HIGH_U_HIGH_V", OcclusionWord.HIGH_U_HIGH_V);
    }

    static PipelineSpec.Builder inFrontOfOpaque(PipelineSpec.Builder builder) {
        return builder.withDefine("IN_FRONT_OF_OPAQUE").withBinding(Binding.sampled(OPAQUE_DEPTH));
    }

    static PipelineSpec.Builder behindFront(PipelineSpec.Builder builder) {
        return builder.withDefine("BEHIND_FRONT").withBinding(Binding.sampled(FRONT_DEPTH));
    }

    static void bindOpaqueDepth(Pass pass, Texture opaqueDepth) {
        pass.bind(OPAQUE_DEPTH, opaqueDepth, Sampler.NEAREST);
    }

    static void bindFrontDepth(Pass pass, Texture frontDepth) {
        pass.bind(FRONT_DEPTH, frontDepth, Sampler.NEAREST);
    }

    static Sampler atlasSampler(TerrainFilter filter) {
        return filter == TerrainFilter.NEAREST ? Sampler.NEAREST_MIPPED : Sampler.LINEAR_MIPPED;
    }

    static void bind(Pass pass, GeometryArena arena, ModelPublisher models, @Nullable Texture lightmap, Sampler atlas,
            Buffer frame, @Nullable TexelView nearSections) {
        pass.bind(FarFrame.BLOCK.name(), frame);
        pass.bind(QUADS, arena.quads());
        pass.bind(MESH_RECORDS, arena.records().texels());
        pass.bind(MODEL_RECORDS, models.records().texels());
        pass.bind(MODEL_VARIANTS, models.variants().texels());
        if (nearSections != null) {
            pass.bind(NEAR_SECTIONS, nearSections);
        }
        pass.bind(ATLAS, models.atlas().color(), atlas);
        pass.bind(TINT_MASK, models.atlas().tintMask(), atlas);
        if (lightmap != null) {
            pass.bind(LIGHTMAP, lightmap, Sampler.LINEAR);
        }
        pass.quadIndices(MAX_GROUP_INDICES);
    }

    private FarQuads() {
    }
}
