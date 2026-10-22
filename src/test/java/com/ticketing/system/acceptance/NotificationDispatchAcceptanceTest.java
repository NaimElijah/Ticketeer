package com.ticketing.system.acceptance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.application.port.in.INotificationService;
import com.ticketing.system.organization.application.port.out.ProductionCompanyRepository;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.sales.application.port.out.OrderReceiptRepository;
import com.ticketing.system.sales.application.service.CheckoutService;
import com.ticketing.system.sales.application.service.ReservationService;
import com.ticketing.system.sales.domain.ReceiptLine;
import com.ticketing.system.shared.dto.AppointmentResponseDTO;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CardDetailsDTO;
import com.ticketing.system.shared.dto.CheckoutResultDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.MarketControlRequestDTO;
import com.ticketing.system.shared.dto.OwnerAppointmentRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.event.RoleChangedNotice;
import com.ticketing.system.shared.persistence.PersistenceProfiles;

/**
 * #29 / ADR-004 / SLR.5: notifications are delivered after commit on the {@code notify-} thread, so a
 * failing or slow channel never fails, rolls back or blocks a use case, and a rolled-back transaction
 * notifies no one. Drives the real services (test profile) with the notifications port mocked as the
 * channel. The mock replaces a bean, so this class gets its own context, closed after the class.
 */
