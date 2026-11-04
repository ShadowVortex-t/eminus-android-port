package com.eminus.store;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.eminus.cell.Cell;
import com.eminus.cell.CellKey;
import com.eminus.cell.ColumnCoverage;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.VoxelEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(60)
class FileCellStoreTest {
    private static final int LOWEST_LEVEL = 0;
    private static final long STONE = VoxelEntry.pack(17, 4, VoxelEntry.light(0, 0));
    private static final long DIRT = VoxelEntry.pack(3, 4, VoxelEntry.light(0, 0));
    private static final long COLUMN_A = ColumnCoverage.pack(3, -7);

    @TempDir
    Path folder;

    @Test
    void aCellWrittenComesBackIdenticalAfterAReopen() {
        long key = CellKey.pack(1, 2, 3, 4);
        Cell written = patterned(key);
        try (FileCellStore store = open()) {
            store.write(written);
            assertCellEquals(written, store.read(key));
        }

        try (FileCellStore store = open()) {
            assertCellEquals(written, store.read(key));
            assertNull(store.read(CellKey.pack(1, 2, 3, 5)));
        }
    }

    @Test
    void aCellWrittenAgainKeepsOnlyItsNewestRecord() {
        long key = CellKey.pack(1, 2, 3, 4);
        try (FileCellStore store = open()) {
            store.write(Cell.blank(key));
            store.write(patterned(key));
        }

        try (FileCellStore store = open()) {
            assertCellEquals(patterned(key), store.read(key));
        }
    }

    @Test
    void aDeletedCellStaysGoneAfterAReopen() {
        long key = CellKey.pack(1, 2, 3, 4);
        try (FileCellStore store = open()) {
            store.write(patterned(key));
            store.delete(key);
            assertNull(store.read(key));
        }

        try (FileCellStore store = open()) {
            assertNull(store.read(key));
        }
    }

    @Test
    void dictionariesComeBackInIdOrderPerName() {
        try (FileCellStore store = open()) {
            store.putDictionaryEntry("blocks", 1, "minecraft:stone");
            store.putDictionaryEntry("blocks", 0, "minecraft:air");
            store.putDictionaryEntry("biomes", 0, "minecraft:plains");
        }

        List<String> blocks = new ArrayList<>();
        List<String> biomes = new ArrayList<>();
        try (FileCellStore store = open()) {
            store.readDictionary("blocks", (id, value) -> blocks.add(id + "=" + value));
            store.readDictionary("biomes", (id, value) -> biomes.add(id + "=" + value));
        }

        assertEquals(List.of("0=minecraft:air", "1=minecraft:stone"), blocks);
        assertEquals(List.of("0=minecraft:plains"), biomes);
    }

    @Test
    void aColumnKeepsTheLowestLevelEverWrittenForIt() {
        try (FileCellStore store = open()) {
            store.putColumn(COLUMN_A, 3);
            store.putColumn(COLUMN_A, 1);
            store.putColumn(COLUMN_A, 2);
        }

        Map<Long, Integer> read = new HashMap<>();
        try (FileCellStore store = open()) {
            store.readColumns(read::put);
        }

        assertEquals(Map.of(COLUMN_A, 1), read);
    }

    @Test
    void beamsComeBackAfterAReopenWithDeletedOnesGone() {
        byte[] red = {1, 2, 3};
        byte[] white = {9};
        try (FileCellStore store = open()) {
            store.putBeam(10L, red);
            store.putBeam(20L, white);
            store.putBeam(20L, red);
            store.deleteBeam(10L);
        }

        Map<Long, byte[]> read = new HashMap<>();
        try (FileCellStore store = open()) {
            store.readBeams(read::put);
        }

        assertEquals(1, read.size());
        assertArrayEquals(red, read.get(20L));
    }

    @Test
    void cellsBelowTheHeldLevelAreDroppedAndWritingThemIsRefused() {
        long low = CellKey.pack(0, 1, 1, 1);
        long high = CellKey.pack(2, 1, 1, 1);
        try (FileCellStore store = open(LOWEST_LEVEL)) {
            store.write(patterned(low));
            store.write(patterned(high));
        }

        try (FileCellStore store = open(1)) {
            assertNull(store.read(low));
            assertNotNull(store.read(high));
            assertThrows(IllegalArgumentException.class, () -> store.write(patterned(low)));
        }
    }

