package com.eminus.render.arena;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.OptionalLong;

import com.eminus.cell.DetailLevel;
import com.eminus.mesh.MeshOcclusion;
import com.eminus.mesh.OcclusionWord;
import com.eminus.settings.DetailDistance;
import com.eminus.settings.FarDistance;
import com.eminus.settings.Settings;

import org.junit.jupiter.api.Test;

class ArenaSizingTest {
    private static final long ROOMY_DEVICE = 8L * 1024 * 1024 * 1024;
    private static final long CRAMPED_DEVICE = 64L * 1024 * 1024;
    private static final long TEXEL_LIMITED = 1024L * 1024 * 1024;
    private static final float FOCAL_PIXELS = 978.0F;
    private static final int FAR_CELLS = Settings.DEFAULT_FAR_RENDER_CHUNKS;
    private static final int SUBDIVISION = Settings.DEFAULT_DETAIL_DISTANCE.pixels();
    private static final int LOWEST_LEVEL = Settings.defaults().lodQuality().storedLevel();
    private static final float MEASURED_FOCAL_PIXELS = 977.6F;
    private static final int MEASURED_FAR_CHUNKS = 128;
    private static final int MEASURED_FAR_CELLS = FarDistance.chunksToCells(MEASURED_FAR_CHUNKS);
    private static final long WHOLE_PERCENT = 100L;
    private static final long HIGH_WATER_PERCENT = 85L;
    private static final long FILL_PERCENT = 95L;
    private static final long SPARSE_PAGE_BYTES = 64L * 1024;

    @Test
    void aBudgetBelowTheFloorIsLiftedToIt() {
        assertEquals(ArenaSizing.MIN_BYTES, ArenaSizing.wanted(Settings.MIN_FAR_RENDER_CHUNKS,
                DetailDistance.MINIMAL.pixels(), FOCAL_PIXELS, DetailLevel.MAX, MeshOcclusion.EVERY_LEVEL));
    }

