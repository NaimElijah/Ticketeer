package com.ticketing.system.bootstrap;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.shared.exception.ExternalServiceUnavailableException;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs platform initialization (UC-1) at startup and decides whether a failure stops the boot
 * (ADR-003). An unreachable payment service or ticket issuer is the one failure that waiting can
 * fix: it is logged, the process boots with the market not open (UC-32 guards that), and
 * {@code MarketSelfHealScheduler} retries. Every other failure, such as a default admin that
 * cannot be created or persisted state that breaks an invariant, propagates out of this runner,
 * so {@code SpringApplication.run} fails and the process exits.
 *
 * <p>{@code @Order(0)} runs this before {@code MarketOpener} and {@code DevUserSeeder}
 * ({@code @Order(1)}) and {@code ScenarioRunner} ({@code @Order(2)}).
 * Excluded from the {@code test} profile so acceptance tests drive initialization explicitly.
 */
@Component
@Profile("!test")
@Order(0)
@Slf4j
public class PlatformInitializationRunner implements ApplicationRunner {

    private final SystemAdminService systemAdminService;

    /**
     * Creates the runner over the service that owns the platform lifecycle.
     *
     * @param systemAdminService the service whose {@code initializePlatform()} this runner calls
     */
    public PlatformInitializationRunner(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    /**
     * Initializes the platform, logging an unreachable payment or ticket-issuing service instead of
     * failing.
     *
     * @param args the application arguments, unused
     * @throws RuntimeException any initialization failure other than
     *         {@link ExternalServiceUnavailableException}, which stops the boot (ADR-003)
     */
    @Override
    public void run(ApplicationArguments args) {
        try {
            systemAdminService.initializePlatform();
        } catch (ExternalServiceUnavailableException e) {
            log.warn("Platform not initialized yet; the market stays closed and self-heal retries: {}",
                    e.getMessage());
        }
    }
}
