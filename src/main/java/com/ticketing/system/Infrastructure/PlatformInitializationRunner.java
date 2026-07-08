package com.ticketing.system.Infrastructure;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.shared.exception.DomainException;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs platform initialization (UC-1) at startup. Non-fatal by design: if external services
 * are unreachable or the default admin cannot be created, the platform stays uninitialized and
 * the market cannot open (UC-32 guards that) — but the process still boots, so the health
 * endpoint and ops tooling remain available to diagnose and recover.
 *
 * <p>{@code @Order(0)} runs this before the dev seeders ({@code @Order(1)}/{@code (2)}).
 * Excluded from the {@code test} profile so acceptance tests drive initialization explicitly.
 */
@Component
@Profile("!test")
@Order(0)
@Slf4j
public class PlatformInitializationRunner implements ApplicationRunner {

    private final SystemAdminService systemAdminService;

    public PlatformInitializationRunner(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            systemAdminService.initializePlatform();
        } catch (DomainException e) {
            log.error("Platform NOT initialized — market cannot open until resolved: {}", e.getMessage());
        }
    }
}
