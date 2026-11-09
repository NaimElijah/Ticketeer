package com.ticketing.system.catalog.adapter.out.organization;

import org.springframework.stereotype.Component;

import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.organization.application.port.out.CompanyEventStatsPort;

/**
 * Catalog-side implementation of {@link CompanyEventStatsPort}: answers organization's
 * active-event-count question by reading catalog's own {@link EventRepository}. Living in
 * {@code catalog} keeps the catalog→organization arrow (catalog depends on the organization port),
 * so organization never depends on catalog.
 */
@Component
public class CompanyEventStatsAdapter implements CompanyEventStatsPort {

    // Catalog's driven port for reading events; injected so this adapter never touches persistence directly.
    private final EventRepository eventRepository;

    /**
     * Wires the adapter to catalog's event repository.
     *
     * @param eventRepository catalog port used to look up a company's events
     */
    public CompanyEventStatsAdapter(EventRepository eventRepository) {
        this.eventRepository = eventRepository;
    }

    /** {@inheritDoc} */
    @Override
    public int countActiveEvents(int companyId) {
        int count = 0;
        for (Event event : eventRepository.findByCompanyId(companyId)) {
            EventStatus status = event.getStatus();
            // "Active" = scheduled, currently on sale, or sold out (excludes drafts/cancelled/ended).
            if (status == EventStatus.ON_SALE || status == EventStatus.SCHEDULED || status == EventStatus.SOLD_OUT) {
                count++;
            }
        }
        return count;
    }
}
