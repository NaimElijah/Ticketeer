package com.ticketing.system.bootstrap.scheduling;

import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.application.service.SystemAdminService;

import lombok.extern.slf4j.Slf4j;

/**
 * Self-heals the trading market in every real run (#455). If a transient external-service outage —
 * e.g. a cold-starting WSEP endpoint — left the market closed at boot, this periodically re-attempts
 * the open so the system recovers <i>without a restart</i> (V3 Req 6).
 *
 * <p>{@link SystemAdminService#ensureMarketOpen()} is idempotent and respects an admin's deliberate
 * close, so this is a no-op once the market is open (or was closed on purpose). Runs in every profile
 * except {@code test} (tests drive the market state themselves); an admin can still open/close via UC-32.
 */
@Component
@Profile("!test")
@Slf4j
public class MarketSelfHealScheduler {

    private final SystemAdminService systemAdminService;

    public MarketSelfHealScheduler(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    @Scheduled(fixedDelayString = "${market.self-heal-delay-ms:30000}")
    public void reopenMarketIfNeeded() {
        systemAdminService.ensureMarketOpen();
    }
}
