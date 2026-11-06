package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.CompanyPolicyConfigDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.shared.dto.EventPolicyConfigDTO;
import com.ticketing.system.shared.dto.InventoryZoneDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.PurchasePolicyDTO;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.dto.VenueMapDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.exception.PolicyViolationException;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * Eval point 3 — purchase policy "between 2 and 4 tickets". An event carries an
 * {@code AND(MIN_TICKETS 2, MAX_TICKETS 4)} policy; a buyer over the maximum or under the
 * minimum must be rejected with a <b>clear reason</b>, and a valid quantity must go through. A company's
 * own policy is enforced the same way on the events it runs.
 *
 * <p>This drives the real reserve → checkout path against live services (test profile:
 * in-memory repos + the in-process payment/issuer stubs). It is the regression guard for the
 * fix that makes {@code Event.validateEffectivePolicy} throw {@link PolicyViolationException}
 * (mapped by the checkout presenter to a "Cannot purchase: …" message) instead of a generic
 * {@code IllegalStateException} (which surfaced only as "Payment could not be completed").
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
class PurchasePolicyEnforcementAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private MemberAccountService memberAccountService;
    @Autowired private Clock clock;

    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private static final int ZONE_CAPACITY = 100;
    private static final double TICKET_PRICE = 50.0;
    private static final String CURRENCY = "ILS";

    /** Reservations/checkout require the trading market open; the test profile boots closed. */
    @BeforeEach
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform(); // idempotent — no-op if already initialized
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "eval acceptance setup", admin.token()));
    }

    @Test
    void GivenMinTwoMaxFourPolicy_WhenBuyerReservesFive_ThenRejectedWithClearReason() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        // MAX is enforced already at reserve — five tickets in one go is over the limit.
        assertPolicyViolation(
                () -> reservationService.reserveForMember(
                        buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(5)),
                "at most 4");
    }

    @Test
    void GivenMinTwoMaxFourPolicy_WhenBuyerBuysThree_ThenCheckoutSucceeds() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(3));

        CheckoutResultDTO result =
                checkoutService.checkoutMember(buyer.token(), "idem-" + SEQ.incrementAndGet(), "ILS", card());

        assertNotNull(result, "a valid 3-ticket purchase must complete");
        assertEquals(150.0, result.totalCharged(), "3 standing tickets @ 50.0");
    }

    @Test
    void GivenMinTwoMaxFourPolicy_WhenBuyerBuysTwo_ThenCheckoutSucceeds() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(2));

        CheckoutResultDTO result =
                checkoutService.checkoutMember(buyer.token(), "idem-" + SEQ.incrementAndGet(), CURRENCY, card());

        assertEquals(2 * TICKET_PRICE, result.totalCharged(), "the minimum, 2 standing tickets, must be bought");
        // The control for the refused minimum's empty history: the same read sees a completed purchase.
        assertEquals(1, memberAccountService.viewMyHistory(buyer).records().size(),
                "the member's history must hold the completed purchase");
    }

    @Test
    void GivenMinTwoMaxFourPolicy_WhenBuyerBuysFour_ThenCheckoutSucceeds() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(4));

        CheckoutResultDTO result =
                checkoutService.checkoutMember(buyer.token(), "idem-" + SEQ.incrementAndGet(), CURRENCY, card());

        assertEquals(4 * TICKET_PRICE, result.totalCharged(), "the maximum, 4 standing tickets, must be bought");
    }

    // MAX counts what the cart already holds for the event, so two reservations cannot add up past it.
    @Test
    void GivenMinTwoMaxFourPolicy_WhenBuyerReservesThreeThenTwo_ThenTheSecondIsRejectedAndThreeStayHeld() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");
        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(3));

        assertPolicyViolation(
                () -> reservationService.reserveForMember(
                        buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(2)),
                "at most 4");

        assertEquals(3, reservationService.viewMyActiveOrder(buyer.token()).lines().size(),
                "the cart keeps the first three tickets and nothing of the refused two");
        assertEquals(3, zoneOf(f).getReservedAmount(), "the zone holds only the first three");
    }

    // II.2.8, all or nothing: the refused checkout records no purchase, returns the hold to stock and
    // empties the cart. That it charges nothing is CheckoutServiceTest's, whose gateway is a mock
    // (GivenQuantityBelowMinTicketsPolicy_WhenCheckout_ThenThrowExceptionAndPaymentNotCharged).
    @Test
    void GivenMinTwoPolicy_WhenBuyerChecksOutOne_ThenNothingIsBoughtAndTheHoldIsReleased() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");
        // MIN is deferred at reserve, while the cart is still being built, so one ticket reserves fine.
        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(1));

        assertPolicyViolation(
                () -> checkoutService.checkoutMember(
                        buyer.token(), "idem-" + SEQ.incrementAndGet(), CURRENCY, card()),
                "at least 2");

        assertTrue(memberAccountService.viewMyHistory(buyer).records().isEmpty(),
                "the refused checkout must record no purchase");
        InventoryZoneDTO zone = zoneOf(f);
        assertEquals(0, zone.getReservedAmount(), "the refused checkout must release the hold");
        assertEquals(ZONE_CAPACITY, zone.getAvailableAmount(), "the held ticket must be back in stock");
        ActiveOrderDTO cart = reservationService.viewMyActiveOrder(buyer.token());
        assertTrue(cart == null || cart.lines().isEmpty(), "the refused checkout must empty the cart, but it holds "
                + (cart == null ? null : cart.lines()));
    }

    // A company's policy applies to every event it runs, on top of the event's own (here none).
    @Test
    void GivenCompanyMaxTicketsPolicy_WhenBuyerReservesOverIt_ThenRejectedWithClearReasonAndNothingIsHeld() {
        Fixture f = newEventOfACompanyCappingTicketsAt(2);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        assertPolicyViolation(
                () -> reservationService.reserveForMember(
                        buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(3)),
                "at most 2");

        assertEquals(0, zoneOf(f).getReservedAmount(), "a refused reservation must hold nothing");
    }

    // ---- helpers --------------------------------------------------------

    private record Fixture(String ownerToken, int companyId, int eventId, int zoneId) { }

    /** Owner + company + published single-standing-zone event (@50) carrying AND(min,max). */
    private Fixture newPolicyEvent(int min, int max) {
        Fixture f = newPublishedEvent();
        PurchasePolicyDTO minDto = new PurchasePolicyDTO("MIN_TICKETS", null, min, null, null);
        PurchasePolicyDTO maxDto = new PurchasePolicyDTO("MAX_TICKETS", null, null, max, null);
        PurchasePolicyDTO and = new PurchasePolicyDTO("AND", null, null, null, List.of(minDto, maxDto));
        eventManagementService.setEventPolicies(f.ownerToken, new EventPolicyConfigDTO(f.companyId, f.eventId, and));
        return f;
    }

    /** The same event, with no policy of its own, run by a company whose policy caps a purchase at {@code max}. */
    private Fixture newEventOfACompanyCappingTicketsAt(int max) {
        Fixture f = newPublishedEvent();
        companyService.setCompanyPolicies(f.ownerToken, new CompanyPolicyConfigDTO(f.companyId,
                new PurchasePolicyDTO("MAX_TICKETS", null, null, max, null), List.of()));
        return f;
    }

    /** Owner + company + published single-standing-zone event (@50) with no purchase policy. */
    private Fixture newPublishedEvent() {
        AuthTokenDTO owner = registerAndLogin("polOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("PolCo" + SEQ.incrementAndGet(), "desc")).companyId();

        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Policy Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now(clock).plusDays(10), LocalDateTime.now(clock).plusDays(10).plusHours(3))),
                ZoneId.of("Asia/Jerusalem"),
                null));
        int eventId = Integer.parseInt(event.eventId());

        ZoneConfigDTO standing = new ZoneConfigDTO(
                "Standing", false, ZONE_CAPACITY, null, TICKET_PRICE, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Test Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);

        VenueMapDTO map = catalogService.getEventVenueMap(owner.token(), eventId);
        int zoneId = map.inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();

        return new Fixture(owner.token(), companyId, eventId, zoneId);
    }

    /** The event's standing zone as its owner reads it, with its reserved and available counts. */
    private InventoryZoneDTO zoneOf(Fixture f) {
        return catalogService.getEventVenueMap(f.ownerToken, f.eventId).inventoryZones().stream()
                .filter(z -> z.getId() == f.zoneId)
                .findFirst().orElseThrow();
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + SEQ.incrementAndGet();
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 25));
        return authService.login(new LoginRequestDTO(name, "Password1", sid)).authToken();
    }

    private static CardDetailsDTO card() {
        return new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer");
    }

    /** Assert the action fails with a PolicyViolationException whose reason contains the fragment. */
    private static void assertPolicyViolation(Executable action, String expectedFragment) {
        RuntimeException ex = assertThrows(RuntimeException.class, action);
        PolicyViolationException pve = findCause(ex, PolicyViolationException.class);
        assertNotNull(pve, "expected a PolicyViolationException in the cause chain, but got: " + ex);
        assertTrue(pve.getMessage().contains(expectedFragment),
                "expected the reason to contain '" + expectedFragment + "' but was: " + pve.getMessage());
    }

    private static <T extends Throwable> T findCause(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        return null;
    }
}
