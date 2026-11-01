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
 *
 * <p>Each tick also leaves a fresh WSEP reachability result for the admin dashboard: when
 * {@code ensureMarketOpen()} did not probe (the market is OPEN or CLOSED), it calls
 * {@link SystemAdminService#refreshServiceReachability()}, so one tick never probes twice.
 */
@Component
@Profile("!test")
@Slf4j
public class MarketSelfHealScheduler {

    private final SystemAdminService systemAdminService;

    public MarketSelfHealScheduler(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    /**
     * One self-heal tick: tries to open the market, then refreshes the WSEP reachability unless that
     * probed. Runs on its own scheduler, since a probe takes about two minutes while WSEP hangs (two
     * handshakes of up to three 20-second attempts each).
     */
    @Scheduled(fixedDelayString = "${market.self-heal-delay-ms:30000}",
            scheduler = SchedulingConfig.MARKET_SELF_HEAL_TASK_SCHEDULER)
    public void reopenMarketIfNeeded() {
        boolean probed = systemAdminService.ensureMarketOpen();
        if (!probed) {
            systemAdminService.refreshServiceReachability();
        }
    }
}
