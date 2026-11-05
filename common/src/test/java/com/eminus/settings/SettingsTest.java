package com.eminus.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SettingsTest {
    private static final LodQuality CUSTOM_EDIT = new LodQuality(3, LeafMode.OPAQUE, GrassAndFlowers.FULL, true, CornerOcclusion.EVERY_LEVEL);

    @Test
    void defaultsCarryEverySettingOfTheFirstVersion() {
        Settings defaults = Settings.defaults();

        assertTrue(defaults.ingestion());
        assertEquals(LodChoice.ULTRA, defaults.lodChoice());
        assertEquals(512, defaults.farRenderChunks());
        assertEquals(DetailDistance.MEDIUM, defaults.detailDistance());
        assertTrue(defaults.fog());
        assertTrue(defaults.fade());
        assertEquals(Settings.defaultWorkerThreads(Runtime.getRuntime().availableProcessors()),
                defaults.workerThreads());
    }

    @Test
    void workerThreadsAreTheCoreCountDividedByOneAndAHalf() {
        assertEquals(2, Settings.defaultWorkerThreads(3));
        assertEquals(5, Settings.defaultWorkerThreads(8));
        assertEquals(8, Settings.defaultWorkerThreads(12));
    }

    @Test
    void workerThreadsNeverDropBelowOne() {
        assertEquals(1, Settings.defaultWorkerThreads(1));
        assertEquals(1, Settings.defaultWorkerThreads(2));
    }

    @Test
    void workerThreadsNeverRiseAboveEight() {
        assertEquals(8, Settings.defaultWorkerThreads(16));
        assertEquals(8, Settings.defaultWorkerThreads(1024));
    }

    @Test
    void thePresetsAreReadUnderAPresetAndTheCustomSetUnderCustom() {
        LodQuality custom = new LodQuality(1, LeafMode.SEE_THROUGH, GrassAndFlowers.FULL, false, CornerOcclusion.EVERY_LEVEL);
        Settings settings = Settings.defaults().withCustomLod(custom);

        assertEquals(LodChoice.ULTRA.presetQuality().orElseThrow(), settings.lodQuality());
        assertEquals(custom, settings.withLodChoice(LodChoice.CUSTOM).lodQuality());
    }

    @Test
    void theCustomSetSurvivesATripThroughAPreset() {
        LodQuality custom = new LodQuality(1, LeafMode.SEE_THROUGH, GrassAndFlowers.FULL, false, CornerOcclusion.EVERY_LEVEL);
        Settings settings = Settings.defaults().withLodChoice(LodChoice.CUSTOM).withCustomLod(custom);

        assertEquals(custom, settings.withLodChoice(LodChoice.LOW).withLodChoice(LodChoice.CUSTOM).lodQuality());
    }

    @Test
    void eachWitherChangesItsOwnFieldAlone() {
        Settings base = new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, true);

        assertEquals(new Settings(false, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, true), base.withIngestion(false));
        assertEquals(new Settings(true, LodChoice.LOW, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, true), base.withLodChoice(LodChoice.LOW));
        assertEquals(new Settings(true, LodChoice.MEDIUM, CUSTOM_EDIT, 16, 4, DetailDistance.MEDIUM, true, true,
                ShaderPackLod.DEFAULT, true), base.withCustomLod(CUSTOM_EDIT));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 40, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, true), base.withFarRenderChunks(40));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 9, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, true), base.withWorkerThreads(9));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.LOW, true, true, ShaderPackLod.DEFAULT, true),
                base.withDetailDistance(DetailDistance.LOW));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, false, true, ShaderPackLod.DEFAULT, true), base.withFog(false));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, false, ShaderPackLod.DEFAULT, true), base.withFade(false));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DISTANT_HORIZONS,
                        true),
                base.withShaderPackLod(ShaderPackLod.DISTANT_HORIZONS));
        assertEquals(new Settings(true, LodChoice.MEDIUM, Settings.DEFAULT_CUSTOM_LOD, 16, 4, DetailDistance.MEDIUM, true, true, ShaderPackLod.DEFAULT, false),
                base.withLodAnimations(false));
    }
}