    @Test
    void theSmallestRenderDistanceStillPaysForTheFinestShell() {
        long bytes = ArenaSizing.wanted(Settings.MIN_FAR_RENDER_CHUNKS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertTrue(bytes > ArenaSizing.MIN_BYTES,
                "The level-0 shell reaches past the shortest far distance, so the floor does not bind: " + bytes);
    }

    @Test
    void theLargestRenderDistanceIsCappedAtTheCeiling() {
        long wanted = ArenaSizing.wanted(Settings.MAX_FAR_RENDER_CHUNKS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertTrue(wanted > TEXEL_LIMITED, "The largest render distance asks for more than the ceiling: " + wanted);
        assertEquals(TEXEL_LIMITED, ArenaSizing.fitted(wanted, TEXEL_LIMITED));
    }

    @Test
    void theDefaultSettingsLandBetweenTheBounds() {
        long bytes = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertTrue(bytes > ArenaSizing.MIN_BYTES, "The default asks for more than the minimum: " + bytes);
        assertTrue(bytes < ArenaSizing.UNREAD_TEXEL_BYTES, "The default asks for less than the fallback: " + bytes);
    }

    @Test
    void theCeilingIsTheTexelLimitOnAnUnboundedDevice() {
        assertEquals(TEXEL_LIMITED,
                ArenaSizing.ceiling(OptionalLong.of(TEXEL_LIMITED), Long.MAX_VALUE, OptionalLong.empty()));
    }

    @Test
    void anUnreadTexelLimitLeavesTheFallback() {
        assertEquals(ArenaSizing.UNREAD_TEXEL_BYTES,
                ArenaSizing.ceiling(OptionalLong.empty(), Long.MAX_VALUE, OptionalLong.empty()));
    }

    @Test
    void noCeilingPassesTheVertexIndexCap() {
        assertEquals(4L * 1024 * 1024 * 1024, ArenaSizing.VERTEX_INDEX_BYTES);
        assertEquals(ArenaSizing.VERTEX_INDEX_BYTES,
                ArenaSizing.ceiling(OptionalLong.of(Long.MAX_VALUE), Long.MAX_VALUE, OptionalLong.empty()));
    }

    @Test
    void halfTheFreeVideoMemoryBoundsTheCeiling() {
        assertEquals(TEXEL_LIMITED / 4, ArenaSizing.ceiling(OptionalLong.of(TEXEL_LIMITED), Long.MAX_VALUE,
                OptionalLong.of(TEXEL_LIMITED / 2)));
    }

    @Test
    void theDeviceShareBoundsTheCeiling() {
        assertEquals(TEXEL_LIMITED / ArenaSizing.DEVICE_SHARE,
                ArenaSizing.ceiling(OptionalLong.of(TEXEL_LIMITED), TEXEL_LIMITED, OptionalLong.empty()));
    }

    @Test
    void aFloorAboveTheCeilingStartsAtTheCeiling() {
        assertEquals(TEXEL_LIMITED, ArenaSizing.fitted(4 * TEXEL_LIMITED, TEXEL_LIMITED));
    }

    @Test
    void theDemandAtLowIsPinned() {
        long[] quads = {733L * 3 * 7400, 733L * 2 * 10900, 733L * 11400, 201L * 7200, 50L * 5700};

        assertEquals(withHeadroom(quads, MeshOcclusion.EVERY_LEVEL), ArenaSizing.wanted(MEASURED_FAR_CELLS,
                DetailDistance.LOW.pixels(), MEASURED_FOCAL_PIXELS, DetailLevel.MIN, MeshOcclusion.EVERY_LEVEL));
    }

    @Test
    void theDemandAtMediumIsPinned() {
        long[] quads = {2932L * 3 * 7400, 2932L * 2 * 10900, 804L * 11400, 201L * 7200, 50L * 5700};

        assertEquals(withHeadroom(quads, MeshOcclusion.EVERY_LEVEL), ArenaSizing.wanted(MEASURED_FAR_CELLS,
                DetailDistance.MEDIUM.pixels(), MEASURED_FOCAL_PIXELS, DetailLevel.MIN, MeshOcclusion.EVERY_LEVEL));
    }

    @Test
    void aLevelWithoutCornerOcclusionReservesNoStream() {
        long[] quads = {733L * 3 * 7400, 733L * 2 * 10900, 733L * 11400, 201L * 7200, 50L * 5700};

        for (MeshOcclusion occlusion : new MeshOcclusion[] {MeshOcclusion.FINEST_LEVEL, MeshOcclusion.NONE}) {
            assertEquals(withHeadroom(quads, occlusion), ArenaSizing.wanted(MEASURED_FAR_CELLS,
                    DetailDistance.LOW.pixels(), MEASURED_FOCAL_PIXELS, DetailLevel.MIN, occlusion));
        }

        long every = withHeadroom(quads, MeshOcclusion.EVERY_LEVEL);
        long finest = withHeadroom(quads, MeshOcclusion.FINEST_LEVEL);
        assertTrue(every > finest && finest > withHeadroom(quads, MeshOcclusion.NONE));
    }

    @Test
    void shadedWaterReservesASecondStreamOnTheLevelsThatHaveOne() {
        long[] quads = {733L * 3 * 7400, 733L * 2 * 10900, 733L * 11400, 201L * 7200, 50L * 5700};

        for (MeshOcclusion occlusion : new MeshOcclusion[] {MeshOcclusion.EVERY_LEVEL.withWater(true),
                MeshOcclusion.FINEST_LEVEL.withWater(true), MeshOcclusion.NONE.withWater(true)}) {
            assertEquals(withHeadroom(quads, occlusion), ArenaSizing.wanted(MEASURED_FAR_CELLS,
                    DetailDistance.LOW.pixels(), MEASURED_FOCAL_PIXELS, DetailLevel.MIN, occlusion));
        }

        assertTrue(withHeadroom(quads, MeshOcclusion.FINEST_LEVEL.withWater(true))
                > withHeadroom(quads, MeshOcclusion.FINEST_LEVEL));
        assertEquals(withHeadroom(quads, MeshOcclusion.NONE), withHeadroom(quads, MeshOcclusion.NONE.withWater(true)));
    }

    private static long withHeadroom(long[] quadsPerLevel, MeshOcclusion occlusion) {
        long slots = 0L;
        for (int level = DetailLevel.MIN; level < quadsPerLevel.length; level++) {
            long quads = quadsPerLevel[level];
            long words = Math.ceilDiv(quads, OcclusionWord.PER_SLOT);
            slots += quads + (occlusion.at(level) ? words : 0) + (occlusion.water(level) ? words : 0);
        }

        return Math.ceilDiv(slots * Long.BYTES * WHOLE_PERCENT * WHOLE_PERCENT, HIGH_WATER_PERCENT * FILL_PERCENT);
    }

    @Test
    void halvingTheSubdivisionSizeAsksForAboutFourTimesAsMuch() {
        long coarse = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION * 2, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);
        long fine = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertTrue(fine > 3 * coarse && fine < 5 * coarse,
                "A twice finer subdivision asks for " + fine + " against " + coarse);
    }

    @Test
    void aTallerWindowAsksForMore() {
        long shortWindow = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS / 2, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);
        long tallWindow = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertTrue(tallWindow > shortWindow,
                "A twice taller window asks for " + tallWindow + " against " + shortWindow);
    }

