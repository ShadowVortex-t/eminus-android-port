package com.eminus.gpu;

import java.nio.ByteBuffer;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.BufferUsage;
import com.eminus.gpu.buffer.Staging;
import com.eminus.gpu.buffer.TexelView;
import com.eminus.gpu.compute.Compute;
import com.eminus.gpu.pass.Pass;
import com.eminus.gpu.pass.PassSpec;
import com.eminus.gpu.pipeline.Pipeline;
import com.eminus.gpu.pipeline.PipelineSpec;
import com.eminus.gpu.texture.Texture;
import com.eminus.gpu.texture.TextureUsage;

public interface Gpu extends AutoCloseable {
    Capabilities capabilities();

    int maxTextureSide(Format format);

    void assertRenderThread();

    Buffer buffer(String label, Set<BufferUsage> usage, long bytes);

    Buffer buffer(String label, Set<BufferUsage> usage, ByteBuffer data);

    void write(Buffer target, long offset, ByteBuffer data);

    void copy(Buffer source, long sourceOffset, Buffer target, long targetOffset, long bytes);

    OptionalLong freeBytes();

    TexelView texelView(Buffer buffer, Format format);

    /**
     * A view of the buffer as a 2D texture kept in step with it, for a buffer too big for a texel buffer. The buffer
     * stays the copy that is written; the texture is what shaders read.
     */
    default TexelView textureView(Buffer buffer, Format format) {
        throw new UnsupportedOperationException("This device has no texture view of a buffer");
    }

    Texture texture(String label, Set<TextureUsage> usage, Format format, int width, int height, int mips);

    void write(Texture target, int mip, int x, int y, int width, int height, ByteBuffer data);

    Texture mainColor();

    Texture mainDepth();

    Texture lightmap();

    Pipeline pipeline(PipelineSpec spec);

    Pass pass(PassSpec spec);

    Staging staging(String label, int bytes, boolean persistentlyMapped);

    Optional<Compute> compute();

    Optional<Foreign> foreign();

    Optional<Sparse> sparse();

    @Override
    void close();
}
