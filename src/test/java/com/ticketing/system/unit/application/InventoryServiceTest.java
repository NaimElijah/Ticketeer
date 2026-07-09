package com.ticketing.system.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.port.in.InventoryLineDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.InventoryService;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.InventorySelection;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Seat;
import com.ticketing.system.catalog.domain.SeatedZone;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.shared.domain.policy.PurchaseContext;
import com.ticketing.system.shared.exception.ConcurrentReservationException;
import com.ticketing.system.shared.exception.EventNotFoundException;
import com.ticketing.system.shared.exception.EventNotOnSaleException;
import com.ticketing.system.shared.exception.InsufficientInventoryException;
import com.ticketing.system.shared.exception.PolicyViolationException;

/**
 * Direct unit coverage of {@link InventoryService} — the catalog inbound port implementation that took
 * ownership of Event/InventoryZone inventory mutation from sales. Verifies the reserve/release/restore
 * lock discipline and delegation, the CHECKOUT/RESERVE policy gate, the RESERVED->SOLD confirm with
 * partial-failure compensation, the seat-ownership confirm validation, discount pricing, on-sale checks,
 * and the best-effort SOLD->AVAILABLE refund return. Uses manual mocks (lenient) with mock Events for
 * delegation/ordering assertions and real zones where seat/inventory state is the thing under test.
 */
class InventoryServiceTest {

    private EventRepository eventRepository;
    private ProductionCompanyRepository companyRepository;
    private InventoryService service;

    private static final int EVENT_ID = 10;
    private static final int SEATED_ZONE = 1;
    private static final int STANDING_ZONE = 2;
    private static final int COMPANY_ID = 100;
    private static final String ORDER_KEY = "order-1";

    @BeforeEach
    void setUp() {
        eventRepository = mock(EventRepository.class);
        companyRepository = mock(ProductionCompanyRepository.class);
        service = new InventoryService(eventRepository, companyRepository);
    }

    // ---- reserve --------------------------------------------------------

    /** Reserve acquires the event buyer-lock, validates policy, mutates, saves, and unlocks — in order. */
    @Test
    void reserve_locksValidatesReservesSaves_andReturnsUnitPrice() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        InventoryZone zone = mock(InventoryZone.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(SEATED_ZONE)).thenReturn(zone);
        when(zone.getprice()).thenReturn(120.0);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(activeCompany());

        double price = service.reserve(EVENT_ID, SEATED_ZONE,
                InventorySelectionDTO.seated(List.of("A1", "A2")), ORDER_KEY, 42, 30, 2);

