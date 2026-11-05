package com.eminus.render.tree;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.eminus.cell.CellFrame;
import com.eminus.cell.CellKey;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.OccupancyMask;

import org.junit.jupiter.api.Test;

class TreeCleanerTest {
    private static final int CELL = DetailLevel.blocksPerCell(DetailLevel.MAX);
    private static final double INSIDE = CELL / 2.0;
    private static final long OLD = 1L;
    private static final long NEWER = 3L;
    private static final long WALK = 5L;
    private static final int WHOLE_SET = OccupancyMask.OCTANTS;
    private static final int HALF_SET = 4;
    private static final int FIVE_CHILDREN = 5;
    private static final int SETS_OF_FIVE = 13;
    private static final int FIRST_FAR_CELL = 10;
    private static final int FARTHER_CELL = 20;
    private static final int NEAR_CELL = 3;
    private static final int WIDE_FAR_CELLS = 8;
    private static final int ONE_FAR_CELL = 1;
    private static final int MANY_FAR_CELLS = 64;
    private static final float ONE_PIXEL_PER_BLOCK = 1.0F;
    private static final double NEAR_ROOT_DISTANCE = NEAR_CELL * CELL - INSIDE;
    private static final double NEAR_ROOT_REACH = NEAR_ROOT_DISTANCE / (1.5 * 1.5 * 1.5);
    private static final double REACH_TOLERANCE = 0.001;
    private static final double LOW_HORIZON = 100.0;
    private static final double BUILT_AHEAD_DISTANCE = FIRST_FAR_CELL * CELL - INSIDE;
    private static final double BAND_MIDDLE = (1.0 + ChildDemand.PREFETCH_SHARE) / 2.0;
    private static final float BUILT_AHEAD_PIXELS_PER_BLOCK =
            (float) (BAND_MIDDLE * FakeCameras.THRESHOLD_PIXELS * BUILT_AHEAD_DISTANCE / CELL);

    private final NodeTable nodes = new NodeTable(NodeTable.CAPACITY);
    private final TreeCleaner cleaner = new TreeCleaner();
    private final TreeHorizon horizon = new TreeHorizon();
    private final CellFrame frame = new CellFrame(0);

    @Test
    void nothingIsEvictedWithoutPressure() {
        children(root(0), WHOLE_SET, OLD);

        assertTrue(cleaner.pick(nodes.all(), FakeCameras.everything(INSIDE, INSIDE, INSIDE, MANY_FAR_CELLS,
                ONE_PIXEL_PER_BLOCK), frame, horizon).isEmpty());
    }

