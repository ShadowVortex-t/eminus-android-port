package com.eminus.settings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import com.eminus.Eminus;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import org.jspecify.annotations.Nullable;

public final class SettingsFile {
    public static final String INGESTION_KEY = "ingestion";
    public static final String LOD_QUALITY_KEY = "lod_quality";
    public static final String LOD_NEAREST_VOXEL_KEY = "lod_nearest_voxel";
    public static final String LOD_SEE_THROUGH_LEAVES_KEY = "lod_see_through_leaves";
    public static final String LOD_GRASS_AND_FLOWERS_KEY = "lod_grass_and_flowers";
    public static final String LOD_TEXTURES_KEY = "lod_textures";
    public static final String LOD_CORNER_OCCLUSION_KEY = "lod_corner_occlusion";
    public static final String LOWEST_STORED_LEVEL_KEY = "lowest_stored_level";
    public static final String FAR_RENDER_CHUNKS_KEY = "far_render_chunks";
    private static final String FORMER_FAR_RENDER_CELLS_KEY = "far_render_cells";
    public static final String WORKER_THREADS_KEY = "worker_threads";
    public static final String DETAIL_DISTANCE_KEY = "detail_distance";
    public static final String FOG_KEY = "fog";
    public static final String FADE_KEY = "fade";
    public static final String SHADER_PACK_LOD_KEY = "shader_pack_lod";
    public static final String LOD_ANIMATIONS_KEY = "lod_animations";

    private static final String LOD_QUALITY_COMMENT = """
            LOD quality: ultra, high, medium, low or custom. Every preset starts LOD at
            1 block per voxel and differs by its details alone: ultra draws see-through
            leaves and full grass and flowers; high draws leaves opaque and grass and
            flowers simplified; medium is high with textures at half resolution; low is
            medium with no grass, flowers or corner darkening. custom takes the five
            lod_ keys below it, which the Custom LOD Quality screen sets.""";

    private static final String LOD_CUSTOM_COMMENT = """
            Act under custom alone, and stay under a preset so custom comes back as it
            was. lod_nearest_voxel: the blocks one voxel covers at LOD's nearest edge,
            1, 2, 4 or 8. lod_see_through_leaves: whether leaves keep their holes,
            whatever the game's See-Through Leaves says. lod_grass_and_flowers: full
            draws grass, ferns, flowers and saplings with petals at their own heights,
            simplified draws each of them as one plane instead of two and petals flat at
            one height, off draws none of them.
            lod_textures: full keeps 16 by 16 texels per face, half keeps 8 by 8.
            lod_corner_occlusion: where the game's
            Smooth Lighting darkens the corners blocks meet in, every_level darkens them
            at every LOD level, finest_level at the finest level alone, so a brightness
            step shows where it meets the next level, and off at none.""";

    private static final String FAR_RENDER_CHUNKS_COMMENT = """
            Radius of LOD, in chunks. 512 chunks reach 8192 blocks. The game raises
            this to at least your render distance plus 2. Work grows with the square of this
            number — more roots in the ring, more nodes walked per frame, more meshes in
            video memory.""";

    private static final String WORKER_THREADS_COMMENT = """
            Background worker threads. Written once from this machine as cores / 1.5,
            at least 1 and at most 8, so this number is yours rather than a universal
            default, and a config copied to another machine keeps it. Higher catches
            LOD up faster and competes harder with the game's own chunk
            builders; lower is the other way round.""";

    private static final String DETAIL_DISTANCE_COMMENT = """
            How far out the finer detail levels reach: ultra, high, medium, low or minimal.
            Each step up doubles the distance at which every level hands over to the next
            and costs more meshes in video memory. A node is replaced by its eight finer
            children once it looks larger on screen than 16, 32, 64, 128 or 256 pixels,
            so a higher resolution reaches further at the same step.""";

    private static final String FOG_COMMENT = """
            Fog over LOD, carried on from the game's own fog at the edge of
            the loaded chunks and full at the LOD distance. false also clears the
            game's open-air fog from the loaded chunks, so the two meet without a step.
            Fog that ends inside the loaded chunks (the Nether, a boss, blindness) and
            fog in water, lava or powder snow stay either way.""";

