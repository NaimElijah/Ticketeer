package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.Core.Application.services.SystemIntegrityVerifier;
import com.ticketing.system.Core.Domain.ActiveOrder.ActiveOrder;
import com.ticketing.system.Core.Domain.ActiveOrder.CartLineItem;
import com.ticketing.system.Core.Domain.ActiveOrder.IActiveOrderRepository;
import com.ticketing.system.Core.Domain.company.IProductionCompanyRepository;
import com.ticketing.system.Core.Domain.company.ProductionCompany;
import com.ticketing.system.Core.Domain.events.DiscountPolicy;
import com.ticketing.system.Core.Domain.events.Event;
import com.ticketing.system.Core.Domain.events.EventCategory;
import com.ticketing.system.Core.Domain.events.EventStatus;
import com.ticketing.system.Core.Domain.events.IEventRepository;
import com.ticketing.system.Core.Domain.events.InventoryZone;
import com.ticketing.system.Core.Domain.events.Location;
import com.ticketing.system.Core.Domain.events.ShowDate;
import com.ticketing.system.Core.Domain.events.StandingZone;
import com.ticketing.system.Core.Domain.events.VenueMap;
import com.ticketing.system.shared.exception.SystemIntegrityViolationException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.Core.Domain.policies.purchase.NoPurchasePolicy;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

class SystemIntegrityVerifierTest {

    private static final LocalDateTime FUTURE_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime FUTURE_END = LocalDateTime.of(2099, 6, 1, 22, 0);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final IProductionCompanyRepository companyRepository = mock(IProductionCompanyRepository.class);
    private final IEventRepository eventRepository = mock(IEventRepository.class);
    private final IActiveOrderRepository activeOrderRepository = mock(IActiveOrderRepository.class);
    private final SystemIntegrityVerifier verifier = new SystemIntegrityVerifier(
            userRepository, companyRepository, eventRepository, activeOrderRepository);

    // Default every enumeration to empty so a test only stubs the aggregate it exercises;
    // the V3 scans iterate all four repos, so an unstubbed findAll() would otherwise NPE.
    @BeforeEach
    void emptyByDefault() {
        when(companyRepository.findActive()).thenReturn(List.of());
        when(userRepository.findAll()).thenReturn(List.of());
        when(companyRepository.findAll()).thenReturn(List.of());
        when(eventRepository.findAll()).thenReturn(List.of());
        when(activeOrderRepository.findAll()).thenReturn(List.of());
    }

    // ---- existing structural scans (#4 / #6) ----

    @Test
    void givenEmptySystem_whenVerify_thenPasses() {
        assertDoesNotThrow(verifier::verify);
    }

    @Test
    void givenActiveCompanyWithOwnerAndKnownProducers_whenVerify_thenPasses() {
        ProductionCompany company = mock(ProductionCompany.class);
        when(company.getOwnerIds()).thenReturn(List.of(5));
        when(company.getManagers()).thenReturn(List.of(6));
        when(companyRepository.findActive()).thenReturn(List.of(company));
        when(userRepository.getUserById(5)).thenReturn(mock(User.class));
        when(userRepository.getUserById(6)).thenReturn(mock(User.class));

        assertDoesNotThrow(verifier::verify);
    }

