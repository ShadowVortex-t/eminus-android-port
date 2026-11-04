package com.eminus.client.render.arena;

import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import com.eminus.Eminus;
import com.eminus.gpu.Format;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Sparse;
import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.BufferUsage;
import com.eminus.gpu.buffer.SparseBuffer;
import com.eminus.gpu.buffer.Staging;
import com.eminus.gpu.buffer.TexelView;
import com.eminus.mesh.CellMesh;
import com.eminus.render.arena.ArenaAllocator;
import com.eminus.render.arena.ArenaPages;
import com.eminus.render.arena.ArenaPressure;
import com.eminus.render.arena.ArenaSizing;
import com.eminus.render.arena.MeshSlot;
import com.eminus.render.arena.MeshSlots;
import com.eminus.render.backend.BackendSupport;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.longs.LongIterator;

import org.jspecify.annotations.Nullable;

public final class GeometryArena implements MeshSlots, AutoCloseable {
    public static final Format QUAD_FORMAT = Format.RG32_UINT;

    private static final String LABEL = "eminus-geometry-arena";
    private static final Set<BufferUsage> USAGE =
            EnumSet.of(BufferUsage.VERTEX, BufferUsage.TEXEL, BufferUsage.STORAGE, BufferUsage.COPY_SRC,
                    BufferUsage.COPY_DST);
    private static final long WARNING_PERIOD_NANOS = 1_000_000_000L;
    private static final int MIB_SHIFT = 20;
    private static final int KIB_SHIFT = 10;
    private static final long PLAIN = 0L;

    private final Gpu gpu;
    private final @Nullable SparseBuffer sparseQuads;
    private final @Nullable ArenaPages pages;
    private final ArenaUploader uploader;
    private final Long2ObjectOpenHashMap<MeshSlot> held = new Long2ObjectOpenHashMap<>();
    private final ArenaPressure pressure = new ArenaPressure();

    private Buffer quads;
    private TexelView quadTexels;
    private long bytes;
    private long targetBytes;
    private ArenaAllocator allocator;
    private MeshRecords records;
    private int refused;
    private long refusedTotal;
    private int refusedSinceWarning;
    private int refusedQuadsMax;
    private long lastWarning = System.nanoTime() - WARNING_PERIOD_NANOS;
    private boolean announcedPressure;

    private GeometryArena(Gpu gpu, Buffer quads, @Nullable SparseBuffer sparseQuads, @Nullable ArenaPages pages,
            long bytes, ArenaAllocator allocator, MeshRecords records, ArenaUploader uploader) {
        this.gpu = gpu;
        this.quads = quads;
        this.sparseQuads = sparseQuads;
        this.pages = pages;
        this.bytes = bytes;
        this.targetBytes = bytes;
        this.allocator = allocator;
        this.records = records;
        this.uploader = uploader;
        quadTexels = view(gpu, quads);
    }

    private static TexelView view(Gpu gpu, Buffer quads) {
        return gpu.capabilities().storageQuads() ? gpu.storageView(quads, QUAD_FORMAT)
                : gpu.texelView(quads, QUAD_FORMAT);
    }

    public static @Nullable GeometryArena create(Gpu gpu, BackendSupport support, long bytes,
            @Nullable Sparse sparse) {
        gpu.assertRenderThread();

        if (!support.accepted()) {
            return null;
        }

        int segments = ArenaSizing.segments(bytes);
        ArenaAllocator allocator = new ArenaAllocator(segments);
        MeshRecords records = MeshRecords.create(gpu, segments);
        if (sparse == null) {
            Buffer quads = gpu.buffer(LABEL, USAGE, bytes);
            Eminus.LOGGER.info("Geometry arena of {} MiB: {} segments of {} quads",
                    bytes >> MIB_SHIFT, segments, ArenaAllocator.QUADS_PER_SEGMENT);
            return new GeometryArena(gpu, quads, null, null, bytes, allocator, records, ArenaUploader.create(gpu));
        }

        SparseBuffer quads = sparse.reserve(LABEL, USAGE, bytes);
        ArenaPages pages = new ArenaPages(segments, ArenaSizing.segmentsPerPage(sparse.pageBytes()));
        Eminus.LOGGER.info("Geometry arena reserved {} MiB as a sparse buffer: {} segments of {} quads in {} pages"
                        + " of {} KiB, 0 MiB committed", bytes >> MIB_SHIFT, segments, ArenaAllocator.QUADS_PER_SEGMENT,
                pages.pages(), sparse.pageBytes() >> KIB_SHIFT);
        return new GeometryArena(gpu, quads, quads, pages, bytes, allocator, records, ArenaUploader.create(gpu));
    }

