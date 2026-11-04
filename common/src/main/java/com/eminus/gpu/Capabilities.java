package com.eminus.gpu;

import java.util.OptionalLong;

public record Capabilities(
        String backend,
        boolean depthZeroToOne,
        boolean depthReversed,
        boolean lightmapHalfTexel,
        boolean drawIndirect,
        boolean multiDrawIndirect,
        boolean persistentMapping,
        long maxAllocationBytes,
        OptionalLong texelElements,
        OptionalLong freeBytes,
        OptionalLong textureBytes) {
    /** Whether the geometry arena is read from a 2D texture because a texel buffer is too small for it. */
    public boolean textureQuads() {
        return textureBytes.isPresent();
    }

}
