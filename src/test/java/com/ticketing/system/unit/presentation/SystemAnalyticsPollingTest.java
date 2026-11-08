package com.ticketing.system.unit.presentation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.shared.dto.MarketStateDTO;
import com.ticketing.system.shared.dto.SystemAnalyticsDTO;
import com.ticketing.system.ui.presenters.admin.SystemAnalyticsPresenter;
import com.ticketing.system.ui.views.admin.SystemAnalyticsView;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.PollEvent;
import com.vaadin.flow.component.UI;

/**
 * The analytics page refreshes itself by polling while it shows, and stops when the admin leaves it: a poll
 * that keeps running after the page detached would reload data for a page nobody sees, and keep the
 * browser sending requests (#189).
 */
class SystemAnalyticsPollingTest {

    private static final int REFRESH_INTERVAL_MS = 5_000;
    private static final int NO_POLL = -1;

    private ViewHarness harness;
    private SystemAnalyticsPresenter presenter;

    @BeforeEach
    void setUp() {
        harness = new ViewHarness();
        ViewHarness.signInAdmin();
        presenter = mock(SystemAnalyticsPresenter.class);
        when(presenter.refreshIntervalMs()).thenReturn(REFRESH_INTERVAL_MS);
        when(presenter.load()).thenReturn(new SystemAnalyticsPresenter.Outcome.Success(
            new MarketStateDTO("OPEN", null, null, true, true, null, true),
            new SystemAnalyticsDTO(0L, 0d, 0d, 0d, 0d, 0d, 0L, 0L, 0L, 0L, 0L, 5)));
    }

    @AfterEach
    void tearDown() {
        harness.close();
    }

    @Test
    void GivenTheAnalyticsPageShown_WhenAPollArrives_ThenItsDataIsReadAgain() {
        harness.attach(new SystemAnalyticsView(presenter, InboxViewHarness.displayTime()));
        UI ui = UI.getCurrent();
        assertEquals(REFRESH_INTERVAL_MS, ui.getPollInterval(), "the page polls at the configured interval");
        clearInvocations(presenter);

        ComponentUtil.fireEvent(ui, new PollEvent(ui, true));

        verify(presenter).load();
    }

    @Test
    void GivenTheAutoRefreshRunning_WhenThePageDetaches_ThenPollingStopsAndAPollReadsNothing() {
        SystemAnalyticsView view = new SystemAnalyticsView(presenter, InboxViewHarness.displayTime());
        harness.attach(view);
        UI ui = UI.getCurrent();
        assertEquals(REFRESH_INTERVAL_MS, ui.getPollInterval(), "precondition: the page polls while it shows");
        clearInvocations(presenter);

        ui.remove(view);
        ComponentUtil.fireEvent(ui, new PollEvent(ui, true));

        assertEquals(NO_POLL, ui.getPollInterval(), "the browser must stop polling once the page is gone");
        verify(presenter, never()).load();
    }
}
