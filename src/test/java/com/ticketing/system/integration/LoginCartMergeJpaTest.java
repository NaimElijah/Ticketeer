package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.integration.SeatedShowFixture.Show;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.shared.dto.ActiveOrderDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.LoginDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;

/**
 * UC-12 / UC-13 (D9a): logging in from a guest session that holds a cart, when the member already
 * holds an order of their own (#185). The guest cart wins while OQ-17 is open, but the member order it
 * replaces must not take its holds with it: a hold is keyed by its order, and the sweeper walks only
 * existing orders, so a hold whose order is deleted stays RESERVED forever. A member order frozen by
 * its own checkout is not the login's to touch at all.
 *
 * <p>Runs on the Jpa adapters over H2, where {@code JpaActiveOrderRepository.save} deletes the other
 * cart of the same member.
 */
@SpringBootTest
@ActiveProfiles("test")
class LoginCartMergeJpaTest {

    private static final String MEMBER_SEAT = SeatedShowFixture.SEATS.get(0);
    private static final String GUEST_SEAT = SeatedShowFixture.SEATS.get(1);

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private ReservationService reservationService;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private Clock clock;

    private SeatedShowFixture fixture;
    private Show show;

    @BeforeEach
    void publishShow() {
        fixture = new SeatedShowFixture(authService, companyService, eventManagementService, catalogService,
                systemAdminService, clock);
        fixture.ensureMarketOpen();
        show = fixture.publishSeatedShow();
    }

    @Test
    void GivenHeldMemberOrderAndGuestCart_WhenLogin_ThenNoTicketStaysHeldWithoutAnOrder() {
        String member = fixture.register("mergeMember");
        AuthTokenDTO firstVisit = fixture.login(member);
        reserveSeat(firstVisit, MEMBER_SEAT);
        authService.logout(new LogoutRequestDTO(firstVisit.token()));
        String guestSessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(GUEST_SEAT)));

        LoginDTO login = authService.login(new LoginRequestDTO(member, SeatedShowFixture.PASSWORD, guestSessionId));

        assertEquals(SeatedShowFixture.SEATS.size() - 1, fixture.availableSeats(show),
                "only the guest's " + GUEST_SEAT + " may stay held: the replaced member order's "
                        + MEMBER_SEAT + " must be back in stock");
        assertEquals(List.of(GUEST_SEAT), seatsOf(login.activeOrder()),
                "the guest cart wins the merge and becomes the member's order");
        AuthTokenDTO otherBuyer = fixture.registerAndLogin("mergeOtherBuyer");
        assertDoesNotThrow(() -> reserveSeat(otherBuyer, MEMBER_SEAT),
                MEMBER_SEAT + " must be reservable again once its order is gone");
    }

    @Test
    void GivenMemberOrderInCheckout_WhenLoginWithGuestCart_ThenMemberOrderAndItsHoldsUntouched() {
        String member = fixture.register("checkoutMember");
        AuthTokenDTO firstVisit = fixture.login(member);
        reserveSeat(firstVisit, MEMBER_SEAT);
        String memberOrderKey = freezeAsAnotherCheckoutWould(firstVisit.userId());
        authService.logout(new LogoutRequestDTO(firstVisit.token()));
        String guestSessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(guestSessionId, show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(GUEST_SEAT)));
        String guestOrderKey = activeOrderRepository.getBySessionId(guestSessionId).orElseThrow().getOrderKey();

        authService.login(new LoginRequestDTO(member, SeatedShowFixture.PASSWORD, guestSessionId));

        ActiveOrder memberOrder = activeOrderRepository.getByUserId(firstVisit.userId());
        assertNotNull(memberOrder, "the member's order in checkout must survive the login");
        ActiveOrder guestCart = activeOrderRepository.getBySessionId(guestSessionId).orElseThrow();
        assertAll(
                () -> assertEquals(memberOrderKey, memberOrder.getOrderKey(),
                        "the member's order in checkout must not be replaced by the guest cart"),
                () -> assertTrue(memberOrder.isCheckoutInProgress(), "the member's checkout must stay frozen"),
                () -> assertEquals(List.of(MEMBER_SEAT), seatsOf(memberOrder),
                        "the member's checkout must keep its seat"),
                () -> assertEquals(guestOrderKey, guestCart.getOrderKey(), "the guest cart must be left as it was"),
                () -> assertTrue(guestCart.isGuest(), "the guest cart must not be attached to the member"),
                () -> assertEquals(SeatedShowFixture.SEATS.size() - 2, fixture.availableSeats(show),
                        "both carts' seats must stay held"));
    }

    private void reserveSeat(AuthTokenDTO buyer, String seat) {
        reservationService.reserveForMember(buyer.token(), show.eventId(), show.zoneId(),
                InventorySelectionDTO.seated(List.of(seat)));
    }

    // What a first checkout attempt's Phase 1 leaves behind: the member's order, CHECKOUT_IN_PROGRESS.
    private String freezeAsAnotherCheckoutWould(int userId) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ActiveOrder order = activeOrderRepository.getByUserId(userId);
            order.markCheckoutInProgress();
            activeOrderRepository.save(order);
            return order.getOrderKey();
        });
    }

    private static List<String> seatsOf(ActiveOrder order) {
        return order.getItems().stream().map(CartLineItem::getSeatNumber).toList();
    }

    private static List<String> seatsOf(ActiveOrderDTO order) {
        return order.lines().stream().map(ActiveOrderDTO.CartLineDTO::seatNumber).toList();
    }
}
