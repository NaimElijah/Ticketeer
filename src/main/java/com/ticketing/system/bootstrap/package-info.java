/**
 * Application bootstrap &amp; composition module — the platform's composition root.
 *
 * <p>This is <em>not</em> a bounded context. It holds the cross-context wiring and lifecycle
 * triggers that orchestrate the domain modules but carry no business rules of their own:
 * <ul>
 *   <li>{@link com.ticketing.system.bootstrap.PlatformInitializationRunner} — drives the
 *       {@code UNINITIALIZED -> READY} platform lifecycle (UC-1) at startup.</li>
 *   <li>{@link com.ticketing.system.bootstrap.CommittedDefaultSecretsGuard} — stops a boot outside
 *       {@code dev} and {@code test} that still uses a secret committed in {@code application.yml}.</li>
 *   <li>{@link com.ticketing.system.bootstrap.CommittedAdminPasswordRotation} — outside {@code dev}
 *       and {@code test}, while the context starts, gives a default admin still stored with the
 *       committed password the configured one and ends that admin's sessions (ADR-014, ADR-021).</li>
 *   <li>{@link com.ticketing.system.bootstrap.DatabaseUnavailableFailureAnalyzer} — reports a
 *       database that cannot be reached or signed in to at startup, or a startup statement that
 *       exceeded {@code database.socket-timeout}, instead of a raw stack trace.
 *       {@link com.ticketing.system.bootstrap.DatasourceUrls} shows the datasource URL there, and in
 *       the seeder, without its query string.</li>
 *   <li>{@code bootstrap.config} — the module's bound configuration records.</li>
 *   <li>{@code bootstrap.scheduling} — the {@code @Scheduled} driving adapters (session/order
 *       sweeper, event completion, market opener/self-heal, notification polling) that trigger
 *       use cases on a timer.</li>
 *   <li>{@code bootstrap.dev.seed} — the dataset seeding and {@code .scenario} replay engine.
 *       Despite the package name it runs in every profile except {@code test} whenever
 *       {@code seed.mode} is not {@code off}; {@code dev} sets it to {@code reseed}. Destructive
 *       modes are not yet refused outside {@code dev} (#78).</li>
 * </ul>
 *
 * <p>By nature it depends inward on many contexts' application ports and services, while nothing
 * depends on it — so it is a pure <em>sink</em> in the module graph and introduces no cycles.
 * Because it composes across contexts (and dev tooling reaches persistence adapters directly for
 * wipe/reseed), it is declared an {@link org.springframework.modulith.ApplicationModule.Type#OPEN
 * open} module.
 */
@org.springframework.modulith.ApplicationModule(
        displayName = "Bootstrap & Composition",
        type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.bootstrap;
