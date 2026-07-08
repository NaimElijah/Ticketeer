package com.ticketing.system.unit.infrastructure.scheduling;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.Infrastructure.scheduling.MarketSelfHealScheduler;

/**
 * The self-heal tick is a thin delegate — it just re-runs the idempotent {@code ensureMarketOpen()}
 * (whose open/close/no-op behaviour is covered in SystemAdminServiceTest).
 */
class MarketSelfHealSchedulerTest {

    @Test
    void reopenMarketIfNeeded_delegatesToEnsureMarketOpen() {
        SystemAdminService admin = mock(SystemAdminService.class);
        new MarketSelfHealScheduler(admin).reopenMarketIfNeeded();
        verify(admin).ensureMarketOpen();
    }
}
