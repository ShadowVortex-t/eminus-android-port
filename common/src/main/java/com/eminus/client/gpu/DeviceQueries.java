package com.eminus.client.gpu;

import java.nio.IntBuffer;
import java.util.OptionalLong;
import java.util.function.Supplier;

import com.eminus.Eminus;

import org.lwjgl.opengl.ATIMeminfo;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.opengl.NVXGPUMemoryInfo;
import org.lwjgl.system.MemoryStack;

public final class DeviceQueries {
    public static final String OPENGL = "OpenGL";
    public static final String TEXEL_LIMIT = "texel-buffer limit";
    public static final String FREE_MEMORY = "free video memory";

    private static final long BYTES_PER_KIB = 1024L;
    private static final int ATI_MEMINFO_VALUES = 4;
    private static final int ATI_TOTAL_FREE = 0;

    private DeviceQueries() {
    }

    public static OptionalLong read(String backend, String name, Supplier<OptionalLong> query) {
        try {
            return query.get();
        } catch (RuntimeException | LinkageError refused) {
            refused(backend, name, refused);
            return OptionalLong.empty();
        }
    }

    public static void refused(String backend, String name, Throwable refused) {
        Eminus.LOGGER.warn("The {} was not read on {}: {}", name, backend, refused.toString());
    }

    public static OptionalLong openGlTexelElements() {
        return read(OPENGL, TEXEL_LIMIT, () -> {
            long elements = GL32C.glGetInteger64(GL31C.GL_MAX_TEXTURE_BUFFER_SIZE);
            if (elements <= 0) {
                // Translation layers such as MobileGlues answer 0 for a limit they do not forward. No device has a
                // zero-element texel buffer, so treat it as unread, the same as a failed query.
                Eminus.LOGGER.warn("The {} was reported as {} on {}: treating it as unread", TEXEL_LIMIT, elements,
                        OPENGL);
                return OptionalLong.empty();
            }

            Eminus.LOGGER.info("The {} on {} is {} elements", TEXEL_LIMIT, OPENGL, elements);
            return OptionalLong.of(elements);
        });
    }

    public static OptionalLong openGlFreeBytes() {
        return read(OPENGL, FREE_MEMORY, DeviceQueries::queryOpenGlFreeBytes);
    }

    private static OptionalLong queryOpenGlFreeBytes() {
        GLCapabilities capabilities = GL.getCapabilities();
        if (capabilities.GL_NVX_gpu_memory_info) {
            return OptionalLong.of(GL11C.glGetInteger(NVXGPUMemoryInfo.GL_GPU_MEMORY_INFO_CURRENT_AVAILABLE_VIDMEM_NVX)
                    * BYTES_PER_KIB);
        }

        if (capabilities.GL_ATI_meminfo) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                IntBuffer values = stack.mallocInt(ATI_MEMINFO_VALUES);
                GL11C.glGetIntegerv(ATIMeminfo.GL_VBO_FREE_MEMORY_ATI, values);
                return OptionalLong.of(Integer.toUnsignedLong(values.get(ATI_TOTAL_FREE)) * BYTES_PER_KIB);
            }
        }

        return OptionalLong.empty();
    }
}
