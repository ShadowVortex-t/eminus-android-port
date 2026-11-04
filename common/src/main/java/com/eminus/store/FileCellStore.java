package com.eminus.store;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;

import com.eminus.Eminus;
import com.eminus.cell.Cell;
import com.eminus.cell.CellKey;
import com.eminus.cell.ColumnCoverage;
import com.eminus.cell.DetailLevel;
import com.eminus.cell.Dictionary;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import org.jspecify.annotations.Nullable;

/**
 * A cell store in one plain file, for devices where SQLite's native library cannot load, such as Android. The file is
 * an append-only log of checksummed records; opening it replays the log into an index in memory that points at each
 * cell's bytes in the file. A torn record at the end, from a crash, is cut off. Dead records are squeezed out when the
 * file opens with more garbage than live data.
 */
public final class FileCellStore implements CellStore {
    public static final String FILE_NAME = "cells.log";

    private static final String TEMP_NAME = "cells.log.tmp";
    private static final int MAGIC = 0x454D4C47;
    private static final int HEADER_BYTES = 2 * Integer.BYTES;
    private static final int FRAME_BYTES = 2 * Integer.BYTES;
    private static final int MAX_BODY_BYTES = 64 * 1024 * 1024;
    private static final int LENGTH_BITS = 24;
    private static final long LENGTH_MASK = (1L << LENGTH_BITS) - 1;
    private static final long ABSENT = -1L;
    private static final long COMPACT_MIN_GARBAGE = 16L * 1024 * 1024;

    private static final byte CELL = 1;
    private static final byte DELETE_CELL = 2;
    private static final byte DICTIONARY = 3;
    private static final byte COLUMN = 4;
    private static final byte BEAM = 5;
    private static final byte DELETE_BEAM = 6;
    private static final int CELL_BODY_HEAD = Byte.BYTES + Long.BYTES;

    private final Path file;
    private final int heldLevel;
    private final long compactMinGarbage;
    private final Long2LongOpenHashMap cells = new Long2LongOpenHashMap();
    private final Map<String, TreeMap<Integer, String>> dictionaries = new java.util.HashMap<>();
    private final Long2IntOpenHashMap columns = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<byte[]> beams = new Long2ObjectOpenHashMap<>();

    private FileChannel channel;
    private long end;
    private long garbage;
    private boolean closed;

    private FileCellStore(Path file, int heldLevel, FileChannel channel, long compactMinGarbage) {
        this.file = file;
        this.compactMinGarbage = compactMinGarbage;
        this.heldLevel = heldLevel;
        this.channel = channel;
        cells.defaultReturnValue(ABSENT);
    }

    public static FileCellStore open(Path folder, int lowestStoredLevel) {
        return open(folder, lowestStoredLevel, COMPACT_MIN_GARBAGE);
    }

