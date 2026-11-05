package com.eminus.render.tree;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.eminus.cell.CellFrame;
import com.eminus.cell.OccupancyMask;
import com.eminus.settings.FarDistance;

import org.jspecify.annotations.Nullable;

final class TreeCleaner {
    static final int PRESSURE_EVICTIONS = 64;

    private static final Comparator<Candidate> OLDEST_FIRST = Comparator.comparingLong(Candidate::lastSeen)
            .thenComparingDouble(Candidate::size)
            .thenComparingLong(Candidate::key);
    private static final Comparator<Candidate> FARTHEST_FIRST =
            Comparator.comparingDouble(Candidate::reach).reversed().thenComparingLong(Candidate::key);

    private final List<Candidate> unwanted = new ArrayList<>();
    private final List<Candidate> far = new ArrayList<>();
    private final List<TreeNode> picked = new ArrayList<>();
    private final CellBox box = new CellBox();

    private int unwantedPicked;

    private record Candidate(TreeNode parent, long lastSeen, float size, double reach) {
        long key() {
            return parent.key();
        }
    }

    List<TreeNode> pick(Collection<TreeNode> all, CameraFrame camera, CellFrame frame, TreeHorizon horizon) {
        picked.clear();
        unwantedPicked = 0;
        if (!camera.pressure()) {
            return List.of();
        }

        unwanted.clear();
        far.clear();
        double farBlocks = (double) camera.farChunks() * FarDistance.BLOCKS_PER_CHUNK;
        for (TreeNode node : all) {
            classify(node, camera, frame, horizon, farBlocks);
        }

        unwanted.sort(OLDEST_FIRST);
        far.sort(FARTHEST_FIRST);

        take(unwanted, null);
        unwantedPicked = picked.size();
        take(far, horizon);

        return List.copyOf(picked);
    }

    int unwantedPicked() {
        return unwantedPicked;
    }

    private void classify(TreeNode parent, CameraFrame camera, CellFrame frame, TreeHorizon horizon,
            double farBlocks) {
        long lastSeen = 0;
        boolean meshed = false;

        for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
            TreeNode child = parent.child(octant);
            if (child != null) {
                lastSeen = Math.max(lastSeen, child.lastSeen());
                meshed |= child.mesh() != null;
            }
        }

        if (!meshed) {
            return;
        }

        float size = ProjectedSize.of(box.set(frame, parent.key(), camera), camera.pixelsPerBlock());
        double reach = TreeHorizon.key(box.horizontalDistance(), parent.level());
        Candidate candidate = new Candidate(parent, lastSeen, size, reach);
        if (ChildDemand.wants(box, size, camera.subdivisionPixels(), farBlocks, horizon)) {
            far.add(candidate);
        } else {
            unwanted.add(candidate);
        }
    }

    private void take(List<Candidate> candidates, @Nullable TreeHorizon lowered) {
        int taken = 0;

        while (taken < candidates.size() && picked.size() < PRESSURE_EVICTIONS) {
            Candidate candidate = candidates.get(taken++);
            if (lowered != null) {
                lowered.lower(candidate.reach());
            }

            TreeNode parent = candidate.parent();
            for (int octant = 0; octant < OccupancyMask.OCTANTS; octant++) {
                TreeNode child = parent.child(octant);
                if (child != null) {
                    picked.add(child);
                }
            }
        }
    }
}
