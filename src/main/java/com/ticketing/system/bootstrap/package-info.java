/**
 * Application bootstrap &amp; composition module — the platform's composition root.
 *
 * <p>This is <em>not</em> a bounded context. It holds the cross-context wiring and lifecycle
 * triggers that orchestrate the domain modules but carry no business rules of their own:
 * <ul>
 *   <li>{@link com.ticketing.system.bootstrap.PlatformInitializationRunner} — drives the
 *       {@code UNINITIALIZED -> READY} platform lifecycle (UC-1) at startup.</li>
 *   <li>{@code bootstrap.scheduling} — the {@code @Scheduled} driving adapters (session/order
 *       sweeper, event completion, market opener/self-heal, notification polling) that trigger
 *       use cases on a timer.</li>
 *   <li>{@code bootstrap.dev.seed} — the dev-only dataset seeding and {@code .scenario} replay
 *       engine, active under the {@code dev} profile.</li>
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
