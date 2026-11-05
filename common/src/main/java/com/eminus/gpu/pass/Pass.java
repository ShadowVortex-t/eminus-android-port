package com.eminus.gpu.pass;

import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.pipeline.Pipeline;

public interface Pass extends Bindings, AutoCloseable {
    int INDEXED_INDIRECT_INTS = 5;
    int INDEXED_INDIRECT_BYTES = INDEXED_INDIRECT_INTS * Integer.BYTES;

    void pipeline(Pipeline pipeline);

    void quadIndices(int maxIndices);

    void draw(int vertices);

    void drawIndexedIndirect(Buffer commands, int firstCommand, int count);

    /**
     * The same draws for commands whose order does not matter, as opaque geometry under a depth test: a pass that
     * runs each command as a call of its own may sort and merge them into fewer.
     */
    default void drawIndexedIndirectUnordered(Buffer commands, int firstCommand, int count) {
        drawIndexedIndirect(commands, firstCommand, count);
    }

    @Override
    void close();
}