    private static final String FADE_COMMENT = """
            Whether LOD's outer edge fades out over its last 512 blocks, or
            ends in a hard line.""";

    private static final String SHADER_PACK_LOD_COMMENT = """
            How LOD is drawn under an Iris shader pack that does not ship the Eminus
            contract: default draws it over the pack's finished image in Eminus's own
            shading; distant_horizons lets a pack with Distant Horizons programs draw it,
            with the pack's light and fog but one flat color per block. A pack that
            ships the contract always draws LOD itself.""";

    private static final String LOD_ANIMATIONS_COMMENT = """
            Whether a shader pack that ships the Eminus contract may sway plants, leaves
            and water in LOD as it does nearby. false hands the pack's vertex code no
            block id, so the pack moves nothing it would pick by block, and saves the GPU
            time that costs on every LOD vertex.""";

    private static final String TEXTURES_FULL = "full";
    private static final String TEXTURES_HALF = "half";
    private static final int FORMER_STORED_LEVEL_MIN = 0;
    private static final int FORMER_STORED_LEVEL_MAX = 4;
    private static final String FORMER_MINIMAL_KEY = "minimal";

    private static final Gson GSON = new Gson();
    private static final String INDENT = "  ";

    public static Settings load(Path file) {
        Settings defaults = Settings.defaults();
        if (!Files.isRegularFile(file)) {
            save(file, defaults);
            return defaults;
        }

        JsonObject json = read(file);
        if (json == null) {
            return defaults;
        }

        Settings loaded = new Settings(
                bool(json, INGESTION_KEY, defaults.ingestion()),
                lodChoice(json, defaults.lodChoice()),
                customLod(json, defaults.customLod()),
                farRenderChunks(json, defaults.farRenderChunks()),
                bounded(json, WORKER_THREADS_KEY, defaults.workerThreads(),
                        Settings.MIN_WORKER_THREADS, Settings.MAX_WORKER_THREADS),
                keyed(json, DETAIL_DISTANCE_KEY, defaults.detailDistance(), DetailDistance::fromKey),
                bool(json, FOG_KEY, defaults.fog()),
                bool(json, FADE_KEY, defaults.fade()),
                keyed(json, SHADER_PACK_LOD_KEY, defaults.shaderPackLod(), ShaderPackLod::fromKey),
                bool(json, LOD_ANIMATIONS_KEY, defaults.lodAnimations()));
        if (holdsFormerFarCells(json) || holdsStoredLevelOnly(json) || holdsFormerMinimal(json) || holdsFormerGrassToggle(json)) {
            save(file, loaded);
        }

        return loaded;
    }

    public static void save(Path file, Settings settings) {
        write(file, entries(settings));
    }

