package com.ticketing.system.acceptance;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.dto.InventorySelectionDTO;
import com.ticketing.system.catalog.application.service.CatalogService;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.governance.application.service.SystemAdminService;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.domain.Admin;
import com.ticketing.system.messaging.application.service.MessagingService;
import com.ticketing.system.notifications.application.port.out.NotificationRepository;
import com.ticketing.system.notifications.domain.Notification;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.reporting.application.service.CompanyAnalyticsService;
import com.ticketing.system.reporting.application.service.MemberAccountService;
import com.ticketing.system.sales.application.port.out.ActiveOrderRepository;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.RefundService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ActiveOrder;
import com.ticketing.system.sales.domain.CartLineItem;
import com.ticketing.system.sales.domain.OrderReceipt;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.ConversationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.LogoutRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.OutreachRequestDTO;
import com.ticketing.system.shared.dto.SendMessageRequestDTO;
import com.ticketing.system.shared.dto.StartConversationRequestDTO;
import com.ticketing.system.shared.dto.SubmitComplaintRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.exception.DomainException;
import com.ticketing.system.shared.exception.SessionExpiredException;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * Member and admin ids share one integer space: the default admin is id 1, and so is the first
 * registered member. A session token therefore has to say which kind of session it belongs to, and
 * every path has to accept only its own kind (#185). Each test arranges an admin and a member with
 * the same id explicitly, so the collision does not depend on which classes ran first in the shared
 * context.
 *
 * <ul>
 *   <li>An admin token is rejected on every member path exactly as a revoked token is, and leaves
 *       the member's data alone.</li>
 *   <li>Admin paths still accept the admin token, and still reject the member's token.</li>
 *   <li>A guest credential is a live guest session id, nothing else.</li>
 *   <li>Nothing addressed to the admin lands in the inbox of the member with the admin's id.</li>
 * </ul>
 *
 * <p>Saving admins with custom usernames would stop the default admin from being created for a
 * later class in the same cached context, hence {@code @DirtiesContext}, as in
 * {@code MessagingAcceptanceTest}.
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AdminAndMemberSessionAcceptanceTest {

    private static final String PASSWORD = "Password1";
    private static final int MEMBER_AGE = 30;
    private static final double TICKET_PRICE = 50.0;
    private static final int TICKETS_HELD = 2;
    private static final int ZONE_CAPACITY = 100;
    private static final int DAYS_UNTIL_SHOW = 10;
    private static final int SHOW_HOURS = 3;
    private static final String CURRENCY = "ILS";
    private static final CardDetailsDTO CARD = new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer");
    private static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(10);
    private static final String CONVERSATION_ID_KEY = "conversationId";
    private static final String SUBJECT_KEY = "subject";
    // Far above any member id this suite registers, so the market admin never shares an id.
    private static final int MARKET_ADMIN_ID_BASE = 1_000_000;

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired private AuthenticationService authService;
    @Autowired private SessionManager sessionManager;
    @Autowired private AdminRepository adminRepository;
    @Autowired private PasswordHasher passwordHasher;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private RefundService refundService;
    @Autowired private MemberAccountService memberAccountService;
    @Autowired private MessagingService messagingService;
    @Autowired private CompanyAnalyticsService companyAnalyticsService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private ActiveOrderRepository activeOrderRepository;
    @Autowired private NotificationRepository notificationRepository;
    @Autowired private Clock clock;

    /**
     * Reservations and checkout need the market open; the test profile boots it closed. The market is
     * opened by an admin this class creates: in a fresh context the first member registered here gets
     * id 1, so the admin saved with that member's id replaces the default admin, whose sign-in then
     * no longer works.
     */
    @BeforeEach
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform();
        String marketAdminName = nextName("kind.market.admin");
        adminRepository.save(new Admin(MARKET_ADMIN_ID_BASE + SEQ.incrementAndGet(), marketAdminName,
                passwordHasher.hash(PASSWORD), false));
        AuthTokenDTO marketAdmin = authService.signInAsAdmin(marketAdminName, PASSWORD);
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "session acceptance setup", marketAdmin.token()));
    }

    // ---- an admin token on member paths ---------------------------------------------------------

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenRequestsARefund_ThenRejectedAndTheMembersOrderIsNotRefunded() {
        SharedId ids = adminAndMemberWithTheSameId();
        int receiptId = buy(ids.member(), newStandingEvent());

        assertRejectedAsARevokedMemberTokenIs(ids,
                token -> refundService.requestRefund(token, receiptId, "changed plans"));

        OrderReceipt receipt = orderReceiptRepository.findByOrderReceiptId(receiptId).orElseThrow();
        assertTrue(receipt.getReceiptLines().stream().noneMatch(ReceiptLine::isRefunded),
                "the member's order must not be refunded by an admin token");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenViewsPurchaseHistory_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();
        buy(ids.member(), newStandingEvent());
        assertEquals(1, memberAccountService.viewMyHistory(ids.member()).records().size(),
                "arrangement: the member has one purchase");

        assertRejectedAsARevokedMemberTokenIs(ids,
                token -> memberAccountService.viewMyHistory(withToken(ids.member(), token)));
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenReserves_ThenRejectedAndTheMembersCartIsUnchanged() {
        SharedId ids = adminAndMemberWithTheSameId();
        StandingEvent event = newStandingEvent();
        hold(ids.member(), event);
        int heldLines = cartOf(ids.member()).getItems().size();

        assertRejectedAsARevokedMemberTokenIs(ids, token -> reservationService.reserveForMember(
                token, event.eventId(), event.zoneId(), InventorySelectionDTO.standing(1)));

        assertEquals(heldLines, cartOf(ids.member()).getItems().size(),
                "an admin token must not add to the cart of the member with the admin's id");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenChecksOut_ThenRejectedAndNothingIsBought() {
        SharedId ids = adminAndMemberWithTheSameId();
        StandingEvent event = newStandingEvent();
        hold(ids.member(), event);
        int heldLines = cartOf(ids.member()).getItems().size();

        assertRejectedAsARevokedMemberTokenIs(ids,
                token -> checkoutService.checkoutMember(token, nextIdempotencyKey(), CURRENCY, CARD));

        assertTrue(orderReceiptRepository.findByHolderUserId(ids.member().userId()).isEmpty(),
                "an admin token must not buy the cart of the member with the admin's id");
        assertEquals(heldLines, cartOf(ids.member()).getItems().size(), "the member's cart is still held");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenOpensTheMemberInbox_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();
        inquiryFrom(ids.member());

        assertRejectedAsARevokedMemberTokenIs(ids, token -> messagingService.viewMyConversations(token));
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenOpensTheMembersInquiry_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();
        ConversationDTO inquiry = inquiryFrom(ids.member());

        assertThrows(DomainException.class,
                () -> messagingService.viewConversation(ids.admin().token(), inquiry.conversationId()),
                "an admin is not a participant in a member's inquiry to a company");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenRepliesInTheMembersInquiry_ThenRejectedAndNoMessageIsAdded() {
        SharedId ids = adminAndMemberWithTheSameId();
        ConversationDTO inquiry = inquiryFrom(ids.member());

        assertThrows(DomainException.class, () -> messagingService.sendMessage(ids.admin().token(),
                new SendMessageRequestDTO(inquiry.conversationId(), 0, null, "posted as the member")),
                "an admin token must not post as the member with the admin's id");

        assertEquals(1, messagingService.viewConversation(ids.member().token(), inquiry.conversationId())
                .messages().size(), "the inquiry still holds only the member's own message");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenRegistersACompany_ThenRejectedAndTheMemberFoundsNoCompany() {
        SharedId ids = adminAndMemberWithTheSameId();
        int ownedBefore = companyService.findOwnedCompanies(ids.member().token()).size();

        assertRejectedAsARevokedMemberTokenIs(ids, token -> companyService.registerCompany(
                token, new CompanyRegistrationDTO("KindCo" + SEQ.incrementAndGet(), "desc")));

        assertEquals(ownedBefore, companyService.findOwnedCompanies(ids.member().token()).size(),
                "an admin token must not found a company for the member with the admin's id");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenOpensTheMembersCompanyDashboard_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();
        int companyId = registerCompanyAs(ids.member());

        assertRejectedAsARevokedMemberTokenIs(ids, token -> companyAnalyticsService.dashboard(token, companyId));
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminTokenAddsAnEventToTheMembersCompany_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();
        int companyId = registerCompanyAs(ids.member());

        assertRejectedAsARevokedMemberTokenIs(ids,
                token -> eventManagementService.addEvent(token, showFor(companyId)));
    }

    // ---- admin paths ------------------------------------------------------------------------------

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminClosesAndReopensTheMarket_ThenBothSucceed() {
        SharedId ids = adminAndMemberWithTheSameId();

        systemAdminService.closeMarket(new MarketControlRequestDTO("CLOSE", "maintenance", ids.admin().token()));
        assertFalse(systemAdminService.isMarketOpen(), "the admin token closes the market");

        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "maintenance over", ids.admin().token()));
        assertTrue(systemAdminService.isMarketOpen(), "the admin token reopens the market");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminViewsTheComplaintQueue_ThenAnotherMembersComplaintIsListed() {
        SharedId ids = adminAndMemberWithTheSameId();
        ConversationDTO complaint = complaintFrom(registerAndLogin(nextName("kind.complainant")));

        List<ConversationDTO> queue = messagingService.viewAllComplaints(ids.admin().token(), null);

        assertTrue(queue.stream().anyMatch(c -> complaint.conversationId().equals(c.conversationId())),
                "the admin's complaint queue lists the complaint");
    }

    @Test
    void GivenAdminWithAMembersId_WhenTheAdminListsAllCompanies_ThenTheMembersCompanyIsListed() {
        SharedId ids = adminAndMemberWithTheSameId();
        int companyId = registerCompanyAs(ids.member());

        assertTrue(companyService.adminListAllCompanies(ids.admin().token()).stream()
                        .anyMatch(c -> c.companyId() == companyId),
                "the admin's company list includes every company");
    }

    @Test
    void GivenMemberWithAnAdminsId_WhenTheMemberTokenClosesTheMarket_ThenRejectedAndTheMarketStaysOpen() {
        SharedId ids = adminAndMemberWithTheSameId();

        assertThrows(DomainException.class, () -> systemAdminService.closeMarket(
                new MarketControlRequestDTO("CLOSE", "not an admin", ids.member().token())));
        assertTrue(systemAdminService.isMarketOpen(), "a member token must not close the market");
    }

    @Test
    void GivenMemberWithAnAdminsId_WhenTheMemberTokenViewsTheComplaintQueue_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();

        assertThrows(DomainException.class, () -> messagingService.viewAllComplaints(ids.member().token(), null));
    }

    @Test
    void GivenMemberWithAnAdminsId_WhenTheMemberTokenListsAllCompanies_ThenRejected() {
        SharedId ids = adminAndMemberWithTheSameId();

        assertThrows(DomainException.class, () -> companyService.adminListAllCompanies(ids.member().token()));
    }

    // ---- guest credentials ------------------------------------------------------------------------

    @Test
    void GivenASessionIdThatWasNeverIssued_WhenReserveForGuest_ThenSessionExpiredAndNoCartIsCreated() {
        StandingEvent event = newStandingEvent();
        String unknownSessionId = "never-issued-" + SEQ.incrementAndGet();

        assertThrows(SessionExpiredException.class, () -> reservationService.reserveForGuest(
                unknownSessionId, event.eventId(), event.zoneId(), InventorySelectionDTO.standing(1)));
        assertTrue(activeOrderRepository.getBySessionId(unknownSessionId).isEmpty(),
                "no cart is opened for a session id nobody was issued");
    }

    @Test
    void GivenALiveMembersSessionId_WhenReserveForGuest_ThenSessionExpiredAndTheMembersCartIsUnchanged() {
        AuthTokenDTO member = registerAndLogin(nextName("kind.buyer"));
        StandingEvent event = newStandingEvent();
        hold(member, event);
        int heldLines = cartOf(member).getItems().size();
        String membersSessionId = sessionManager.extractSessionId(member.token());

        assertThrows(SessionExpiredException.class, () -> reservationService.reserveForGuest(
                membersSessionId, event.eventId(), event.zoneId(), InventorySelectionDTO.standing(1)),
                "a member session's id is not a guest credential");
        assertEquals(heldLines, cartOf(member).getItems().size(), "the member's cart is unchanged");
    }

    @Test
    void GivenALiveGuestSession_WhenReserveForGuest_ThenTheGuestHoldsTheTickets() {
        StandingEvent event = newStandingEvent();
        String guestSessionId = authService.startGuestSession().sessionId();

        reservationService.reserveForGuest(guestSessionId, event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(1));

        assertFalse(activeOrderRepository.getBySessionId(guestSessionId).orElseThrow().getItems().isEmpty(),
                "a live guest session holds what it reserved");
    }

    // A member's token and a member session's raw id are no guest credential on any guest path
    // (ADR-012, decision 4): only the raw id of a live guest row is.

    @Test
    void GivenAMembersToken_WhenReserveForGuest_ThenSessionExpiredAndNoCartIsOpenedUnderIt() {
        AuthTokenDTO member = registerAndLogin(nextName("kind.token.as.guest"));
        StandingEvent event = newStandingEvent();

        assertThrows(SessionExpiredException.class, () -> reservationService.reserveForGuest(
                member.token(), event.eventId(), event.zoneId(), InventorySelectionDTO.standing(1)),
                "a member token is not a guest session id");
        assertTrue(activeOrderRepository.getBySessionId(member.token()).isEmpty(),
                "no guest cart may be opened under a member's token");
    }

    @Test
    void GivenALiveMembersSessionId_WhenViewMyActiveOrder_ThenSessionExpiredAndNotTheMembersCart() {
        MemberWithCartOnItsSession member = memberWhoseCartIsBoundToTheirSessionId();

        assertThrows(SessionExpiredException.class,
                () -> reservationService.viewMyActiveOrder(member.sessionId()),
                "a member session's raw id must not open the member's cart as a guest");
    }

    @Test
    void GivenALiveMembersSessionId_WhenRestoreActiveOrderForGuest_ThenSessionExpired() {
        MemberWithCartOnItsSession member = memberWhoseCartIsBoundToTheirSessionId();

        assertThrows(SessionExpiredException.class,
                () -> reservationService.restoreActiveOrderForGuest(member.sessionId()),
                "a member session's raw id must not restore the member's cart as a guest");
    }

    @Test
    void GivenALiveMembersSessionId_WhenRenewReservationsForGuestCheckout_ThenSessionExpiredAndTheHoldsAreNotRenewed() {
        MemberWithCartOnItsSession member = memberWhoseCartIsBoundToTheirSessionId();
        List<LocalDateTime> holdsBefore = holdTimesOf(cartOf(member.auth()));

        assertThrows(SessionExpiredException.class,
                () -> reservationService.renewReservationsForGuestCheckout(member.sessionId()),
                "a member session's raw id must not renew the member's holds as a guest");
        assertEquals(holdsBefore, holdTimesOf(cartOf(member.auth())), "the member's holds are unchanged");
    }

    @Test
    void GivenAMembersToken_WhenCheckoutGuest_ThenSessionExpiredAndNothingIsBought() {
        AuthTokenDTO member = registerAndLogin(nextName("kind.token.guest.checkout"));
        hold(member, newStandingEvent());
        int heldLines = cartOf(member).getItems().size();

        RuntimeException failure = assertThrows(RuntimeException.class, () -> checkoutService.checkoutGuest(
                member.token(), member.username() + "@test.com", nextIdempotencyKey(), CURRENCY, CARD, MEMBER_AGE));

        assertTrue(failure.getCause() instanceof SessionExpiredException,
                "a member token is not a guest session id, so guest checkout must fail as for an expired "
                        + "guest session, but failed with " + failure + " caused by " + failure.getCause());
        assertTrue(orderReceiptRepository.findByHolderUserId(member.userId()).isEmpty(), "nothing is bought");
        assertEquals(heldLines, cartOf(member).getItems().size(), "the member's cart is still held");
    }

    @Test
    void GivenALiveMembersSessionId_WhenEndGuestSession_ThenTheMemberSessionStaysLive() {
        AuthTokenDTO member = registerAndLogin(nextName("kind.end.guest"));
        String membersSessionId = sessionManager.extractSessionId(member.token());

        authService.endGuestSession(membersSessionId);

        assertTrue(assertDoesNotThrow(() -> sessionManager.validateToken(member.token()),
                        "ending a guest session must not revoke a member's session"),
                "the member's token is still live");
    }

    // ---- notifications ----------------------------------------------------------------------------

    @Test
    void GivenAdminWithAMembersId_WhenAnotherMemberFilesAComplaint_ThenTheMemberWithTheAdminsIdIsNotNotified() {
        SharedId ids = adminAndMemberWithTheSameId();
        ConversationDTO complaint = complaintFrom(registerAndLogin(nextName("kind.complainant")));
        // Notices are stored one at a time, in commit order, on one thread (ADR-004). Once a notice
        // published after the complaint has arrived, the complaint's would have arrived too.
        String laterSubject = "Later notice " + SEQ.incrementAndGet();
        messagingService.sendOutreach(ids.admin().token(), new OutreachRequestDTO(
                laterSubject, "Body", List.of(ids.member().userId()), false, false));
        int memberId = ids.member().userId();
        await().atMost(DELIVERY_DEADLINE).untilAsserted(() -> assertTrue(
                notificationRepository.findByRecipient(memberId).stream()
                        .anyMatch(n -> laterSubject.equals(n.getData().get(SUBJECT_KEY))),
                "arrangement: the later notice reaches the member"));

        List<Notification> aboutTheComplaint = notificationRepository.findByRecipient(memberId).stream()
                .filter(n -> complaint.conversationId().equals(n.getData().get(CONVERSATION_ID_KEY)))
                .toList();

        assertTrue(aboutTheComplaint.isEmpty(),
                "a complaint filed by someone else must not notify the member with the admin's id; got "
                        + aboutTheComplaint.stream().map(Notification::getMessage).toList());
    }

    // ---- assertions -------------------------------------------------------------------------------

    @FunctionalInterface
    private interface MemberCall {
        Object callWith(String token);
    }

    /** What a call did: the exception it threw (and that exception's cause), or what it returned. */
    private record Outcome(Class<?> thrown, Class<?> thrownCause, Object returned) {

        static Outcome of(MemberCall call, String token) {
            try {
                return new Outcome(null, null, call.callWith(token));
            } catch (RuntimeException e) {
                return new Outcome(e.getClass(), e.getCause() == null ? null : e.getCause().getClass(), null);
            }
        }
    }

    /**
     * The token of the admin who shares the member's id gets exactly what the member's own revoked
     * token gets on this path: an admin token is no member credential at all.
     */
    private void assertRejectedAsARevokedMemberTokenIs(SharedId ids, MemberCall call) {
        Outcome revoked = Outcome.of(call, ids.revokedMemberToken());
        assertNotNull(revoked.thrown(),
                "arrangement: the member's revoked token must be rejected on this path, but it returned "
                        + revoked.returned());

        Outcome withAdminToken = Outcome.of(call, ids.admin().token());

        assertEquals(revoked, withAdminToken,
                "a member path must treat the admin's token as it treats the member's revoked token");
    }

    // ---- arrangement ------------------------------------------------------------------------------

    /** The member, a token of the same member that has been signed out, and the admin with the member's id. */
    private record SharedId(AuthTokenDTO member, String revokedMemberToken, AuthTokenDTO admin) { }

    private record StandingEvent(int eventId, int zoneId) { }

    /**
     * A signed-in member, and a signed-in admin whose admin id is the member's user id. The revoked
     * token comes from a second sign-in made before the test touches the member's cart.
     */
    private SharedId adminAndMemberWithTheSameId() {
        String memberName = nextName("kind.member");
        AuthTokenDTO member = registerAndLogin(memberName);
        String revokedMemberToken = login(memberName).token();
        authService.logout(new LogoutRequestDTO(revokedMemberToken));
        String adminName = nextName("kind.admin");
        adminRepository.save(new Admin(member.userId(), adminName, passwordHasher.hash(PASSWORD), false));
        AuthTokenDTO admin = authService.signInAsAdmin(adminName, PASSWORD);
        assertEquals(member.userId(), admin.userId(), "arrangement: the admin and the member share an id");
        return new SharedId(member, revokedMemberToken, admin);
    }

    private record MemberWithCartOnItsSession(AuthTokenDTO auth, String sessionId) { }

    /**
     * A member whose cart is bound to their session id: the guest holds a ticket, then signs in from
     * the same session, which keeps its id and takes the cart over (UC-13).
     */
    private MemberWithCartOnItsSession memberWhoseCartIsBoundToTheirSessionId() {
        StandingEvent event = newStandingEvent();
        String sessionId = authService.startGuestSession().sessionId();
        reservationService.reserveForGuest(sessionId, event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(TICKETS_HELD));
        String name = nextName("kind.promoted");
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sessionId, MEMBER_AGE));
        AuthTokenDTO member = authService.login(new LoginRequestDTO(name, PASSWORD, sessionId)).authToken();
        ActiveOrder boundCart = activeOrderRepository.getBySessionId(sessionId).orElseThrow();
        assertEquals(member.userId(), boundCart.getUserId(),
                "arrangement: the member's cart is found by the member's session id");
        return new MemberWithCartOnItsSession(member, sessionId);
    }

    private static List<LocalDateTime> holdTimesOf(ActiveOrder cart) {
        return cart.getItems().stream().map(CartLineItem::getAddedAt).toList();
    }

    private static AuthTokenDTO withToken(AuthTokenDTO original, String token) {
        return new AuthTokenDTO(token, original.expiresAtEpochMillis(), original.userId(), original.username(),
                original.isAdmin());
    }

    private AuthTokenDTO registerAndLogin(String name) {
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sid, MEMBER_AGE));
        return authService.login(new LoginRequestDTO(name, PASSWORD, sid)).authToken();
    }

    private AuthTokenDTO login(String name) {
        String sid = authService.startGuestSession().sessionId();
        return authService.login(new LoginRequestDTO(name, PASSWORD, sid)).authToken();
    }

    private static String nextName(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }

    private static String nextIdempotencyKey() {
        return "idem-session-kind-" + SEQ.incrementAndGet();
    }

    private int registerCompanyAs(AuthTokenDTO owner) {
        return companyService.registerCompany(owner.token(),
                new CompanyRegistrationDTO("KindCo" + SEQ.incrementAndGet(), "desc")).companyId();
    }

    private EventCreationDTO showFor(int companyId) {
        LocalDateTime start = LocalDateTime.now(clock).plusDays(DAYS_UNTIL_SHOW);
        return new EventCreationDTO(companyId, "Kind Show " + SEQ.incrementAndGet(), "desc", List.of("Artist"),
                EventCategory.CONCERT, 4.5, new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(start, start.plusHours(SHOW_HOURS))), ZoneId.of("Asia/Jerusalem"), null);
    }

    /** A published single standing-zone show, owned by a member who takes no other part in the test. */
    private StandingEvent newStandingEvent() {
        AuthTokenDTO owner = registerAndLogin(nextName("kind.owner"));
        int companyId = registerCompanyAs(owner);
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), showFor(companyId));
        int eventId = Integer.parseInt(event.eventId());
        ZoneConfigDTO standing = new ZoneConfigDTO("Standing", false, ZONE_CAPACITY, null, TICKET_PRICE,
                new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Test Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);
        int zoneId = catalogService.getEventVenueMap(owner.token(), eventId).inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();
        return new StandingEvent(eventId, zoneId);
    }

    private void hold(AuthTokenDTO member, StandingEvent event) {
        reservationService.reserveForMember(member.token(), event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(TICKETS_HELD));
    }

    private int buy(AuthTokenDTO member, StandingEvent event) {
        hold(member, event);
        return checkoutService.checkoutMember(member.token(), nextIdempotencyKey(), CURRENCY, CARD).orderReceiptId();
    }

    private ActiveOrder cartOf(AuthTokenDTO member) {
        ActiveOrder cart = activeOrderRepository.getByUserId(member.userId());
        assertNotNull(cart, "arrangement: the member holds a cart");
        return cart;
    }

    private ConversationDTO inquiryFrom(AuthTokenDTO member) {
        int companyId = registerCompanyAs(registerAndLogin(nextName("kind.inbox.owner")));
        return messagingService.startConversation(member.token(),
                new StartConversationRequestDTO(companyId, "Parking?", "Is there parking near the venue?"));
    }

    private ConversationDTO complaintFrom(AuthTokenDTO member) {
        return messagingService.submitComplaint(member.token(), new SubmitComplaintRequestDTO(
                member.userId(), "Complaint " + SEQ.incrementAndGet(), "Please review.", null));
    }
}
