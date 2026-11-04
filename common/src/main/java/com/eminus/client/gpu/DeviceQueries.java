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
    public static final int QUAD_TEXTURE_SHIFT = 12;
    public static final int QUAD_TEXTURE_WIDTH = 1 << QUAD_TEXTURE_SHIFT;
    public static final long QUAD_TEXTURE_CAP_BYTES = 64L * 1024 * 1024;
    public static final String TEXTURE_LIMIT = "texture-size limit";
    public static final String TEXTURE_PROPERTY = "eminus.quadTexture";

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

    /**
     * The size of the 2D texture the geometry arena is read from, when it has to be: the device's texel buffer holds
     * less than the arena's minimum. Empty keeps the texel buffer. The property {@value #TEXTURE_PROPERTY} set to true
     * or false overrides the choice.
     */
    public static OptionalLong openGlQuadTextureBytes(OptionalLong texelElements, long minimumBytes, long quadBytes,
            int widthTexels, long capBytes) {
        String forced = System.getProperty(TEXTURE_PROPERTY);
        boolean texelTooSmall = texelElements.isPresent() && texelElements.getAsLong() * quadBytes < minimumBytes;
        boolean wanted = forced == null ? texelTooSmall : Boolean.parseBoolean(forced);
        if (!wanted) {
            return OptionalLong.empty();
        }

        return read(OPENGL, TEXTURE_LIMIT, () -> {
            int side = GL11C.glGetInteger(GL11C.GL_MAX_TEXTURE_SIZE);
            long bytes = Math.min((long) side * widthTexels * quadBytes, capBytes);
            Eminus.LOGGER.info("The geometry arena is read from a {} texel wide texture on {}: largest texture side {},"
                    + " {} MiB at most", widthTexels, OPENGL, side, bytes >> 20);
            return bytes >= minimumBytes ? OptionalLong.of(bytes) : OptionalLong.empty();
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