    private static List<String> entries(Settings settings) {
        List<String> entries = new ArrayList<>();
        entries.add(entry(INGESTION_KEY, new JsonPrimitive(settings.ingestion()), null));
        LodQuality custom = settings.customLod();
        entries.add(entry(LOD_QUALITY_KEY, new JsonPrimitive(settings.lodChoice().key()), LOD_QUALITY_COMMENT));
        entries.add(entry(LOD_NEAREST_VOXEL_KEY, new JsonPrimitive(LodQuality.voxelBlocks(custom.storedLevel())),
                LOD_CUSTOM_COMMENT));
        entries.add(entry(LOD_SEE_THROUGH_LEAVES_KEY, new JsonPrimitive(custom.leaves() == LeafMode.SEE_THROUGH),
                null));
        entries.add(entry(LOD_GRASS_AND_FLOWERS_KEY, new JsonPrimitive(custom.grassAndFlowers().key()), null));
        entries.add(entry(LOD_TEXTURES_KEY,
                new JsonPrimitive(custom.fullTextures() ? TEXTURES_FULL : TEXTURES_HALF), null));
        entries.add(entry(LOD_CORNER_OCCLUSION_KEY, new JsonPrimitive(custom.cornerOcclusion().key()), null));

        entries.add(entry(FAR_RENDER_CHUNKS_KEY, new JsonPrimitive(settings.farRenderChunks()),
                FAR_RENDER_CHUNKS_COMMENT));
        entries.add(entry(WORKER_THREADS_KEY, new JsonPrimitive(settings.workerThreads()),
                WORKER_THREADS_COMMENT));
        entries.add(entry(DETAIL_DISTANCE_KEY, new JsonPrimitive(settings.detailDistance().key()),
                DETAIL_DISTANCE_COMMENT));
        entries.add(entry(FOG_KEY, new JsonPrimitive(settings.fog()), FOG_COMMENT));
        entries.add(entry(FADE_KEY, new JsonPrimitive(settings.fade()), FADE_COMMENT));
        entries.add(entry(SHADER_PACK_LOD_KEY, new JsonPrimitive(settings.shaderPackLod().key()),
                SHADER_PACK_LOD_COMMENT));
        entries.add(entry(LOD_ANIMATIONS_KEY, new JsonPrimitive(settings.lodAnimations()), LOD_ANIMATIONS_COMMENT));
        return entries;
    }

