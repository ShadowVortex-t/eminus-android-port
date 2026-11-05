package com.eminus.render.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.eminus.cell.CellKey;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.OccupancyMask;
import com.eminus.mesh.MeshSummary;
import com.eminus.settings.FarDistance;

import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import org.joml.FrustumIntersection;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

final class TreeTraversal {
    private static final Comparator<Candidate> LARGEST_FIRST =
            (first, second) -> Float.compare(second.size(), first.size());
    private static final Comparator<Candidate> CENTER_FIRST =
            Comparator.comparingDouble(Candidate::angle).thenComparing(LARGEST_FIRST);
    private static final float SCREEN_CENTER = 0.5F;
    private static final int UNSERVED = -1;

    private final NodeTable nodes;
    private final TreeExtent extent;
    private final TreeHorizon horizon;
    private final FrustumIntersection frustum = new FrustumIntersection();
    private final CellBox box = new CellBox();
    private final Vector3f axis = new Vector3f();
    private final List<TreeNode> current = new ArrayList<>();
    private final List<TreeNode> next = new ArrayList<>();
    private final List<MeshSummary> drawn = new ArrayList<>();
    private final LongOpenHashSet drawnKeys = new LongOpenHashSet();
    private final LongOpenHashSet descendedKeys = new LongOpenHashSet();
    private final LongOpenHashSet waitingKeys = new LongOpenHashSet();
    private final List<MeshSummary> outOfViewDrawn = new ArrayList<>();
    private final LongOpenHashSet outOfViewDrawnKeys = new LongOpenHashSet();
    private final LongOpenHashSet outOfViewDescendedKeys = new LongOpenHashSet();
    private final LongOpenHashSet outOfViewWaitingKeys = new LongOpenHashSet();
    private final List<TreeNode> outside = new ArrayList<>();
    private final List<Candidate> candidates = new ArrayList<>();
    private final List<TreeNode> requested = new ArrayList<>();
    private final FloatArrayList requestedPriorities = new FloatArrayList();
    private final List<TreeNode> outOfViewRequested = new ArrayList<>();
    private final FloatArrayList outOfViewSizes = new FloatArrayList();
    private final List<TreeNode> rebuilds = new ArrayList<>();
    private final PendingColumns pending = new PendingColumns();
    private final PendingColumns pendingOutOfView = new PendingColumns();
    private final ArrivalFront front = new ArrivalFront();
    private final TreeBorders borders = new TreeBorders(drawnKeys, outOfViewDrawnKeys, descendedKeys,
            outOfViewDescendedKeys, waitingKeys, outOfViewWaitingKeys);

    private boolean starved;
    private boolean listing;
    private int hiddenBehind;
    private int hiddenUnder;
    private int hiddenUnderOutOfView;

    private record Candidate(TreeNode node, float size, float angle) {
    }

    TreeTraversal(NodeTable nodes, TreeExtent extent, TreeHorizon horizon) {
        this.nodes = nodes;
        this.extent = extent;
        this.horizon = horizon;
    }

    void walk(Collection<TreeNode> roots, CameraFrame camera, int budget, int outOfViewBudget, long walk) {
        begin(roots, camera);
        double farBlocks = (double) camera.farChunks() * FarDistance.BLOCKS_PER_CHUNK;
        visitAll(camera, farBlocks, walk);
        if (!camera.pressure()) {
            request(camera, farBlocks, budget, outOfViewBudget);
        }
    }

    private void begin(Collection<TreeNode> roots, CameraFrame camera) {
        frustum.set(camera.viewProjection());
        camera.viewProjection().frustumRayDir(SCREEN_CENTER, SCREEN_CENTER, axis).normalize();
        current.clear();
        current.addAll(roots);
        drawn.clear();
        drawnKeys.clear();
        descendedKeys.clear();
        waitingKeys.clear();
        outside.clear();
        candidates.clear();
        requested.clear();
        requestedPriorities.clear();
        outOfViewRequested.clear();
        outOfViewSizes.clear();
        rebuilds.clear();
        pending.clear();
        pendingOutOfView.clear();
        front.begin(camera.eyeX(), camera.eyeZ(), (double) camera.farChunks() * FarDistance.BLOCKS_PER_CHUNK
                - DetailLevel.blocksPerCell(DetailLevel.MAX));
        starved = false;
    }

