package com.ticketing.system.organization.application.port.out;

/**
 * Outbound port letting {@code organization} learn how many <em>active</em> events a company runs
 * without importing any {@code catalog} type. Inverts the former direct read of catalog's event
 * repository: organization asks the question through this port, and {@code catalog} supplies the
 * answer via an adapter ({@code CompanyEventStatsAdapter}). This keeps organization strictly below
 * catalog in the dependency graph.
 */
public interface CompanyEventStatsPort {

    /**
     * Counts the company's events that are currently considered active (scheduled, on sale, or
     * sold out — i.e. not draft/cancelled/ended).
     *
     * @param companyId the production company whose active events are counted
     * @return the number of active events for that company (0 if it has none)
     */
    int countActiveEvents(int companyId);
}
