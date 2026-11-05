package com.eminus.render.arena;

import com.eminus.cell.DetailLevel;
import com.eminus.settings.FarDistance;

public final class ArenaDemand {
    public static final int TERRAIN_BAND_BLOCKS = 96;

    private ArenaDemand() {
    }

    public static int cellsPerColumn(int level) {
        return Math.max(1, Math.ceilDiv(TERRAIN_BAND_BLOCKS, DetailLevel.blocksPerCell(level)));
    }

    public static double subdivisionRadius(int level, float focalPixels, int subdivisionPixels) {
        return (double) DetailLevel.blocksPerCell(level) * focalPixels / subdivisionPixels;
    }

    public static int columns(int level, float focalPixels, int subdivisionPixels, int farRenderChunks) {
        double far = (double) farRenderChunks * FarDistance.BLOCKS_PER_CHUNK;
        double reach = level >= DetailLevel.MAX
                ? far
                : Math.min(2.0 * subdivisionRadius(level, focalPixels, subdivisionPixels), far);
        double side = DetailLevel.blocksPerCell(level);

        return (int) Math.round(Math.PI * reach * reach / (side * side));
    }

    public static int totalColumns(int lowestLevel, float focalPixels, int subdivisionPixels, int farRenderChunks) {
        int total = 0;

        for (int level = Math.max(lowestLevel, DetailLevel.MIN); level <= DetailLevel.MAX; level++) {
            total += columns(level, focalPixels, subdivisionPixels, farRenderChunks);
        }

        return total;
    }
}
