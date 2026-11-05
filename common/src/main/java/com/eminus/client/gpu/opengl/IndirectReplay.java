package com.eminus.client.gpu.opengl;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import com.eminus.Eminus;
import com.eminus.gpu.pass.Pass;

import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL32C;

/**
 * Runs indirect draw commands on a driver that lists none, as ordinary indexed draws from the CPU's copy of them.
 * Every GL call costs a phone driver far more than a command costs on a desktop one, so commands that draw quads
 * lying next to each other in the arena are merged into one call, and opaque commands, whose order does not matter, are
 * sorted by position first so that far more of them lie next to each other. A budget per call keeps a runaway frame
 * from queueing so much work that the GPU stops answering.
 */
final class IndirectReplay {
    static final String BUDGET_PROPERTY = "eminus.maxDrawCalls";

    private static final int DEFAULT_BUDGET = 4000;
    private static final int INDICES_PER_QUAD = 6;
    private static final int VERTICES_PER_QUAD = 4;
    private static final int INSTANCES_AT = Integer.BYTES;
    private static final int FIRST_INDEX_AT = 2 * Integer.BYTES;
    private static final int BASE_VERTEX_AT = 3 * Integer.BYTES;
    private static final long REPORT_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final int BUDGET = Math.max(1, Integer.getInteger(BUDGET_PROPERTY, DEFAULT_BUDGET));

    private static long[] ranges = new long[1024];
    private static long reportedAt = System.nanoTime();
    private static long commandsRead;
    private static long callsMade;
    private static long runs;
    private static boolean overBudgetReported;

    private IndirectReplay() {
    }

    static void run(OpenGlBuffer commands, int firstCommand, int count, boolean unordered) {
        ByteBuffer shadow = commands.shadow();
        if (shadow == null) {
            throw new IllegalStateException("Indirect commands were not kept for replay");
        }

        if (ranges.length < count) {
            ranges = new long[Math.max(count, ranges.length * 2)];
        }

        int ranged = 0;
        int made = 0;
        for (int command = firstCommand; command < firstCommand + count; command++) {
            int at = command * Pass.INDEXED_INDIRECT_BYTES;
            int indices = shadow.getInt(at);
            if (indices <= 0 || shadow.getInt(at + INSTANCES_AT) <= 0) {
                continue;
            }

            int firstIndex = shadow.getInt(at + FIRST_INDEX_AT);
            int baseVertex = shadow.getInt(at + BASE_VERTEX_AT);
            if (firstIndex != 0 || indices % INDICES_PER_QUAD != 0 || baseVertex < 0) {
                made += draw(indices, firstIndex, baseVertex, made);
                continue;
            }

            ranges[ranged++] = (long) baseVertex << Integer.SIZE | indices;
        }

        if (unordered) {
            Arrays.sort(ranges, 0, ranged);
        }

        made += merged(ranged, made);
        commandsRead += count;
        callsMade += made;
        runs++;
        report();
    }

    private static int merged(int ranged, int alreadyMade) {
        int made = 0;
        int index = 0;
        while (index < ranged) {
            int baseVertex = (int) (ranges[index] >>> Integer.SIZE);
            int indices = (int) ranges[index];
            index++;
            while (index < ranged && (int) (ranges[index] >>> Integer.SIZE)
                    == baseVertex + indices / INDICES_PER_QUAD * VERTICES_PER_QUAD) {
                indices += (int) ranges[index];
                index++;
            }

            made += draw(indices, 0, baseVertex, alreadyMade + made);
        }

        return made;
    }

    private static int draw(int indices, int firstIndex, int baseVertex, int madeSoFar) {
        if (madeSoFar >= BUDGET) {
            if (!overBudgetReported) {
                overBudgetReported = true;
                Eminus.LOGGER.warn("[eminus/gl] a pass needs more than {} draw calls on this driver; the rest are"
                        + " skipped (set -D{} to change that)", BUDGET, BUDGET_PROPERTY);
            }

            return 0;
        }

        GL32C.glDrawElementsBaseVertex(GL11C.GL_TRIANGLES, indices, GL11C.GL_UNSIGNED_INT,
                (long) firstIndex * Integer.BYTES, baseVertex);
        return 1;
    }

    private static void report() {
        long now = System.nanoTime();
        if (now - reportedAt < REPORT_NANOS) {
            return;
        }

        Eminus.LOGGER.info("[eminus/gl] indirect replay in the last {}s: {} commands became {} draw calls over {}"
                + " passes", TimeUnit.NANOSECONDS.toSeconds(now - reportedAt), commandsRead, callsMade, runs);
        reportedAt = now;
        commandsRead = 0;
        callsMade = 0;
        runs = 0;
    }
}
