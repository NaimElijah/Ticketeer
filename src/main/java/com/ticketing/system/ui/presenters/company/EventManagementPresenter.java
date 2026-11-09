package com.ticketing.system.ui.presenters.company;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventUpdateDTO;
import com.ticketing.system.shared.dto.MyCompanyDTO;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.config.PlatformTimeProperties;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.InvalidTokenException;
import com.ticketing.system.shared.exception.RefundFailedException;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code EventManagementView}: loads, edits, creates and cancels one event of the
 * caller's company. Holds no Vaadin imports; each call takes the member token the view reads from its
 * session and returns a typed outcome.
 */
@Component
public class EventManagementPresenter {

    private final EventManagementService eventService;
    private final CompanyManagementService companyService;
    private final PlatformTimeProperties platformTime;

    /**
     * Creates the presenter.
     *
     * @param eventService  loads, edits, creates and cancels events
     * @param companyService lists the companies the caller belongs to
     * @param platformTime  the platform's time-zone setting, behind {@link #defaultTimeZone()}
     */
    @Autowired
    public EventManagementPresenter(EventManagementService eventService,
                                    CompanyManagementService companyService,
                                    PlatformTimeProperties platformTime) {
        this.eventService = eventService;
        this.companyService = companyService;
        this.platformTime = platformTime;
    }

    /** The zone a new event's form starts on: the platform's home zone ({@code platform.time-zone}). */
    public ZoneId defaultTimeZone() {
        return platformTime.timeZone();
    }

