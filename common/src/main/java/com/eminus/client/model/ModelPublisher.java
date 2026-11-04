package com.eminus.client.model;

import java.util.Arrays;

import com.eminus.Eminus;
import com.eminus.gpu.Gpu;
import com.eminus.model.BakedModel;
import com.eminus.model.ModelBakery;

public final class ModelPublisher implements AutoCloseable {
    public static final int START_ATLAS_CELLS_PER_SIDE = 4;
    public static final int START_RECORDS = 1024;
    public static final int START_VARIANTS = 256;

    private static final int GROWTH = 2;

    private final Gpu gpu;
    private final ModelBakery bakery;
    private ModelAtlas atlas;
    private ModelRecords records;
    private ModelVariants variants;
    private int recordCapacity = START_RECORDS;
    private int[] variantStarts = new int[START_RECORDS];
    private int published;
    private int variantEntries;
    private int growths;
    private int reuploaded;
    private boolean atlasFull;
    private boolean texelLimitWarned;

    private ModelPublisher(Gpu gpu, ModelBakery bakery, ModelAtlas atlas, ModelRecords records,
            ModelVariants variants) {
        this.gpu = gpu;
        this.bakery = bakery;
        this.atlas = atlas;
        this.records = records;
        this.variants = variants;
    }

    public static ModelPublisher start(Gpu gpu, ModelBakery bakery, int atlasFirstMip) {
        gpu.assertRenderThread();
        return new ModelPublisher(gpu, bakery, ModelAtlas.create(gpu, START_ATLAS_CELLS_PER_SIDE, atlasFirstMip),
                ModelRecords.create(gpu, START_RECORDS), ModelVariants.create(gpu, START_VARIANTS));
    }

    public ModelAtlas atlas() {
        return atlas;
    }

    public ModelRecords records() {
        return records;
    }

    public ModelVariants variants() {
        return variants;
    }

    public int published() {
        return published;
    }

    public int growths() {
        return growths;
    }

    public int reuploaded() {
        return reuploaded;
    }

    public void publish() {
        gpu.assertRenderThread();
        int baked = bakery.modelCount();
        if (baked > recordCapacity) {
            growRecords(baked);
        }

        while (published < baked && fitAtlas(published)) {
            publish(bakery.model(published));
            published++;
        }
    }

    private void publish(BakedModel model) {
        if (variantEntries + model.variantCount() > variants.capacity()) {
            growVariants(variantEntries + model.variantCount());
        }

        atlas.upload(published, model);
        variantStarts[published] = variantEntries;
        records.write(published, model, variantEntries);
        if (model.variantCount() > 0) {
            variants.write(variantEntries, model.variants());
            variantEntries += model.variantCount();
        }
    }

    public void retexture(int atlasFirstMip) {
        gpu.assertRenderThread();
        atlas.close();
        atlas = ModelAtlas.create(gpu, START_ATLAS_CELLS_PER_SIDE, atlasFirstMip);
        atlasFull = false;
        published = 0;
        variantEntries = 0;
        publish();
    }

    @Override
    public void close() {
        variants.close();
        records.close();
        atlas.close();
    }

    private boolean fitAtlas(int modelId) {
        while (!atlas.fits(modelId)) {
            int moved = atlas.grow(bakery);
            if (moved == 0) {
                if (!atlasFull) {
                    Eminus.LOGGER.error("The model atlas is full at {} models; the rest stay unpublished", published);
                    atlasFull = true;
                }

                return false;
            }

            growths++;
            reuploaded += moved;
        }

        return true;
    }

    private void growRecords(int wanted) {
        int grown = grownCapacity(recordCapacity, wanted);
        records.close();
        records = ModelRecords.create(gpu, grown);
        recordCapacity = grown;
        warnPastTexelLimit((long) grown * ModelRecords.TEXELS);
        variantStarts = Arrays.copyOf(variantStarts, grown);
        rewriteRecords();
    }

    private void warnPastTexelLimit(long texels) {
        java.util.OptionalLong limit = gpu.capabilities().texelElements();
        if (!texelLimitWarned && limit.isPresent() && texels > limit.getAsLong()) {
            texelLimitWarned = true;
            Eminus.LOGGER.warn("The model records need {} texels but a texel buffer here holds {}: models past that"
                    + " draw wrong", texels, limit.getAsLong());
        }
    }

    private void rewriteRecords() {
        for (int modelId = 0; modelId < published; modelId++) {
            records.write(modelId, bakery.model(modelId), variantStarts[modelId]);
        }
    }

    private void growVariants(int wanted) {
        int grown = grownCapacity(variants.capacity(), wanted);
        variants.close();
        variants = ModelVariants.create(gpu, grown);
        rewriteVariants();
    }

    private static int grownCapacity(int capacity, int wanted) {
        int grown = capacity;
        while (grown < wanted) {
            grown *= GROWTH;
        }

        return grown;
    }

    private void rewriteVariants() {
        for (int modelId = 0; modelId < published; modelId++) {
            BakedModel model = bakery.model(modelId);
            if (model.variantCount() > 0) {
                variants.write(variantStarts[modelId], model.variants());
            }
        }
    }
}
