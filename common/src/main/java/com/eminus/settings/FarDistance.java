package com.eminus.settings;

import com.eminus.cell.DetailLevel;

public final class FarDistance {
    public static final int BLOCKS_PER_TOP_LEVEL_CELL = DetailLevel.blocksPerCell(DetailLevel.MAX);
    public static final int BLOCKS_PER_CHUNK = 16;
    public static final int EXTRA_OVER_RENDER_DISTANCE = 2;
    public static final int CHUNKS_PER_TOP_LEVEL_CELL = BLOCKS_PER_TOP_LEVEL_CELL / BLOCKS_PER_CHUNK;

    public static int cellsToChunks(int cells) {
        return cells * CHUNKS_PER_TOP_LEVEL_CELL;
    }

    public static int cellsToBlocks(int cells) {
        return cells * BLOCKS_PER_TOP_LEVEL_CELL;
    }

    public static int chunksToBlocks(int chunks) {
        return chunks * BLOCKS_PER_CHUNK;
    }

    public static int effective(int setting, int renderDistance) {
        return Math.max(setting, minimumChunks(renderDistance));
    }

    public static int minimumChunks(int renderDistance) {
        return Math.max(Settings.MIN_FAR_RENDER_CHUNKS, renderDistance + EXTRA_OVER_RENDER_DISTANCE);
    }

    public static int chunksToCells(int chunks) {
        return Math.ceilDiv(chunks, CHUNKS_PER_TOP_LEVEL_CELL);
    }

    private FarDistance() {
    }
}
