package com.eminus.render.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.eminus.cell.DetailLevel;
import com.eminus.settings.Settings;

import org.junit.jupiter.api.Test;

class ArenaDemandTest {
    private static final float FOCAL_PIXELS = 978.0F;
    private static final int FAR_CELLS = Settings.DEFAULT_FAR_RENDER_CHUNKS;
    private static final int SUBDIVISION = Settings.DEFAULT_DETAIL_DISTANCE.pixels();
    private static final int NEAREST_RING = 1;
    private static final int PERCENT = 100;

    @Test
    void everyLevelBelowTheTopReachesAsFarInItsOwnCells() {
        int first = ArenaDemand.columns(DetailLevel.MIN, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS);

        for (int level = DetailLevel.MIN + 1; level < DetailLevel.MAX; level++) {
            assertEquals(first, ArenaDemand.columns(level, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS),
                    "Level " + level + " covers as many of its own columns as level " + DetailLevel.MIN);
        }
    }

    @Test
    void theColumnsAreTheCellsWhoseCenterLiesInsideTheReach() {
        int level = DetailLevel.MIN;
        double reach = 2.0 * ArenaDemand.subdivisionRadius(level, FOCAL_PIXELS, SUBDIVISION);
        int side = DetailLevel.blocksPerCell(level);
        int span = (int) Math.ceil(reach / side);
        int counted = 0;

        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                double centerX = (x + 0.5) * side;
                double centerZ = (z + 0.5) * side;

                if (centerX * centerX + centerZ * centerZ <= reach * reach) {
                    counted++;
                }
            }
        }

        int columns = ArenaDemand.columns(level, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS);
        assertTrue(Math.abs(columns - counted) <= counted / PERCENT,
                "Counted " + counted + " cells inside the reach, the formula answers " + columns);
    }

    @Test
    void theTopLevelCoversTheDiscOfRootsTheRingBuilds() {
        int counted = 0;

        for (int x = -FAR_CELLS; x <= FAR_CELLS; x++) {
            for (int z = -FAR_CELLS; z <= FAR_CELLS; z++) {
                if (x * x + z * z <= FAR_CELLS * FAR_CELLS) {
                    counted++;
                }
            }
        }

        int columns = ArenaDemand.columns(DetailLevel.MAX, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS);
        assertTrue(Math.abs(columns - counted) <= counted / (PERCENT / 5),
                "The disc holds " + counted + " root columns, the formula answers " + columns);
    }

    @Test
    void aFarDistanceShorterThanTheReachClipsTheCoarserLevelsHardest() {
        int fine = ArenaDemand.columns(DetailLevel.MIN, FOCAL_PIXELS, SUBDIVISION, NEAREST_RING);
        int coarse = ArenaDemand.columns(DetailLevel.MAX - 1, FOCAL_PIXELS, SUBDIVISION, NEAREST_RING);

        assertTrue(fine > coarse, "Clipped at the same radius, " + fine
                + " level-0 columns stand against " + coarse + " level-3 ones");
    }

    @Test
    void theTotalCountsEveryLevelFromTheLowestStoredOne() {
        int whole = ArenaDemand.totalColumns(DetailLevel.MIN, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS);
        int withoutTheFinest = ArenaDemand.totalColumns(DetailLevel.MIN + 1, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS);

        assertEquals(ArenaDemand.columns(DetailLevel.MIN, FOCAL_PIXELS, SUBDIVISION, FAR_CELLS),
                whole - withoutTheFinest);
    }
}
