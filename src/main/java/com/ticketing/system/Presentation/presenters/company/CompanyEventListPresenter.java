package com.ticketing.system.Presentation.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.shared.exception.InvalidTokenException;

@Component
public class CompanyEventListPresenter {

    private final EventManagementService eventService;
    private final CompanyManagementService companyManagementService;

    @Autowired
    public CompanyEventListPresenter(EventManagementService eventService,
                                     CompanyManagementService companyManagementService) {
        this.eventService = eventService;
        this.companyManagementService = companyManagementService;
    }

    public Outcome load(String token, Integer companyId, CatalogSearchFiltersDTO filters) {
        if (token == null) return new Outcome.NotAuthenticated();
        try {
            Integer resolved = resolveCompanyId(token, companyId);
            if (resolved == null) return new Outcome.NoCompany();
            // Null filters means "unfiltered" — substitute an empty filter set so the service's
            // filters.withoutCompanyRating() call can't NPE into a generic Failure.
            CatalogSearchFiltersDTO effectiveFilters = filters == null ? CatalogSearchFiltersDTO.empty() : filters;
            List<EventDetailDTO> events = eventService.listEventsForCompany(token, resolved, effectiveFilters);
            return new Outcome.Success(events);
        } catch (InvalidTokenException e) {
            return new Outcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new Outcome.Failure(e.getMessage());
        }
    }

    /**
     * Resolve which of the caller's companies to scope to. Uses {@code findMyCompanies}
     * (manager-inclusive, unlike {@code findOwnedCompanies}) so a manager granted
     * {@code MANAGE_INVENTORY} sees their company's events; honors the workspace's selected company
     * ({@code preferredCompanyId}) when it is one of theirs, else falls back to the first — the same
     * select-or-first idiom as {@code CompanyInquiryInboxPresenter}. {@code null} when the caller
     * belongs to no company.
     */
    private Integer resolveCompanyId(String token, Integer preferredCompanyId) {
        List<MyCompanyDTO> companies = companyManagementService.findMyCompanies(token);
        if (companies.isEmpty()) return null;
        return companies.stream()
                .filter(c -> preferredCompanyId != null && c.companyId() == preferredCompanyId)
                .findFirst()
                .orElse(companies.get(0))
                .companyId();
    }

    // ---- filter options (company scope): distinct country/city of THIS company's own events ----

    /** This company's events (all statuses), unfiltered; empty when there's no token / no company. */
    private List<EventDetailDTO> companyEvents(String token, Integer companyId) {
        if (token == null) return List.of();
        Integer resolved = resolveCompanyId(token, companyId);
        if (resolved == null) return List.of();
        return eventService.listEventsForCompany(token, resolved, CatalogSearchFiltersDTO.empty());
    }

    /** Distinct countries this company has events in, sorted; empty list on any failure. */
    public List<String> countries(String token, Integer companyId) {
        try {
            return companyEvents(token, companyId).stream()
                    .map(ev -> ev.location() == null ? null : ev.location().country())
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    /** Distinct cities this company has events in for {@code country}, sorted; empty list on any failure. */
    public List<String> cities(String token, Integer companyId, String country) {
        if (country == null) return List.of();
        try {
            return companyEvents(token, companyId).stream()
                    .filter(ev -> ev.location() != null && country.equalsIgnoreCase(ev.location().country()))
                    .map(ev -> ev.location().city())
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    public ActionOutcome cancelEvent(String token, int eventId) {
        if (token == null) return new ActionOutcome.NotAuthenticated();
        try {
            eventService.cancelEventAndRefund(token, eventId);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    public ActionOutcome deleteEvent(String token, int eventId) {
        if (token == null) return new ActionOutcome.NotAuthenticated();
        try {
            eventService.deleteEvent(token, eventId);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    public ActionOutcome changeEventStatus(String token, int eventId, EventStatus targetStatus) {
        if (token == null) return new ActionOutcome.NotAuthenticated();
        try {
            eventService.changeEventStatus(token, eventId, targetStatus);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(e.getMessage());
        }
    }

    public sealed interface Outcome {
        record Success(List<EventDetailDTO> events) implements Outcome {}
        record NotAuthenticated() implements Outcome {}
        record NoCompany() implements Outcome {}
        record Failure(String reason) implements Outcome {}
    }

    public sealed interface ActionOutcome {
        record Success() implements ActionOutcome {}
        record NotAuthenticated() implements ActionOutcome {}
        record Failure(String reason) implements ActionOutcome {}
    }
}