    @Test
    void aTornRecordAtTheEndIsCutOffAndTheRestSurvives() throws IOException {
        long first = CellKey.pack(1, 1, 1, 1);
        long second = CellKey.pack(1, 2, 2, 2);
        try (FileCellStore store = open()) {
            store.write(patterned(first));
            store.write(patterned(second));
        }

        Path file = folder.resolve(FileCellStore.FILE_NAME);
        long whole = Files.size(file);
        try (var channel = java.nio.channels.FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.truncate(whole - 5);
        }

        try (FileCellStore store = open()) {
            assertCellEquals(patterned(first), store.read(first));
            assertNull(store.read(second));
            store.write(patterned(second));
        }

        try (FileCellStore store = open()) {
            assertCellEquals(patterned(second), store.read(second));
        }
    }

    @Test
    void aCorruptedRecordEndsTheReplayThere() throws IOException {
        long first = CellKey.pack(1, 1, 1, 1);
        long second = CellKey.pack(1, 2, 2, 2);
        try (FileCellStore store = open()) {
            store.write(patterned(first));
        }

        long goodEnd = Files.size(folder.resolve(FileCellStore.FILE_NAME));
        try (FileCellStore store = open()) {
            store.write(patterned(second));
        }

        Path file = folder.resolve(FileCellStore.FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        bytes[(int) goodEnd + 20] ^= 0x5A;
        Files.write(file, bytes);

        try (FileCellStore store = open()) {
            assertNotNull(store.read(first));
            assertNull(store.read(second));
        }
    }

    @Test
    void aFileOfAnotherFormatVersionIsRebuiltEmpty() throws IOException {
        long key = CellKey.pack(1, 2, 3, 4);
        try (FileCellStore store = open()) {
            store.write(patterned(key));
        }

        Path file = folder.resolve(FileCellStore.FILE_NAME);
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer.wrap(bytes).putInt(Integer.BYTES, StoreFormat.VERSION + 1);
        Files.write(file, bytes);

        try (FileCellStore store = open()) {
            assertNull(store.read(key));
        }
    }

    @Test
    void compactionKeepsEverythingLiveAndShrinksTheFile() throws IOException {
        long key = CellKey.pack(1, 2, 3, 4);
        long other = CellKey.pack(1, 9, 9, 9);
        try (FileCellStore store = FileCellStore.open(folder, LOWEST_LEVEL, 1L)) {
            for (int rewrite = 0; rewrite < 200; rewrite++) {
                store.write(rewrite % 2 == 0 ? patterned(key) : Cell.blank(key));
            }
            store.write(patterned(other));
            store.putDictionaryEntry("blocks", 0, "minecraft:air");
            store.putColumn(COLUMN_A, 2);
            store.putBeam(5L, new byte[] {7});
        }

        long before = Files.size(folder.resolve(FileCellStore.FILE_NAME));
        List<String> blocks = new ArrayList<>();
        Map<Long, Integer> columns = new HashMap<>();
        Map<Long, byte[]> beams = new HashMap<>();
        try (FileCellStore store = FileCellStore.open(folder, LOWEST_LEVEL, 1L)) {
            assertCellEquals(Cell.blank(key), store.read(key));
            assertCellEquals(patterned(other), store.read(other));
            store.readDictionary("blocks", (id, value) -> blocks.add(value));
            store.readColumns(columns::put);
            store.readBeams(beams::put);
            store.write(patterned(key));
        }

        assertTrue(Files.size(folder.resolve(FileCellStore.FILE_NAME)) < before);
        assertEquals(List.of("minecraft:air"), blocks);
        assertEquals(Map.of(COLUMN_A, 2), columns);
        assertArrayEquals(new byte[] {7}, beams.get(5L));

        try (FileCellStore store = open()) {
            assertCellEquals(patterned(key), store.read(key));
            assertCellEquals(patterned(other), store.read(other));
        }
    }

    private FileCellStore open() {
        return open(LOWEST_LEVEL);
    }

    private FileCellStore open(int level) {
        return FileCellStore.open(folder, level);
    }

    private static Cell patterned(long key) {
        short[] indices = new short[DetailLevel.VOXELS_PER_CELL];
        for (int voxel = 0; voxel < indices.length; voxel += 3) {
            indices[voxel] = 1;
        }

        return Cell.of(key, new long[] {DIRT, STONE}, indices);
    }

    private static void assertCellEquals(Cell expected, Cell actual) {
        assertNotNull(actual);
        assertEquals(expected.key(), actual.key());
        assertArrayEquals(expected.copyPalette(), actual.copyPalette());
        assertArrayEquals(expected.indices(), actual.indices());
    }
}
