package com.ticketing.system.Infrastructure.scheduling;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.application.service.SystemAdminService;

import lombok.extern.slf4j.Slf4j;

/**
 * Opens the trading market automatically on boot in <b>every real run</b> (dev, jpa, supabase, prod),
 * so the app comes up usable without a manual admin open. Only the {@code test} profile is excluded —
 * tests drive the market state themselves.
 *
 * <p>Ordering matters because reserve/checkout are gated on an OPEN market: {@code @Order(1)} places
 * this after {@code PlatformInitializationRunner} (@Order(0), which brings the platform to READY) and
 * before the purchasing {@code ScenarioRunner} (@Order(2)), so the market is OPEN before any seeded
 * reservation or checkout runs.
 *
 * <p>Delegates to the system-internal {@link SystemAdminService#ensureMarketOpen()} (idempotent; the
 * WSEP handshake it triggers retries). It respects a deliberate admin close — never auto-reopens a
 * CLOSED market — and an admin can still open/close via UC-32. If a transient external-service outage
 * leaves the market closed at boot, {@link MarketSelfHealScheduler} keeps re-attempting until it opens.
 */
@Component
@Profile("!test")
@Order(1)
@Slf4j
public class MarketOpener implements ApplicationRunner {

    private final SystemAdminService systemAdminService;

    public MarketOpener(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        systemAdminService.ensureMarketOpen();
        if (systemAdminService.isMarketOpen()) {
            log.info("Trading market auto-opened on boot.");
        } else {
            log.warn("Market not open yet (platform or external services not ready); "
                    + "the self-heal scheduler will keep retrying.");
        }
    }
}
