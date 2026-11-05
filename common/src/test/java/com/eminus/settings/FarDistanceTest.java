package com.eminus.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FarDistanceTest {
    @Test
    void oneTopLevelCellIsThirtyTwoChunks() {
        assertEquals(32, FarDistance.cellsToChunks(1));
        assertEquals(512, Settings.DEFAULT_FAR_RENDER_CHUNKS);
    }

    @Test
    void chunksRoundUpToWholeCells() {
        assertEquals(1, FarDistance.chunksToCells(1));
        assertEquals(1, FarDistance.chunksToCells(32));
        assertEquals(2, FarDistance.chunksToCells(33));
        assertEquals(16, FarDistance.chunksToCells(Settings.DEFAULT_FAR_RENDER_CHUNKS));
    }

    @Test
    void chunksCarryEveryCellCountBackUnchanged() {
        for (int cells = 1; cells <= 64; cells++) {
            assertEquals(cells, FarDistance.chunksToCells(FarDistance.cellsToChunks(cells)));
        }
    }

    @Test
    void theMinimumIsTwoChunksOverTheRenderDistance() {
        assertEquals(6, FarDistance.minimumChunks(4));
        assertEquals(10, FarDistance.effective(3, 8));
        assertEquals(40, FarDistance.effective(40, 8));
    }
}
