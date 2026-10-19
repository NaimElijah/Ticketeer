package com.ticketing.system.shared.persistence;

/**
 * Profile names and expressions that select a repository adapter for each port (ADR-005).
 *
 * <p>Persistence is the default: the {@code Jpa…Repository} adapters are active in every run
 * except one where both {@code test} and {@link #MEMORY} are active, and only that run gets the
 * {@code Memory…Repository} adapters. Requiring {@code test} as well means a stray
 * {@code memory} in a deployment cannot put production data in RAM.
 *
 * <table>
 *   <caption>Adapter selected per active profile set</caption>
 *   <tr><th>Active profiles</th><th>Adapters</th></tr>
 *   <tr><td>none, {@code dev}, {@code supabase}, {@code memory}</td><td>Jpa</td></tr>
 *   <tr><td>{@code test}</td><td>Jpa, on H2 ({@code application-test.yml})</td></tr>
 *   <tr><td>{@code test} + {@code memory}</td><td>Memory</td></tr>
 * </table>
 *
 * <p>A Spring test opts into the Memory adapters with
 * {@code @ActiveProfiles({"test", PersistenceProfiles.MEMORY})}. Keep that order: Spring keys its
 * test-context cache on the ordered profile list, so {@code {"memory", "test"}} would start a
 * second context.
 */
public final class PersistenceProfiles {

    /** The profile a test adds to {@code test} to run on the in-memory adapters. */
    public static final String MEMORY = "memory";

    /** {@code @Profile} expression of every {@code Memory…Repository} adapter. */
    public static final String IN_MEMORY_ADAPTERS = "test & " + MEMORY;

    /** {@code @Profile} expression of every {@code Jpa…Repository} adapter: the complement. */
    public static final String JPA_ADAPTERS = "!(" + IN_MEMORY_ADAPTERS + ")";

    private PersistenceProfiles() {
    }
}
