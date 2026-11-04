package com.eminus.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import com.eminus.Eminus;
import com.eminus.beam.BeamSet;
import com.eminus.cell.CellFrame;
import com.eminus.cell.ColumnCoverage;
import com.eminus.cell.Dictionary;
import com.eminus.cell.StateTable;
import com.eminus.cell.cache.CellCache;
import com.eminus.cell.cache.CellHandle;
import com.eminus.ingest.CellChangeListener;
import com.eminus.ingest.CellMerger;
import com.eminus.ingest.IngestService;
import com.eminus.ingest.SectionConverter;
import com.eminus.ingest.SectionPyramid;
import com.eminus.store.CellStore;
import com.eminus.store.EmptyCellStore;
import com.eminus.store.SaveService;
import com.eminus.store.CellStores;
import com.eminus.work.WorkService;

import com.uber.nullaway.annotations.Initializer;

public final class DimensionRuntime {
    private static final String DROP_GRACE_PROPERTY = "eminus.store.dropGraceHours";
    private static final int DROP_GRACE_HOURS = 2;

    private final WorldIdentity identity;
    private final Path folder;
    private final CellFrame frame;
    private final int lowestStoredLevel;

    private volatile CellChangeListener changes;
    private CellStore store;
    private int ingestLevel;
    private SaveService saves;
    private CellCache cells;
    private ColumnCoverage coverage;
    private StateTable states;
    private Dictionary<String> biomes;
    private CellMerger merger;
    private IngestService ingest;
    private BeamSet beams;
    private int references;
    private long idleSince;
    private boolean closed;

    DimensionRuntime(WorldIdentity identity, Path folder, CellFrame frame, int lowestStoredLevel) {
        this.identity = identity;
        this.folder = folder;
        this.frame = frame;
        this.lowestStoredLevel = lowestStoredLevel;
    }

    public WorldIdentity identity() {
        return identity;
    }

    public Path folder() {
        return folder;
    }

    public CellFrame frame() {
        return frame;
    }

    public int lowestStoredLevel() {
        return lowestStoredLevel;
    }

    public CellCache cells() {
        return cells;
    }

    public ColumnCoverage coverage() {
        return coverage;
    }

    public StateTable states() {
        return states;
    }

    public Dictionary<String> biomes() {
        return biomes;
    }

    public CellMerger merger() {
        return merger;
    }

    public IngestService ingest() {
        return ingest;
    }

    public BeamSet beams() {
        return beams;
    }

    public boolean closed() {
        return closed;
    }

    // A listener that takes the handle replaces the one that releases it, so a cell reaches exactly one owner.
    public void listenTo(CellChangeListener listener) {
        changes = listener;
    }

    public void stopListening() {
        changes = (handle, faceMask, edgeMask, occupancy, sequence) -> cells.release(handle);
    }

    void createFolder() {
        try {
            Files.createDirectories(folder);
        } catch (IOException failure) {
            Eminus.LOGGER.error("Could not create the store folder {}.", folder, failure);
        }
    }

    @Initializer
    void openStore(LongSupplier clock) {
        long dropGraceMillis = TimeUnit.HOURS.toMillis(Integer.getInteger(DROP_GRACE_PROPERTY, DROP_GRACE_HOURS));
        try {
            CellStores.Opened opened = CellStores.open(folder, lowestStoredLevel, dropGraceMillis, clock);
            store = opened.store();
            ingestLevel = opened.heldLevel();
        } catch (RuntimeException failure) {
            store = EmptyCellStore.INSTANCE;
            ingestLevel = lowestStoredLevel;
            Eminus.LOGGER.error("Could not open the cell store in {}; {} runs without one.",
                    folder, identity.dimension(), failure);
        }
    }

    @Initializer
    void openCells(WorkService<Void> saveService, WorkService<SectionPyramid> ingestService, LongSupplier clock) {
        Dictionary<String> stateIds = openDictionary(StateTable.DICTIONARY_NAME);
        Dictionary<String> biomeIds = openDictionary(SectionConverter.DICTIONARY_NAME);

        saves = new SaveService(store, saveService);
        cells = new CellCache(store, saves, clock);
        states = new StateTable(stateIds);
        biomes = biomeIds;
        coverage = openCoverage();
        stopListening();
        Changes forwarded = new Changes();
        merger = new CellMerger(cells, frame, ingestLevel, forwarded);
        ingest = new IngestService(ingestService, states, biomes, merger, coverage, forwarded);
        beams = openBeams(saveService);
    }

    private BeamSet openBeams(WorkService<Void> saveService) {
        BeamSet opened = new BeamSet(store, write -> saveService.enqueue(scratch -> write.run()));

        try {
            store.readBeams(opened::load);
        } catch (RuntimeException failure) {
            Eminus.LOGGER.error("Could not read the beams in {}; {} starts with none.", folder,
                    identity.dimension(), failure);
        }

        return opened;
    }

    private ColumnCoverage openCoverage() {
        ColumnCoverage opened = new ColumnCoverage(ingestLevel,
                (chunk, lowestLevel) -> store.putColumn(chunk, lowestLevel));

        try {
            store.readColumns(opened::load);
        } catch (RuntimeException failure) {
            Eminus.LOGGER.error("Could not read the covered columns in {}; {} starts with none.", folder,
                    identity.dimension(), failure);
        }

        return opened;
    }

    private Dictionary<String> openDictionary(String name) {
        Dictionary<String> dictionary = new Dictionary<>((id, value) -> store.putDictionaryEntry(name, id, value));

        try {
            store.readDictionary(name, dictionary::load);
        } catch (RuntimeException failure) {
            Eminus.LOGGER.error("Could not read the {} dictionary in {}; {} runs without a store rather than "
                    + "reassigning ids over the cells already there.", name, folder, identity.dimension(), failure);
            closeStore();
            store = EmptyCellStore.INSTANCE;
        }

        return dictionary;
    }

    void acquire() {
        references++;
    }

    void release(long now) {
        if (references == 0) {
            throw new IllegalStateException("Dimension runtime " + identity.dimension() + " was released more often than acquired.");
        }

        references--;
        if (references == 0) {
            idleSince = now;
        }
    }

    void close() {
        if (closed) {
            return;
        }

        closed = true;
        ingest.stop();
        saves.flush();
        cells.flush();
        beams.flush();
        closeStore();
    }

    private void closeStore() {
        try {
            store.close();
        } catch (RuntimeException failure) {
            Eminus.LOGGER.error("Could not close the cell store in {}.", folder, failure);
        }
    }

    int references() {
        return references;
    }

    private final class Changes implements CellChangeListener {
        @Override
        public void changed(CellHandle handle, int faceMask, int edgeMask, int occupancy, long sequence) {
            changes.changed(handle, faceMask, edgeMask, occupancy, sequence);
        }

        @Override
        public void covered(int chunkX, int chunkZ) {
            changes.covered(chunkX, chunkZ);
        }
    }

    long idleSince() {
        return idleSince;
    }
}