    static FileCellStore open(Path folder, int lowestStoredLevel, long compactMinGarbage) {
        Path file = folder.resolve(FILE_NAME);
        try {
            Files.createDirectories(folder);
            boolean fresh = !Files.exists(file) || !hasCurrentHeader(file);
            FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.READ,
                    StandardOpenOption.WRITE);
            if (fresh) {
                channel.truncate(0);
                writeFully(channel, header(), 0);
            }

            FileCellStore store = new FileCellStore(file, lowestStoredLevel, channel, compactMinGarbage);
            store.end = HEADER_BYTES;
            store.replay();
            store.dropBelowHeldLevel();
            store.compactWhenSparse();
            Eminus.LOGGER.info("Cell store opened at {}: a plain Java log, {} cells, {} bytes ({} dead)", file,
                    store.cells.size(), store.end, store.garbage);
            return store;
        } catch (IOException failure) {
            throw new StoreException("Could not open the cell store " + file, failure);
        }
    }

    public int heldLevel() {
        return heldLevel;
    }

    @Override
    public synchronized @Nullable Cell read(long key) {
        long location = cells.get(key);
        if (location == ABSENT) {
            return null;
        }

        try {
            byte[] record = new byte[(int) (location & LENGTH_MASK)];
            readFully(location >>> LENGTH_BITS, record);
            return CellRecord.decode(key, record);
        } catch (IOException failure) {
            throw new StoreException("Could not read cell " + key + " from " + file, failure);
        } catch (RuntimeException damaged) {
            delete(key);
            Eminus.LOGGER.error("Damaged cell record at level {} ({}, {}, {}) deleted from {}: {}",
                    CellKey.level(key), CellKey.x(key), CellKey.y(key), CellKey.z(key), file, damaged.toString());
            return null;
        }
    }

    @Override
    public synchronized void write(Cell cell) {
        long key = cell.key();
        int level = CellKey.level(key);
        if (level < heldLevel || level > DetailLevel.MAX) {
            throw new IllegalArgumentException("Cell level " + level + " is outside the stored range "
                    + heldLevel + ".." + DetailLevel.MAX);
        }

        byte[] record = CellRecord.encode(cell);
        if (record.length > LENGTH_MASK) {
            throw new StoreException("Cell " + key + " is " + record.length + " bytes, too big for " + file, null);
        }

        ByteBuffer body = ByteBuffer.allocate(CELL_BODY_HEAD + record.length);
        body.put(CELL).putLong(key).put(record);
        long frameStart = append(body);
        forget(key);
        cells.put(key, (frameStart + FRAME_BYTES + CELL_BODY_HEAD) << LENGTH_BITS | record.length);
    }

    @Override
    public synchronized void delete(long key) {
        if (cells.get(key) == ABSENT) {
            return;
        }

        append(ByteBuffer.allocate(CELL_BODY_HEAD).put(DELETE_CELL).putLong(key));
        forget(key);
    }

    @Override
    public synchronized void putDictionaryEntry(String name, int id, String value) {
        byte[] nameBytes = name.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        append(ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + nameBytes.length + Integer.BYTES + Integer.BYTES
                + valueBytes.length).put(DICTIONARY).putInt(nameBytes.length).put(nameBytes).putInt(id)
                .putInt(valueBytes.length).put(valueBytes));
        dictionaries.computeIfAbsent(name, ignored -> new TreeMap<>()).put(id, value);
    }

    @Override
    public synchronized void readDictionary(String name, Dictionary.Persistence<String> into) {
        TreeMap<Integer, String> entries = dictionaries.get(name);
        if (entries != null) {
            entries.forEach(into::persist);
        }
    }

    @Override
    public synchronized void putColumn(long chunk, int lowestLevel) {
        if (columns.containsKey(chunk) && columns.get(chunk) <= lowestLevel) {
            return;
        }

        append(ByteBuffer.allocate(CELL_BODY_HEAD + Integer.BYTES).put(COLUMN).putLong(chunk).putInt(lowestLevel));
        columns.put(chunk, lowestLevel);
    }

    @Override
    public synchronized void readColumns(ColumnCoverage.Persistence into) {
        for (Long2IntMap.Entry column : columns.long2IntEntrySet()) {
            into.persist(column.getLongKey(), column.getIntValue());
        }
    }

    @Override
    public synchronized void putBeam(long pos, byte[] sections) {
        append(ByteBuffer.allocate(CELL_BODY_HEAD + sections.length).put(BEAM).putLong(pos).put(sections));
        byte[] replaced = beams.put(pos, sections.clone());
        garbage += replaced == null ? 0 : FRAME_BYTES + CELL_BODY_HEAD + replaced.length;
    }

    @Override
    public synchronized void deleteBeam(long pos) {
        if (!beams.containsKey(pos)) {
            return;
        }

        append(ByteBuffer.allocate(CELL_BODY_HEAD).put(DELETE_BEAM).putLong(pos));
        byte[] removed = beams.remove(pos);
        garbage += FRAME_BYTES + CELL_BODY_HEAD + removed.length;
    }

    @Override
    public synchronized void readBeams(Rows into) {
        for (Long2ObjectMap.Entry<byte[]> beam : beams.long2ObjectEntrySet()) {
            into.row(beam.getLongKey(), beam.getValue());
        }
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }

        closed = true;
        try {
            channel.force(false);
            channel.close();
        } catch (IOException failure) {
            throw new StoreException("Could not close the cell store " + file, failure);
        }
    }

    private void forget(long key) {
        long location = cells.remove(key);
        if (location != ABSENT) {
            garbage += FRAME_BYTES + CELL_BODY_HEAD + (location & LENGTH_MASK);
        }
    }

    private long append(ByteBuffer body) {
        body.flip();
        CRC32 crc = new CRC32();
        crc.update(body.duplicate());
        ByteBuffer frame = ByteBuffer.allocate(FRAME_BYTES + body.remaining());
        frame.putInt(body.remaining()).putInt((int) crc.getValue()).put(body).flip();

        long start = end;
        try {
            writeFully(channel, frame, start);
        } catch (IOException failure) {
            throw new StoreException("Could not write to the cell store " + file, failure);
        }

        end += frame.limit();
        return start;
    }

    private void replay() throws IOException {
        long position = HEADER_BYTES;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(file), 1 << 16))) {
            in.skipNBytes(HEADER_BYTES);
            while (true) {
                int length;
                int checksum;
                try {
                    length = in.readInt();
                    checksum = in.readInt();
                } catch (EOFException finished) {
                    break;
                }

                if (length < Byte.BYTES || length > MAX_BODY_BYTES) {
                    break;
                }

                byte[] body = new byte[length];
                try {
                    in.readFully(body);
                } catch (EOFException torn) {
                    break;
                }

                CRC32 crc = new CRC32();
                crc.update(body);
                if ((int) crc.getValue() != checksum) {
                    break;
                }

                apply(ByteBuffer.wrap(body), position);
                position += FRAME_BYTES + length;
            }
        }

        if (position < channel.size()) {
            Eminus.LOGGER.warn("Cut {} bytes of an unfinished record off the end of {}", channel.size() - position,
                    file);
            channel.truncate(position);
        }

        end = position;
    }

    private void apply(ByteBuffer body, long frameStart) {
        byte type = body.get();
        switch (type) {
            case CELL -> {
                long key = body.getLong();
                forget(key);
                int length = body.remaining();
                cells.put(key, (frameStart + FRAME_BYTES + CELL_BODY_HEAD) << LENGTH_BITS | length);
            }
            case DELETE_CELL -> forget(body.getLong());
            case DICTIONARY -> {
                byte[] name = new byte[body.getInt()];
                body.get(name);
                int id = body.getInt();
                byte[] value = new byte[body.getInt()];
                body.get(value);
                dictionaries.computeIfAbsent(new String(name, StandardCharsets.UTF_8), ignored -> new TreeMap<>())
                        .put(id, new String(value, StandardCharsets.UTF_8));
            }
            case COLUMN -> {
                long chunk = body.getLong();
                int lowest = body.getInt();
                if (!columns.containsKey(chunk) || lowest < columns.get(chunk)) {
                    columns.put(chunk, lowest);
                }
            }
            case BEAM -> {
                long pos = body.getLong();
                byte[] sections = new byte[body.remaining()];
                body.get(sections);
                beams.put(pos, sections);
            }
            case DELETE_BEAM -> beams.remove(body.getLong());
            default -> throw new StoreException("Unknown record type " + type + " in " + file, null);
        }
    }

    private void dropBelowHeldLevel() {
        long lowestKey = SqliteCellStore.lowestKeyAt(heldLevel);
        int dropped = 0;
        for (ObjectIterator<Long2LongMap.Entry> each = cells.long2LongEntrySet().fastIterator(); each.hasNext(); ) {
            Long2LongMap.Entry cell = each.next();
            if (cell.getLongKey() < lowestKey) {
                garbage += FRAME_BYTES + CELL_BODY_HEAD + (cell.getLongValue() & LENGTH_MASK);
                each.remove();
                dropped++;
            }
        }

        for (Long2IntMap.Entry column : columns.long2IntEntrySet()) {
            if (column.getIntValue() < heldLevel) {
                column.setValue(heldLevel);
            }
        }

        if (dropped > 0) {
            Eminus.LOGGER.info("Dropped {} cells below level {} from {}", dropped, heldLevel, file);
        }
    }

    private void compactWhenSparse() throws IOException {
        if (garbage < compactMinGarbage || garbage < end - garbage) {
            return;
        }

        Path temp = file.resolveSibling(TEMP_NAME);
        Long2LongOpenHashMap moved = new Long2LongOpenHashMap();
        try (FileChannel out = FileChannel.open(temp, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            writeFully(out, header(), 0);
            long position = HEADER_BYTES;
            for (Long2LongMap.Entry cell : cells.long2LongEntrySet()) {
                byte[] record = new byte[(int) (cell.getLongValue() & LENGTH_MASK)];
                readFully(cell.getLongValue() >>> LENGTH_BITS, record);
                ByteBuffer frame = frame(ByteBuffer.allocate(CELL_BODY_HEAD + record.length)
                        .put(CELL).putLong(cell.getLongKey()).put(record));
                writeFully(out, frame, position);
                moved.put(cell.getLongKey(), (position + FRAME_BYTES + CELL_BODY_HEAD) << LENGTH_BITS | record.length);
                position += frame.limit();
            }

            for (Map.Entry<String, TreeMap<Integer, String>> dictionary : dictionaries.entrySet()) {
                for (Map.Entry<Integer, String> entry : dictionary.getValue().entrySet()) {
                    byte[] name = dictionary.getKey().getBytes(StandardCharsets.UTF_8);
                    byte[] value = entry.getValue().getBytes(StandardCharsets.UTF_8);
                    ByteBuffer frame = frame(ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + name.length
                            + Integer.BYTES + Integer.BYTES + value.length).put(DICTIONARY).putInt(name.length)
                            .put(name).putInt(entry.getKey()).putInt(value.length).put(value));
                    writeFully(out, frame, position);
                    position += frame.limit();
                }
            }

            for (Long2IntMap.Entry column : columns.long2IntEntrySet()) {
                ByteBuffer frame = frame(ByteBuffer.allocate(CELL_BODY_HEAD + Integer.BYTES).put(COLUMN)
                        .putLong(column.getLongKey()).putInt(column.getIntValue()));
                writeFully(out, frame, position);
                position += frame.limit();
            }

            for (Long2ObjectMap.Entry<byte[]> beam : beams.long2ObjectEntrySet()) {
                ByteBuffer frame = frame(ByteBuffer.allocate(CELL_BODY_HEAD + beam.getValue().length).put(BEAM)
                        .putLong(beam.getLongKey()).put(beam.getValue()));
                writeFully(out, frame, position);
                position += frame.limit();
            }

            out.force(true);
            end = position;
        }

        channel.close();
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE);
        cells.clear();
        cells.putAll(moved);
        garbage = 0;
    }

    private static ByteBuffer frame(ByteBuffer body) {
        body.flip();
        CRC32 crc = new CRC32();
        crc.update(body.duplicate());
        ByteBuffer frame = ByteBuffer.allocate(FRAME_BYTES + body.remaining());
        frame.putInt(body.remaining()).putInt((int) crc.getValue()).put(body).flip();
        return frame;
    }

    private static boolean hasCurrentHeader(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(Files.newInputStream(file))) {
            return in.readInt() == MAGIC && in.readInt() == StoreFormat.VERSION;
        } catch (EOFException empty) {
            return false;
        }
    }

    private static ByteBuffer header() {
        return ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putInt(StoreFormat.VERSION).flip();
    }

    private void readFully(long position, byte[] into) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(into);
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) {
                throw new EOFException("The cell store " + file + " ends before a record does");
            }

            at += read;
        }
    }

    private static void writeFully(FileChannel to, ByteBuffer from, long position) throws IOException {
        long at = position;
        while (from.hasRemaining()) {
            at += to.write(from, at);
        }
    }
}
