package com.eminus.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SettingsFileTest {
    @TempDir
    Path configDir;

    @Test
    void theFirstLoadWritesTheFileWithEveryDefault() throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);

        assertEquals(Settings.defaults(), SettingsFile.load(file));
        assertTrue(Files.isRegularFile(file));
        assertEquals(Settings.defaults(), SettingsFile.load(file));
    }

    @Test
    void anEditedValueLoadsBack() {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        Settings edited = new Settings(false, LodChoice.LOW, new LodQuality(1, LeafMode.SEE_THROUGH, GrassAndFlowers.OFF, false, CornerOcclusion.EVERY_LEVEL), 24, 3, DetailDistance.HIGH, false, true,
                ShaderPackLod.DISTANT_HORIZONS, false);

        SettingsFile.save(file, edited);

        assertEquals(edited, SettingsFile.load(file));
    }

    @Test
    void theSavedFileCarriesTheSettingCommentsAndStillReadsBack() throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        Settings written = new Settings(true, LodChoice.HIGH, new LodQuality(1, LeafMode.SEE_THROUGH, GrassAndFlowers.OFF, false, CornerOcclusion.EVERY_LEVEL), 24, 3, DetailDistance.LOW, false, false, ShaderPackLod.DEFAULT,
                true);

        SettingsFile.save(file, written);
        String content = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(content.contains("// LOD quality: ultra, high, medium, low or custom."), content);
        assertTrue(content.contains("// Radius of LOD, in top-level cells"), content);
        assertTrue(content.contains("// Background worker threads."), content);
        assertTrue(content.contains("// How far out the finer detail levels reach"), content);
        assertTrue(content.contains("// Fog over LOD"), content);
        assertTrue(content.contains("// Whether LOD's outer edge fades out"), content);
        assertTrue(content.contains("// How LOD is drawn under an Iris shader pack"), content);
        assertTrue(content.contains("// Whether a shader pack that ships the Eminus contract may sway"), content);
        assertEquals(written, SettingsFile.load(file));
    }

    @Test
    void aMalformedValueFallsBackToItsDefault() throws IOException {
        Settings loaded = loadJson("""
                {
                  "ingestion": false,
                  "lod_quality": "sideways",
                  "far_render_chunks": "many",
                  "detail_distance": "sideways",
                  "fog": "sideways",
                  "fade": 3
                }
                """);

        assertFalse(loaded.ingestion());
        assertEquals(Settings.DEFAULT_LOD_CHOICE, loaded.lodChoice());
        assertEquals(Settings.DEFAULT_FAR_RENDER_CHUNKS, loaded.farRenderChunks());
        assertEquals(Settings.DEFAULT_DETAIL_DISTANCE, loaded.detailDistance());
        assertEquals(Settings.DEFAULT_FOG, loaded.fog());
        assertEquals(Settings.DEFAULT_FADE, loaded.fade());
    }

    @Test
    void anOutOfRangeValueFallsBackToItsDefault() throws IOException {
        Settings loaded = loadJson("""
                {
                  "lowest_stored_level": 9,
                  "worker_threads": 0
                }
                """);

        assertEquals(Settings.DEFAULT_LOD_CHOICE, loaded.lodChoice());
        assertEquals(Settings.defaults().workerThreads(), loaded.workerThreads());
    }

    @Test
    void aValueAboveItsMaximumFallsBackToItsDefault() throws IOException {
        Settings defaults = Settings.defaults();
        Settings loaded = loadJson("""
                {
                  "far_render_chunks": %d,
                  "worker_threads": %d
                }
                """.formatted(Settings.MAX_FAR_RENDER_CHUNKS + 1, Settings.MAX_WORKER_THREADS + 1));

        assertEquals(defaults.farRenderChunks(), loaded.farRenderChunks());
        assertEquals(defaults.workerThreads(), loaded.workerThreads());
    }

    @Test
    void aMissingValueFallsBackToItsDefault() throws IOException {
        Settings defaults = Settings.defaults();
        Settings loaded = loadJson("{\"detail_distance\": \"ultra\"}");

        assertEquals(DetailDistance.ULTRA, loaded.detailDistance());
        assertEquals(defaults.ingestion(), loaded.ingestion());
        assertEquals(defaults.lodChoice(), loaded.lodChoice());
        assertEquals(defaults.customLod(), loaded.customLod());
        assertEquals(defaults.farRenderChunks(), loaded.farRenderChunks());
        assertEquals(defaults.workerThreads(), loaded.workerThreads());
        assertEquals(defaults.fog(), loaded.fog());
        assertEquals(defaults.fade(), loaded.fade());
    }

    @Test
    void aKeyTheRecordNoLongerHasIsIgnored() throws IOException {
        Settings loaded = loadJson("""
                {
                  "enabled": true,
                  "ingestion": false,
                  "subdivision_size": 16,
                  "fog_mode": "fade"
                }
                """);

        assertFalse(loaded.ingestion());
        assertEquals(Settings.DEFAULT_DETAIL_DISTANCE, loaded.detailDistance());
        assertEquals(Settings.DEFAULT_FOG, loaded.fog());
    }

    @Test
    void aFormerStoredLevelOpensOnItsPreset() throws IOException {
        LodChoice[] expected = {LodChoice.ULTRA, LodChoice.MEDIUM, LodChoice.LOW, LodChoice.LOW, LodChoice.LOW};

        for (int level = 0; level < expected.length; level++) {
            assertEquals(expected[level], loadJson("{\"lowest_stored_level\": " + level + "}").lodChoice());
        }
    }

    @Test
    void aFormerStoredLevelIsRewrittenAsItsPreset() throws IOException {
        Settings loaded = loadJson("{\"lowest_stored_level\": 1, \"far_render_cells\": 24}");
        String content = Files.readString(configDir.resolve(SettingsService.FILE_NAME), StandardCharsets.UTF_8);

        assertTrue(content.contains("\"lod_quality\": \"medium\""), content);
        assertFalse(content.contains("lowest_stored_level"), content);
        assertEquals(loaded, SettingsFile.load(configDir.resolve(SettingsService.FILE_NAME)));
        assertEquals(24 * 32, loaded.farRenderChunks());
    }

    @Test
    void theFormerMinimalOpensAsLowAndIsRewritten() throws IOException {
        Settings loaded = loadJson("{\"lod_quality\": \"minimal\"}");
        String content = Files.readString(configDir.resolve(SettingsService.FILE_NAME), StandardCharsets.UTF_8);

        assertEquals(LodChoice.LOW, loaded.lodChoice());
        assertTrue(content.contains("\"lod_quality\": \"low\""), content);
        assertFalse(content.contains("minimal\""), content);
    }

    @Test
    void aPresetKeyWinsOverAFormerStoredLevel() throws IOException {
        Settings loaded = loadJson("{\"lod_quality\": \"high\", \"lowest_stored_level\": 3}");

        assertEquals(LodChoice.HIGH, loaded.lodChoice());
    }

    @Test
    void aCustomSetLoadsBackWithItsFiveDetails() throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        Settings written = Settings.defaults().withLodChoice(LodChoice.CUSTOM)
                .withCustomLod(new LodQuality(2, LeafMode.SEE_THROUGH, GrassAndFlowers.SIMPLIFIED, false,
                        CornerOcclusion.FINEST_LEVEL));

        SettingsFile.save(file, written);
        String content = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(content.contains("\"lod_quality\": \"custom\""), content);
        assertTrue(content.contains("\"lod_nearest_voxel\": 4"), content);
        assertTrue(content.contains("\"lod_see_through_leaves\": true"), content);
        assertTrue(content.contains("\"lod_grass_and_flowers\": \"simplified\""), content);
        assertTrue(content.contains("\"lod_textures\": \"half\""), content);
        assertTrue(content.contains("\"lod_corner_occlusion\": \"finest_level\""), content);
        assertEquals(written, SettingsFile.load(file));
    }

    @Test
    void everyCornerOcclusionValueLoadsBack() {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        for (CornerOcclusion occlusion : CornerOcclusion.values()) {
            Settings written = Settings.defaults().withLodChoice(LodChoice.CUSTOM)
                    .withCustomLod(Settings.DEFAULT_CUSTOM_LOD.withCornerOcclusion(occlusion));

            SettingsFile.save(file, written);

            assertEquals(occlusion, SettingsFile.load(file).lodQuality().cornerOcclusion(), occlusion.key());
        }
    }

    @Test
    void everyGrassAndFlowersValueLoadsBack() throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        for (GrassAndFlowers value : GrassAndFlowers.values()) {
            Settings written = Settings.defaults().withLodChoice(LodChoice.CUSTOM)
                    .withCustomLod(Settings.DEFAULT_CUSTOM_LOD.withGrassAndFlowers(value));

            SettingsFile.save(file, written);
            String content = Files.readString(file, StandardCharsets.UTF_8);

            assertTrue(content.contains("\"lod_grass_and_flowers\": \"" + value.key() + "\""), content);
            assertEquals(value, SettingsFile.load(file).lodQuality().grassAndFlowers(), value.key());
        }
    }

    @Test
    void theFormerGrassToggleOpensAsFullOrOffAndIsRewritten() throws IOException {
        Settings drawn = loadJson("{\"lod_quality\": \"custom\", \"lod_grass_and_flowers\": true}");
        String drawnContent = Files.readString(configDir.resolve(SettingsService.FILE_NAME), StandardCharsets.UTF_8);
        Settings left = loadJson("{\"lod_quality\": \"custom\", \"lod_grass_and_flowers\": false}");
        String leftContent = Files.readString(configDir.resolve(SettingsService.FILE_NAME), StandardCharsets.UTF_8);

        assertEquals(GrassAndFlowers.FULL, drawn.customLod().grassAndFlowers());
        assertTrue(drawnContent.contains("\"lod_grass_and_flowers\": \"full\""), drawnContent);
        assertEquals(GrassAndFlowers.OFF, left.customLod().grassAndFlowers());
        assertTrue(leftContent.contains("\"lod_grass_and_flowers\": \"off\""), leftContent);
    }

    @Test
    void aMalformedCornerOcclusionFallsBackToTheDefaultCustomValue() throws IOException {
        Settings loaded = loadJson("{\"lod_quality\": \"custom\", \"lod_corner_occlusion\": \"sometimes\"}");

        assertEquals(Settings.DEFAULT_CUSTOM_LOD.cornerOcclusion(), loaded.customLod().cornerOcclusion());
    }

    @Test
    void aPresetKeepsTheCustomSet() throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        Settings written = Settings.defaults().withLodChoice(LodChoice.MEDIUM)
                .withCustomLod(new LodQuality(3, LeafMode.SEE_THROUGH, GrassAndFlowers.OFF, true,
                        CornerOcclusion.EVERY_LEVEL));

        SettingsFile.save(file, written);
        String content = Files.readString(file, StandardCharsets.UTF_8);

        assertTrue(content.contains("\"lod_quality\": \"medium\""), content);
        assertTrue(content.contains("\"lod_nearest_voxel\": 8"), content);
        assertEquals(written, SettingsFile.load(file));
    }

    @Test
    void aCustomSetEqualToAPresetStaysCustom() throws IOException {
        Settings loaded = loadJson("""
                {
                  "lod_quality": "custom",
                  "lod_nearest_voxel": 1,
                  "lod_see_through_leaves": false,
                  "lod_grass_and_flowers": "simplified",
                  "lod_textures": "full"
                }
                """);

        assertEquals(LodChoice.CUSTOM, loaded.lodChoice());
        assertEquals(LodChoice.HIGH.presetQuality().orElseThrow(), loaded.lodQuality());
    }

    @Test
    void aMissingOrMalformedDetailFallsBackToTheDefaultCustomValue() throws IOException {
        LodQuality fallback = Settings.DEFAULT_CUSTOM_LOD;
        Settings loaded = loadJson("""
                {
                  "lod_quality": "custom",
                  "lod_nearest_voxel": 3,
                  "lod_see_through_leaves": false,
                  "lod_textures": "sideways"
                }
                """);

        assertEquals(new LodQuality(fallback.storedLevel(), LeafMode.OPAQUE, fallback.grassAndFlowers(), fallback.fullTextures(),
                fallback.cornerOcclusion()),
                loaded.customLod());
    }

    @Test
    void aFileWithoutTheCustomKeysTakesTheDefaultCustomSet() throws IOException {
        assertEquals(Settings.DEFAULT_CUSTOM_LOD, loadJson("{\"lod_quality\": \"low\"}").customLod());
    }

    @Test
    void aFileThatIsNotJsonFallsBackToEveryDefault() throws IOException {
        assertEquals(Settings.defaults(), loadJson("not json at all"));
    }

    private Settings loadJson(String content) throws IOException {
        Path file = configDir.resolve(SettingsService.FILE_NAME);
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return SettingsFile.load(file);
    }
}