    private void visitAll(CameraFrame camera, double farBlocks, long walk) {
        while (!current.isEmpty()) {
            next.clear();

            for (TreeNode node : current) {
                visit(node, camera, farBlocks, walk);
            }

            current.clear();
            current.addAll(next);
        }
    }

    private void request(CameraFrame camera, double farBlocks, int budget, int outOfViewBudget) {
        starved = hand(candidates, budget, requested, requestedPriorities, camera, camera.inViewPixelsPerBlock(),
                camera.zoomed()) == UNSERVED;
        int outOfViewLeft = outOfViewBudget - requested.size();
        if (!starved && outOfViewLeft > 0) {
            requestOutOfView(camera, farBlocks, outOfViewLeft);
        }
    }

    RenderList list(CameraFrame camera) {
        listOutside(camera);
        List<MeshSummary> meshes = uncovered();
        for (MeshSummary mesh : meshes) {
            front.shown(mesh.key());
        }

        front.finish();
        return new RenderList(List.copyOf(meshes), borders.of(meshes), camera);
    }

    private void listOutside(CameraFrame camera) {
        outOfViewDrawn.clear();
        outOfViewDrawnKeys.clear();
        outOfViewDescendedKeys.clear();
        outOfViewWaitingKeys.clear();
        double farBlocks = (double) camera.farChunks() * FarDistance.BLOCKS_PER_CHUNK;
        current.clear();
        current.addAll(outside);
        listing = true;

        while (!current.isEmpty()) {
            next.clear();

            for (TreeNode node : current) {
                listOutOfView(node, camera, farBlocks);
            }

            current.clear();
            current.addAll(next);
        }

        listing = false;
    }

    private List<MeshSummary> uncovered() {
        hiddenBehind = 0;
        hiddenUnder = 0;
        hiddenUnderOutOfView = 0;
        List<MeshSummary> meshes = new ArrayList<>(drawn.size() + outOfViewDrawn.size());
        keepUncovered(drawn, drawnKeys, waitingKeys, true, meshes);
        keepUncovered(outOfViewDrawn, outOfViewDrawnKeys, outOfViewWaitingKeys, false, meshes);
        return meshes;
    }

    private void keepUncovered(List<MeshSummary> from, LongOpenHashSet keys, LongOpenHashSet waitingKeys,
            boolean inView, List<MeshSummary> into) {
        PendingColumns ownView = inView ? pending : pendingOutOfView;
        for (MeshSummary mesh : from) {
            long key = mesh.key();
            boolean shown = front.wasShown(key);
            boolean underInView = pending.under(extent.frame(), key);
            boolean under = underInView || pendingOutOfView.under(extent.frame(), key);
            if (under && !shown) {
                hiddenUnder++;
                hiddenUnderOutOfView += inView && !underInView ? 1 : 0;
            } else if (shown || !ownView.over(extent.frame(), key)) {
                if (!front.holds(key, inView)) {
                    into.add(mesh);
                    continue;
                }

                hiddenBehind++;
            }

            keys.remove(key);
            waitingKeys.add(key);
        }
    }

    // Reused by the next walk; consumed before it.
    List<TreeNode> requested() {
        return requested;
    }

    float requestedPriority(int index) {
        return requestedPriorities.getFloat(index);
    }

    // Reused by the next walk; consumed before it.
    List<TreeNode> outOfViewRequested() {
        return outOfViewRequested;
    }

    float outOfViewSize(int index) {
        return outOfViewSizes.getFloat(index);
    }

