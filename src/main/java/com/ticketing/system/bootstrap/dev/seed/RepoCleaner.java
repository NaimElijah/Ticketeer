package com.ticketing.system.bootstrap.dev.seed;

/**
 * Wipes all application data for a {@code seed.mode=wipe}/{@code reset} run (#366).
 *
 * <p>One implementation per persistence backend, selected by the same profiles as the repository
 * adapters ({@code PersistenceProfiles}): {@link MemoryRepoCleaner} clears the in-memory maps;
 * {@code JpaRepoCleaner} deletes the rows from the database.
 * {@code ScenarioRunner} depends only on this interface, so it wipes whichever backend is live.
 */
public interface RepoCleaner {
    void clearAll();
}
