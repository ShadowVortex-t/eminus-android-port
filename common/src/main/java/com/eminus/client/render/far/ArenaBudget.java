package com.eminus.client.render.far;

import java.util.Optional;
import java.util.OptionalLong;

import com.eminus.Eminus;
import com.eminus.gpu.Capabilities;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Sparse;
import com.eminus.render.arena.ArenaSizing;

import org.jspecify.annotations.Nullable;

final class ArenaBudget {
    private static final long BYTES_PER_MIB = 1L << 20;

    private ArenaBudget() {
    }

    static long ceiling(Capabilities device, OptionalLong deviceFreeBytes, long replacedArenaBytes) {
        OptionalLong texelBytes = device.textureQuads() ? device.textureBytes()
                : device.texelElements().isPresent()
                ? OptionalLong.of(device.texelElements().getAsLong() * ArenaSizing.QUAD_BYTES)
                : OptionalLong.empty();
        OptionalLong freeBytes = deviceFreeBytes.isPresent()
                ? OptionalLong.of(deviceFreeBytes.getAsLong() + replacedArenaBytes)
                : OptionalLong.empty();
        long maxAllocation = device.maxAllocationBytes();
        long ceiling = ArenaSizing.ceiling(texelBytes, maxAllocation, freeBytes);
        if (device.textureQuads() && device.texelElements().isPresent()) {
            // The quads sit in a texture, but each segment still has a record in a texel buffer.
            ceiling = Math.min(ceiling, device.texelElements().getAsLong() * ArenaSizing.SEGMENT_BYTES);
        }
        logCeiling(ceiling, texelBytes, maxAllocation, freeBytes, replacedArenaBytes);
        return ceiling;
    }

    private static void logCeiling(long ceiling, OptionalLong texelBytes, long maxAllocation, OptionalLong freeBytes,
            long replacedArenaBytes) {
        Eminus.LOGGER.info("Geometry arena ceiling {} MiB: texel buffer {}, vertex index {} MiB, device share {}, "
                + "free video memory {}", ceiling / BYTES_PER_MIB,
                texelBytes.isPresent() ? texelBytes.getAsLong() / BYTES_PER_MIB + " MiB"
                        : "unread, " + ArenaSizing.UNREAD_TEXEL_BYTES / BYTES_PER_MIB + " MiB in its place",
                ArenaSizing.VERTEX_INDEX_BYTES / BYTES_PER_MIB,
                maxAllocation == Long.MAX_VALUE ? "unbounded"
                        : maxAllocation / ArenaSizing.DEVICE_SHARE / BYTES_PER_MIB + " MiB",
                freeBytes.isPresent() ? freeBytes.getAsLong() / BYTES_PER_MIB + " MiB with the replaced arena's "
                        + replacedArenaBytes / BYTES_PER_MIB + " MiB, "
                        + freeBytes.getAsLong() / ArenaSizing.FREE_MEMORY_SHARE / BYTES_PER_MIB + " MiB taken"
                        : "not reported");
    }

    static @Nullable Sparse sparse(Gpu gpu) {
        Optional<Sparse> sparse = gpu.sparse();
        if (sparse.isEmpty()) {
            return null;
        }

        long pageBytes = sparse.get().pageBytes();
        if (ArenaSizing.segmentsPerPage(pageBytes) == ArenaSizing.NO_PAGES) {
            Eminus.LOGGER.info("Sparse pages of {} bytes do not hold whole arena segments of {} bytes: the arena is a"
                    + " plain buffer", pageBytes, ArenaSizing.SEGMENT_BYTES);
            return null;
        }

        return sparse.get();
    }

    static long bytes(long wanted, long ceiling, @Nullable Sparse sparse) {
        return sparse == null
                ? ArenaSizing.fitted(bounded(wanted, ceiling), ceiling)
                : ArenaSizing.reserved(ArenaSizing.fitted(bounded(ceiling, ceiling), ceiling), sparse.pageBytes());
    }

    static long bounded(long wanted, long ceiling) {
        return capped(raisedToFloor(wanted, ceiling), wanted);
    }

    private static long raisedToFloor(long wanted, long ceiling) {
        Long floorMiB = Long.getLong(FarRenderer.ARENA_FLOOR_PROPERTY);
        if (floorMiB == null) {
            return wanted;
        }

        Eminus.LOGGER.info("Geometry arena raised to at least {} MiB by -D{}, {} MiB wanted", floorMiB,
                FarRenderer.ARENA_FLOOR_PROPERTY, wanted / BYTES_PER_MIB);
        long bytes = Math.max(wanted, floorMiB * BYTES_PER_MIB);
        if (bytes > ceiling) {
            Eminus.LOGGER.info("Geometry arena floor of {} MiB is above the device ceiling: the arena starts at "
                    + "{} MiB", floorMiB, ceiling / BYTES_PER_MIB);
        }

        return bytes;
    }

    private static long capped(long bytes, long wanted) {
        Long capMiB = Long.getLong(FarRenderer.ARENA_CAP_PROPERTY);
        if (capMiB == null) {
            return bytes;
        }

        Eminus.LOGGER.info("Geometry arena capped at {} MiB by -D{}, {} MiB wanted", capMiB,
                FarRenderer.ARENA_CAP_PROPERTY, wanted / BYTES_PER_MIB);
        return Math.min(bytes, capMiB * BYTES_PER_MIB);
    }
}