@SpringBootTest
@ActiveProfiles({"test", PersistenceProfiles.MEMORY})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class NotificationDispatchAcceptanceTest {

    private static final double TICKET_PRICE = 50.0;
    private static final int TICKETS_BOUGHT = 2;
    private static final Duration DELIVERY_DEADLINE = Duration.ofSeconds(10);
    private static final long SLOW_CHANNEL_HOLD_SECONDS = 30;
    private static final int ROLLED_BACK_RECIPIENT = 9_001;
    private static final int SENTINEL_RECIPIENT = 9_002;

    @MockitoBean private INotificationService channel;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private CatalogService catalogService;
    @Autowired private ReservationService reservationService;
    @Autowired private CheckoutService checkoutService;
    @Autowired private SystemAdminService systemAdminService;
    @Autowired private OrderReceiptRepository orderReceiptRepository;
    @Autowired private ProductionCompanyRepository companyRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    private static final AtomicInteger SEQ = new AtomicInteger(0);

    // Released after every test, so a held slow-channel call never pins the notify- thread.
    private final CountDownLatch releaseSlowChannel = new CountDownLatch(1);

    @BeforeEach
    void ensureMarketOpen() {
        if (systemAdminService.isMarketOpen()) {
            return;
        }
        systemAdminService.initializePlatform();
        AuthTokenDTO admin = authService.signInAsAdmin("admin", "admin");
        systemAdminService.openMarket(new MarketControlRequestDTO("OPEN", "notification dispatch setup", admin.token()));
    }

    @AfterEach
    void releaseChannel() {
        releaseSlowChannel.countDown();
    }

    @Test
    void GivenFailingChannel_WhenMemberReservesAndChecksOut_ThenThePurchaseCompletes() {
        doThrow(new IllegalStateException("notification channel down"))
                .when(channel).notifyTicketReservationSuccess(anyInt(), anyInt(), anyInt(), anyInt());
        doThrow(new IllegalStateException("notification channel down"))
                .when(channel).notifyPurchaseCompleted(anyInt(), anyDouble(), anyList());
        Fixture event = newStandingEvent(registerAndLogin("dispatchOwner"));

        CheckoutResultDTO order = buy(event);

        assertThat(orderReceiptRepository.findByOrderReceiptId(order.orderReceiptId())).isPresent();
        await().atMost(DELIVERY_DEADLINE).untilAsserted(() ->
                verify(channel).notifyPurchaseCompleted(anyInt(), anyDouble(), anyList()));
    }

    @Test
    void GivenFailingChannel_WhenOwnerAppointsAnotherOwner_ThenTheAppointmentIsRecorded() {
        doThrow(new IllegalStateException("notification channel down"))
                .when(channel).notifyOwnerAppointmentPending(anyInt(), anyInt(), anyString());
        AuthTokenDTO owner = registerAndLogin("dispatchOwner");
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("DispatchCo" + SEQ.incrementAndGet(), "desc")).companyId();
        String appointee = register("dispatchAppointee");
        int appointeeId = userIdOf(appointee);

        companyService.appointOwner(owner.token(), new OwnerAppointmentRequestDTO(companyId, appointeeId));

        // The appointment was recorded: the appointee can accept it and becomes an owner.
        companyService.respondToAppointment(login(appointee).token(), new AppointmentResponseDTO(companyId, true));
        assertTrue(companyRepository.getCompanyById(companyId).isOwner(appointeeId));
        await().atMost(DELIVERY_DEADLINE).untilAsserted(() ->
                verify(channel).notifyOwnerAppointmentPending(eq(appointeeId), eq(companyId), anyString()));
    }

    @Test
    void GivenFailingChannel_WhenOwnerCancelsASoldEvent_ThenTheBuyerIsRefunded() {
        doThrow(new IllegalStateException("notification channel down"))
                .when(channel).notifyEventCancelled(anyInt(), anyInt(), anyString());
        AuthTokenDTO owner = registerAndLogin("dispatchOwner");
        Fixture event = newStandingEvent(owner);
        CheckoutResultDTO order = buy(event);

        eventManagementService.cancelEventAndRefund(owner.token(), event.eventId());

        assertTrue(orderReceiptRepository.findByOrderReceiptId(order.orderReceiptId()).orElseThrow()
                .getReceiptLines().stream().allMatch(ReceiptLine::isRefunded));
        await().atMost(DELIVERY_DEADLINE).untilAsserted(() ->
                verify(channel).notifyEventCancelled(anyInt(), eq(event.eventId()), anyString()));
    }

    @Test
    @Timeout(value = SLOW_CHANNEL_HOLD_SECONDS, unit = TimeUnit.SECONDS)
    void GivenSlowChannel_WhenMemberChecksOut_ThenCheckoutReturnsWhileTheNotificationIsStillPending()
            throws InterruptedException {
        CountDownLatch deliveryStarted = new CountDownLatch(1);
        AtomicReference<String> deliveryThread = new AtomicReference<>();
        doAnswer(invocation -> {
            deliveryThread.set(Thread.currentThread().getName());
            deliveryStarted.countDown();
            releaseSlowChannel.await(SLOW_CHANNEL_HOLD_SECONDS, TimeUnit.SECONDS);
            return null;
        }).when(channel).notifyPurchaseCompleted(anyInt(), anyDouble(), anyList());
        Fixture event = newStandingEvent(registerAndLogin("dispatchOwner"));

        CheckoutResultDTO order = buy(event);

        assertThat(order.orderReceiptId()).isPositive();
        assertTrue(deliveryStarted.await(DELIVERY_DEADLINE.toSeconds(), TimeUnit.SECONDS),
                "the purchase notification must still be delivered");
        assertThat(deliveryThread.get()).startsWith("notify-");
    }

    @Test
    void GivenRolledBackTransaction_WhenItPublishedANotice_ThenNoNotificationIsDelivered() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.executeWithoutResult(status -> {
            eventPublisher.publishEvent(new RoleChangedNotice(ROLLED_BACK_RECIPIENT, 1, "RolledBackCo", "OWNER"));
            status.setRollbackOnly();
        });
        transaction.executeWithoutResult(status ->
                eventPublisher.publishEvent(new RoleChangedNotice(SENTINEL_RECIPIENT, 1, "SentinelCo", "OWNER")));

        // One notify- thread delivers in publish order, so once the later sentinel has arrived, a
        // wrongly queued rolled-back notice would already have been delivered.
        await().atMost(DELIVERY_DEADLINE).untilAsserted(() ->
                verify(channel).notifyRoleChanged(eq(SENTINEL_RECIPIENT), anyInt(), anyString(), anyString()));
        verify(channel, never()).notifyRoleChanged(eq(ROLLED_BACK_RECIPIENT), anyInt(), anyString(), anyString());
    }

    // ---- helpers --------------------------------------------------------

    private record Fixture(int companyId, int eventId, int zoneId) { }

    /** A new buyer reserves and checks out {@link #TICKETS_BOUGHT} standing tickets. */
    private CheckoutResultDTO buy(Fixture event) {
        AuthTokenDTO buyer = registerAndLogin("dispatchBuyer");
        reservationService.reserveForMember(buyer.token(), event.eventId(), event.zoneId(),
                InventorySelectionDTO.standing(TICKETS_BOUGHT));
        return checkoutService.checkoutMember(buyer.token(), "idem-dispatch-" + SEQ.incrementAndGet(),
                "ILS", new CardDetailsDTO("4111111111111111", "123", 12, 2030, "Demo Buyer"));
    }

    /** A company and a published single standing-zone event at {@link #TICKET_PRICE}. */
    private Fixture newStandingEvent(AuthTokenDTO owner) {
        int companyId = companyService.registerCompany(
                owner.token(), new CompanyRegistrationDTO("DispatchCo" + SEQ.incrementAndGet(), "desc")).companyId();
        LocalDateTime start = LocalDateTime.now().plusDays(10);
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Dispatch Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"), List.of(new ShowDate(start, start.plusHours(3))), ZoneId.of("Asia/Jerusalem"), null));
        int eventId = Integer.parseInt(event.eventId());
        ZoneConfigDTO standing = new ZoneConfigDTO("Standing", false, 100, null, TICKET_PRICE, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Test Arena", 1, 1, List.of(standing)));
        eventManagementService.publishEvent(owner.token(), companyId, eventId);
        int zoneId = catalogService.getEventVenueMap(owner.token(), eventId).inventoryZones().stream()
                .filter(z -> "STANDING".equals(z.getZoneType()))
                .findFirst().orElseThrow().getId();
        return new Fixture(companyId, eventId, zoneId);
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        return login(register(baseName));
    }

    private AuthTokenDTO login(String username) {
        String sid = authService.startGuestSession().sessionId();
        return authService.login(new LoginRequestDTO(username, "Password1", sid)).authToken();
    }

    /** Registers a fresh member and returns the unique username, without signing in. */
    private String register(String baseName) {
        String name = baseName + SEQ.incrementAndGet();
        String sid = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", "Password1", sid, 25));
        return name;
    }

    private int userIdOf(String username) {
        return userRepository.findByUsername(username).orElseThrow().getUserId();
    }
}
