package com.eminus.client.gpu.opengl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

import com.eminus.Eminus;
import com.eminus.gpu.Format;
import com.eminus.gpu.Location;
import com.eminus.gpu.ShaderSources;
import com.eminus.gpu.pipeline.Binding;
import com.eminus.gpu.pipeline.Pipeline;
import com.eminus.gpu.pipeline.PipelineSpec;

import org.jspecify.annotations.Nullable;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL31C;

final class OpenGlPipeline implements Pipeline {
    private static final String SHADER_FOLDER = "shaders/";
    private static final String INCLUDE_FOLDER = "shaders/include/";
    private static final String VERTEX_EXTENSION = ".vsh";
    private static final String FRAGMENT_EXTENSION = ".fsh";
    private static final int NO_PROGRAM = 0;
    private static final int NO_SHADER = 0;
    private static final int NOT_ACTIVE = -1;
    private static final int FIRST_UNIT = 0;
    private static final int LOG_LENGTH = 32768;
    private static final String INACTIVE = "inactive";

    record Slot(Binding.Kind kind, int index, @Nullable Format format) {
        void requireFormat(String name, Format viewFormat) {
            if (viewFormat != format) {
                throw new IllegalArgumentException("Binding " + name + " declares " + format + " but its view is "
                        + viewFormat);
            }
        }
    }

    private final PipelineSpec spec;
    private final int program;
    private final Map<String, @Nullable Slot> slots;
    private final int textureUnits;

    private OpenGlPipeline(PipelineSpec spec, int program, Map<String, @Nullable Slot> slots, int textureUnits) {
        this.spec = spec;
        this.program = program;
        this.slots = slots;
        this.textureUnits = textureUnits;
    }

    static OpenGlPipeline of(OpenGlObjects objects, PipelineSpec spec) {
        return of(objects, spec, FIRST_UNIT, UnaryOperator.identity());
    }

    static OpenGlPipeline of(OpenGlObjects objects, PipelineSpec spec, int firstUnit,
            UnaryOperator<ShaderSources> finish) {
        requireTextureUnits(spec, firstUnit);
        String name = spec.location().toString();
        ShaderSources sources = sources(spec, finish);
        int vertex = sources == null ? NO_SHADER
                : compile(spec, spec.vertexShader(), VERTEX_EXTENSION, GL20C.GL_VERTEX_SHADER, sources.vertex());
        int fragment = sources == null ? NO_SHADER
                : compile(spec, spec.fragmentShader(), FRAGMENT_EXTENSION, GL20C.GL_FRAGMENT_SHADER,
                        sources.fragment());
        if (vertex == NO_SHADER || fragment == NO_SHADER) {
            GL20C.glDeleteShader(vertex);
            GL20C.glDeleteShader(fragment);
            return new OpenGlPipeline(spec, NO_PROGRAM, Map.of(), firstUnit);
        }

        int program = link(name, vertex, fragment);
        if (program == NO_PROGRAM) {
            return new OpenGlPipeline(spec, NO_PROGRAM, Map.of(), firstUnit);
        }

        objects.created(OpenGlObjects.Kind.PROGRAM, program, name);
        Map<String, @Nullable Slot> slots = new HashMap<>();
        int textureUnits = resolve(program, spec, slots, firstUnit);
        return new OpenGlPipeline(spec, program, slots, textureUnits);
    }

    private static int link(String name, int vertex, int fragment) {
        int program = GL20C.glCreateProgram();
        GL20C.glAttachShader(program, vertex);
        GL20C.glAttachShader(program, fragment);
        GL20C.glLinkProgram(program);
        GL20C.glDetachShader(program, vertex);
        GL20C.glDetachShader(program, fragment);
        GL20C.glDeleteShader(vertex);
        GL20C.glDeleteShader(fragment);
        if (GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == GL11C.GL_FALSE) {
            Eminus.LOGGER.error("Program {} did not link: {}", name, GL20C.glGetProgramInfoLog(program, LOG_LENGTH).strip());
            GL20C.glDeleteProgram(program);
            return NO_PROGRAM;
        }

        return program;
    }

    static void requireTextureUnits(PipelineSpec spec, int firstUnit) {
        long units = firstUnit + spec.bindings().stream()
                .filter(binding -> binding.kind() == Binding.Kind.TEXEL || binding.kind() == Binding.Kind.SAMPLED)
                .count();
        if (units > GameHandles.GAME_TRACKED_TEXTURE_UNITS) {
            throw new IllegalArgumentException("Pipeline " + spec.location() + " declares texture bindings up to unit "
                    + units + ", more than the " + GameHandles.GAME_TRACKED_TEXTURE_UNITS + " units the game tracks");
        }
    }

