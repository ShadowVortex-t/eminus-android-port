package com.eminus.client.render.far;

import java.util.List;
import java.util.OptionalDouble;

import com.eminus.Eminus;
import com.eminus.client.model.ModelPublisher;
import com.eminus.client.render.arena.GeometryArena;
import com.eminus.gpu.Capabilities;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Location;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.TexelView;
import com.eminus.gpu.pass.Pass;
import com.eminus.gpu.pass.PassSpec;
import com.eminus.gpu.pipeline.Pipeline;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.gpu.texture.Sampler;
import com.eminus.gpu.texture.Texture;
import com.eminus.model.port.VariantDraw;
import com.eminus.render.backend.DepthConvention;

import org.joml.Vector4f;
import org.joml.Vector4fc;

public final class OpaquePass {
    public static final float ALPHA_CUTOUT = 0.5F;

    private static final Location PIPELINE = new Location(Eminus.MODID, "far_opaque");
    private static final Location SOLID_PIPELINE = new Location(Eminus.MODID, "far_opaque_solid");
    private static final String PASS_LABEL = "eminus-far-opaque";
    private static final Vector4fc CLEAR_COLOR = new Vector4f(0.0F, 0.0F, 0.0F, 0.0F);
    private static final int FIRST_COMMAND = 0;

    private final Gpu gpu;
    private final Pipeline solid;
    private final Pipeline pipeline;

    private OpaquePass(Gpu gpu, Pipeline solid, Pipeline pipeline) {
        this.gpu = gpu;
        this.solid = solid;
        this.pipeline = pipeline;
    }

    public static OpaquePass create(Gpu gpu, DepthConvention depth, VariantDraw variantDraw) {
        gpu.assertRenderThread();
        Capabilities capabilities = gpu.capabilities();
        return new OpaquePass(gpu,
                gpu.pipeline(target(FarQuads.solidPipeline(SOLID_PIPELINE, capabilities, variantDraw), depth)),
                gpu.pipeline(target(FarQuads.pipeline(PIPELINE, ALPHA_CUTOUT, capabilities, variantDraw)
                        .withDefine("NEAR_SECTIONS"), depth)));
    }

    public List<Pipeline> pipelines() {
        return List.of(solid, pipeline);
    }

    public void draw(FarTarget target, GeometryArena arena, ModelPublisher models, Texture lightmap, Sampler atlas,
            Buffer commands, int solidCount, int drawCount, Buffer frame, TexelView nearSections) {
        gpu.assertRenderThread();

        try (Pass pass = gpu.pass(PassSpec.of(PASS_LABEL, target.color(), CLEAR_COLOR)
                .withDepth(target.depth(), OptionalDouble.empty()))) {
            if (solidCount > 0) {
                pass.pipeline(solid);
                FarQuads.bind(pass, arena, models, lightmap, atlas, frame, null);
                pass.drawIndexedIndirectUnordered(commands, FIRST_COMMAND, solidCount);
            }

            pass.pipeline(pipeline);
            FarQuads.bind(pass, arena, models, lightmap, atlas, frame, nearSections);
            if (drawCount > solidCount) {
                pass.drawIndexedIndirectUnordered(commands, solidCount, drawCount - solidCount);
            }
        }
    }

    private static PipelineSpec target(PipelineSpec.Builder builder, DepthConvention depth) {
        return builder.withDefine("FULL_COVERAGE")
                .withColorTarget(FarTarget.COLOR_FORMAT, null, true)
                .withDepthTest(depth.compare(), true)
                .build();
    }
}
