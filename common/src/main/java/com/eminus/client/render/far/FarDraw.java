package com.eminus.client.render.far;

import java.util.OptionalDouble;

import com.eminus.Eminus;
import com.eminus.client.handoff.NearSectionTable;
import com.eminus.client.model.ModelPublisher;
import com.eminus.client.render.arena.GeometryArena;
import com.eminus.client.render.beam.BeamPass;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Location;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.pass.Pass;
import com.eminus.gpu.pass.PassSpec;
import com.eminus.gpu.pipeline.Blend;
import com.eminus.gpu.pipeline.DepthCompare;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.gpu.texture.Sampler;
import com.eminus.gpu.texture.Texture;
import com.eminus.model.port.VariantDraw;
import com.eminus.render.backend.DepthConvention;

import com.uber.nullaway.annotations.Initializer;

import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.jspecify.annotations.Nullable;

public final class FarDraw {
    private static final Location PACK_SHADER = new Location(Eminus.MODID, "core/far_pack");
    private static final float NO_CUTOUT = 0.0F;
    private static final String FRONT_DEPTH_CLEAR_LABEL = "eminus-pack-translucent-front-depth-clear";

    private final Gpu gpu;
    private final DepthConvention depth;
    private final VariantDraw variantDraw;
    private final GeometryArena arena;
    private final ModelPublisher models;
    private final FarFrame frame;
    private final NearSectionTable nearSections;
    private final FarTarget target;
    private final BeamPass beams;
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f previousProjection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();

    private Buffer commands;
    private int opaqueCount;
    private int translucentCount;
    private Texture lightmap;
    private Sampler atlas;
    private int farBlocks;

    public enum PackVertex {
        OURS,
        HOOK,
        SHADOW_HOOK,
        PROGRAM,
        SHADOW_PROGRAM;

        public boolean hook() {
            return this == HOOK || this == SHADOW_HOOK;
        }
    }

    public enum TranslucentStep {
        FRONT_DEPTH,
        SECOND_DEPTH,
        BEHIND,
        FRONT,
        DEPTH;

        public boolean drawsColor() {
            return this == BEHIND || this == FRONT;
        }
    }

    FarDraw(Gpu gpu, DepthConvention depth, VariantDraw variantDraw, GeometryArena arena, ModelPublisher models,
            FarFrame frame, NearSectionTable nearSections, FarTarget target, BeamPass beams) {
        this.gpu = gpu;
        this.depth = depth;
        this.variantDraw = variantDraw;
        this.arena = arena;
        this.models = models;
        this.frame = frame;
        this.nearSections = nearSections;
        this.target = target;
        this.beams = beams;
    }

    @Initializer
    void frame(Buffer commands, int opaqueCount, int translucentCount, Texture lightmap, Sampler atlas,
            Matrix4fc projection, Matrix4fc view, int farBlocks) {
        previousProjection.set(this.projection);
        depth.forward(projection, this.projection);
        this.view.set(view);
        this.commands = commands;
        this.opaqueCount = opaqueCount;
        this.translucentCount = translucentCount;
        this.lightmap = lightmap;
        this.atlas = atlas;
        this.farBlocks = farBlocks;
    }

    public Gpu gpu() {
        return gpu;
    }

    public FarTarget target() {
        return target;
    }

    public Texture lightmap() {
        return lightmap;
    }

    public Matrix4fc projection() {
        return projection;
    }

    public Matrix4fc previousProjection() {
        return previousProjection;
    }

    public Matrix4fc view() {
        return view;
    }

    public int farBlocks() {
        return farBlocks;
    }

    public PipelineSpec packOpaquePipeline(Location location, PackVertex vertex, boolean animated, boolean separateAo,
            @Nullable AlphaCut cut, boolean packCuts) {
        PipelineSpec.Builder builder = coverage(pack(location, cut, null, true, depth.compare(), true, vertex,
                animated), packCuts);
        return separateAo ? builder.withDefine("SEPARATE_AO").build() : builder.build();
    }

    public PipelineSpec packTranslucentPipeline(Location location, TranslucentStep step, PackVertex vertex,
            boolean animated, boolean separateAo, @Nullable AlphaCut cut) {
        PipelineSpec.Builder builder = switch (step) {
            case FRONT_DEPTH -> FarQuads.inFrontOfOpaque(
                    pack(location, cut, null, false, depth.compare(), true, vertex, animated));
            case SECOND_DEPTH -> FarQuads.behindFront(
                    pack(location, cut, null, false, depth.compare(), true, vertex, animated));
            case BEHIND -> FarQuads.behindFront(
                    pack(location, cut, Blend.TRANSLUCENT, true, depth.compare(), false, vertex, animated));
            case FRONT -> pack(location, cut, Blend.TRANSLUCENT, true, DepthCompare.EQUAL, false, vertex, animated);
            case DEPTH -> pack(location, cut, null, false, depth.compare(), true, vertex, animated);
        };
        builder.withDefine("TRANSLUCENT_PASS");
        if (depth.reversed()) {
            builder.withDefine("EMINUS_DEPTH_REVERSED");
        }
        return separateAo && step.drawsColor() ? builder.withDefine("SEPARATE_AO").build() : builder.build();
    }