        assertThat(price).isEqualTo(120.0);
        // Strict ordering: buyer-lock first, policy before the mutation, save under the lock, unlock last.
        InOrder ordered = inOrder(eventRepository, event);
        ordered.verify(eventRepository).lockForBuyerOperation(EVENT_ID);
        ordered.verify(event).validateEffectivePolicy(any(), any(PurchaseContext.class));
        ordered.verify(event).reserveInventory(eq(SEATED_ZONE), any(InventorySelection.class));
        ordered.verify(eventRepository).save(event);
        ordered.verify(eventRepository).unlockBuyerOperation(EVENT_ID);
        // The selection handed to the domain carries the seats and the owning order key.
        ArgumentCaptor<InventorySelection> sel = ArgumentCaptor.forClass(InventorySelection.class);
        verify(event).reserveInventory(eq(SEATED_ZONE), sel.capture());
        assertThat(sel.getValue().getSeatNumbers()).containsExactly("A1", "A2");
        assertThat(sel.getValue().getOrderKey()).isEqualTo(ORDER_KEY);
    }

    /** A policy rejection is raised BEFORE any inventory mutation, and the lock is still released. */
    @Test
    void reserve_policyRejected_doesNotReserve_andStillUnlocks() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        InventoryZone zone = mock(InventoryZone.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(STANDING_ZONE)).thenReturn(zone);
        doThrow(new PolicyViolationException("max 1 ticket"))
                .when(event).validateEffectivePolicy(any(), any(PurchaseContext.class));

        assertThatThrownBy(() -> service.reserve(EVENT_ID, STANDING_ZONE,
                InventorySelectionDTO.standing(2), ORDER_KEY, 42, 30, 2))
                .isInstanceOf(PolicyViolationException.class);

        verify(event, never()).reserveInventory(anyInt(), any());
        verify(eventRepository, never()).save(any());
        verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    /** A missing event fails fast (and, being pre-mutation, releases the lock it took). */
    @Test
    void reserve_eventNotFound_throwsAndUnlocks() {
        when(eventRepository.findById(EVENT_ID)).thenReturn(null);

        assertThatThrownBy(() -> service.reserve(EVENT_ID, SEATED_ZONE,
                InventorySelectionDTO.standing(1), ORDER_KEY, -1, null, 1))
                .isInstanceOf(IllegalArgumentException.class);

        verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    // ---- release / restore ---------------------------------------------

    /** Release delegates to the domain, saves under the lock, unlocks, and returns the unit price. */
    @Test
    void release_releasesSavesUnlocks_andReturnsPrice() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        InventoryZone zone = mock(InventoryZone.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(STANDING_ZONE)).thenReturn(zone);
        when(zone.getprice()).thenReturn(55.0);

        double price = service.release(EVENT_ID, STANDING_ZONE, InventorySelectionDTO.standing(3), ORDER_KEY);

        assertThat(price).isEqualTo(55.0);
        InOrder ordered = inOrder(eventRepository, event);
        ordered.verify(eventRepository).lockForBuyerOperation(EVENT_ID);
        ordered.verify(event).releaseInventory(eq(STANDING_ZONE), any(InventorySelection.class));
        ordered.verify(eventRepository).save(event);
        ordered.verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    /** Restore re-reserves WITHOUT re-running the purchase policy (it is a rollback compensation). */
    @Test
    void restore_reReservesWithoutPolicy() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        service.restore(EVENT_ID, SEATED_ZONE, InventorySelectionDTO.seated(List.of("A1")), ORDER_KEY);

        verify(event).reserveInventory(eq(SEATED_ZONE), any(InventorySelection.class));
        verify(eventRepository).save(event);
        verify(event, never()).validateEffectivePolicy(any(), any());
        verify(companyRepository, never()).getCompanyById(anyInt());
        verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    // ---- returnSoldToStock (refund) ------------------------------------

    /** Refund return groups by event+zone, returns SOLD seats/places to stock under the buyer lock. */
    @Test
    void returnSoldToStock_returnsSeatedAndStandingUnderLock() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        service.returnSoldToStock(List.of(
                new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1"),
                new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A2"),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)));

        InOrder ordered = inOrder(eventRepository);
        ordered.verify(eventRepository).lockForBuyerOperation(EVENT_ID);
        ordered.verify(eventRepository).save(event);
        ordered.verify(eventRepository).unlockBuyerOperation(EVENT_ID);

        ArgumentCaptor<InventorySelection> sel = ArgumentCaptor.forClass(InventorySelection.class);
        verify(event, times(2)).returnSoldToStock(anyInt(), sel.capture());
        // The standing selection is a count of 2; the seated selection carries both seat labels.
        assertThat(sel.getAllValues())
                .anySatisfy(s -> assertThat(s.getSeatNumbers()).containsExactlyInAnyOrder("A1", "A2"))
                .anySatisfy(s -> assertThat(s.isStandingSelection() && s.getQuantity() == 2).isTrue());
    }

    /** A stock-return failure is swallowed (best-effort) and the lock is still released. */
    @Test
    void returnSoldToStock_failureIsSwallowed_andLockReleased() {
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        doThrow(new IllegalStateException("boom")).when(event).returnSoldToStock(anyInt(), any());

        // Must not throw despite the domain failure.
        service.returnSoldToStock(List.of(new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)));

        verify(eventRepository).unlockBuyerOperation(EVENT_ID);
    }

    // ---- pricing / name / policy / on-sale reads -----------------------

    /** Pricing delegates to the event's discount policy; a missing event fails with EventNotFound. */
    @Test
    void priceTicket_delegatesToDiscountPolicy_andFailsWhenMissing() {
        Event event = mock(Event.class);
        LocalDateTime now = LocalDateTime.now();
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.calculatePriceforoneticket(2, 40.0, now)).thenReturn(36.0);

        assertThat(service.priceTicket(EVENT_ID, 2, 40.0, now)).isEqualTo(36.0);

        when(eventRepository.findById(999)).thenReturn(null);
        assertThatThrownBy(() -> service.priceTicket(999, 1, 10.0, now))
                .isInstanceOf(EventNotFoundException.class);
    }

    /** eventName returns the name, or fails with EventNotFound when the event is gone. */
    @Test
    void eventName_returnsName_orThrowsWhenMissing() {
        Event event = mock(Event.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getName()).thenReturn("Rock Night");
        assertThat(service.eventName(EVENT_ID)).isEqualTo("Rock Night");

        when(eventRepository.findById(999)).thenReturn(null);
        assertThatThrownBy(() -> service.eventName(999)).isInstanceOf(EventNotFoundException.class);
    }

    /** CHECKOUT-stage policy validation loads the event, resolves its company, and delegates. */
    @Test
    void validatePurchasePolicy_delegates_andPropagatesRejection() {
        Event event = mock(Event.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getId()).thenReturn(EVENT_ID);
        when(event.getCompanyId()).thenReturn(COMPANY_ID);
        when(companyRepository.getCompanyById(COMPANY_ID)).thenReturn(activeCompany());
        doThrow(new PolicyViolationException("min 2")).when(event)
                .validateEffectivePolicy(any(), any(PurchaseContext.class));

        assertThatThrownBy(() -> service.validatePurchasePolicy(EVENT_ID, 42, 30, 1))
                .isInstanceOf(PolicyViolationException.class);
    }

    /** On-sale check accepts ON_SALE and SOLD_OUT, rejects other states, and flags a missing event. */
    @Test
    void validateEventsOnSale_acceptsSellable_rejectsOthers() {
        Event onSale = mock(Event.class);
        when(onSale.getStatus()).thenReturn(EventStatus.ON_SALE);
        Event soldOut = mock(Event.class);
        when(soldOut.getStatus()).thenReturn(EventStatus.SOLD_OUT);
        Event draft = mock(Event.class);
        when(draft.getStatus()).thenReturn(EventStatus.DRAFT);
        when(eventRepository.findById(1)).thenReturn(onSale);
        when(eventRepository.findById(2)).thenReturn(soldOut);
        when(eventRepository.findById(3)).thenReturn(draft);
        when(eventRepository.findById(4)).thenReturn(null);

        service.validateEventsOnSale(List.of(1, 2)); // ON_SALE + SOLD_OUT are both allowed

        assertThatThrownBy(() -> service.validateEventsOnSale(List.of(3)))
                .isInstanceOf(EventNotOnSaleException.class);
        assertThatThrownBy(() -> service.validateEventsOnSale(List.of(4)))
                .isInstanceOf(EventNotFoundException.class);
    }

    // ---- validateCanConfirmSale (seat ownership / status) --------------

    /** A seat still RESERVED by this order passes; a different owner or a non-RESERVED seat fails. */
    @Test
    void validateCanConfirmSale_enforcesSeatStatusAndOwnership() {
        SeatedZone seated = new SeatedZone(SEATED_ZONE, "Orchestra", 120.0,
                List.of(new Seat("A1", 0, 0), new Seat("A2", 1, 0)));
        seated.reserve(InventorySelection.seated(List.of("A1"), ORDER_KEY)); // A1 RESERVED by order-1
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(SEATED_ZONE)).thenReturn(seated);

        // Owner + RESERVED → OK.
        service.validateCanConfirmSale(List.of(new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1")), ORDER_KEY);

        // Different order key holding the seat → rejected.
        assertThatThrownBy(() -> service.validateCanConfirmSale(
                List.of(new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1")), "someone-else"))
                .isInstanceOf(ConcurrentReservationException.class);

        // A seat that is not RESERVED (A2 still AVAILABLE) → rejected.
        assertThatThrownBy(() -> service.validateCanConfirmSale(
                List.of(new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A2")), ORDER_KEY))
                .isInstanceOf(ConcurrentReservationException.class);
    }

    /** A standing zone without enough RESERVED stock to cover the lines cannot be confirmed. */
    @Test
    void validateCanConfirmSale_standingWithoutEnoughReserved_throws() {
        StandingZone standing = new StandingZone(STANDING_ZONE, "Floor", 10, 40.0);
        standing.reserve(InventorySelection.standing(1, ORDER_KEY)); // only 1 reserved
        Event event = mock(Event.class, RETURNS_DEEP_STUBS);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        when(event.getVenueMap().getZone(STANDING_ZONE)).thenReturn(standing);

        // Two standing lines but only one place reserved → insufficient.
        assertThatThrownBy(() -> service.validateCanConfirmSale(List.of(
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)), ORDER_KEY))
                .isInstanceOf(InsufficientInventoryException.class);
    }

    // ---- confirmSale (+ compensation) / releaseHeld --------------------

    /** Confirm marks each zone SOLD and saves the event once. */
    @Test
    void confirmSale_confirmsEachZone_andSaves() {
        Event event = mock(Event.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);

        service.confirmSale(List.of(
                new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1"),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)), ORDER_KEY);

        verify(event, times(2)).confirmInventorySale(anyInt(), any(InventorySelection.class));
        verify(eventRepository).save(event);
    }

    /** A confirm that fails partway compensates the already-confirmed unit back to AVAILABLE, then rethrows. */
    @Test
    void confirmSale_partialFailure_compensatesConfirmedUnits() {
        Event event = mock(Event.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(inv -> {
            if (calls.incrementAndGet() == 2) {
                throw new RuntimeException("confirm boom on the second zone");
            }
            return null;
        }).when(event).confirmInventorySale(anyInt(), any(InventorySelection.class));

        assertThatThrownBy(() -> service.confirmSale(List.of(
                new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1"),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)), ORDER_KEY))
                .isInstanceOf(RuntimeException.class);

        // Two confirm attempts; the first (successful) unit is reversed SOLD -> AVAILABLE.
        verify(event, times(2)).confirmInventorySale(anyInt(), any(InventorySelection.class));
        verify(event, times(1)).returnSoldToStock(anyInt(), any(InventorySelection.class));
    }

    /** Release-held frees each still-RESERVED zone; a zone that can't be released is skipped best-effort. */
    @Test
    void releaseHeld_releasesEachZone_bestEffort() {
        Event event = mock(Event.class);
        when(eventRepository.findById(EVENT_ID)).thenReturn(event);
        // The standing zone can no longer be released; the seated one still can.
        doAnswer(inv -> {
            InventorySelection sel = inv.getArgument(1);
            if (sel.isStandingSelection()) {
                throw new IllegalStateException("already confirmed");
            }
            return null;
        }).when(event).releaseInventory(anyInt(), any(InventorySelection.class));

        // Must not throw — per-zone failure is logged and skipped.
        service.releaseHeld(List.of(
                new InventoryLineDTO(EVENT_ID, SEATED_ZONE, "A1"),
                new InventoryLineDTO(EVENT_ID, STANDING_ZONE, null)), ORDER_KEY);

        verify(event, times(2)).releaseInventory(anyInt(), any(InventorySelection.class));
        verify(eventRepository).save(event); // at least the seated zone released → event persisted
    }

    // ---- helpers --------------------------------------------------------

    /** A permissive active company (its default NoPurchasePolicy never rejects). */
    private ProductionCompany activeCompany() {
        return new ProductionCompany(COMPANY_ID, 1, "Test Co", CompanyStatus.ACTIVE, "desc", 5.0);
    }
}
