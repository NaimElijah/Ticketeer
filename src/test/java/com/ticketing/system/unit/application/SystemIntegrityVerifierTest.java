package com.ticketing.system.unit.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.ticketing.system.governance.application.service.SystemIntegrityVerifier;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.domain.CompanyStatus;
import com.ticketing.system.organization.domain.ProductionCompany;
import com.ticketing.system.catalog.domain.DiscountPolicy;
import com.ticketing.system.catalog.domain.Event;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.domain.InventoryZone;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.catalog.domain.StandingZone;
import com.ticketing.system.catalog.domain.VenueMap;
import com.ticketing.system.shared.exception.SystemIntegrityViolationException;
import com.ticketing.system.shared.exception.UserNotFoundException;
import com.ticketing.system.shared.domain.policy.NoPurchasePolicy;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.domain.User;

class SystemIntegrityVerifierTest {

    private static final LocalDateTime FUTURE_START = LocalDateTime.of(2099, 6, 1, 18, 0);
    private static final LocalDateTime FUTURE_END = LocalDateTime.of(2099, 6, 1, 22, 0);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final ProductionCompanyRepository companyRepository = mock(ProductionCompanyRepository.class);
    private final EventRepository eventRepository = mock(EventRepository.class);
    private final ActiveOrderRepository activeOrderRepository = mock(ActiveOrderRepository.class);
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
                ZoneId.of("Asia/Jerusalem"),
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

    // ---- Defect 1: a race-legal pair of guest carts stops the boot (#187) ----

    // Carts are stamped from a fixed instant, never the system clock (ADR-008).
    private static final LocalDateTime CART_OPENED_AT = LocalDateTime.of(2027, 7, 15, 17, 0);
    private static final int SHARED_EVENT_ID = 7;
    private static final int MEMBER_ID = 5;

    // Two carts on one guest session id are a known, accepted race (#224, requirements.md §1 #8).
    // Failing the scan on them would stop the boot (ADR-003) and block openMarket from CLOSED.
    @Test
    void GivenTwoGuestCartsOnOneSessionHoldingOneEvent_WhenVerify_ThenPasses() {
        ActiveOrder first = guestCartHolding("guest-session-1", SHARED_EVENT_ID);
        ActiveOrder second = guestCartHolding("guest-session-1", SHARED_EVENT_ID);
        when(activeOrderRepository.findAll()).thenReturn(List.of(first, second));

        assertDoesNotThrow(verifier::verify,
                "two guest carts on one session id are a known race (#224), not a boot-stopping violation");
    }

    // Members stay in the scan: uk_active_orders_user_id holds them, so two carts are corrupt state.
    @Test
    void GivenTwoMemberCartsHoldingOneEvent_WhenVerify_ThenIntegrityViolation() {
        ActiveOrder first = memberCartHolding(MEMBER_ID, "member-session-1", SHARED_EVENT_ID);
        ActiveOrder second = memberCartHolding(MEMBER_ID, "member-session-2", SHARED_EVENT_ID);
        when(activeOrderRepository.findAll()).thenReturn(List.of(first, second));

        assertThrows(SystemIntegrityViolationException.class, verifier::verify,
                "a member with two carts on one event must still fail the scan");
    }

    private static ActiveOrder guestCartHolding(String sessionId, int eventId) {
        ActiveOrder cart = ActiveOrder.forGuest(sessionId, CART_OPENED_AT);
        cart.addStandingReservation(eventId, 1, 1, 50.0, CART_OPENED_AT);
        return cart;
    }

    private static ActiveOrder memberCartHolding(int userId, String sessionId, int eventId) {
        ActiveOrder cart = ActiveOrder.forMember(userId, sessionId, CART_OPENED_AT);
        cart.addStandingReservation(eventId, 1, 1, 50.0, CART_OPENED_AT);
        return cart;
    }

    // ---- Defect 2: the integrity failure message carries a guest's session id (#187) ----

    // Nine digits, so the id cannot turn up by chance inside a random order-key UUID.
    private static final int DUPLICATED_EVENT_ID = 987654321;
    private static final String SESSION_ID_MARKER = "SECRET-SESSION-ID";

    // A session id is a credential, and LoggingAspect logs this message at WARN: name the carts, not the session.
    @Test
    void GivenMemberWithTwoCartsOnOneEvent_WhenVerify_ThenMessageNamesOrderKeysAndEventButNoSessionId() {
        ActiveOrder first = memberCartHolding(MEMBER_ID, SESSION_ID_MARKER + "-1", DUPLICATED_EVENT_ID);
        ActiveOrder second = memberCartHolding(MEMBER_ID, SESSION_ID_MARKER + "-2", DUPLICATED_EVENT_ID);
        when(activeOrderRepository.findAll()).thenReturn(List.of(first, second));

        String message = assertThrows(SystemIntegrityViolationException.class, verifier::verify).getMessage();

        assertTrue(message.contains(first.getOrderKey()) && message.contains(second.getOrderKey()),
                "expected both order keys " + first.getOrderKey() + " and " + second.getOrderKey()
                        + " in: " + message);
        assertTrue(message.contains(String.valueOf(DUPLICATED_EVENT_ID)),
                "expected event id " + DUPLICATED_EVENT_ID + " in: " + message);
        assertFalse(message.contains(SESSION_ID_MARKER), "a session id must not reach the message: " + message);
    }

    // ---- Defect 6: causes are dropped (#187) ----

    private static final int CORRUPT_EVENT_ID = 4242;