    public PipelineSpec packBeamPipeline(Location location, boolean glow, PackVertex vertex, boolean animated) {
        return withVertex(BeamPass.packPipeline(location, glow, depth), vertex, animated).build();
    }

    private PipelineSpec.Builder pack(Location location, @Nullable AlphaCut cut, @Nullable Blend blend,
            boolean writesColor, DepthCompare compare, boolean writesDepth, PackVertex vertex, boolean animated) {
        return withVertex(packShader(location, cut)
                .withColorTarget(FarTarget.COLOR_FORMAT, blend, writesColor)
                .withDepthTest(compare, writesDepth), vertex, animated);
    }

    // Under a pack Iris clears the shadow depth to 1.0 and draws it forward, whatever the game's own direction.
    public PipelineSpec shadowPipeline(Location location, boolean translucent, PackVertex vertex, boolean animated,
            @Nullable AlphaCut cut, boolean packCuts) {
        PipelineSpec.Builder builder = packShader(location, cut)
                .withColorTarget(FarTarget.COLOR_FORMAT, null, true)
                .withDepthTest(DepthConvention.of(depth.zeroToOne(), false).compare(), true)
                .withDefine("SHADOW_PASS");
        builder = withVertex(translucent ? builder.withDefine("TRANSLUCENT_PASS") : coverage(builder, packCuts),
                vertex, animated);
        if (vertex.hook() && depth.zeroToOne()) {
            builder.withDefine("SHADOW_ZERO_TO_ONE");
        }
        return builder.build();
    }

    private PipelineSpec.Builder packShader(Location location, @Nullable AlphaCut cut) {
        PipelineSpec.Builder builder = FarQuads.pipeline(location, PACK_SHADER,
                cut == null ? NO_CUTOUT : cut.reference(), gpu.capabilities(), variantDraw);
        return cut == null ? builder : cut.define(builder);
    }

    private static PipelineSpec.Builder coverage(PipelineSpec.Builder builder, boolean packCuts) {
        return packCuts ? builder : builder.withDefine("FULL_COVERAGE");
    }

    private static PipelineSpec.Builder withVertex(PipelineSpec.Builder builder, PackVertex vertex, boolean animated) {
        return switch (vertex) {
            case OURS -> builder;
            case HOOK -> animated ? builder.withDefine("PACK_VERTEX")
                    : builder.withDefine("PACK_VERTEX").withDefine("STILL");
            case SHADOW_HOOK -> builder.withDefine("PACK_SHADOW_VERTEX");
            case PROGRAM -> program(builder);
            case SHADOW_PROGRAM -> program(builder).withDefine("PACK_SHADOW_PROGRAM");
        };
    }

    private static PipelineSpec.Builder program(PipelineSpec.Builder builder) {
        return builder.withDefine("DH_PROGRAM");
    }

    public void bind(Pass pass) {
        FarQuads.bind(pass, arena, models, null, atlas, frame.buffer(), nearSections.texels());
    }

    public void bindOpaqueDepth(Pass pass) {
        FarQuads.bindOpaqueDepth(pass, target.depth());
    }

    public void bindFrontDepth(Pass pass) {
        FarQuads.bindFrontDepth(pass, target.frontDepth());
    }

    public void bindSecondDepth(Pass pass) {
        FarQuads.bindFrontDepth(pass, target.secondDepth());
    }

    public void clearFrontDepth() {
        gpu.pass(PassSpec.of(FRONT_DEPTH_CLEAR_LABEL, target.color(), null)
                .withDepth(target.frontDepth(), OptionalDouble.of(depth.farthest()))).close();
    }

    public void bindShadow(Pass pass, FarShadow shadow) {
        FarQuads.bind(pass, arena, models, null, atlas, shadow.frameBuffer(), nearSections.texels());
    }

    public void drawOpaque(Pass pass) {
        if (opaqueCount > 0) {
            pass.drawIndexedIndirectUnordered(commands, 0, opaqueCount);
        }
    }

    public void drawTranslucent(Pass pass) {
        if (translucentCount > 0) {
            pass.drawIndexedIndirect(commands, opaqueCount, translucentCount);
        }
    }

    public boolean beamsDrawn() {
        return beams.drawsAny();
    }

    public void drawBeams(Pass pass) {
        beams.drawPack(pass, frame.buffer());
    }
}
