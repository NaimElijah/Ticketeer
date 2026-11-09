/**
 * Platform Governance bounded context.
 *
 * <p>Owns platform-wide governance: the market lifecycle (initialize / open / close), system-wide
 * analytics, and the structural-integrity verification run at initialization and each market open.
 * It has no domain package.
 *
 * <p>{@code application.service} holds {@code SystemAdminService} (the platform lifecycle state
 * machine, which is in memory and not persisted; the default System Admin, which it saves through
 * identity's {@code AdminRepository}; and the admin's global purchase history),
 * {@code SystemAnalyticsService} and {@code SystemIntegrityVerifier}. {@code adapter.out} holds
 * {@code MarketGateAdapter}, which implements sales' {@code MarketGate} port over the market state.
 * {@code config} holds the properties records ({@code AnalyticsProperties},
 * {@code PlatformAdminProperties}). The runners and schedulers that drive the lifecycle
 * ({@code PlatformInitializationRunner}, {@code MarketOpener}, {@code MarketSelfHealScheduler}) live
 * in {@code bootstrap}.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Platform Governance", type = org.springframework.modulith.ApplicationModule.Type.OPEN)
package com.ticketing.system.governance;
