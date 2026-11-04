package com.eminus.client.gpu.opengl;

import java.nio.ByteBuffer;

import com.eminus.client.gpu.DeviceQueries;
import com.eminus.gpu.Format;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.TexelView;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL21C;

/**
 * A 2D texture that mirrors a buffer of 8-byte quads, for devices whose texel buffers are too small to hold the
 * geometry arena. The texture is {@link DeviceQueries#QUAD_TEXTURE_WIDTH} texels wide and quad {@code n} sits at texel
 * ({@code n} mod width, {@code n} div width). The buffer is still the copy that is written; every write to it
 * refreshes the texture from the buffer, which is bound as the pixel unpack source.
 */
final class OpenGlQuadTexture implements TexelView {
    private static final String LABEL_SUFFIX = "-quad-texture";
    private static final int BASE_MIP = 0;
    private static final int NO_BORDER = 0;
    private static final int PACKED_ROWS = 0;
    private static final int UNPACK_ALIGNMENT = 4;
    private static final int UNBOUND = 0;
    private static final int TEXEL_BYTES = 2 * Integer.BYTES;
    private static final int WIDTH = DeviceQueries.QUAD_TEXTURE_WIDTH;
    private static final int SHIFT = DeviceQueries.QUAD_TEXTURE_SHIFT;
    private static final long COLUMN_MASK = WIDTH - 1L;

    private final OpenGlBuffer buffer;
    private final Format format;
    private final OpenGlName texture;

    private OpenGlQuadTexture(OpenGlBuffer buffer, Format format, OpenGlName texture) {
        this.buffer = buffer;
        this.format = format;
        this.texture = texture;
    }

    static OpenGlQuadTexture create(OpenGlObjects objects, String label, OpenGlBuffer buffer, Format format) {
        long texels = buffer.size() / TEXEL_BYTES;
        int rows = (int) Math.max(1L, (texels + COLUMN_MASK) >> SHIFT);

        OpenGlErrors.clear();
        int id = GameHandles.genTexture();
        GameHandles.bindTexture(id);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_BASE_LEVEL, BASE_MIP);
        GL11C.glTexParameteri(GL11C.GL_TEXTURE_2D, GL12C.GL_TEXTURE_MAX_LEVEL, BASE_MIP);
        GL11C.glTexImage2D(GL11C.GL_TEXTURE_2D, BASE_MIP, OpenGlTypes.internalFormat(format), WIDTH, rows, NO_BORDER,
                OpenGlTypes.externalFormat(format), OpenGlTypes.componentType(format), (ByteBuffer) null);
        OpenGlErrors.check("quad texture " + label + " " + WIDTH + "x" + rows);
        objects.created(OpenGlObjects.Kind.TEXEL_VIEW, id, label + LABEL_SUFFIX);

        OpenGlQuadTexture view = new OpenGlQuadTexture(buffer, format, new OpenGlName(id, owned -> {
            GameHandles.deleteTexture(owned);
            objects.deleted(OpenGlObjects.Kind.TEXEL_VIEW);
        }));
        buffer.mirror(view);
        view.refresh(0L, buffer.size());
        return view;
    }

    int texture() {
        return texture.id();
    }

    /** Copies the bytes of the buffer in the range into the texture. */
    void refresh(long byteOffset, long byteLength) {
        long first = byteOffset / TEXEL_BYTES;
        long last = (byteOffset + byteLength + TEXEL_BYTES - 1) / TEXEL_BYTES;
        GameHandles.bindTexture(texture.id());
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ROW_LENGTH, PACKED_ROWS);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_PIXELS, PACKED_ROWS);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_SKIP_ROWS, PACKED_ROWS);
        GL11C.glPixelStorei(GL11C.GL_UNPACK_ALIGNMENT, UNPACK_ALIGNMENT);
        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, buffer.handle());

        long texel = first;
        while (texel < last) {
            int row = (int) (texel >> SHIFT);
            int column = (int) (texel & COLUMN_MASK);
            long remaining = last - texel;
            if (column == 0 && remaining >= WIDTH) {
                int rows = (int) (remaining >> SHIFT);
                upload(0, row, WIDTH, rows, texel);
                texel += (long) rows << SHIFT;
            } else {
                int width = (int) Math.min(WIDTH - column, remaining);
                upload(column, row, width, 1, texel);
                texel += width;
            }
        }

        GL15C.glBindBuffer(GL21C.GL_PIXEL_UNPACK_BUFFER, UNBOUND);
    }

    private void upload(int x, int y, int width, int height, long firstTexel) {
        GL11C.glTexSubImage2D(GL11C.GL_TEXTURE_2D, BASE_MIP, x, y, width, height, OpenGlTypes.externalFormat(format),
                OpenGlTypes.componentType(format), firstTexel * TEXEL_BYTES);
    }

    @Override
    public Buffer buffer() {
        return buffer;
    }

    @Override
    public Format format() {
        return format;
    }

    @Override
    public void close() {
        buffer.mirror(null, this);
        texture.delete();
    }
}
