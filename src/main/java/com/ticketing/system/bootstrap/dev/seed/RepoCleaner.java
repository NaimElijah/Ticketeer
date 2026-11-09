package com.ticketing.system.bootstrap.dev.seed;

/**
 * Wipes the business data for a {@code seed.mode=wipe}/{@code reset} run (#366); the admins stay.
 *
 * <p>{@link JpaRepoCleaner} deletes the rows from the database. The in-memory adapters need no
 * cleaner: they exist only in the test suite, where {@code ScenarioRunner}, the sole caller, never
 * runs. {@code ScenarioRunner} depends only on this interface, so its tests can stub the wipe.
 */
public interface RepoCleaner {

    /**
     * Deletes every aggregate's rows except the admins'. Destructive and not reversible; the caller
     * has already checked the opt-in ({@code seed.assume-yes}).
     */
    void clearAll();
}
