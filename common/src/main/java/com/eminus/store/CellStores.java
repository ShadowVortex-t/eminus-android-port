package com.eminus.store;

import java.nio.file.Path;
import java.util.function.LongSupplier;

import com.eminus.Eminus;

import org.sqlite.NativeLibraryNotFoundException;

/**
 * Opens the cell store a device can run: SQLite where its native library loads, and otherwise a plain Java file.
 * Setting {@value #STORE_PROPERTY} to {@code file} or {@code sqlite} forces the choice.
 */
public final class CellStores {
    public static final String STORE_PROPERTY = "eminus.store";
    private static final String FILE = "file";
    private static final String SQLITE = "sqlite";

    private static volatile boolean sqliteUnusable;

    public record Opened(CellStore store, int heldLevel) {
    }

    private CellStores() {
    }

    public static Opened open(Path folder, int lowestStoredLevel, long dropGraceMillis, LongSupplier clock) {
        String forced = System.getProperty(STORE_PROPERTY);
        if (!FILE.equals(forced) && (!sqliteUnusable || SQLITE.equals(forced))) {
            try {
                SqliteCellStore sqlite = SqliteCellStore.open(folder, lowestStoredLevel, dropGraceMillis, clock);
                return new Opened(sqlite, sqlite.heldLevel());
            } catch (RuntimeException | LinkageError failure) {
                // Once SQLite's native library has failed to load, later opens throw a bare UnsatisfiedLinkError, so
                // the first failure is the last attempt.
                sqliteUnusable = true;
                if (SQLITE.equals(forced)) {
                    throw failure instanceof RuntimeException runtime ? runtime
                            : new StoreException("SQLite cannot load here", failure);
                }

                Eminus.LOGGER.warn("SQLite is not usable here ({}); the cell store is a plain Java file instead",
                        describe(failure));
            }
        }

        FileCellStore file = FileCellStore.open(folder, lowestStoredLevel);
        return new Opened(file, file.heldLevel());
    }

    private static String describe(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && !(cause instanceof NativeLibraryNotFoundException)) {
            cause = cause.getCause();
        }

        return cause.toString();
    }
}
