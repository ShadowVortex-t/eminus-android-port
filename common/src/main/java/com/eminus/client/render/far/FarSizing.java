package com.eminus.client.render.far;

import java.util.OptionalLong;

import com.eminus.client.mesh.MeshWiring;
import com.eminus.gpu.Gpu;
import com.eminus.gpu.Sparse;
import com.eminus.render.arena.ArenaSizing;
import com.eminus.render.tree.NodeTable;
import com.eminus.settings.FarDistance;
import com.eminus.settings.Settings;

import net.minecraft.client.Minecraft;

import org.jspecify.annotations.Nullable;

record FarSizing(long arenaBytes, @Nullable Sparse sparse, int nodeCapacity) {
    static FarSizing of(Minecraft client, Gpu gpu, OptionalLong freeBytes, long replacedArenaBytes, int lowestLevel,
            Settings settings, int levelHeight) {
        long ceiling = ArenaBudget.ceiling(gpu.capabilities(), freeBytes, replacedArenaBytes);
        float focal = FarProjection.focalPixels(client.options.fov().get(), gpu.mainColor().height());
        int chunks = FarDistance.effective(settings.farRenderChunks(), client.options.getEffectiveRenderDistance());
        int subdivisionPixels = settings.detailDistance().pixels();
        @Nullable Sparse sparse = ArenaBudget.sparse(gpu);
        long bytes = ArenaBudget.bytes(ArenaSizing.wanted(chunks, subdivisionPixels, focal,
                lowestLevel, MeshWiring.occlusion(settings.lodQuality().cornerOcclusion())), ceiling, sparse);

        return new FarSizing(bytes, sparse, NodeTable.capacity(lowestLevel, focal, subdivisionPixels,
                chunks, levelHeight));
    }
}
