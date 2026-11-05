package com.eminus.render.far;

import com.eminus.settings.FarDistance;

public record CompositeFog(float gameFogStart, float gameFogEnd, float reach, float fogStart, float fogEnd,
        float fadeStart, float fadeEnd, boolean skip) {
    public static final float NONE = Float.MAX_VALUE;
    public static final float FADE_BAND_BLOCKS = FarDistance.BLOCKS_PER_TOP_LEVEL_CELL;

    public static CompositeFog of(boolean fog, boolean fade, float environmentalStart, float environmentalEnd,
            float nearBlocks, float reachBlocks, float horizontalReachBlocks, int farChunks) {
        float farBlocks = (float) farChunks * FarDistance.BLOCKS_PER_CHUNK;

        return new CompositeFog(
                fog ? environmentalStart : NONE,
                fog ? environmentalEnd : NONE,
                fog ? reachBlocks : NONE,
                fog ? stretchedStart(environmentalStart, environmentalEnd, reachBlocks, farBlocks) : NONE,
                fog ? stretchedEnd(environmentalEnd, reachBlocks, farBlocks) : NONE,
                fade ? bandStart(horizontalReachBlocks, farBlocks) : NONE,
                fade ? farBlocks : NONE,
                skipped(environmentalEnd, nearBlocks));
    }

    private static float bandStart(float horizontalReachBlocks, float farBlocks) {
        return Math.min(Math.max(farBlocks - FADE_BAND_BLOCKS, horizontalReachBlocks), farBlocks);
    }

    public static boolean skipped(float environmentalEnd, float nearBlocks) {
        return environmentalEnd <= nearBlocks;
    }

    // Mirrors far_composite.fsh.
    public float valueAt(float distance) {
        return distance <= reach
                ? linear(distance, gameFogStart, gameFogEnd)
                : linear(distance, fogStart, fogEnd);
    }

    private static float linear(float distance, float start, float end) {
        if (distance <= start) {
            return 0.0F;
        }
        if (distance >= end) {
            return 1.0F;
        }

        return (distance - start) / (end - start);
    }

    // The line through the game's fog value at the reach and full fog at the far render distance.
    private static float stretchedStart(float gameStart, float gameEnd, float reachBlocks, float farBlocks) {
        if (farBlocks <= reachBlocks || gameEnd <= reachBlocks) {
            return gameStart;
        }

        float atReach = Math.clamp((reachBlocks - gameStart) / (gameEnd - gameStart), 0.0F, 1.0F);
        return reachBlocks - atReach * (farBlocks - reachBlocks) / (1.0F - atReach);
    }

    private static float stretchedEnd(float gameEnd, float reachBlocks, float farBlocks) {
        return farBlocks <= reachBlocks || gameEnd <= reachBlocks ? gameEnd : farBlocks;
    }
}