    @Test
    void GivenLoadedEventBreakingItsInvariant_WhenVerify_ThenViolationNamesTheEventAndCarriesTheCause() {
        Event corrupt = realEvent(CORRUPT_EVENT_ID);
        setPrivateField(corrupt, "rating", 9.0);   // a stored row out of the 0..5 range
        when(eventRepository.findAll()).thenReturn(List.of(corrupt));

        SystemIntegrityViolationException thrown =
                assertThrows(SystemIntegrityViolationException.class, verifier::verify);

        String message = thrown.getMessage();
        assertTrue(message.toLowerCase(Locale.ROOT).contains("event")
                        && message.contains(String.valueOf(CORRUPT_EVENT_ID)),
                "expected the aggregate type and id " + CORRUPT_EVENT_ID + " in: " + message);
        IllegalStateException cause = assertInstanceOf(IllegalStateException.class, thrown.getCause(),
                "the invariant failure must be kept as the cause");
        assertEquals(invariantFailureOf(corrupt), cause.getMessage());
    }

    private static Event realEvent(int id) {
        InventoryZone zone = new StandingZone(1, "Floor", 100, 50);
        VenueMap venueMap = new VenueMap(1, new Location("Belgium", "Brussels"), List.of(zone));
        return new Event(id, "Show", 4.5, List.of("Artist"), EventCategory.CONCERT, 10,
                EventStatus.ON_SALE, venueMap, List.of(new ShowDate(FUTURE_START, FUTURE_END)),
                ZoneId.of("Asia/Jerusalem"),
                new NoPurchasePolicy(), new DiscountPolicy(0));
    }

    private static String invariantFailureOf(Event event) {
        return assertThrows(IllegalStateException.class, event::checkInvariants).getMessage();
    }

    // Simulates a corrupt stored row: the domain API never lets the field hold this value.
    private static void setPrivateField(Object target, String fieldName, Object value) {
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("test could not corrupt " + fieldName, e);
        }
    }

    // ---- Review follow-up 5: integrity messages name records by id (#187) ----

    // These messages are logged at ERROR on every self-heal tick while the violation lasts: name each record
    // by its id, never by a company name or a username.
    private static final int GHOST_COMPANY_ID = 12;
    private static final String GHOST_COMPANY_NAME = "Ghost Productions";
    private static final int FOUNDER_ID = 5;

    @Test
    void GivenActiveCompanyWithoutOwner_WhenVerify_ThenMessageNamesTheCompanyIdNotItsName() {
        ProductionCompany company = activeCompany();
        setPrivateField(company, "ownerIds", new ArrayList<Integer>());   // a stored row the domain API cannot produce
        when(companyRepository.findActive()).thenReturn(List.of(company));

        String message = assertThrows(SystemIntegrityViolationException.class, verifier::verify).getMessage();

        assertTrue(message.contains("company " + GHOST_COMPANY_ID), "expected the company id in: " + message);
        assertFalse(message.contains(GHOST_COMPANY_NAME), "the company name must not reach the message: " + message);
    }

    @Test
    void GivenProducerWhoIsNotAUser_WhenVerify_ThenMessageNamesTheCompanyIdAndUserIdNotTheName() {
        when(companyRepository.findActive()).thenReturn(List.of(activeCompany()));
        when(userRepository.getUserById(FOUNDER_ID)).thenThrow(new UserNotFoundException(FOUNDER_ID));

        String message = assertThrows(SystemIntegrityViolationException.class, verifier::verify).getMessage();

        assertTrue(message.contains("company " + GHOST_COMPANY_ID) && message.contains("producer " + FOUNDER_ID),
                "expected the company and producer ids in: " + message);
        assertFalse(message.contains(GHOST_COMPANY_NAME), "the company name must not reach the message: " + message);
    }

    @Test
    void GivenTwoUsersWithOneUsername_WhenVerify_ThenMessageNamesTheUserIdsNotTheUsername() {
        User first = new User(3, "shared-name", "first@example.com", "HASH", 30);
        User second = new User(9, "shared-name", "second@example.com", "HASH", 30);
        when(userRepository.findAll()).thenReturn(List.of(first, second));

        String message = assertThrows(SystemIntegrityViolationException.class, verifier::verify).getMessage();

        assertTrue(message.contains("users 3 and 9"), "expected both user ids in: " + message);
        assertFalse(message.contains("shared-name"), "the username must not reach the message: " + message);
    }

    private static ProductionCompany activeCompany() {
        return new ProductionCompany(GHOST_COMPANY_ID, FOUNDER_ID, GHOST_COMPANY_NAME, CompanyStatus.ACTIVE,
                "a company for the integrity scan", 4.0);
    }

    // ---- Review follow-up 7e: a corrupt cart is named by its order key, never its session id (#187) ----

    private static final int CORRUPT_USER_ID = -3;

    // The message is logged at ERROR on every self-heal tick, and a session id is a credential.
    @Test
    void GivenLoadedCartBreakingItsInvariant_WhenVerify_ThenViolationNamesTheOrderKeyAndNoSessionId() {
        ActiveOrder cart = memberCartHolding(MEMBER_ID, SESSION_ID_MARKER + "-cart", SHARED_EVENT_ID);
        setPrivateField(cart, "userId", CORRUPT_USER_ID);   // a stored row the domain API cannot produce
        when(activeOrderRepository.findAll()).thenReturn(List.of(cart));

        SystemIntegrityViolationException thrown =
                assertThrows(SystemIntegrityViolationException.class, verifier::verify);

        String message = thrown.getMessage();
        assertTrue(message.contains("active order " + cart.getOrderKey()),
                "expected the cart's order key in: " + message);
        assertFalse(message.contains(SESSION_ID_MARKER), "a session id must not reach the message: " + message);
        assertInstanceOf(IllegalStateException.class, thrown.getCause(), "the invariant failure is kept as the cause");
    }
}
