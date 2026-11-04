package com.eminus.client.gpu.opengl;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.eminus.gpu.Location;
import com.eminus.gpu.pipeline.PipelineSpec;

public final class GlslSource {
    public static final String VERTEX_INDEX_ALIAS = "#define gl_VertexIndex gl_VertexID";

    private static final Pattern INCLUDE = Pattern.compile("^\\s*#include\\s*<([^>]+)>\\s*$");
    private static final String VERSION = "#version";
    private static final String DEFINE = "#define ";
    private static final String LINE = "\n";
    private static final int MAX_INCLUDE_DEPTH = 8;

    private GlslSource() {
    }

    public static String compose(String source, List<PipelineSpec.Define> defines,
            Function<Location, Optional<String>> includes) {
        StringBuilder composed = new StringBuilder();
        boolean versionSeen = false;
        for (String line : source.split(LINE, -1)) {
            composed.append(expand(line, includes, 0)).append(LINE);
            if (!versionSeen && line.stripLeading().startsWith(VERSION)) {
                versionSeen = true;
                composed.append(VERTEX_INDEX_ALIAS).append(LINE);
                for (PipelineSpec.Define define : defines) {
                    composed.append(DEFINE).append(define.name());
                    if (define.value() != null) {
                        composed.append(' ').append(literal(define.value()));
                    }
                    composed.append(LINE);
                }
            }
        }

        composed.setLength(composed.length() - LINE.length());
        return composed.toString();
    }

    private static String expand(String line, Function<Location, Optional<String>> includes, int depth) {
        Matcher include = INCLUDE.matcher(line);
        if (!include.matches()) {
            return line;
        }

        if (depth >= MAX_INCLUDE_DEPTH) {
            throw new IllegalStateException("Include <" + include.group(1) + "> nests deeper than " + MAX_INCLUDE_DEPTH);
        }

        Location location = Location.parse(include.group(1));
        String body = includes.apply(location)
                .orElseThrow(() -> new IllegalStateException("Include <" + location + "> was not found"));
        StringBuilder expanded = new StringBuilder();
        for (String included : body.split(LINE, -1)) {
            expanded.append(expand(included, includes, depth + 1)).append(LINE);
        }
        expanded.setLength(expanded.length() - LINE.length());
        return expanded.toString();
    }

    private static String literal(Number value) {
        return switch (value) {
            case Integer integer -> Integer.toString(integer);
            case Float real -> String.format(Locale.ROOT, "%s", real);
            default -> throw new IllegalArgumentException("Define value " + value + " is neither int nor float");
        };
    }
}
