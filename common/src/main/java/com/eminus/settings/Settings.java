package com.eminus.settings;

import java.util.function.UnaryOperator;

public record Settings(
        boolean ingestion,
        LodChoice lodChoice,
        LodQuality customLod,
        int farRenderChunks,
        int workerThreads,
        DetailDistance detailDistance,
        boolean fog,
        boolean fade,
        ShaderPackLod shaderPackLod,
        boolean lodAnimations) {

    public static final boolean DEFAULT_INGESTION = true;
    public static final LodChoice DEFAULT_LOD_CHOICE = LodChoice.ULTRA;
    public static final LodQuality DEFAULT_CUSTOM_LOD = LodChoice.ULTRA.presetQuality().orElseThrow();
    public static final int DEFAULT_FAR_RENDER_CHUNKS = 512;
    public static final DetailDistance DEFAULT_DETAIL_DISTANCE = DetailDistance.MEDIUM;
    public static final boolean DEFAULT_FOG = true;
    public static final boolean DEFAULT_FADE = true;
    public static final ShaderPackLod DEFAULT_SHADER_PACK_LOD = ShaderPackLod.DEFAULT;
    public static final boolean DEFAULT_LOD_ANIMATIONS = true;

    public static final int MIN_FAR_RENDER_CHUNKS = 3;
    public static final int MAX_FAR_RENDER_CHUNKS = 2048;
    public static final int MIN_WORKER_THREADS = 1;
    public static final int MAX_WORKER_THREADS = 32;

    private static final double CORES_PER_WORKER = 1.5;
    private static final int MAX_DEFAULT_WORKER_THREADS = 8;

    public static Settings defaults() {
        return new Settings(
                DEFAULT_INGESTION,
                DEFAULT_LOD_CHOICE,
                DEFAULT_CUSTOM_LOD,
                DEFAULT_FAR_RENDER_CHUNKS,
                defaultWorkerThreads(Runtime.getRuntime().availableProcessors()),
                DEFAULT_DETAIL_DISTANCE,
                DEFAULT_FOG,
                DEFAULT_FADE,
                DEFAULT_SHADER_PACK_LOD,
                DEFAULT_LOD_ANIMATIONS);
    }

    public static int defaultWorkerThreads(int cores) {
        return Math.clamp((int) (cores / CORES_PER_WORKER), MIN_WORKER_THREADS, MAX_DEFAULT_WORKER_THREADS);
    }

    public LodQuality lodQuality() {
        return lodChoice.quality(customLod);
    }

    public Settings withIngestion(boolean value) {
        return new Settings(value, lodChoice, customLod, farRenderChunks, workerThreads, detailDistance, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withLodChoice(LodChoice value) {
        return new Settings(ingestion, value, customLod, farRenderChunks, workerThreads, detailDistance, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withCustomLod(LodQuality value) {
        return new Settings(ingestion, lodChoice, value, farRenderChunks, workerThreads, detailDistance, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withEditedLod(UnaryOperator<LodQuality> edit) {
        return withLodChoice(LodChoice.CUSTOM).withCustomLod(edit.apply(lodQuality()));
    }

    public Settings withFarRenderChunks(int value) {
        return new Settings(ingestion, lodChoice, customLod, value, workerThreads, detailDistance, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withWorkerThreads(int value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, value, detailDistance, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withDetailDistance(DetailDistance value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, workerThreads, value, fog, fade,
                shaderPackLod, lodAnimations);
    }

    public Settings withFog(boolean value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, workerThreads, detailDistance, value,
                fade, shaderPackLod, lodAnimations);
    }

    public Settings withFade(boolean value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, workerThreads, detailDistance, fog,
                value, shaderPackLod, lodAnimations);
    }

    public Settings withShaderPackLod(ShaderPackLod value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, workerThreads, detailDistance, fog,
                fade, value, lodAnimations);
    }

    public Settings withLodAnimations(boolean value) {
        return new Settings(ingestion, lodChoice, customLod, farRenderChunks, workerThreads, detailDistance, fog,
                fade, shaderPackLod, value);
    }
}
