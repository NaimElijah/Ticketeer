package com.ticketing.system.bootstrap.dev.seed;

/**
 * Wipes all application data for a {@code seed.mode=wipe}/{@code reset} run (#366).
 *
 * <p>{@link JpaRepoCleaner} deletes the rows from the database. The in-memory adapters need no
 * cleaner: they exist only in the test suite, where {@code ScenarioRunner}, the sole caller, never
 * runs. {@code ScenarioRunner} depends only on this interface, so its tests can stub the wipe.
 */
public interface RepoCleaner {
    void clearAll();
}
