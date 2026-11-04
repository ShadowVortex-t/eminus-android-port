package com.eminus.client.gpu.opengl;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import com.eminus.gpu.buffer.Buffer;
import com.eminus.gpu.buffer.Staging;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.ARBBufferStorage;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.MemoryUtil;

abstract class OpenGlStaging implements Staging {
    private static final int UNBOUND = 0;

    static OpenGlStaging create(OpenGlObjects objects, String label, int bytes, boolean persistentlyMapped) {
        return persistentlyMapped ? new Mapped(objects, label, bytes) : new Copied(bytes);
    }

    static void copy(int source, long sourceOffset, OpenGlBuffer target, long targetOffset, long bytes) {
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, source);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, target.handle());
        GL31C.glCopyBufferSubData(GL31C.GL_COPY_READ_BUFFER, GL31C.GL_COPY_WRITE_BUFFER, sourceOffset, targetOffset,
                bytes);
        GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, UNBOUND);
        GL15C.glBindBuffer(GL31C.GL_COPY_WRITE_BUFFER, UNBOUND);
        target.changed(targetOffset, bytes);
    }

    private static final class Copied extends OpenGlStaging {
        private final int capacity;

        private Copied(int capacity) {
            this.capacity = capacity;
        }

        @Override
        public Step step() {
            return new CopiedStep();
        }

        @Override
        public void close() {
        }

        private record Held(ByteBuffer bytes) implements Staged {
        }

        private final class CopiedStep implements Step {
            private final List<ByteBuffer> held = new ArrayList<>();
            private int used;

            @Override
            public @Nullable Staged stage(ByteBuffer bytes) {
                int length = bytes.remaining();
                if (used + length > capacity) {
                    return null;
                }

                ByteBuffer copy = MemoryUtil.memAlloc(length);
                MemoryUtil.memCopy(bytes, copy);
                held.add(copy);
                used += length;
                return new Held(copy);
            }

            @Override
            public void copy(Staged staged, Buffer target, long offset) {
                ((OpenGlBuffer) target).write(offset, ((Held) staged).bytes());
            }

            @Override
            public void close() {
                held.forEach(MemoryUtil::memFree);
                held.clear();
            }
        }
    }

    private static final class Mapped extends OpenGlStaging {
        private static final int HALVES = 2;
        private static final int MAPPING_FLAGS = GL30C.GL_MAP_WRITE_BIT | ARBBufferStorage.GL_MAP_PERSISTENT_BIT
                | ARBBufferStorage.GL_MAP_COHERENT_BIT;
        private static final long NANOS_PER_SECOND = 1_000_000_000L;
        private static final long FENCE_WAIT_NANOS = NANOS_PER_SECOND;
        private static final int FENCE_WAITS = 10;
        private static final long NO_FENCE = 0L;

        private final OpenGlObjects objects;
        private final int ring;
        private final int halfBytes;
        private final ByteBuffer mapped;
        private final long[] fences = new long[HALVES];
        private int half = HALVES - 1;

        private Mapped(OpenGlObjects objects, String label, int bytes) {
            this.objects = objects;
            this.halfBytes = bytes / HALVES;
            this.ring = GL15C.glGenBuffers();
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, ring);
            ARBBufferStorage.glBufferStorage(GL31C.GL_COPY_READ_BUFFER, bytes, MAPPING_FLAGS);
            ByteBuffer view = GL30C.glMapBufferRange(GL31C.GL_COPY_READ_BUFFER, 0L, bytes, MAPPING_FLAGS);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, UNBOUND);
            if (view == null) {
                GL15C.glDeleteBuffers(ring);
                throw new IllegalStateException("The staging ring " + label + " could not be mapped");
            }

            this.mapped = view;
            objects.created(OpenGlObjects.Kind.BUFFER, ring, label);
        }

        @Override
        public Step step() {
            half = (half + 1) % HALVES;
            awaitFence(half);
            return new MappedStep(half * halfBytes);
        }

        private void awaitFence(int index) {
            long fence = fences[index];
            if (fence == NO_FENCE) {
                return;
            }

            for (int wait = 0; wait < FENCE_WAITS; wait++) {
                int state = GL32C.glClientWaitSync(fence, GL32C.GL_SYNC_FLUSH_COMMANDS_BIT, FENCE_WAIT_NANOS);
                if (state == GL32C.GL_ALREADY_SIGNALED || state == GL32C.GL_CONDITION_SATISFIED) {
                    GL32C.glDeleteSync(fence);
                    fences[index] = NO_FENCE;
                    return;
                }
            }

            throw new IllegalStateException("The staging ring's fence did not signal within "
                    + FENCE_WAITS * FENCE_WAIT_NANOS / NANOS_PER_SECOND + " seconds");
        }

        @Override
        public void close() {
            for (int index = 0; index < HALVES; index++) {
                if (fences[index] != NO_FENCE) {
                    GL32C.glDeleteSync(fences[index]);
                }
            }
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, ring);
            GL15C.glUnmapBuffer(GL31C.GL_COPY_READ_BUFFER);
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, UNBOUND);
            GL15C.glDeleteBuffers(ring);
            objects.deleted(OpenGlObjects.Kind.BUFFER);
        }

        private record Region(long offset, long bytes) implements Staged {
        }

        private final class MappedStep implements Step {
            private final int end;
            private int position;

            private MappedStep(int start) {
                this.position = start;
                this.end = start + halfBytes;
            }

            @Override
            public @Nullable Staged stage(ByteBuffer bytes) {
                int length = bytes.remaining();
                if (position + length > end) {
                    return null;
                }

                MemoryUtil.memCopy(bytes, MemoryUtil.memSlice(mapped, position, length));
                Region region = new Region(position, length);
                position += length;
                return region;
            }

            @Override
            public void copy(Staged staged, Buffer target, long offset) {
                Region region = (Region) staged;
                OpenGlStaging.copy(ring, region.offset(), (OpenGlBuffer) target, offset, region.bytes());
            }

            @Override
            public void close() {
                fences[half] = GL32C.glFenceSync(GL32C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
            }
        }
    }
}
