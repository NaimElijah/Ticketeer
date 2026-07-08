package com.ticketing.system.ui.presenters.company;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventUpdateDTO;
import com.ticketing.system.Core.Application.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;

@Component
public class EventManagementPresenter {

    private final EventManagementService eventService;
    private final CompanyManagementService companyService;

    @Autowired
    public EventManagementPresenter(EventManagementService eventService,
                                    CompanyManagementService companyService) {
        this.eventService = eventService;
        this.companyService = companyService;
    }

    public LoadOutcome load(String token, int eventId) {
        if (token == null) return new LoadOutcome.NotAuthenticated();
        try {
            EventDetailDTO ev = eventService.getEventDetail(token, eventId);
            return new LoadOutcome.Success(ev);
        } catch (InvalidTokenException e) {
            return new LoadOutcome.NotAuthenticated();
        } catch (EventNotFoundException e) {
            return new LoadOutcome.NotFound();
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(e.getMessage());
        }
    }

    public SaveOutcome save(String token, EventUpdateDTO dto) {
        if (token == null) return new SaveOutcome.NotAuthenticated();
        try {
            eventService.editEventDetails(token, dto);
            return new SaveOutcome.Success();
        } catch (InvalidTokenException e) {
            return new SaveOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SaveOutcome.Failure(e.getMessage());
        }
    }

    /**
     * Creates a new event in DRAFT state under the caller's company. The company is resolved from
     * the caller's companies — {@code preferredCompanyId} (the workspace's currently selected
     * company) when it is one of them, otherwise the first (matching how
     * {@code CompanyEventListPresenter} picks the company for the event list the user came from).
     * Domain value objects ({@link Location}, {@link ShowDate}) are assembled here so the view stays
     * free of domain construction.
     */
    public CreateOutcome create(String token, Integer preferredCompanyId, String name,
                                String description, String categoryName, String country, String city,
                                LocalDateTime starts, LocalDateTime ends, List<String> artists,
                                Double rating) {
        if (token == null) return new CreateOutcome.NotAuthenticated();

        // Validate required inputs up front so bad/missing data surfaces as InvalidInput rather than
        // a leaked NullPointerException (from EventCategory.valueOf / new ShowDate) mapped to Failure.
        if (isBlank(name))         return new CreateOutcome.InvalidInput("An event title is required.");
        if (isBlank(categoryName)) return new CreateOutcome.InvalidInput("A category is required.");
        if (isBlank(country) || isBlank(city))
                                   return new CreateOutcome.InvalidInput("A country and a city are required.");
        if (starts == null || ends == null)
                                   return new CreateOutcome.InvalidInput("A start and an end time are required.");
        if (artists == null || artists.isEmpty())
                                   return new CreateOutcome.InvalidInput("At least one artist is required.");
        if (rating == null)        return new CreateOutcome.InvalidInput("A rating (0–5) is required.");
        // Double.isFinite guards against NaN/Infinity, for which `< 0`/`> 5` are both false and would
        // otherwise let an invalid rating slip past the range check into the domain.
        if (!Double.isFinite(rating) || rating < 0 || rating > 5)
                                   return new CreateOutcome.InvalidInput("Rating must be between 0 and 5.");

        try {
            Integer companyId = resolveCompanyId(token, preferredCompanyId);
            if (companyId == null) return new CreateOutcome.NoCompany();

            EventCreationDTO request = new EventCreationDTO(
                companyId,
                name,
                description,
                artists,
                EventCategory.valueOf(categoryName),
                rating,                                 // required 0–5 rating set by the organizer
                new Location(country, city),
                List.of(new ShowDate(starts, ends)),
                null);                                  // purchase policy — inherits company policy
            EventDetailDTO created = eventService.addEvent(token, request);
            return new CreateOutcome.Success(created.eventId());
        } catch (InvalidTokenException e) {
            return new CreateOutcome.NotAuthenticated();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new CreateOutcome.InvalidInput(e.getMessage());
        } catch (RuntimeException e) {
            return new CreateOutcome.Failure(e.getMessage());
        }
    }

    /**
     * Resolve the company to create under from the caller's companies (manager-inclusive via
     * {@code findMyCompanies}, so a MANAGE_INVENTORY manager can create under their company). When a
     * company is selected ({@code preferredCompanyId} non-null) it must be one of the caller's —
     * otherwise we return {@code null} ({@code → NoCompany}) rather than silently retargeting to a
     * different company. With no selection, use the first. {@code null} when the caller has none.
     * The {@code addEvent} service still enforces the MANAGE_INVENTORY permission.
     */
    private Integer resolveCompanyId(String token, Integer preferredCompanyId) {
        List<MyCompanyDTO> companies = companyService.findMyCompanies(token);
        if (companies.isEmpty()) return null;
        if (preferredCompanyId != null) {
            for (MyCompanyDTO c : companies) {
                if (c.companyId() == preferredCompanyId) return preferredCompanyId;
            }
            return null;   // selected company isn't one of the caller's — don't create elsewhere
        }
        return companies.get(0).companyId();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public CancelOutcome cancel(String token, int eventId) {
        if (token == null) return new CancelOutcome.NotAuthenticated();
        try {
            eventService.cancelEventAndRefund(token, eventId);
            return new CancelOutcome.Success();
        } catch (InvalidTokenException e) {
            return new CancelOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new CancelOutcome.Failure(e.getMessage());
        }
    }

    public sealed interface LoadOutcome {
        record Success(EventDetailDTO event) implements LoadOutcome {}
        record NotAuthenticated() implements LoadOutcome {}
        record NotFound() implements LoadOutcome {}
        record Failure(String reason) implements LoadOutcome {}
    }

    public sealed interface SaveOutcome {
        record Success() implements SaveOutcome {}
        record NotAuthenticated() implements SaveOutcome {}
        record Failure(String reason) implements SaveOutcome {}
    }

    public sealed interface CreateOutcome {
        record Success(String eventId) implements CreateOutcome {}
        record NotAuthenticated() implements CreateOutcome {}
        record NoCompany() implements CreateOutcome {}
        record InvalidInput(String reason) implements CreateOutcome {}
        record Failure(String reason) implements CreateOutcome {}
    }

    public sealed interface CancelOutcome {
        record Success() implements CancelOutcome {}
        record NotAuthenticated() implements CancelOutcome {}
        record Failure(String reason) implements CancelOutcome {}
    }
}