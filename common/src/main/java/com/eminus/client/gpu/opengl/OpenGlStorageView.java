package com.eminus.client.gpu.opengl;

import com.eminus.gpu.Format;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.TexelView;

/** A buffer bound as a shader storage buffer; there is no texture to create or delete. */
final class OpenGlStorageView implements TexelView {
    private final OpenGlBuffer buffer;
    private final Format format;

    OpenGlStorageView(OpenGlBuffer buffer, Format format) {
        this.buffer = buffer;
        this.format = format;
    }

    int handle() {
        return buffer.handle();
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
    }
}
