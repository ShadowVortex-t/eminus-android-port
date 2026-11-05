package com.eminus.client.gpu.opengl;


import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.TexelView;
import com.eminus.gpu.pass.Pass;
import com.eminus.gpu.pass.PassSpec;
import com.eminus.gpu.pipeline.Pipeline;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.gpu.texture.Sampler;
import com.eminus.gpu.texture.Texture;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBDrawIndirect;
import org.lwjgl.opengl.ARBMultiDrawIndirect;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;

final class OpenGlPass implements Pass {
    private static final int COLOR_INDEX = 0;
    private static final int WRITE_ALL = 0b1111;
    private static final int WRITE_NONE = 0;
    private static final int FIRST_VERTEX = 0;
    private static final int TIGHTLY_PACKED = 0;
    private static final int UNBOUND = 0;
    private static final int VECTOR_COMPONENTS = 4;

    private static final int ONE_TARGET = 1;

    private final OpenGlGpu gpu;
    private final int colorTargets;
    private @Nullable OpenGlPipeline pipeline;

    private OpenGlPass(OpenGlGpu gpu, int colorTargets) {
        this.gpu = gpu;
        this.colorTargets = colorTargets;
    }

    static OpenGlPass openForeign(OpenGlGpu gpu, int framebuffer, int width, int height, int colorTargets) {
        GameHandles.bindFramebuffer(framebuffer);
        GameHandles.viewport(0, 0, width, height);
        GameHandles.scissor(0, 0, width, height);
        return new OpenGlPass(gpu, colorTargets);
    }

    static OpenGlPass open(OpenGlGpu gpu, PassSpec spec) {
        OpenGlTexture color = (OpenGlTexture) spec.color();
        OpenGlTexture depth = (OpenGlTexture) spec.depth();
        int framebuffer = gpu.framebuffer(color, depth);
        GameHandles.bindFramebuffer(framebuffer);
        GameHandles.viewport(0, 0, color.width(), color.height());
        GameHandles.scissor(0, 0, color.width(), color.height());
        GL20C.glDrawBuffers(GL30C.GL_COLOR_ATTACHMENT0);
        clear(spec, depth);
        return new OpenGlPass(gpu, ONE_TARGET);
    }

    private static void clear(PassSpec spec, @Nullable OpenGlTexture depth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            if (spec.clearColor() != null) {
                GameHandles.colorMask(COLOR_INDEX, WRITE_ALL);
                GL30C.glClearBufferfv(GL11C.GL_COLOR, COLOR_INDEX,
                        spec.clearColor().get(stack.mallocFloat(VECTOR_COMPONENTS)));
            }

            if (depth != null && spec.clearDepth().isPresent()) {
                GameHandles.depthMask(true);
                GL30C.glClearBufferfv(GL11C.GL_DEPTH, COLOR_INDEX,
                        stack.floats((float) spec.clearDepth().getAsDouble()));
            }
        }
    }

    @Override
    public void pipeline(Pipeline pipeline) {
        OpenGlPipeline own = (OpenGlPipeline) pipeline;
        this.pipeline = own;
        GL20C.glUseProgram(own.program());
        GL30C.glBindVertexArray(gpu.vertexArray());

        PipelineSpec spec = own.spec();
        setDepth(spec.depth());
        setColor(spec.color());
        GameHandles.fillBothFaces();
    }

    private static void setDepth(PipelineSpec.@Nullable DepthTest depth) {
        if (depth == null) {
            GameHandles.noDepthTest();
        } else {
            GameHandles.depthTest(OpenGlTypes.depthFunction(depth.compare()), depth.writes());
        }
    }

    private void setColor(PipelineSpec.ColorTarget color) {
        for (int index = COLOR_INDEX; index < colorTargets; index++) {
            GameHandles.colorMask(index, color.writes() ? WRITE_ALL : WRITE_NONE);
            if (color.blend() == null) {
                GameHandles.noBlend(index);
            } else {
                OpenGlTypes.BlendFactors factors = OpenGlTypes.blend(color.blend());
                GameHandles.blend(index, factors.sourceRgb(), factors.destinationRgb(), factors.sourceAlpha(),
                        factors.destinationAlpha());
            }
        }
    }

    @Override
    public void bind(String name, Buffer uniform) {
        OpenGlPipeline.Slot slot = slot(name);
        if (slot != null) {
            GL30C.glBindBufferBase(GL31C.GL_UNIFORM_BUFFER, slot.index(), ((OpenGlBuffer) uniform).handle());
        }
    }

    @Override
    public void bind(String name, TexelView texels) {
        OpenGlPipeline.Slot slot = slot(name);
        if (slot != null && texels instanceof OpenGlQuadTexture quads) {
            slot.requireFormat(name, texels.format());
            GameHandles.activeTexture(slot.index());
            GameHandles.bindTexture(quads.texture());
            GL33C.glBindSampler(slot.index(), gpu.sampler(Sampler.NEAREST));
        } else if (slot != null) {
            slot.requireFormat(name, texels.format());
            GameHandles.activeTexture(slot.index());
            GL11C.glBindTexture(GL31C.GL_TEXTURE_BUFFER, ((OpenGlTexelView) texels).texture());
        }
    }

    @Override
    public void bind(String name, Texture texture, Sampler sampler) {
        OpenGlPipeline.Slot slot = slot(name);
        if (slot != null) {
            GameHandles.activeTexture(slot.index());
            GameHandles.bindTexture(((OpenGlTexture) texture).id());
            GL33C.glBindSampler(slot.index(), gpu.sampler(sampler));
        }
    }

    private OpenGlPipeline.@Nullable Slot slot(String name) {
        if (pipeline == null) {
            throw new IllegalStateException("Binding " + name + " before a pipeline was set");
        }
        return pipeline.slot(name);
    }

    @Override
    public void quadIndices(int maxIndices) {
        gpu.bindQuadIndices(maxIndices);
    }

    @Override
    public void draw(int vertices) {
        GL11C.glDrawArrays(GL11C.GL_TRIANGLES, FIRST_VERTEX, vertices);
    }

    @Override
    public void drawIndexedIndirect(Buffer commands, int firstCommand, int count) {
        draw((OpenGlBuffer) commands, firstCommand, count, false);
    }

    @Override
    public void drawIndexedIndirectUnordered(Buffer commands, int firstCommand, int count) {
        draw((OpenGlBuffer) commands, firstCommand, count, true);
    }

    private void draw(OpenGlBuffer commands, int firstCommand, int count, boolean unordered) {
        if (gpu.emulatedIndirect()) {
            IndirectReplay.run(commands, firstCommand, count, unordered);
            return;
        }

        GL15C.glBindBuffer(ARBDrawIndirect.GL_DRAW_INDIRECT_BUFFER, commands.handle());
        ARBMultiDrawIndirect.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT,
                (long) firstCommand * INDEXED_INDIRECT_BYTES, count, TIGHTLY_PACKED);
        GL15C.glBindBuffer(ARBDrawIndirect.GL_DRAW_INDIRECT_BUFFER, UNBOUND);
    }

    @Override
    public void close() {
        GL30C.glBindVertexArray(UNBOUND);
        GL20C.glUseProgram(UNBOUND);
        GameHandles.bindFramebuffer(UNBOUND);
    }
}
