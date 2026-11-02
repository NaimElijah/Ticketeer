package com.ticketing.system.unit.infrastructure.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import com.ticketing.system.bootstrap.scheduling.MarketOpener;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.testutil.LogCapture;

import ch.qos.logback.classic.Level;

/** The boot-time market opener's own log lines (#187 review follow-up 3). */
class MarketOpenerTest {

    // ensureMarketOpen has already logged why (WARN for a WSEP outage, ERROR otherwise): this line points
    // there rather than guessing a cause, since a failed integrity check or database also ends here.
    @Test
    void GivenTheMarketDidNotOpen_WhenTheOpenerRuns_ThenItWarnsWithoutBlamingAnyCause() {
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.isMarketOpen()).thenReturn(false);

        try (LogCapture log = LogCapture.of(MarketOpener.class)) {
            new MarketOpener(admin).run(null);

            assertThat(log.messagesAt(Level.WARN)).containsExactly(
                    "Market not open yet; see the preceding log line. The self-heal scheduler will keep retrying.");
        }
    }

    @Test
    void GivenTheMarketOpened_WhenTheOpenerRuns_ThenItSaysSoAndWarnsNothing() {
        SystemAdminService admin = mock(SystemAdminService.class);
        when(admin.isMarketOpen()).thenReturn(true);

        try (LogCapture log = LogCapture.of(MarketOpener.class)) {
            new MarketOpener(admin).run(null);

            assertThat(log.messagesAt(Level.INFO)).containsExactly("Trading market auto-opened on boot.");
            assertThat(log.messagesAt(Level.WARN)).isEmpty();
        }
    }
}