    @Test
    void givenActiveCompanyWithNoOwner_whenVerify_thenThrows() {   // constraint #6
        ProductionCompany company = mock(ProductionCompany.class);
        when(company.getOwnerIds()).thenReturn(List.of());
        when(company.getName()).thenReturn("Ghost Co");
        when(companyRepository.findActive()).thenReturn(List.of(company));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    @Test
    void givenProducerThatIsNotAUser_whenVerify_thenThrows() {     // constraint #4
        ProductionCompany company = mock(ProductionCompany.class);
        when(company.getOwnerIds()).thenReturn(List.of(5));
        when(company.getManagers()).thenReturn(List.of());
        when(company.getName()).thenReturn("Phantom Co");
        when(companyRepository.findActive()).thenReturn(List.of(company));
        when(userRepository.getUserById(5)).thenThrow(new UserNotFoundException(5));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    // ---- #372 V3 scans against loaded state ----

    @Test
    void givenLoadedAggregateThatViolatesItsInvariant_whenVerify_thenThrows() {   // re-validation sweep
        Event badEvent = mock(Event.class);
        doThrow(new IllegalStateException("rating out of range")).when(badEvent).checkInvariants();
        when(eventRepository.findAll()).thenReturn(List.of(badEvent));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    @Test
    void givenDuplicateUsernames_whenVerify_thenThrows() {   // constraint #1
        User a = mock(User.class);
        User b = mock(User.class);
        when(a.getUsername()).thenReturn("alice");
        when(b.getUsername()).thenReturn("alice");
        when(userRepository.findAll()).thenReturn(List.of(a, b));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    @Test
    void givenDistinctUsernames_whenVerify_thenPasses() {   // constraint #1 — happy path
        User a = mock(User.class);
        User b = mock(User.class);
        when(a.getUsername()).thenReturn("alice");
        when(b.getUsername()).thenReturn("bob");
        when(userRepository.findAll()).thenReturn(List.of(a, b));

        assertDoesNotThrow(verifier::verify);
    }

    @Test
    void givenBuyerWithTwoOrdersForSameEvent_whenVerify_thenThrows() {   // constraint #8
        ActiveOrder order1 = orderFor(5, "ok-1", 7);
        ActiveOrder order2 = orderFor(5, "ok-2", 7);
        when(activeOrderRepository.findAll()).thenReturn(List.of(order1, order2));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    @Test
    void givenBuyerWithOrdersForDifferentEvents_whenVerify_thenPasses() {   // constraint #8 — happy path
        ActiveOrder order1 = orderFor(5, "ok-1", 7);
        ActiveOrder order2 = orderFor(5, "ok-2", 8);
        when(activeOrderRepository.findAll()).thenReturn(List.of(order1, order2));

        assertDoesNotThrow(verifier::verify);
    }

    @Test
    void givenOversoldZone_whenVerify_thenThrows() {   // constraint #12
        when(eventRepository.findAll()).thenReturn(List.of(eventWithSoldUnits(200))); // 200 > capacity 100

        assertThrows(SystemIntegrityViolationException.class, verifier::verify);
    }

    @Test
    void givenZoneWithinCapacity_whenVerify_thenPasses() {   // constraint #12 — happy path
        when(eventRepository.findAll()).thenReturn(List.of(eventWithSoldUnits(0))); // sold 0 <= capacity 100

        assertDoesNotThrow(verifier::verify);
    }

    // ---- helpers ----

    private static ActiveOrder orderFor(int userId, String orderKey, int eventId) {
        CartLineItem item = mock(CartLineItem.class);
        when(item.geteventId()).thenReturn(eventId);
        ActiveOrder order = mock(ActiveOrder.class);
        when(order.userIdOrNull()).thenReturn(userId);
        when(order.getOrderKey()).thenReturn(orderKey);
        when(order.getItems()).thenReturn(List.of(item));
        return order;
    }

    // Real Event with a real 100-capacity StandingZone. Inventory accessors are abstract/final so they
    // resist mocking; instead the zone's soldAmount is corrupted directly to simulate an oversold DB row
    // (the domain API never lets sold exceed capacity, which is the whole point of the boot scan).
    private static Event eventWithSoldUnits(int sold) {
        InventoryZone zone = new StandingZone(1, "Floor", 100, 50);
        if (sold > 0) {
            setPrivateInt(zone, "soldAmount", sold);
        }
        VenueMap venueMap = new VenueMap(1, new Location("Belgium", "Brussels"), List.of(zone));
        return new Event(1, "Show", 4.5, List.of("Artist"), EventCategory.CONCERT, 10,
                EventStatus.ON_SALE, venueMap, List.of(new ShowDate(FUTURE_START, FUTURE_END)),
                new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    private static void setPrivateInt(Object target, String fieldName, int value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test could not corrupt " + fieldName, e);
        }
    }
}
