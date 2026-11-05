package com.eminus.api.v1;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.eminus.box.BoxRegistry;
import com.eminus.client.render.far.FarRenderer;
import com.eminus.client.session.ClientSession;
import com.eminus.settings.FarDistance;
import com.eminus.settings.SettingsService;

import org.jspecify.annotations.Nullable;

/**
 * Entry point for reading the far layer on the client and handing it boxes to draw.
 */
public final class EminusApi {

    /**
     * Whether a far renderer runs for the dimension the client holds. Call on the render thread.
     */
    public static FarLayerStatus status() {
        if (ClientSession.renderer() != null) {
            return FarLayerStatus.RUNNING;
        }

        return ClientSession.refusal() == null ? FarLayerStatus.NO_LEVEL : FarLayerStatus.REFUSED;
    }

    /**
     * Why the far renderer was refused, as a sentence fit for a log line, or {@code null} unless {@link #status}
     * answers {@link FarLayerStatus#REFUSED}. Call on the render thread.
     */
    public static @Nullable String refusal() {
        return ClientSession.refusal();
    }

    /**
     * The LOD Distance the player set, in blocks of horizontal distance from the camera: the far layer draws
     * from the game's own render distance out to it. It answers the setting whether or not a far renderer runs, on
     * any thread, once mod loading has finished.
     */
    public static int farDistanceBlocks() {
        return FarDistance.chunksToBlocks(SettingsService.get().settings().farRenderChunks());
    }

    /**
     * The far layer's state for the dimension the client holds, or {@code null} unless {@link #status} answers
     * {@link FarLayerStatus#RUNNING}. Call on the render thread. Eminus keeps that state on a thread of its own, so the future
     * completes once that thread has taken the request, and completes exceptionally when the renderer stops first.
     */
    public static @Nullable CompletableFuture<FarLayerState> farLayer() {
        FarRenderer renderer = ClientSession.renderer();
        return renderer == null ? null
                : renderer.state().thenApply(state -> new FarLayerState(state.dimension(), state.settled()));
    }

    /**
     * Registers a group of boxes the far layer draws in the given dimension, at every distance from the camera out to
     * {@link #farDistanceBlocks}: fogged as the terrain around them is, faded with the far layer at its far end, and
     * hidden behind any terrain nearer than they are. Call on any thread, whether or not a far renderer runs; the
     * group is drawn from the next frame on, and lives until {@link FarBoxGroup#remove} or until the client leaves
     * the world. Boxes of one frame blend in the order their groups were registered. Under a shader pack that ships
     * the Eminus contract, or one drawn through its Distant Horizons programs, no group is drawn.
     *
     * @param dimension the dimension's identifier, such as {@code minecraft:overworld}
     * @throws NullPointerException when the dimension, the list or one of its boxes is {@code null}
     */
    public static FarBoxGroup registerBoxes(String dimension, List<FarBox> boxes) {
        return BoxRegistry.get().register(dimension, boxes);
    }

    private EminusApi() {
    }
}
