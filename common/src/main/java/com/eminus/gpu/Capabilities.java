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
        OptionalLong storageBytes) {
    /** Whether the geometry arena is read as a shader storage buffer because a texel buffer is too small for it. */
    public boolean storageQuads() {
        return storageBytes.isPresent();
    }

}
