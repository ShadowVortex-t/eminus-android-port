package com.eminus.render.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicReference;

import com.eminus.settings.FarDistance;
import com.eminus.Eminus;
import com.eminus.cell.CellKey;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.cache.CellHandle;
import com.eminus.ingest.CellChangeListener;
import com.eminus.mesh.CellMesh;
import com.eminus.mesh.MeshListener;
import com.eminus.mesh.MeshSummary;

import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;

public final class TreeManager implements CellChangeListener, MeshListener {
    public static final String THREAD_NAME = "eminus-tree";
    public static final double STILL_BLOCKS = 0.5;

    private static final int THREAD_PRIORITY = Thread.NORM_PRIORITY - 1;
    private static final double STILL_BLOCKS_SQUARED = STILL_BLOCKS * STILL_BLOCKS;
    private static final float MATRIX_EPSILON = 1.0e-6F;
    private static final String PRESSURE_ON = "on";
    private static final String PRESSURE_OFF = "off";

    private final TreeBuilds builds;
    private final TreeExtent extent;
    private final NodeTable nodes;
    private final TreeRoots roots = new TreeRoots();
    private final TreeRoots.Columns columns = new Columns();
    private final TreeTraversal traversal;
    private final TreeCleaner cleaner = new TreeCleaner();
    private final TreeHorizon horizon = new TreeHorizon();
    private final TreeChurn churn = new TreeChurn();
    private final TreeDispatch dispatch;
    private final TreeChanges changes;
    private final BlockingQueue<TreeMessage> messages = new LinkedBlockingQueue<>();
    private final AtomicReference<CameraFrame> frames = new AtomicReference<>();
    private final TreeBatches batches = new TreeBatches();
    private final FailedBuilds failed = new FailedBuilds();
    private final Matrix4f lastViewProjection = new Matrix4f();
    private final Thread thread = new Thread(this::serve, THREAD_NAME);

    private TreeBatch batch = new TreeBatch();
    private boolean treeChanged = true;
    private int lastRequested;
    private boolean lastStarved;
    private int lastWaiting;
    private FrontState lastFront = FrontState.none(0, 0, 0);
    private int lastDrawn;
    private long pressureEvictions;
    private boolean outOfViewDue;
    private boolean lastTablePressure;
    private boolean lastArenaPressure;
    private boolean urgent;
    private @Nullable CameraFrame camera;
    private double lastEyeX;
    private double lastEyeY;
    private double lastEyeZ;

    private volatile long walks;
    private volatile boolean horizonBounded;
    private volatile boolean running = true;

    private TreeManager(TreeBuilds builds, TreeExtent extent, int capacity) {
        this.builds = builds;
        this.extent = extent;
        nodes = new NodeTable(capacity);
        traversal = new TreeTraversal(nodes, extent, horizon);
        dispatch = new TreeDispatch(builds, extent);
        changes = new TreeChanges(nodes, extent, builds, dispatch);
    }

    public static TreeManager start(TreeBuilds builds, TreeExtent extent) {
        return start(builds, extent, NodeTable.CAPACITY);
    }

    @SuppressWarnings("ThreadPriorityCheck")
    public static TreeManager start(TreeBuilds builds, TreeExtent extent, int capacity) {
        TreeManager manager = new TreeManager(builds, extent, capacity);
        manager.thread.setDaemon(true);
        manager.thread.setPriority(THREAD_PRIORITY);
        manager.thread.start();
        return manager;
    }

    @Override
    public void changed(CellHandle handle, int faceMask, int edgeMask, int occupancy, long sequence) {
        messages.add(new TreeMessage.CellChanged(handle, faceMask, edgeMask, occupancy, sequence));
    }

    @Override
    public void covered(int chunkX, int chunkZ) {
        messages.add(new TreeMessage.ColumnCovered(chunkX, chunkZ));
    }

    @Override
    public void meshed(CellMesh mesh, long request) {
        messages.add(new TreeMessage.CellMeshed(mesh, request));
    }

    @Override
    public void failed(long key, long request) {
        messages.add(new TreeMessage.CellFailed(key, request));
    }

    public void frame(CameraFrame frame) {
        if (frames.getAndSet(frame) == null) {
            messages.add(new TreeMessage.FrameReady());
        }
    }

