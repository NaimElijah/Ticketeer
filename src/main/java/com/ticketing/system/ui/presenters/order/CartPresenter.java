package com.ticketing.system.ui.presenters.order;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.session.SessionIdentity;
import com.ticketing.system.ui.support.ServiceErrors;

/**
 * Presenter for {@code CartView}: loads the visitor's active order as a view model and removes one
 * line from it. Holds no Vaadin imports. The visitor is resolved through {@link SessionIdentity}, so a
 * member and a guest use the same calls.
 */
@Component
public class CartPresenter {

    private final ReservationService reservationService;
    private final SessionIdentity identity;

    /**
     * Creates the presenter.
     *
     * @param reservationService reads and edits the visitor's active order
     * @param identity           resolves the visitor's credential
     */
    @Autowired
    public CartPresenter(ReservationService reservationService, SessionIdentity identity) {
        this.reservationService = reservationService;
        this.identity = identity;
    }

    /**
     * Loads the visitor's active order with its subtotal.
     *
     * @return {@link LoadOutcome.NotAuthenticated} when the visitor has no credential;
     *     {@link LoadOutcome.Empty} when there is no order or it has no lines;
     *     {@link LoadOutcome.Failure} when the read fails, with the message of a {@code DomainException}
     *     as it is, else {@code ServiceErrors}' text
     */
    public LoadOutcome loadCart() {
        String credential = identity.credential();
        if (credential == null || credential.isBlank()) {
            return new LoadOutcome.NotAuthenticated();
        }
        try {
            ActiveOrderDTO order = reservationService.viewMyActiveOrder(credential);
            if (order == null || order.lines().isEmpty()) {
                return new LoadOutcome.Empty();
            }
            return new LoadOutcome.Shown(toVM(order), subtotalCents(order));
        } catch (DomainException e) {
            return new LoadOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            return new LoadOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    /**
     * Removes one seat, or one standing place, from the visitor's active order and re-reads the order.
     *
     * @param line the line to remove; a line with a seat number removes that seat, any other removes
     *     one standing place of its zone
     * @return {@link RemoveOutcome.Removed} with the order as it now stands, which has no lines after
     *     the last one is removed; {@link RemoveOutcome.Failure} when {@code line} is {@code null},
     *     the visitor has no credential, or the service refuses or fails
     */
    public RemoveOutcome removeLine(CartVM.LineVM line) {
        if (line == null) {
            return new RemoveOutcome.Failure("No line selected");
        }
        String credential = identity.credential();
        if (credential == null || credential.isBlank()) {
            return new RemoveOutcome.Failure("Your session has expired");
        }
        try {
            InventorySelectionDTO selection = (line.seatNumber() != null)
                ? InventorySelectionDTO.seated(List.of(line.seatNumber()))
                : InventorySelectionDTO.standing(1);

            reservationService.removeLine(credential, line.eventId(), line.zoneId(), selection);

            ActiveOrderDTO updated = reservationService.viewMyActiveOrder(credential);
            if (updated == null || updated.lines().isEmpty()) {
                // Removing the last line empties the order — return an empty cart, not an error.
                return new RemoveOutcome.Removed(new CartVM(List.of(), 0L), 0L);
            }
            return new RemoveOutcome.Removed(toVM(updated), subtotalCents(updated));
        } catch (DomainException e) {
            return new RemoveOutcome.Failure(e.getMessage());
        } catch (RuntimeException e) {
            return new RemoveOutcome.Failure(ServiceErrors.userMessage(e));
        }
    }

    private CartVM toVM(ActiveOrderDTO order) {
        List<CartVM.LineVM> lines = order.lines().stream()
            .map(l -> new CartVM.LineVM(
                l.eventName(), l.seatNumber(), l.pricePerTicket(),
                l.eventId(), l.zoneId()))
            .toList();
        return new CartVM(lines, order.remainingSecondsBeforeExpiry());
    }

    private long subtotalCents(ActiveOrderDTO order) {
        if (order == null || order.lines().isEmpty()) return 0L;
        return order.lines().stream()
                .mapToLong(l -> Money.toCents(l.pricePerTicket()))
                .sum();
    }

    /**
     * The active order as the cart page shows it.
     *
     * @param lines                       one entry per seat or standing place
     * @param remainingSecondsBeforeExpiry how long the order's hold lasts, in seconds
     */
    public record CartVM(List<LineVM> lines, long remainingSecondsBeforeExpiry) {
        /**
         * One seat or standing place in the cart.
         *
         * @param eventName     the event's display name
         * @param seatNumber    the seat, or {@code null} for a standing place
         * @param pricePerTicket the ticket's price
         * @param eventId       the event the line belongs to
         * @param zoneId        the zone the line belongs to
         */
        public record LineVM(
            String eventName,
            String seatNumber,
            double pricePerTicket,
            int eventId,
            int zoneId
        ) {}
    }

    /** Sealed outcome of {@link #loadCart}, which the view switches on. */
    public sealed interface LoadOutcome {
        /**
         * The cart has lines.
         *
         * @param cart          the order's lines and remaining hold time
         * @param subtotalCents the sum of the lines' prices, in cents
         */
        record Shown(CartVM cart, long subtotalCents)        implements LoadOutcome { }

        /** The visitor has no order, or an order with no lines. */
        record Empty()                                       implements LoadOutcome { }

        /** The visitor has no credential. */
        record NotAuthenticated()                            implements LoadOutcome { }

        /**
         * The read failed.
         *
         * @param reason the text the user may read
         */
        record Failure(String reason)                        implements LoadOutcome { }
    }

    /** Sealed outcome of {@link #removeLine}, which the view switches on. */
    public sealed interface RemoveOutcome {
        /**
         * The line is removed.
         *
         * @param cart          the order as it now stands; no lines when the last one was removed
         * @param subtotalCents the sum of the remaining lines' prices, in cents; 0 for no lines
         */
        record Removed(CartVM cart, long subtotalCents)      implements RemoveOutcome { }

        /**
         * The line was not removed.
         *
         * @param reason the text the user may read
         */
        record Failure(String reason)                        implements RemoveOutcome { }
    }
}