    public TexelView quads() {
        return quadTexels;
    }

    public MeshRecords records() {
        return records;
    }

    public int meshes() {
        return held.size();
    }

    public int usedSegments() {
        return allocator.usedSegments();
    }

    public ArenaState state() {
        return new ArenaState(bytes, committedBytes(), pages == null ? PLAIN : pageBytes(pages, 1),
                allocator.segments(), held.size(), allocator.usedSegments(),
                allocator.freeSegments(), allocator.largestFreeRun(), allocator.freeRuns(), refusedTotal, pressure());
    }

    public long committedBytes() {
        return pages == null ? bytes : pageBytes(pages, pages.committed());
    }

    public boolean pressure() {
        return pressure.holding();
    }

    @Override
    public @Nullable MeshSlot slot(long key) {
        return held.get(key);
    }

    public int accept(List<CellMesh> meshes, int from) {
        gpu.assertRenderThread();
        refused = 0;
        int index = upload(meshes, from);
        if (refused > 0) {
            warnAboutRefusals();
        }

        compactIfFits();
        updatePressure();
        return index;
    }

    private int upload(List<CellMesh> meshes, int from) {
        int index = from;

        try (ArenaUploader.Step step = uploader.step()) {
            while (index < meshes.size()) {
                CellMesh mesh = meshes.get(index);
                if (mesh.isEmpty()) {
                    release(mesh.key());
                    index++;
                    continue;
                }

                Staging.Staged staged = step.stage(mesh);
                if (staged == null) {
                    break;
                }

                release(mesh.key());
                MeshSlot placed = place(mesh);
                if (placed == null) {
                    refuse(mesh.quadCount());
                } else {
                    step.copy(staged, quads, placed.segment() * ArenaSizing.SEGMENT_BYTES);
                }

                index++;
            }
        }

        return index;
    }

    private void updatePressure() {
        if (pressure.update(allocator.usedSegments(), ArenaSizing.segments(targetBytes), refused > 0)
                && !announcedPressure) {
            announcedPressure = true;
            Eminus.LOGGER.info("Geometry arena of {} MiB is full: the far layer stops refining and is drawn coarser"
                    + " than the detail distance asks", targetBytes >> MIB_SHIFT);
        }
    }

    public void resize(long wanted) {
        gpu.assertRenderThread();
        if (pages != null || wanted == targetBytes) {
            return;
        }

        targetBytes = wanted;
        if (wanted > bytes) {
            grow(wanted);
        } else if (wanted < bytes) {
            Eminus.LOGGER.info("Geometry arena of {} MiB shrinks to {} MiB once its meshes fit", bytes >> MIB_SHIFT,
                    wanted >> MIB_SHIFT);
            compactIfFits();
        }
    }

    private void grow(long wanted) {
        int segments = ArenaSizing.segments(wanted);
        Buffer grown = gpu.buffer(LABEL, USAGE, wanted);
        gpu.copy(quads, 0, grown, 0, bytes);
        Eminus.LOGGER.info("Geometry arena grew in place from {} to {} MiB: {} segments of {} quads",
                bytes >> MIB_SHIFT, wanted >> MIB_SHIFT, segments, ArenaAllocator.QUADS_PER_SEGMENT);
        replaceQuads(grown, wanted);
        allocator.grow(segments);
        records = records.grown(segments);
    }