    private static void write(Path file, List<String> entries) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(file, "{\n" + String.join(",\n", entries) + "\n}\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            Eminus.LOGGER.error("Could not write the settings file {}", file, e);
        }
    }

    private static String entry(String key, JsonPrimitive value, @Nullable String comment) {
        StringBuilder line = new StringBuilder();
        if (comment != null) {
            comment.lines().forEach(text -> line.append(INDENT).append("// ").append(text).append('\n'));
        }

        return line.append(INDENT)
                .append(GSON.toJson(new JsonPrimitive(key)))
                .append(": ")
                .append(GSON.toJson(value))
                .toString();
    }

    private static @Nullable JsonObject read(Path file) {
        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (parsed.isJsonObject()) {
                return parsed.getAsJsonObject();
            }

            Eminus.LOGGER.warn("The settings file {} is not a JSON object; every setting falls back to its default", file);
        } catch (IOException | RuntimeException e) {
            Eminus.LOGGER.warn("Could not read the settings file {}; every setting falls back to its default", file, e);
        }

        return null;
    }

    private static boolean holdsFormerFarCells(JsonObject json) {
        return !json.has(FAR_RENDER_CHUNKS_KEY) && json.has(FORMER_FAR_RENDER_CELLS_KEY);
    }

    private static int farRenderChunks(JsonObject json, int fallback) {
        if (holdsFormerFarCells(json)) {
            int cells = bounded(json, FORMER_FAR_RENDER_CELLS_KEY, FarDistance.chunksToCells(fallback), 1, 64);
            return Math.clamp(FarDistance.cellsToChunks(cells), Settings.MIN_FAR_RENDER_CHUNKS,
                    Settings.MAX_FAR_RENDER_CHUNKS);
        }

        return bounded(json, FAR_RENDER_CHUNKS_KEY, fallback, Settings.MIN_FAR_RENDER_CHUNKS,
                Settings.MAX_FAR_RENDER_CHUNKS);
    }

    private static LodChoice lodChoice(JsonObject json, LodChoice fallback) {
        if (holdsStoredLevelOnly(json)) {
            int fallbackLevel = fallback.presetQuality().map(LodQuality::storedLevel)
                    .orElse(FORMER_STORED_LEVEL_MIN);
            return LodChoice.fromLowestStoredLevel(bounded(json, LOWEST_STORED_LEVEL_KEY, fallbackLevel,
                    FORMER_STORED_LEVEL_MIN, FORMER_STORED_LEVEL_MAX));
        }

        if (holdsFormerMinimal(json)) {
            return LodChoice.LOW;
        }

        return keyed(json, LOD_QUALITY_KEY, fallback, LodChoice::fromKey);
    }

    private static LodQuality customLod(JsonObject json, LodQuality fallback) {
        if (!holdsCustomLod(json)) {
            return fallback;
        }

        return new LodQuality(
                nearestVoxelLevel(json, fallback.storedLevel()),
                LeafMode.of(bool(json, LOD_SEE_THROUGH_LEAVES_KEY, fallback.leaves() == LeafMode.SEE_THROUGH)),
                grassAndFlowers(json, fallback.grassAndFlowers()),
                keyed(json, LOD_TEXTURES_KEY, fallback.fullTextures(),
                        key -> named(key, TEXTURES_FULL, TEXTURES_HALF)),
                keyed(json, LOD_CORNER_OCCLUSION_KEY, fallback.cornerOcclusion(), CornerOcclusion::fromKey));
    }

    private static boolean holdsStoredLevelOnly(JsonObject json) {
        return !json.has(LOD_QUALITY_KEY) && json.has(LOWEST_STORED_LEVEL_KEY);
    }

    private static boolean holdsFormerMinimal(JsonObject json) {
        JsonElement value = json.get(LOD_QUALITY_KEY);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
                && FORMER_MINIMAL_KEY.equals(value.getAsString());
    }

    private static GrassAndFlowers grassAndFlowers(JsonObject json, GrassAndFlowers fallback) {
        if (holdsFormerGrassToggle(json)) {
            return GrassAndFlowers.of(json.get(LOD_GRASS_AND_FLOWERS_KEY).getAsBoolean());
        }

        return keyed(json, LOD_GRASS_AND_FLOWERS_KEY, fallback, GrassAndFlowers::fromKey);
    }

    private static boolean holdsFormerGrassToggle(JsonObject json) {
        JsonElement value = json.get(LOD_GRASS_AND_FLOWERS_KEY);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean();
    }

    private static boolean holdsCustomLod(JsonObject json) {
        return json.has(LOD_NEAREST_VOXEL_KEY) || json.has(LOD_SEE_THROUGH_LEAVES_KEY)
                || json.has(LOD_GRASS_AND_FLOWERS_KEY) || json.has(LOD_TEXTURES_KEY)
                || json.has(LOD_CORNER_OCCLUSION_KEY);
    }

    private static int nearestVoxelLevel(JsonObject json, int fallbackLevel) {
        int fallback = LodQuality.voxelBlocks(fallbackLevel);
        int blocks = bounded(json, LOD_NEAREST_VOXEL_KEY, fallback, LodQuality.voxelBlocks(LodQuality.MIN_STORED_LEVEL),
                LodQuality.voxelBlocks(LodQuality.MAX_STORED_LEVEL));
        if (Integer.bitCount(blocks) != 1) {
            blocks = fellBack(LOD_NEAREST_VOXEL_KEY, json.get(LOD_NEAREST_VOXEL_KEY), fallback);
        }

        return Integer.numberOfTrailingZeros(blocks);
    }

    private static Optional<Boolean> named(String key, String whenTrue, String whenFalse) {
        if (whenTrue.equals(key)) {
            return Optional.of(true);
        }

        return whenFalse.equals(key) ? Optional.of(false) : Optional.empty();
    }

    private static boolean bool(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()) {
            return value.getAsBoolean();
        }

        return fellBack(key, value, fallback);
    }

    private static int bounded(JsonObject json, String key, int fallback, int min, int max) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
            int number = value.getAsInt();
            if (number >= min && number <= max) {
                return number;
            }
        }

        return fellBack(key, value, fallback);
    }

    private static <T> T keyed(JsonObject json, String key, T fallback, Function<String, Optional<T>> fromKey) {
        JsonElement value = json.get(key);
        if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            Optional<T> parsed = fromKey.apply(value.getAsString());
            if (parsed.isPresent()) {
                return parsed.get();
            }
        }

        return fellBack(key, value, fallback);
    }

    private static <T> T fellBack(String key, JsonElement value, T fallback) {
        Eminus.LOGGER.warn("Setting {} is {}; falling back to {}", key, value == null ? "missing" : value, fallback);
        return fallback;
    }

    private SettingsFile() {
    }
}
