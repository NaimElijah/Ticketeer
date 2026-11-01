package com.ticketing.system.unit.infrastructure.scheduling;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.bootstrap.scheduling.MarketSelfHealScheduler;

/**
 * The self-heal tick is a thin delegate — it re-runs the idempotent {@code ensureMarketOpen()}
 * (whose open/close/no-op behaviour is covered in SystemAdminServiceTest), then refreshes the WSEP
 * reachability the dashboard shows unless that call already probed.
 */
class MarketSelfHealSchedulerTest {

    @Test
    void reopenMarketIfNeeded_delegatesToEnsureMarketOpen() {
        SystemAdminService admin = mock(SystemAdminService.class);
        new MarketSelfHealScheduler(admin).reopenMarketIfNeeded();
        verify(admin).ensureMarketOpen();
    }

    // An open or closed market makes ensureMarketOpen a no-op, and the dashboard would otherwise keep
    // the reachability of the last open forever.
    @Test
    void GivenEnsureMarketOpenDidNotProbe_WhenTheTickRuns_ThenReachabilityIsRefreshedAfterIt() {
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.ensureMarketOpen()).thenReturn(false);

        new MarketSelfHealScheduler(admin).reopenMarketIfNeeded();

        InOrder order = inOrder(admin);
        order.verify(admin).ensureMarketOpen();
        order.verify(admin).refreshServiceReachability();
    }

    // A probe takes about two minutes against a hanging WSEP: one per tick is enough.
    @Test
    void GivenEnsureMarketOpenProbed_WhenTheTickRuns_ThenItDoesNotProbeAgain() {
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.ensureMarketOpen()).thenReturn(true);

        new MarketSelfHealScheduler(admin).reopenMarketIfNeeded();

        verify(admin, never()).refreshServiceReachability();
    }
}
