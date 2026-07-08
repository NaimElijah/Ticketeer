package com.ticketing.system.acceptance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.Core.Application.dto.AuthTokenDTO;
import com.ticketing.system.Core.Application.dto.CardDetailsDTO;
import com.ticketing.system.Core.Application.dto.CheckoutResultDTO;
import com.ticketing.system.Core.Application.dto.CompanyRegistrationDTO;
import com.ticketing.system.Core.Application.dto.EventCreationDTO;
import com.ticketing.system.Core.Application.dto.EventDetailDTO;
import com.ticketing.system.Core.Application.dto.EventPolicyConfigDTO;
import com.ticketing.system.Core.Application.dto.InventorySelectionDTO;
import com.ticketing.system.Core.Application.dto.LoginRequestDTO;
import com.ticketing.system.Core.Application.dto.MarketControlRequestDTO;
import com.ticketing.system.Core.Application.dto.PurchasePolicyDTO;
import com.ticketing.system.Core.Application.dto.RegisterRequestDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.Core.Application.dto.VenueMapDTO;
import com.ticketing.system.Core.Application.dto.GridPlacementDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.Core.Application.services.CheckoutService;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.Core.Application.services.ReservationService;
import com.ticketing.system.Core.Application.services.SystemAdminService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.shared.exception.PolicyViolationException;

/**
 * Eval point 3 — purchase policy "between 2 and 4 tickets". An event carries an
 * {@code AND(MIN_TICKETS 2, MAX_TICKETS 4)} policy; a buyer over the maximum or under the
 * minimum must be rejected with a <b>clear reason</b>, and a valid quantity must go through.
 *
 * <p>This drives the real reserve → checkout path against live services (test profile:
 * in-memory repos + the in-process payment/issuer stubs). It is the regression guard for the
 * fix that makes {@code Event.validateEffectivePolicy} throw {@link PolicyViolationException}
 * (mapped by the checkout presenter to a "Cannot purchase: …" message) instead of a generic
 * {@code IllegalStateException} (which surfaced only as "Payment could not be completed").
 */
@SpringBootTest
@ActiveProfiles("test")
class PurchasePolicyEnforcementAcceptanceTest {

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

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
    void GivenMinTwoPolicy_WhenBuyerChecksOutOne_ThenRejectedWithClearReason() {
        Fixture f = newPolicyEvent(2, 4);
        AuthTokenDTO buyer = registerAndLogin("polBuyer");

        // MIN is deferred at reserve (cart still being built) — one ticket reserves fine …
        reservationService.reserveForMember(
                buyer.token(), f.eventId, f.zoneId, InventorySelectionDTO.standing(1));

        // … but fails at checkout, and the buyer must see the specific reason.
        assertPolicyViolation(
                () -> checkoutService.checkoutMember(
                        buyer.token(), "idem-" + SEQ.incrementAndGet(), "ILS", card()),
                "at least 2");
    }

    // ---- helpers --------------------------------------------------------

    private record Fixture(int companyId, int eventId, int zoneId) { }

    /** Owner + company + published single-standing-zone event (@50) carrying AND(min,max). */
    private Fixture newPolicyEvent(int min, int max) {
        AuthTokenDTO owner = registerAndLogin("polOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("PolCo" + SEQ.incrementAndGet(), "desc")).companyId();

        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Policy Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(LocalDateTime.now().plusDays(10), LocalDateTime.now().plusDays(10).plusHours(3))),
                null));
        int eventId = Integer.parseInt(event.eventId());

        ZoneConfigDTO standing = new ZoneConfigDTO(
                "Standing", false, 100, null, 50.0, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Test Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);

        PurchasePolicyDTO minDto = new PurchasePolicyDTO("MIN_TICKETS", null, min, null, null);
        PurchasePolicyDTO maxDto = new PurchasePolicyDTO("MAX_TICKETS", null, null, max, null);
        PurchasePolicyDTO and = new PurchasePolicyDTO("AND", null, null, null, List.of(minDto, maxDto));
        eventManagementService.setEventPolicies(owner.token(), new EventPolicyConfigDTO(companyId, eventId, and));

        VenueMapDTO map = catalogService.getEventVenueMap(owner.token(), eventId);
        int zoneId = map.inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();

        return new Fixture(companyId, eventId, zoneId);
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