    // Reused by the next walk; consumed before it.
    List<TreeNode> rebuilds() {
        return rebuilds;
    }

    boolean starved() {
        return starved;
    }

    int waitingInView() {
        return front.waitingNear();
    }


    FrontState front(long now) {
        return front.state(extent.frame(), hiddenBehind, hiddenUnder, hiddenUnderOutOfView, now);
    }


    private void visit(TreeNode node, CameraFrame camera, double farBlocks, long walk) {
        box.set(extent.frame(), node.key(), camera);

        if (box.horizontalDistance() > farBlocks) {
            return;
        }

        if (!box.meets(frustum)) {
            outside.add(node);
            return;
        }

        node.seen(walk);

        float size = ProjectedSize.of(box, camera.inViewPixelsPerBlock());
        float angle = camera.zoomed() && size != ProjectedSize.CONTAINS_CAMERA ? (float) box.angleFrom(axis) : 0.0F;
        if (refines(node, size, camera)) {
            if (node.missingOctants() != OccupancyMask.EMPTY) {
                candidates.add(new Candidate(node, size, angle));
            }

            keepChildren(node, walk);
            if (givesWay(node, camera)) {
                node.gaveWay(true);
                node.markDescended();
                descendedKeys.add(node.key());
                descend(node);
                return;
            }
        } else if (prefetches(node, size, camera, farBlocks)) {
            if (node.missingOctants() != OccupancyMask.EMPTY) {
                candidates.add(new Candidate(node, size, angle));
            }

            keepChildren(node, walk);
        }

        node.gaveWay(false);
        draw(node);
    }

    private boolean prefetches(TreeNode node, float size, CameraFrame camera, double farBlocks) {
        return node.level() > extent.lowestLevel()
                && ChildDemand.wants(box, size, camera.subdivisionPixels(), farBlocks, horizon);
    }

    private boolean givesWay(TreeNode node, CameraFrame camera) {
        return node.occupancy() != OccupancyMask.EMPTY
                && (node.childrenReady() || (!camera.pressure() && !camera.zoomed() && nodes.free() > 0
                        && !node.childStuck()));
    }

    private boolean refines(TreeNode node, float size, CameraFrame camera) {
        return node.level() > extent.lowestLevel() && size > camera.subdivisionPixels()
                && ChildDemand.withinHorizon(box, horizon);
    }

    private void listOutOfView(TreeNode node, CameraFrame camera, double farBlocks) {
        box.set(extent.frame(), node.key(), camera);

        if (box.horizontalDistance() > farBlocks) {
            return;
        }

        if (refines(node, ProjectedSize.of(box, camera.pixelsPerBlock()), camera) && givesWay(node, camera)) {
            node.gaveWay(true);
            node.markDescended();
            outOfViewDescendedKeys.add(node.key());
            descend(node);
            return;
        }

        node.gaveWay(false);
        MeshSummary mesh = node.mesh();
        if (mesh == null) {
            waiting(node.key(), node);
            return;
        }

        if (childrenStandIn(node)) {
            node.markDescended();
            outOfViewDescendedKeys.add(node.key());
            descend(node);
            return;
        }

        outOfViewDrawnKeys.add(node.key());
        if (!mesh.isEmpty()) {
            outOfViewDrawn.add(mesh);
        }
    }

