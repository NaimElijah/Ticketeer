package com.ticketing.system.bootstrap.dev.seed;

/**
 * Wipes all application data for a {@code seed.mode=wipe}/{@code reset} run (#366).
 *
 * <p>One implementation per persistence backend, selected by profile:
 * {@link MemoryRepoCleaner} ({@code @Profile("!jpa")}) clears the in-memory maps;
 * {@code JpaRepoCleaner} ({@code @Profile("jpa")}) deletes the rows from the database.
 * {@code ScenarioRunner} depends only on this interface, so it wipes whichever backend is live.
 */
public interface RepoCleaner {
    void clearAll();
}
