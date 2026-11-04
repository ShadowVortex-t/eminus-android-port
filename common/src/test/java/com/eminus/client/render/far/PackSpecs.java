package com.eminus.client.render.far;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

import com.eminus.Eminus;
import com.eminus.gpu.Capabilities;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Location;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.model.port.VariantDraw;
import com.eminus.render.backend.DepthConvention;

public final class PackSpecs {
    private static final String CAPABILITIES = "capabilities";
    private static final Capabilities ANY = new Capabilities("test", false, false, false, true, true, true,
            Long.MAX_VALUE, OptionalLong.empty(), OptionalLong.empty(), OptionalLong.empty());

    public static List<PipelineSpec> all() {
        Gpu gpu = (Gpu) Proxy.newProxyInstance(Gpu.class.getClassLoader(), new Class<?>[] {Gpu.class},
                (proxy, method, args) -> {
                    if (method.getName().equals(CAPABILITIES)) {
                        return ANY;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        FarDraw draw = new FarDraw(gpu, DepthConvention.of(false, false), VariantDraw.NEXT_INT, null, null, null,
                null, null, null);
        List<PipelineSpec> specs = new ArrayList<>();
        for (FarDraw.PackVertex vertex : FarDraw.PackVertex.values()) {
            for (boolean animated : new boolean[] {false, true}) {
                String suffix = "_" + vertex.name().toLowerCase(Locale.ROOT) + (animated ? "_animated" : "_still");
                AlphaCut opaqueCut = AlphaCut.atLeast(OpaquePass.ALPHA_CUTOUT);
                AlphaCut translucentCut = AlphaCut.atLeast(TranslucentPass.ALPHA_CUTOUT);
                for (boolean separateAo : new boolean[] {false, true}) {
                    String ao = separateAo ? "_separate_ao" : "";
                    specs.add(draw.packOpaquePipeline(location("opaque" + ao + suffix), vertex, animated,
                            separateAo, opaqueCut, false));
                    for (FarDraw.TranslucentStep step : FarDraw.TranslucentStep.values()) {
                        specs.add(draw.packTranslucentPipeline(location("translucent_"
                                + step.name().toLowerCase(Locale.ROOT) + ao + suffix), step, vertex, animated,
                                separateAo, translucentCut));
                    }
                }
                for (boolean translucent : new boolean[] {false, true}) {
                    specs.add(draw.shadowPipeline(location("shadow_" + (translucent ? "translucent" : "opaque")
                            + suffix), translucent, vertex, animated, translucent ? translucentCut : opaqueCut,
                            false));
                }
                for (boolean glow : new boolean[] {false, true}) {
                    specs.add(draw.packBeamPipeline(location((glow ? "beam_glow" : "beam") + suffix), glow, vertex,
                            animated));
                }
            }
        }
        return specs;
    }

    private static Location location(String name) {
        return new Location(Eminus.MODID, "pack/" + name);
    }

    private PackSpecs() {
    }
}
