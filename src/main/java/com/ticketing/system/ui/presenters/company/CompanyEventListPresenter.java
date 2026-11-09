package com.ticketing.system.ui.presenters.company;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.CatalogSearchFiltersDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.ui.presenters.ListOutcome;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code CompanyEventListView}: lists the events of the caller's company, offers the
 * country and city filter options drawn from those events, and runs the cancel, delete and status
 * actions on one event. Holds no Vaadin imports; each call takes the member token the view reads from
 * its session and returns a typed outcome.
 *
 * <p>The company is the caller's workspace selection when it is one of their companies, else the
 * first of their companies. Managers count, since they may hold {@code MANAGE_INVENTORY}.
 */
@Component
public class CompanyEventListPresenter {

    private final EventManagementService eventService;
    private final CompanyManagementService companyManagementService;

    /**
     * Creates the presenter.
     *
     * @param eventService             lists and changes the company's events
     * @param companyManagementService resolves which companies the caller belongs to
     */
    @Autowired
    public CompanyEventListPresenter(EventManagementService eventService,
                                     CompanyManagementService companyManagementService) {
        this.eventService = eventService;
        this.companyManagementService = companyManagementService;
    }

    /**
     * Loads the company's events, narrowed by the filters.
     *
     * @param token     the caller's member token; {@code null} gives {@link Outcome.NotAuthenticated}
     * @param companyId the company the workspace has selected, or {@code null}; used when it is one of
     *     the caller's companies, else the first of them is used
     * @param filters   the view's filter set, or {@code null} for no filtering
     * @return {@link Outcome.NoCompany} when the caller belongs to no company; {@link Outcome.Failure}
     *     with {@code ServiceErrors}' text for any other failure
     */
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
            return new Outcome.Failure(ServiceErrors.userMessage(e));
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

    /**
     * Distinct countries this company has events in, sorted; none without a token or a company, and a
     * {@link ListOutcome.Failure} with {@code ServiceErrors}' text when the read fails.
     */
    public ListOutcome<String> countries(String token, Integer companyId) {
        try {
            return new ListOutcome.Loaded<>(companyEvents(token, companyId).stream()
                    .map(ev -> ev.location() == null ? null : ev.location().country())
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList());
        } catch (RuntimeException e) {
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Distinct cities this company has events in for {@code country}, sorted; none for a {@code null}
     * country, and a {@link ListOutcome.Failure} with {@code ServiceErrors}' text when the read fails.
     */
    public ListOutcome<String> cities(String token, Integer companyId, String country) {
        if (country == null) return new ListOutcome.Loaded<>(List.of());
        try {
            return new ListOutcome.Loaded<>(companyEvents(token, companyId).stream()
                    .filter(ev -> ev.location() != null && country.equalsIgnoreCase(ev.location().country()))
                    .map(ev -> ev.location().city())
                    .filter(c -> c != null && !c.isBlank())
                    .distinct()
                    .sorted(String.CASE_INSENSITIVE_ORDER)
                    .toList());
        } catch (RuntimeException e) {
            return new ListOutcome.Failure<>(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Cancels the event and refunds its buyers.
     *
     * @param token   the caller's member token, or {@code null}
     * @param eventId the event to cancel
     * @return {@link CancelOutcome.RefundFailed} when the event is cancelled but some refunds failed, or
     *     {@link CancelOutcome.Failure} with {@code ServiceErrors}' text for any other failure
     */
    public CancelOutcome cancelEvent(String token, int eventId) {
        if (token == null) return new CancelOutcome.NotAuthenticated();
        try {
            eventService.cancelEventAndRefund(token, eventId);
            return new CancelOutcome.Success();
        } catch (InvalidTokenException e) {
            return new CancelOutcome.NotAuthenticated();
        } catch (RefundFailedException e) {
            return new CancelOutcome.RefundFailed(CancelRefundFailure.reason(e),
                    CancelRefundFailure.cancelAgainRetries(e));
        } catch (RuntimeException e) {
            return new CancelOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Permanently deletes a cancelled event.
     *
     * @param token   the caller's member token; {@code null} gives {@link ActionOutcome.NotAuthenticated}
     * @param eventId the event to delete
     * @return {@link ActionOutcome.Failure} with {@code ServiceErrors}' text when the service refuses,
     *     for example for an event that is not cancelled or one with sales history
     */
    public ActionOutcome deleteEvent(String token, int eventId) {
        if (token == null) return new ActionOutcome.NotAuthenticated();
        try {
            eventService.deleteEvent(token, eventId);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Moves an event to another status without cancelling it; cancelling goes through
     * {@link #cancelEvent}.
     *
     * @param token        the caller's member token; {@code null} gives {@link ActionOutcome.NotAuthenticated}
     * @param eventId      the event to change
     * @param targetStatus the status to move to; the service accepts {@code SCHEDULED} and
     *     {@code ON_SALE}
     * @return {@link ActionOutcome.Failure} with {@code ServiceErrors}' text when the service refuses
     *     the transition
     */
    public ActionOutcome changeEventStatus(String token, int eventId, EventStatus targetStatus) {
        if (token == null) return new ActionOutcome.NotAuthenticated();
        try {
            eventService.changeEventStatus(token, eventId, targetStatus);
            return new ActionOutcome.Success();
        } catch (InvalidTokenException e) {
            return new ActionOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new ActionOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /** Sealed outcome of {@link #load}, which the view switches on. */
    public sealed interface Outcome {
        /**
         * The events loaded.
         *
         * @param events the company's events, possibly none
         */
        record Success(List<EventDetailDTO> events) implements Outcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements Outcome {}

        /** The caller belongs to no company. */
        record NoCompany() implements Outcome {}

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements Outcome {}
    }

    /** Sealed outcome of {@link #deleteEvent} and {@link #changeEventStatus}. */
    public sealed interface ActionOutcome {
        /** The service carried out the action. */
        record Success() implements ActionOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements ActionOutcome {}

        /**
         * The service refused the action or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements ActionOutcome {}
    }

    /** What {@link #cancelEvent} did. */
    public sealed interface CancelOutcome {
        /** The event is cancelled and the service reported no refund failure. */
        record Success() implements CancelOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements CancelOutcome {}

        /**
         * The event is cancelled, but some of its refunds failed: the cancel commits before its refunds run.
         *
         * @param reason             why, for the owner: fixed text for a failure the payment gateway raised,
         *                           else the refund service's summary naming the orders it could not refund
         * @param cancelAgainRetries {@code true} only when the payment gateway could not be reached
         *                           ({@code RefundGatewayUnreachableException}): no money moved and
         *                           {@code EventCancellationRefundService} released the claim, so
         *                           cancelling again retries the refund. {@code false} when the outcome
         *                           is unknown ({@code RefundOutcomeUnknownException}): the service leaves
         *                           the lines PENDING for manual recovery and a re-cancel skips them. Also
         *                           {@code false} for a plain {@code RefundFailedException}, which can mix
         *                           declined receipts, retried by a re-cancel, with pending ones, which
         *                           are not; its own message says which (ADR-001)
         */
        record RefundFailed(String reason, boolean cancelAgainRetries) implements CancelOutcome {}

        /**
         * The cancel failed for a reason other than a refund failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements CancelOutcome {}
    }
}