    public void resize(int capacity) {
        messages.add(new TreeMessage.Resize(capacity));
    }

    public CompletableFuture<List<long[]>> describe(List<long[]> rows) {
        CompletableFuture<List<long[]>> answer = new CompletableFuture<>();
        messages.add(new TreeMessage.Describe(rows, answer));
        return answer;
    }

    public TreeBatches batches() {
        return batches;
    }

    public long walks() {
        return walks;
    }

    public boolean horizonBounded() {
        return horizonBounded;
    }

    public CompletableFuture<TreeState> snapshot() {
        CompletableFuture<TreeState> answer = new CompletableFuture<>();
        messages.add(new TreeMessage.Snapshot(answer));
        return answer;
    }

    public CompletableFuture<Map<String, Long>> churn() {
        CompletableFuture<Map<String, Long>> answer = new CompletableFuture<>();
        messages.add(new TreeMessage.Churn(answer));
        return answer;
    }

    public void stop() {
        running = false;
        thread.interrupt();

        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }

        if (urgent) {
            urgent = false;
            builds.urgent(false);
        }

        refuseUnanswered();
    }

    private void refuseUnanswered() {
        for (TreeMessage left : messages) {
            switch (left) {
                case TreeMessage.Snapshot snapshot -> snapshot.answer().completeExceptionally(stopped());
                case TreeMessage.Describe describe -> describe.answer().completeExceptionally(stopped());
                case TreeMessage.Churn churned -> churned.answer().completeExceptionally(stopped());
                default -> {
                }
            }
        }
    }

    private void serve() {
        while (running) {
            TreeMessage message;

            try {
                message = messages.take();
            } catch (InterruptedException interrupted) {
                return;
            }

            do {
                apply(message);
                publish();
            } while ((message = messages.poll()) != null);
        }
    }

    private void apply(TreeMessage message) {
        switch (message) {
            case TreeMessage.CellChanged changed -> {
                if (changes.changed(changed.handle(), changed.faceMask(), changed.edgeMask(), changed.occupancy(),
                        changed.sequence(), camera)) {
                    treeChanged = true;
                }
            }
            case TreeMessage.CellMeshed meshed -> applyMesh(meshed.mesh(), meshed.request());
            case TreeMessage.CellFailed failure -> applyFailure(failure.key(), failure.request());
            case TreeMessage.ColumnCovered covered -> changes.covered(covered.chunkX(), covered.chunkZ(), camera);
            case TreeMessage.FrameReady ready -> applyFrame();
            case TreeMessage.Resize resize -> {
                nodes.resize(resize.capacity());
                horizon.reset();
                treeChanged = true;
            }
            case TreeMessage.Describe describe ->
                    describe.answer().complete(NodeRow.describe(nodes, describe.rows(), walks));
            case TreeMessage.Snapshot snapshot -> snapshot.answer().complete(state());
            case TreeMessage.Churn churned ->
                    churned.answer().complete(churn.reading(nodes.size(), nodes.capacity(), horizon));
        }
    }

    private static IllegalStateException stopped() {
        return new IllegalStateException("The far renderer stopped.");
    }

    private TreeState state() {
        int building = 0;
        for (TreeNode node : nodes.all()) {
            if (node.building()) {
                building++;
            }
        }

        int backlog = builds.backlog();
        boolean batchWaiting = !batch.isEmpty() || batches.waiting();
        boolean settled = settled(building, backlog, batchWaiting);

        return new TreeState(walks, nodes.size(), nodes.free(), lastDrawn, backlog, building, lastRequested,
                lastStarved, treeChanged, batchWaiting, pressureEvictions, settled, lastWaiting, lastFront,
                levels());
    }

    private List<LevelState> levels() {
        int[] levelNodes = new int[DetailLevel.COUNT];
        int[] levelMeshed = new int[DetailLevel.COUNT];
        long[] levelQuads = new long[DetailLevel.COUNT];

        for (TreeNode node : nodes.all()) {
            int level = node.level() - DetailLevel.MIN;
            levelNodes[level]++;
            MeshSummary mesh = node.mesh();
            if (mesh != null) {
                levelMeshed[level]++;
                levelQuads[level] += mesh.quadCount();
            }
        }

        List<LevelState> levels = new ArrayList<>(DetailLevel.COUNT);
        for (int level = 0; level < DetailLevel.COUNT; level++) {
            levels.add(new LevelState(level + DetailLevel.MIN, levelNodes[level], levelMeshed[level], levelQuads[level]));
        }

        return List.copyOf(levels);
    }

    private boolean settled(int building, int backlog, boolean batchWaiting) {
        return building == 0 && failed.isEmpty() && !treeChanged && lastRequested == 0 && !lastStarved
                && backlog == 0 && !batchWaiting && horizon.settled();
    }

    private void applyMesh(CellMesh mesh, long request) {
        TreeNode node = nodes.get(mesh.key());
        if (node == null || node.request() != request) {
            return;
        }

        if (!node.isRoot()) {
            dispatch.settle(node);
        }

        node.meshed(mesh.summary());
        pruneStaleChildren(node);
        batch.add(mesh);
        treeChanged = true;

        if (!node.takeRebuild()) {
            return;
        }

        if (node.gaveWay()) {
            changes.defer(node);
        } else {
            dispatch.build(node, dispatch.priority(node, camera), false);
        }
    }

    private void applyFailure(long key, long request) {
        TreeNode node = nodes.get(key);
        if (node == null || node.request() != request || !node.building()) {
            return;
        }

        if (!node.isRoot()) {
            dispatch.settle(node);
        }

        if (failed.add(node, node.buildFailed(), System.nanoTime()) || node.mesh() != null) {
            return;
        }

        CellMesh empty = CellMesh.empty(key);
        node.meshed(empty.summary());
        batch.add(empty);
        treeChanged = true;
    }

    private void retryFailed() {
        for (TreeNode node : failed.take()) {
            if (nodes.get(node.key()) == node && !node.building()) {
                dispatch.build(node, dispatch.priority(node, camera), node.outOfViewBuild());
            }
        }

        treeChanged = true;
    }

    private void applyFrame() {
        CameraFrame posted = frames.getAndSet(null);
        if (posted == null) {
            return;
        }

        CameraFrame camera = acceptFrame(posted);
        if (failed.due(System.nanoTime())) {
            retryFailed();
        }

        boolean still = still(camera);
        if (!treeChanged && still && !outOfViewDue) {
            return;
        }

        boolean evicted = evictStale(camera);
        long walk = walks + 1;
        walkTree(camera, still, walk);
        dispatchRequested();
        list(camera);
        treeChanged = evicted;
        remember(camera, walk);
    }

    private CameraFrame acceptFrame(CameraFrame posted) {
        this.camera = posted;
        if (roots.update(posted.eyeX(), posted.eyeZ(), FarDistance.chunksToCells(posted.farChunks()), columns)) {
            treeChanged = true;
        }

        if (posted.pressure() != lastArenaPressure) {
            lastArenaPressure = posted.pressure();
            treeChanged = true;
        }

        boolean tablePressure = nodes.pressure();
        if (tablePressure != lastTablePressure) {
            lastTablePressure = tablePressure;
            treeChanged = true;
            Eminus.LOGGER.info("[eminus/tree] table pressure={} nodes={} capacity={} walk={}",
                    tablePressure ? PRESSURE_ON : PRESSURE_OFF, nodes.size(), nodes.capacity(), walks);
        }

        CameraFrame camera = tablePressure && !posted.pressure() ? posted.underPressure() : posted;
        this.camera = camera;
        churn.moved(camera.eyeX(), camera.eyeZ());
        if (horizon.frame(camera, lastRequested == 0 && !lastStarved && dispatch.inFlight() == 0)) {
            treeChanged = true;
        }

        return camera;
    }

    private boolean evictStale(CameraFrame camera) {
        List<TreeNode> stale = cleaner.pick(nodes.all(), camera, extent.frame(), horizon);
        for (int index = 0; index < stale.size(); index++) {
            TreeNode node = stale.get(index);
            if (nodes.get(node.key()) == node) {
                churn.evicted(index < cleaner.unwantedPicked());
                evict(node, true);
                pressureEvictions++;
            }
        }

        return !stale.isEmpty();
    }

    private void walkTree(CameraFrame camera, boolean still, long walk) {
        int free = nodes.free();
        int budget = dispatch.budget(free);
        int outOfViewBudget = still ? dispatch.outOfViewBudget(free) : 0;
        traversal.walk(nodes.roots(), camera, budget, outOfViewBudget, walk);
        lastRequested = traversal.requested().size() + traversal.outOfViewRequested().size();
        lastStarved = traversal.starved();
        lastWaiting = traversal.waitingInView();
        outOfViewDue = !still;
    }

    private void dispatchRequested() {
        List<TreeNode> requested = traversal.requested();
        List<TreeNode> outOfView = traversal.outOfViewRequested();
        for (int index = 0; index < requested.size(); index++) {
            churn.requested(requested.get(index));
            dispatch.build(requested.get(index), traversal.requestedPriority(index), false);
        }

        for (int index = 0; index < outOfView.size(); index++) {
            churn.requested(outOfView.get(index));
            dispatch.build(outOfView.get(index), ProjectedSize.outOfView(traversal.outOfViewSize(index)), true);
        }
    }

    private void list(CameraFrame camera) {
        RenderList listed = traversal.list(camera);
        for (TreeNode node : traversal.rebuilds()) {
            dispatch.build(node, dispatch.priority(node, camera), false);
        }

        lastFront = traversal.front(System.nanoTime());
        batch.renderList(listed);
        lastDrawn = listed.meshes().size();
    }

    private void remember(CameraFrame camera, long walk) {
        lastEyeX = camera.eyeX();
        lastEyeY = camera.eyeY();
        lastEyeZ = camera.eyeZ();
        lastViewProjection.set(camera.viewProjection());
        horizonBounded = horizon.bounded();
        walks = walk;
    }

    private boolean still(CameraFrame camera) {
        double dx = camera.eyeX() - lastEyeX;
        double dy = camera.eyeY() - lastEyeY;
        double dz = camera.eyeZ() - lastEyeZ;

        return dx * dx + dy * dy + dz * dz < STILL_BLOCKS_SQUARED
                && camera.viewProjection().equals(lastViewProjection, MATRIX_EPSILON);
    }

    private void pruneStaleChildren(TreeNode node) {
        int stale = node.requestedOctants() & ~node.occupancy();

        while (stale != 0) {
            int octant = Integer.numberOfTrailingZeros(stale);
            TreeNode child = node.child(octant);
            if (child != null) {
                evict(child);
            } else {
                node.detach(octant);
            }

            stale &= stale - 1;
        }
    }

    private void evict(TreeNode node) {
        evict(node, false);
    }

    private void evict(TreeNode node, boolean underPressure) {
        nodes.remove(node, removed -> {
            if (underPressure) {
                churn.removed(removed);
            }

            if (removed.mesh() != null) {
                batch.evict(removed.key());
            }

            if (removed.building() && !removed.isRoot()) {
                dispatch.settle(removed);
            }

            CellHandle pending = removed.pending();
            if (pending != null) {
                builds.release(pending, removed.pendingReferences());
            }
        });
        treeChanged = true;
    }

    private void publish() {
        if (!batch.isEmpty() && batches.offer(batch)) {
            batch = new TreeBatch();
        }

        followRefining();
    }

    private void followRefining() {
        boolean refining = dispatch.refining();
        if (refining != urgent) {
            urgent = refining;
            builds.urgent(refining);
        }
    }

    private final class Columns implements TreeRoots.Columns {
        @Override
        public void added(int cellX, int cellZ) {
            for (int cellY = 0; cellY < extent.heightCells(); cellY++) {
                TreeNode root = nodes.root(CellKey.pack(DetailLevel.MAX, cellX, cellY, cellZ));
                if (root.mesh() == null && !root.building()) {
                    dispatch.build(root, dispatch.priority(root, camera), false);
                }
            }

            treeChanged = true;
        }

        @Override
        public void removed(int cellX, int cellZ) {
            for (int cellY = 0; cellY < extent.heightCells(); cellY++) {
                TreeNode root = nodes.get(CellKey.pack(DetailLevel.MAX, cellX, cellY, cellZ));
                if (root != null) {
                    evict(root);
                }
            }
        }
    }
}
