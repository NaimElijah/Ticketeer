/**
 * Platform Governance bounded context.
 *
 * <p>Owns platform-wide governance: the market lifecycle (initialize / open / close), system-wide
 * analytics, and the structural-integrity verification run at startup and market-open. Currently a
 * service-only context ({@code application.service}: {@code SystemAdminService},
 * {@code SystemAnalyticsService}, {@code SystemIntegrityVerifier}).
 *
 * <p>Migration note: this step is a mechanical service move. Deferred to dedicated later steps:
 * splitting {@code SystemAdminService} (its default-admin bootstrap belongs to identity), modelling
 * the currently in-memory market state as a domain concept behind a {@code MarketStatusQuery} inbound
 * port (that sales conforms to), and relocating the governance schedulers/runners
 * ({@code MarketOpener}, {@code MarketSelfHealScheduler}, {@code PlatformInitializationRunner}).
 * Module-boundary verification is switched on at Step 10.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform Governance", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.governance;