    @Test
    void aSetTheWalkUsedIsTakenLikeAnyOther() {
        List<TreeNode> set = children(root(0), HALF_SET, OLD);
        set.get(1).seen(WALK);

        assertEquals(set, pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS));
    }

    @Test
    void eachFarPickLowersTheHorizonToItsReach() {
        children(root(NEAR_CELL), WHOLE_SET, WALK);

        pick(FakeCameras.CLOSE_PIXELS_PER_BLOCK, WIDE_FAR_CELLS);

        assertEquals(NEAR_ROOT_REACH, horizon.horizon(), REACH_TOLERANCE);
    }

    @Test
    void aSetPastTheHorizonIsUnwantedAndLowersNothing() {
        horizon.lower(LOW_HORIZON);
        children(root(NEAR_CELL), WHOLE_SET, WALK);

        pick(FakeCameras.CLOSE_PIXELS_PER_BLOCK, WIDE_FAR_CELLS);

        assertEquals(WHOLE_SET, cleaner.unwantedPicked());
        assertEquals(LOW_HORIZON, horizon.horizon());
    }

    @Test
    void aSetTheWalkBuildsAheadGoesWithTheFarSetsAndLowersTheHorizon() {
        List<TreeNode> unwanted = children(root(FARTHER_CELL), WHOLE_SET, OLD);
        List<TreeNode> builtAhead = children(root(FIRST_FAR_CELL), WHOLE_SET, WALK);

        List<TreeNode> picked = pick(BUILT_AHEAD_PIXELS_PER_BLOCK, MANY_FAR_CELLS);

        assertEquals(concat(unwanted, builtAhead), picked);
        assertEquals(WHOLE_SET, cleaner.unwantedPicked());
        assertEquals(TreeHorizon.key(BUILT_AHEAD_DISTANCE, DetailLevel.MAX), horizon.horizon(), REACH_TOLERANCE);
    }

    @Test
    void aSetWithoutAMeshIsNoCandidate() {
        TreeNode root = root(0);
        for (int octant = 0; octant < HALF_SET; octant++) {
            nodes.child(root, octant).seen(OLD);
        }

        assertTrue(pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS).isEmpty());
    }

    @Test
    void anUnusedSetIsTakenWhole() {
        List<TreeNode> set = children(root(0), HALF_SET, OLD);

        assertEquals(set, pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS));
    }

    @Test
    void theQuotaNeverSplitsASet() {
        List<TreeNode> expected = new ArrayList<>();
        for (int x = 0; x < SETS_OF_FIVE; x++) {
            expected.addAll(children(root(FIRST_FAR_CELL + x), FIVE_CHILDREN, OLD));
        }

        List<TreeNode> picked = pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS);

        assertEquals(SETS_OF_FIVE * FIVE_CHILDREN, picked.size());
        assertTrue(picked.containsAll(expected));
    }

    @Test
    void theRefinableSetsWaitWhileAnUnwantedOneIsLeft() {
        List<TreeNode> unwanted = new ArrayList<>();
        for (int x = 0; x < TreeCleaner.PRESSURE_EVICTIONS / WHOLE_SET; x++) {
            unwanted.addAll(children(root(FIRST_FAR_CELL + x), WHOLE_SET, OLD));
        }
        children(root(0), WHOLE_SET, OLD);

        List<TreeNode> picked = pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS);

        assertEquals(unwanted.size(), picked.size());
        assertTrue(picked.containsAll(unwanted));
        assertEquals(picked.size(), cleaner.unwantedPicked());
    }

    @Test
    void theRefinableSetsFollowOnceTheUnwantedRunOut() {
        List<TreeNode> unwanted = children(root(FIRST_FAR_CELL), WHOLE_SET, OLD);
        List<TreeNode> outOfView = children(root(0), WHOLE_SET, OLD);

        List<TreeNode> picked = pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS);

        assertEquals(concat(unwanted, outOfView), picked);
        assertEquals(WHOLE_SET, cleaner.unwantedPicked());
    }

    @Test
    void aFartherParentGoesBeforeANearerOne() {
        List<TreeNode> nearer = children(root(0), WHOLE_SET, OLD);
        List<TreeNode> farther = children(root(NEAR_CELL), WHOLE_SET, OLD);

        List<TreeNode> picked = pick(FakeCameras.CLOSE_PIXELS_PER_BLOCK, WIDE_FAR_CELLS);

        assertEquals(concat(farther, nearer), picked);
        assertEquals(0, cleaner.unwantedPicked());
    }

    @Test
    void aSetBeyondTheFarRenderDistanceIsUnwantedHoweverLargeOnScreen() {
        List<TreeNode> beyond = children(root(NEAR_CELL), WHOLE_SET, OLD);
        List<TreeNode> inside = children(root(0), WHOLE_SET, OLD);

        List<TreeNode> picked = pick(FakeCameras.CLOSE_PIXELS_PER_BLOCK, ONE_FAR_CELL);

        assertEquals(concat(beyond, inside), picked);
        assertEquals(WHOLE_SET, cleaner.unwantedPicked());
    }

    @Test
    void theOldestUnwantedSetGoesFirstEvenWhenAnotherIsSmallerOnScreen() {
        List<TreeNode> newer = children(root(FARTHER_CELL), WHOLE_SET, NEWER);
        List<TreeNode> older = children(root(FIRST_FAR_CELL), WHOLE_SET, OLD);

        assertEquals(concat(older, newer), pick(ONE_PIXEL_PER_BLOCK, MANY_FAR_CELLS));
    }

    @Test
    void aSetOnlyAZoomRefinesIsUnwantedAfterTheZoom() {
        List<TreeNode> zoomOnly = children(root(FIRST_FAR_CELL), WHOLE_SET, OLD);
        List<TreeNode> outOfView = children(root(0), WHOLE_SET, OLD);
        CameraFrame zoomed = FakeCameras.zoomed(FakeCameras.underPressure(FakeCameras.everything(INSIDE, INSIDE,
                INSIDE, MANY_FAR_CELLS, ONE_PIXEL_PER_BLOCK)), FakeCameras.CLOSE_PIXELS_PER_BLOCK);

        assertEquals(concat(zoomOnly, outOfView), cleaner.pick(nodes.all(), zoomed, frame, horizon));
        assertEquals(WHOLE_SET, cleaner.unwantedPicked());
    }

    @Test
    void aFullTableWithoutPressureEvictsNothing() {
        NodeTable full = new NodeTable(NodeTable.CAPACITY);
        for (int x = 0; full.free() > 0; x++) {
            subdivide(full, full.root(CellKey.pack(DetailLevel.MAX, x, 0, 0)));
        }

        assertTrue(cleaner.pick(full.all(), FakeCameras.everything(INSIDE, INSIDE, INSIDE, MANY_FAR_CELLS,
                ONE_PIXEL_PER_BLOCK), frame, horizon).isEmpty());
    }

    private List<TreeNode> pick(float pixelsPerBlock, int farChunks) {
        return cleaner.pick(nodes.all(), FakeCameras.underPressure(
                FakeCameras.everything(INSIDE, INSIDE, INSIDE, farChunks, pixelsPerBlock)), frame, horizon);
    }

    private TreeNode root(int cellX) {
        TreeNode root = nodes.root(CellKey.pack(DetailLevel.MAX, cellX, 0, 0));
        root.meshed(TestMeshes.summary(root.key(), OccupancyMask.EMPTY));
        return root;
    }

    private List<TreeNode> children(TreeNode parent, int count, long seen) {
        List<TreeNode> children = new ArrayList<>();
        for (int octant = 0; octant < count; octant++) {
            TreeNode child = nodes.child(parent, octant);
            child.meshed(TestMeshes.summary(child.key(), OccupancyMask.EMPTY));
            child.seen(seen);
            children.add(child);
        }

        return children;
    }

    private static List<TreeNode> concat(List<TreeNode> first, List<TreeNode> second) {
        List<TreeNode> both = new ArrayList<>(first);
        both.addAll(second);
        return both;
    }

    private static void subdivide(NodeTable table, TreeNode node) {
        node.meshed(TestMeshes.summary(node.key(), OccupancyMask.EMPTY));
        node.seen(OLD);
        if (CellKey.level(node.key()) == DetailLevel.MIN) {
            return;
        }

        for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
            TreeNode child = table.child(node, octant);
            if (child == null) {
                return;
            }
            subdivide(table, child);
        }
    }
}