    /**
     * Loads one event for editing.
     *
     * @param token   the caller's member token; {@code null} or a rejected token gives
     *     {@link LoadOutcome.NotAuthenticated}
     * @param eventId the event to load
     * @return {@link LoadOutcome.NotFound} when no such event exists; {@link LoadOutcome.Failure} with
     *     {@code ServiceErrors}' text for any other failure, including a caller who is not a member of
     *     the event's company
     */
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
            return new LoadOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Saves the edited details of an event.
     *
     * @param token the caller's member token; {@code null} gives {@link SaveOutcome.NotAuthenticated}
     * @param dto   the event's id and its new details
     * @return {@link SaveOutcome.Failure} with {@code ServiceErrors}' text when the service refuses
     *     the edit or fails
     */
    public SaveOutcome save(String token, EventUpdateDTO dto) {
        if (token == null) return new SaveOutcome.NotAuthenticated();
        try {
            eventService.editEventDetails(token, dto);
            return new SaveOutcome.Success();
        } catch (InvalidTokenException e) {
            return new SaveOutcome.NotAuthenticated();
        } catch (RuntimeException e) {
            return new SaveOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Creates a new event in DRAFT state under the caller's company. The company is picked by
     * {@link SelectedCompany}'s rule from every company the caller belongs to ({@code findMyCompanies},
     * managers included, since {@code addEvent} itself checks the MANAGE_INVENTORY permission):
     * {@code preferredCompanyId}, the workspace's selection, when it is one of them; with nothing
     * selected, the only one. Otherwise {@link CreateOutcome.ChooseCompany}, and nothing is created,
     * so an event never lands in a company other than the one on screen.
     * Domain value objects ({@link Location}, {@link ShowDate}) are assembled here so the view stays
     * free of domain construction. {@code starts} and {@code ends} are wall-clock times at the venue,
     * read in {@code timeZone} (ADR-008).
     *
     * @param token              the caller's member token; {@code null} or a rejected token gives
     *     {@link CreateOutcome.NotAuthenticated}
     * @param preferredCompanyId the company the workspace has selected, or {@code null} for none
     * @param name               the event title
     * @param description        the event description
     * @param categoryName       the name of an {@code EventCategory} constant
     * @param country            the venue's country
     * @param city               the venue's city
     * @param starts             when the show starts, as venue wall-clock time
     * @param ends               when the show ends, as venue wall-clock time
     * @param timeZone           the venue's time zone
     * @param artists            the lineup, at least one name
     * @param rating             the organizer's rating, from 0 to 5
     * @return {@link CreateOutcome.InvalidInput} when a required input is missing or out of range, or
     *     the service refuses it; {@link CreateOutcome.NoCompany} when the caller belongs to no
     *     company; {@link CreateOutcome.ChooseCompany} when no single company can be picked;
     *     {@link CreateOutcome.Failure} with {@code ServiceErrors}' text for any other failure
     */
    public CreateOutcome create(String token, Integer preferredCompanyId, String name,
                                String description, String categoryName, String country, String city,
                                LocalDateTime starts, LocalDateTime ends, ZoneId timeZone,
                                List<String> artists, Double rating) {
        if (token == null) return new CreateOutcome.NotAuthenticated();

        // Validate required inputs up front so bad/missing data surfaces as InvalidInput rather than
        // a leaked NullPointerException (from EventCategory.valueOf / new ShowDate) mapped to Failure.
        if (isBlank(name))         return new CreateOutcome.InvalidInput("An event title is required.");
        if (isBlank(categoryName)) return new CreateOutcome.InvalidInput("A category is required.");
        if (isBlank(country) || isBlank(city))
                                   return new CreateOutcome.InvalidInput("A country and a city are required.");
        if (starts == null || ends == null)
                                   return new CreateOutcome.InvalidInput("A start and an end time are required.");
        if (timeZone == null)      return new CreateOutcome.InvalidInput("A time zone is required.");
        if (artists == null || artists.isEmpty())
                                   return new CreateOutcome.InvalidInput("At least one artist is required.");
        if (rating == null)        return new CreateOutcome.InvalidInput("A rating (0–5) is required.");
        // Double.isFinite guards against NaN/Infinity, for which `< 0`/`> 5` are both false and would
        // otherwise let an invalid rating slip past the range check into the domain.
        if (!Double.isFinite(rating) || rating < 0 || rating > 5)
                                   return new CreateOutcome.InvalidInput("Rating must be between 0 and 5.");

        try {
            List<MyCompanyDTO> companies = companyService.findMyCompanies(token);
            if (companies.isEmpty()) return new CreateOutcome.NoCompany();
            Optional<MyCompanyDTO> company =
                SelectedCompany.among(preferredCompanyId, companies, MyCompanyDTO::companyId);
            if (company.isEmpty()) return new CreateOutcome.ChooseCompany();

            EventCreationDTO request = new EventCreationDTO(
                company.get().companyId(),
                name,
                description,
                artists,
                EventCategory.valueOf(categoryName),
                rating,                                 // required 0–5 rating set by the organizer
                new Location(country, city),
                List.of(new ShowDate(starts, ends)),
                timeZone,
                null);                                  // purchase policy — inherits company policy
            EventDetailDTO created = eventService.addEvent(token, request);
            return new CreateOutcome.Success(created.eventId());
        } catch (InvalidTokenException e) {
            return new CreateOutcome.NotAuthenticated();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new CreateOutcome.InvalidInput(ServiceErrors.userMessage(e));
        } catch (RuntimeException e) {
            return new CreateOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Cancels the event and refunds its buyers.
     *
     * @param token   the caller's member token, or {@code null}
     * @param eventId the event to cancel
     * @return {@link CancelOutcome.RefundFailed} when the event is cancelled but some refunds failed, or
     *     {@link CancelOutcome.Failure} with {@code ServiceErrors}' text for any other failure
     */
    public CancelOutcome cancel(String token, int eventId) {
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

    /** Sealed outcome of {@link #load}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The event loaded.
         *
         * @param event the event's details
         */
        record Success(EventDetailDTO event) implements LoadOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements LoadOutcome {}

        /** No event has that id. */
        record NotFound() implements LoadOutcome {}

        /**
         * The load failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements LoadOutcome {}
    }

    /** Sealed outcome of {@link #save}, which the view switches on. */
    public sealed interface SaveOutcome {
        /** The service stored the edit. */
        record Success() implements SaveOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements SaveOutcome {}

        /**
         * The service refused the edit or failed.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements SaveOutcome {}
    }

    /** Sealed outcome of {@link #create}, which the view switches on. */
    public sealed interface CreateOutcome {
        /**
         * The event is created in DRAFT state.
         *
         * @param eventId the new event's id
         */
        record Success(String eventId) implements CreateOutcome {}

        /** There is no token, or the service rejected it as invalid. */
        record NotAuthenticated() implements CreateOutcome {}

        /** The caller belongs to no company. */
        record NoCompany() implements CreateOutcome {}

        /**
         * The caller belongs to several companies and has selected none of them, or the selection is a
         * company they do not belong to.
         */
        record ChooseCompany() implements CreateOutcome {}

        /**
         * A required input is missing or out of range, or the service refused the event with an
         * {@code IllegalArgumentException} or {@code IllegalStateException}.
         *
         * @param reason the text the user may read
         */
        record InvalidInput(String reason) implements CreateOutcome {}

        /**
         * Any other failure.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements CreateOutcome {}
    }

    /** What {@link #cancel} did. */
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
         * The call threw something other than a refund failure. The event may already be cancelled,
         * since the cancel commits before its refunds run.
         *
         * @param reason the text the user may read, from {@code ServiceErrors.userMessage}
         */
        record Failure(String reason) implements CancelOutcome {}
    }
}