    private static @Nullable ShaderSources sources(PipelineSpec spec, UnaryOperator<ShaderSources> finish) {
        try {
            return finish.apply(new ShaderSources(compose(spec, spec.vertexShader(), VERTEX_EXTENSION),
                    compose(spec, spec.fragmentShader(), FRAGMENT_EXTENSION)));
        } catch (IOException | RuntimeException refused) {
            Eminus.LOGGER.error("Shaders of {} could not be read: {}", spec.location(), refused.toString());
            return null;
        }
    }

    private static String compose(PipelineSpec spec, Location shader, String extension) throws IOException {
        return GlslSource.compose(read(shader.withPrefix(SHADER_FOLDER).withSuffix(extension)), spec.defines(),
                OpenGlPipeline::includeSource);
    }

    private static int compile(PipelineSpec spec, Location shader, String extension, int type, String source) {
        String name = spec.location() + " " + shader + extension;
        int id = GL20C.glCreateShader(type);
        GL20C.glShaderSource(id, source);
        GL20C.glCompileShader(id);
        if (GL20C.glGetShaderi(id, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
            Eminus.LOGGER.error("Shader {} did not compile: {}", name, GL20C.glGetShaderInfoLog(id, LOG_LENGTH).strip());
            GL20C.glDeleteShader(id);
            return NO_SHADER;
        }

        return id;
    }

    private static Optional<String> includeSource(Location include) {
        try {
            return GameHandles.resource(include.withPrefix(INCLUDE_FOLDER));
        } catch (IOException unreadable) {
            return Optional.empty();
        }
    }

    private static String read(Location file) throws IOException {
        return GameHandles.resource(file).orElseThrow(() -> new IOException("No resource " + file));
    }

    private static int resolve(int program, PipelineSpec spec, Map<String, @Nullable Slot> slots, int firstUnit) {
        int nextBlock = 0;
        int nextUnit = firstUnit;
        GL20C.glUseProgram(program);
        for (Binding binding : spec.bindings()) {
            switch (binding.kind()) {
                case UNIFORM -> {
                    int index = GL31C.glGetUniformBlockIndex(program, binding.name());
                    if (index == GL31C.GL_INVALID_INDEX) {
                        slots.put(binding.name(), null);
                    } else {
                        GL31C.glUniformBlockBinding(program, index, nextBlock);
                        slots.put(binding.name(), new Slot(binding.kind(), nextBlock++, binding.format()));
                    }
                }
                case TEXEL, SAMPLED -> {
                    int location = GL20C.glGetUniformLocation(program, binding.name());
                    if (location == NOT_ACTIVE) {
                        slots.put(binding.name(), null);
                    } else {
                        GL20C.glUniform1i(location, nextUnit);
                        slots.put(binding.name(), new Slot(binding.kind(), nextUnit++, binding.format()));
                    }
                }
                case STORAGE -> throw new IllegalArgumentException("Own OpenGL binds no storage buffers");
            }
        }
        GL20C.glUseProgram(NO_PROGRAM);
        logResolved(spec, slots, nextBlock, nextUnit);
        return nextUnit;
    }

    private static void logResolved(PipelineSpec spec, Map<String, @Nullable Slot> slots, int blocks, int units) {
        StringBuilder resolved = new StringBuilder();
        for (Map.Entry<String, @Nullable Slot> slot : slots.entrySet()) {
            resolved.append(' ').append(slot.getKey()).append(':')
                    .append(slot.getValue() == null ? INACTIVE : slot.getValue().kind() + "" + slot.getValue().index());
        }
        Eminus.LOGGER.info("[eminus/gl] program name={} blocks={} units={} text={}", spec.location(),
                blocks, units, resolved.toString().strip());
    }

    PipelineSpec spec() {
        return spec;
    }

    int textureUnits() {
        return textureUnits;
    }

    Map<String, @Nullable Slot> slots() {
        return slots;
    }

    int program() {
        if (program == NO_PROGRAM) {
            throw new IllegalStateException("Pipeline " + spec.location() + " did not compile");
        }
        return program;
    }

    @Nullable Slot slot(String name) {
        if (!slots.containsKey(name)) {
            throw new IllegalArgumentException("Pipeline " + spec.location() + " declares no binding " + name);
        }
        return slots.get(name);
    }

    @Override
    public Location location() {
        return spec.location();
    }

    @Override
    public boolean compiles() {
        return program != NO_PROGRAM;
    }

    void delete(OpenGlObjects objects) {
        if (program != NO_PROGRAM) {
            GL20C.glDeleteProgram(program);
            objects.deleted(OpenGlObjects.Kind.PROGRAM);
        }
    }
}
