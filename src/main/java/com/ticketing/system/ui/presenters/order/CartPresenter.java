package com.ticketing.system.ui.presenters.order;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.ui.components.Money;
import com.ticketing.system.ui.session.SessionIdentity;

@Component
@Slf4j
public class CartPresenter {

    private final ReservationService reservationService;
    private final SessionIdentity identity;

    @Autowired
    public CartPresenter(ReservationService reservationService, SessionIdentity identity) {
        this.reservationService = reservationService;
        this.identity = identity;
    }

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
            log.error("Loading cart failed unexpectedly", e);
            return new LoadOutcome.Failure(null);
        }
    }

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
            log.error("Removing cart line failed unexpectedly", e);
            return new RemoveOutcome.Failure(null);
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

    public record CartVM(List<LineVM> lines, long remainingSecondsBeforeExpiry) {
        public record LineVM(
            String eventName,
            String seatNumber,
            double pricePerTicket,
            int eventId,
            int zoneId
        ) {}
    }

    public sealed interface LoadOutcome {
        record Shown(CartVM cart, long subtotalCents)        implements LoadOutcome { }
        record Empty()                                       implements LoadOutcome { }
        record NotAuthenticated()                            implements LoadOutcome { }
        record Failure(String reason)                        implements LoadOutcome { }
    }

    public sealed interface RemoveOutcome {
        record Removed(CartVM cart, long subtotalCents)      implements RemoveOutcome { }
        record Failure(String reason)                        implements RemoveOutcome { }
    }
}