    private static void keepChildren(TreeNode node, long walk) {
        for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
            TreeNode child = node.child(octant);
            if (child != null) {
                child.seen(walk);
            }
        }
    }

    private void requestOutOfView(CameraFrame camera, double farBlocks, int budget) {
        candidates.clear();
        current.clear();
        current.addAll(outside);

        while (!current.isEmpty()) {
            next.clear();

            for (TreeNode node : current) {
                collectOutOfView(node, camera, farBlocks);
            }

            current.clear();
            current.addAll(next);
        }

        hand(candidates, budget, outOfViewRequested, outOfViewSizes, camera, camera.pixelsPerBlock(), false);
    }

    private void collectOutOfView(TreeNode node, CameraFrame camera, double farBlocks) {
        box.set(extent.frame(), node.key(), camera);

        if (box.horizontalDistance() > farBlocks) {
            return;
        }

        float size = ProjectedSize.of(box, camera.pixelsPerBlock());
        if (node.level() <= extent.lowestLevel() || size <= camera.subdivisionPixels()
                || !ChildDemand.withinHorizon(box, horizon)) {
            return;
        }

        if (node.missingOctants() != OccupancyMask.EMPTY) {
            candidates.add(new Candidate(node, size, 0.0F));
        }

        for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
            TreeNode child = node.child(octant);
            if (child != null) {
                next.add(child);
            }
        }
    }

    private int hand(List<Candidate> from, int budget, List<TreeNode> into, FloatArrayList sizes, CameraFrame camera,
            float pixelsPerBlock, boolean centerFirst) {
        from.sort(centerFirst ? CENTER_FIRST : LARGEST_FIRST);
        int left = budget;

        for (Candidate candidate : from) {
            TreeNode parent = candidate.node();
            int missing = parent.missingOctants();
            float priority = centerFirst ? ProjectedSize.offAxis(candidate.size(), candidate.angle())
                    : candidate.size();

            while (missing != 0 && left > 0) {
                int octant = Integer.numberOfTrailingZeros(missing);
                TreeNode child = nodes.child(parent, octant);
                if (child == null) {
                    return UNSERVED;
                }

                into.add(child);
                sizes.add(priority);
                left = handGrandchildren(parent.grandchildren(octant), child, left - 1, into, sizes,
                        priority, camera, pixelsPerBlock);
                missing &= missing - 1;
            }

            if (missing != 0) {
                return UNSERVED;
            }
        }

        return left;
    }

    private int handGrandchildren(int occupied, TreeNode child, int budget, List<TreeNode> into, FloatArrayList sizes,
            float size, CameraFrame camera, float pixelsPerBlock) {
        box.set(extent.frame(), child.key(), camera);
        if (occupied == OccupancyMask.EMPTY || !refines(child, ProjectedSize.of(box, pixelsPerBlock), camera)) {
            return budget;
        }

        int left = budget;
        int remaining = occupied;
        while (remaining != 0 && left > 0) {
            TreeNode grandchild = nodes.child(child, Integer.numberOfTrailingZeros(remaining));
            if (grandchild == null) {
                return left;
            }

            into.add(grandchild);
            sizes.add(size);
            left--;
            remaining &= remaining - 1;
        }

        return left;
    }

    private void descend(TreeNode node) {
        int occupied = node.occupancy();

        for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
            if (!OccupancyMask.isSet(occupied, octant)) {
                continue;
            }

            TreeNode child = node.child(octant);
            if (child == null) {
                waiting(CellKey.child(node.key(), octant), null);
            } else {
                next.add(child);
            }
        }
    }

    private void draw(TreeNode node) {
        MeshSummary mesh = node.mesh();
        if (mesh == null) {
            waiting(node.key(), node);
            return;
        }

        if (childrenStandIn(node)) {
            node.markDescended();
            descendedKeys.add(node.key());
            descend(node);
            return;
        }

        drawnKeys.add(node.key());
        if (!mesh.isEmpty()) {
            drawn.add(mesh);
        }
    }

    private boolean childrenStandIn(TreeNode node) {
        if (!node.stale()) {
            return false;
        }

        if (!node.building()) {
            rebuilds.add(node);
        }

        return node.childrenMeshed();
    }

    private void waiting(long key, @Nullable TreeNode node) {
        if (listing) {
            outOfViewWaitingKeys.add(key);
            pendingOutOfView.waiting(extent.frame(), key);
            front.waitingOutside(key);
        } else {
            waitingKeys.add(key);
            pending.waiting(extent.frame(), key);
            front.waiting(key, node);
        }
    }
}
