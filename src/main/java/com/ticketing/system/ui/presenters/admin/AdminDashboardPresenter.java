package com.ticketing.system.ui.presenters.admin;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.AdminOverviewDTO;
import com.ticketing.system.governance.application.service.SystemAnalyticsService;
import com.ticketing.system.ui.session.AuthSession;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * MVP presenter for {@code AdminDashboardView} (upstream#279). Vaadin-free POJO that loads the platform
 * headline counters from {@link SystemAnalyticsService#adminOverview(String)}, with the signed-in
 * admin's token, and returns a sealed outcome the view switches on. Mirrors
 * {@code GlobalHistoryPresenter} / {@code OwnerDashboardPresenter}.
 */
@Component
public class AdminDashboardPresenter {

    private final SystemAnalyticsService systemAnalyticsService;

    /** Creates the presenter over the service that computes the overview. */
    @Autowired
    public AdminDashboardPresenter(SystemAnalyticsService systemAnalyticsService) {
        this.systemAnalyticsService = systemAnalyticsService;
    }

    /** Loads the admin landing KPIs. */
    public Outcome load() {
        try {
            return new Outcome.Success(systemAnalyticsService.adminOverview(AuthSession.token()));
        } catch (RuntimeException e) {
            return new Outcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome the view switches on to render the KPI row. */
    public sealed interface Outcome {
        /**
         * The overview loaded.
         *
         * @param overview the platform's headline counters
         */
        record Success(AdminOverviewDTO overview) implements Outcome { }

        /**
         * The load failed, including a token the service refused.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome { }
    }
}
