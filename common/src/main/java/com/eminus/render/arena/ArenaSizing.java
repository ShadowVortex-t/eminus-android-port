package com.eminus.render.arena;

import java.util.OptionalLong;

import com.eminus.cell.DetailLevel;
import com.eminus.mesh.MeshOcclusion;
import com.eminus.mesh.OcclusionWord;
import com.eminus.render.far.DrawCommands;

public final class ArenaSizing {
    public static final int QUAD_BYTES = Long.BYTES;
    public static final long SEGMENT_BYTES = (long) ArenaAllocator.QUADS_PER_SEGMENT * QUAD_BYTES;
    public static final long MIN_BYTES = 32L * 1024 * 1024;
    public static final long UNREAD_TEXEL_BYTES = 2048L * 1024 * 1024;
    public static final long VERTEX_INDEX_BYTES = (1L << (Integer.SIZE - 1)) / DrawCommands.VERTICES_PER_QUAD * QUAD_BYTES;
    public static final int SEGMENT_FILL_PERCENT = 95;
    public static final int DEVICE_SHARE = 4;
    public static final int FREE_MEMORY_SHARE = 2;
    public static final long REFUSED = 0L;
    public static final int NO_PAGES = 0;

    private static final int[] BUDGETED_QUADS_PER_CELL = {7400, 10900, 11400, 7200, 5700};
    private static final long WHOLE_PERCENT = 100L;

    private ArenaSizing() {
    }

    public static int budgetedQuadsPerCell(int level) {
        return BUDGETED_QUADS_PER_CELL[level - DetailLevel.MIN];
    }

    public static long wanted(int farRenderChunks, int subdivisionPixels, float focalPixels, int lowestLevel,
            MeshOcclusion occlusion) {
        long slots = 0;

        for (int level = Math.max(lowestLevel, DetailLevel.MIN); level <= DetailLevel.MAX; level++) {
            long quads = (long) ArenaDemand.columns(level, focalPixels, subdivisionPixels, farRenderChunks)
                    * ArenaDemand.cellsPerColumn(level) * budgetedQuadsPerCell(level);
            long words = Math.ceilDiv(quads, OcclusionWord.PER_SLOT);
            slots += quads + (occlusion.at(level) ? words : 0) + (occlusion.water(level) ? words : 0);
        }

        long bytes = Math.ceilDiv(slots * QUAD_BYTES * WHOLE_PERCENT * WHOLE_PERCENT,
                (long) ArenaPressure.HIGH_WATER_PERCENT * SEGMENT_FILL_PERCENT);
        return Math.max(bytes, MIN_BYTES);
    }

    public static long ceiling(OptionalLong texelBytes, long maxMemoryAllocationSize, OptionalLong freeBytes) {
        long ceiling = Math.min(texelBytes.orElse(UNREAD_TEXEL_BYTES), VERTEX_INDEX_BYTES);
        ceiling = Math.min(ceiling, maxMemoryAllocationSize / DEVICE_SHARE);
        return freeBytes.isPresent() ? Math.min(ceiling, freeBytes.getAsLong() / FREE_MEMORY_SHARE) : ceiling;
    }

    public static long fitted(long wanted, long ceiling) {
        long allowed = Math.min(wanted, ceiling);
        long aligned = allowed - Math.floorMod(allowed, SEGMENT_BYTES);
        return aligned < MIN_BYTES ? REFUSED : aligned;
    }

    public static int segments(long bytes) {
        return (int) (bytes / SEGMENT_BYTES);
    }

    public static int segmentsPerPage(long pageBytes) {
        return pageBytes > 0 && Math.floorMod(pageBytes, SEGMENT_BYTES) == 0 ? segments(pageBytes) : NO_PAGES;
    }

    public static long reserved(long fitted, long pageBytes) {
        long aligned = fitted - Math.floorMod(fitted, pageBytes);
        return aligned < MIN_BYTES ? REFUSED : aligned;
    }
}