    @Test
    void aHigherLowestStoredLevelDropsTheFinestLevelFromTheBudget() {
        long whole = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, DetailLevel.MIN, MeshOcclusion.EVERY_LEVEL);
        long withoutTheFinest = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, DetailLevel.MIN + 1, MeshOcclusion.EVERY_LEVEL);

        assertTrue(whole > withoutTheFinest,
                "Storing level 0 asks for " + whole + " against " + withoutTheFinest + " without it");
    }

    @Test
    void aRoomyDeviceGetsWhatWasWantedDownToTheSegment() {
        long wanted = ArenaSizing.wanted(FAR_CELLS, SUBDIVISION, FOCAL_PIXELS, LOWEST_LEVEL, MeshOcclusion.EVERY_LEVEL);

        assertEquals(wanted - wanted % ArenaSizing.SEGMENT_BYTES, ArenaSizing.fitted(wanted, ROOMY_DEVICE));
    }

    @Test
    void aDeviceShareBelowTheMinimumRefusesTheArena() {
        long ceiling = ArenaSizing.ceiling(OptionalLong.of(TEXEL_LIMITED), CRAMPED_DEVICE, OptionalLong.empty());

        assertEquals(ArenaSizing.REFUSED, ArenaSizing.fitted(TEXEL_LIMITED, ceiling));
    }

    @Test
    void theFittedSizeIsAWholeNumberOfSegments() {
        long unaligned = ArenaSizing.MIN_BYTES + ArenaSizing.SEGMENT_BYTES + 1L;
        long fitted = ArenaSizing.fitted(unaligned, ROOMY_DEVICE);

        assertEquals(0L, fitted % ArenaSizing.SEGMENT_BYTES);
        assertEquals(fitted / ArenaSizing.SEGMENT_BYTES, ArenaSizing.segments(fitted));
    }

    @Test
    void aSparsePageOfWholeSegmentsHoldsThemAndAnyOtherPageIsUnusable() {
        assertEquals(32, ArenaSizing.segmentsPerPage(SPARSE_PAGE_BYTES));
        assertEquals(ArenaSizing.NO_PAGES, ArenaSizing.segmentsPerPage(SPARSE_PAGE_BYTES + 1L));
        assertEquals(ArenaSizing.NO_PAGES, ArenaSizing.segmentsPerPage(0L));
    }

    @Test
    void theSparseReservationIsAWholeNumberOfPages() {
        long ceiling = TEXEL_LIMITED + 3L * ArenaSizing.SEGMENT_BYTES;

        assertEquals(TEXEL_LIMITED, ArenaSizing.reserved(ceiling, SPARSE_PAGE_BYTES));
        assertEquals(ArenaSizing.REFUSED, ArenaSizing.reserved(SPARSE_PAGE_BYTES, SPARSE_PAGE_BYTES));
    }
}