    private void compactIfFits() {
        int segments = ArenaSizing.segments(targetBytes);
        if (targetBytes >= bytes || allocator.usedSegments() > segments) {
            return;
        }

        Buffer packed = gpu.buffer(LABEL, USAGE, targetBytes);
        ArenaAllocator packing = new ArenaAllocator(segments);
        MeshRecords packedRecords = MeshRecords.create(gpu, segments);
        MeshSlot[] slots = held.values().toArray(MeshSlot[]::new);
        Arrays.sort(slots, Comparator.comparingInt(MeshSlot::segment));
        int runFrom = 0;
        int runTo = 0;
        int runTarget = 0;
        for (MeshSlot slot : slots) {
            int length = ArenaAllocator.segmentsFor(slot.slots());
            int segment = packing.allocate(slot.slots());
            if (slot.segment() != runTo || segment != runTarget + runTo - runFrom) {
                copySegments(packed, runFrom, runTarget, runTo - runFrom);
                runFrom = slot.segment();
                runTarget = segment;
            }
            runTo = slot.segment() + length;
            MeshSlot moved = slot.at(segment);
            held.put(slot.key(), moved);
            packedRecords.write(moved);
        }
        copySegments(packed, runFrom, runTarget, runTo - runFrom);

        Eminus.LOGGER.info("Geometry arena compacted from {} to {} MiB: {} meshes in {} segments",
                bytes >> MIB_SHIFT, targetBytes >> MIB_SHIFT, slots.length, packing.usedSegments());
        replaceQuads(packed, targetBytes);
        allocator = packing;
        records.close();
        records = packedRecords;
    }

    private void copySegments(Buffer target, int from, int to, int length) {
        if (length > 0) {
            gpu.copy(quads, from * ArenaSizing.SEGMENT_BYTES, target, to * ArenaSizing.SEGMENT_BYTES,
                    length * ArenaSizing.SEGMENT_BYTES);
        }
    }

    private void replaceQuads(Buffer replacement, long replacementBytes) {
        quadTexels.close();
        quads.close();
        quads = replacement;
        bytes = replacementBytes;
        quadTexels = view(gpu, quads);
    }

    public void evict(LongCollection keys) {
        gpu.assertRenderThread();

        for (LongIterator key = keys.iterator(); key.hasNext(); ) {
            release(key.nextLong());
        }
    }

    private @Nullable MeshSlot place(CellMesh mesh) {
        int segment = allocator.allocate(mesh.slotCount());
        if (segment == ArenaAllocator.NO_SEGMENT) {
            return null;
        }

        MeshSlot placed = MeshSlot.of(mesh, segment);
        if (pages != null && sparseQuads != null) {
            ArenaPages.PageRun run = pages.place(segment, ArenaAllocator.segmentsFor(mesh.slotCount()));
            if (!run.isEmpty()) {
                sparseQuads.commit(pageBytes(pages, run.first()), pageBytes(pages, run.count()));
            }
        }
        held.put(mesh.key(), placed);
        records.write(placed);

        return placed;
    }

    private void refuse(int quads) {
        refused++;
        refusedTotal++;
        refusedSinceWarning++;
        refusedQuadsMax = Math.max(refusedQuadsMax, quads);
    }

    private void warnAboutRefusals() {
        long now = System.nanoTime();
        if (now - lastWarning < WARNING_PERIOD_NANOS) {
            return;
        }

        lastWarning = now;
        Eminus.LOGGER.warn("{} cells of up to {} quads do not fit the arena and are dropped: {} free segments, "
                        + "largest free run {} segments, {} free runs",
                refusedSinceWarning, refusedQuadsMax, allocator.freeSegments(), allocator.largestFreeRun(),
                allocator.freeRuns());
        refusedSinceWarning = 0;
        refusedQuadsMax = 0;
    }

    private void release(long key) {
        MeshSlot released = held.remove(key);
        if (released == null) {
            return;
        }

        allocator.free(released.segment(), released.slots());
        if (pages != null && sparseQuads != null) {
            ArenaPages.PageRun run = pages.free(released.segment(), ArenaAllocator.segmentsFor(released.slots()));
            if (!run.isEmpty()) {
                sparseQuads.decommit(pageBytes(pages, run.first()), pageBytes(pages, run.count()));
            }
        }
    }

    private static long pageBytes(ArenaPages pages, int count) {
        return (long) count * pages.segmentsPerPage() * ArenaSizing.SEGMENT_BYTES;
    }

    @Override
    public void close() {
        uploader.close();
        records.close();
        quadTexels.close();
        quads.close();
    }
}
