package com.ticketing.system.integration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadPoolExecutor;

import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import com.ticketing.system.catalog.application.dto.EventCreationDTO;
import com.ticketing.system.catalog.application.dto.EventDetailDTO;
import com.ticketing.system.catalog.application.port.out.EventRepository;
import com.ticketing.system.catalog.application.service.EventManagementService;
import com.ticketing.system.catalog.domain.EventCategory;
import com.ticketing.system.catalog.domain.EventStatus;
import com.ticketing.system.catalog.domain.Location;
import com.ticketing.system.catalog.domain.ShowDate;
import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.notifications.config.NotificationAsyncConfig;
import com.ticketing.system.organization.application.service.CompanyManagementService;
import com.ticketing.system.shared.dto.AuthTokenDTO;
import com.ticketing.system.shared.dto.CompanyRegistrationDTO;
import com.ticketing.system.shared.dto.GridPlacementDTO;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.SeatConfigDTO;
import com.ticketing.system.shared.dto.VenueMapConfigDTO.ZoneConfigDTO;
import com.ticketing.system.shared.exception.EventNotFoundException;

import jakarta.persistence.EntityManagerFactory;

/**
 * Requirement V3 appendix 4 ({@code docs/requirements/requirements.md}): each use case runs as one
 * transaction. Its listed exceptions are checkout, event-cancellation refunds and the member refund;
 * deleting an event and changing its status are not among them.
 *
 * <p>The signal is Hibernate's transaction counter ({@link Statistics#getTransactionCount()}, enabled
 * with {@code hibernate.generate_statistics}). On Hibernate 6.6.53 it is incremented once per
 * physical transaction completion: the resource-local coordinator's after-completion callback calls
 * {@code StatisticsImplementor.endTransaction}, which is what Spring's {@code JpaTransactionManager}
 * drives on commit or rollback. A use case inside one {@code @Transactional} boundary therefore adds
 * exactly one, while a use case without one adds one per repository call that opens its own
 * transaction. {@link #GivenAConfiguredEvent_WhenPublished_ThenItRunsAsOneTransaction()} checks that
 * reading against a sibling use case that is {@code @Transactional}.
 *
 * <p>The counter is per {@code SessionFactory}, not per thread, and the {@code notify-} thread
 * stores notices in transactions of its own (ADR-004). Each test therefore waits for that executor to
 * drain before it reads the counter, and asserts that the measured call queued no notice, so every
 * counted transaction belongs to the call. Nothing else runs transactions under {@code test}: its
 * schedulers and runners are {@code @Profile("!test")}.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@DirtiesContext // the statistics property gives this class a context no other class reuses
class EventUseCaseTransactionJpaTest {

    private static final ZoneId VENUE_ZONE = ZoneId.of("Asia/Jerusalem");
    private static final int DAYS_UNTIL_SHOW = 10;
    private static final int SHOW_HOURS = 3;
    private static final Duration NOTICE_DRAIN_TIMEOUT = Duration.ofSeconds(10);
    private static final String PASSWORD = "Password1";
    private static final int MEMBER_AGE = 30;

    @Autowired private AuthenticationService authService;
    @Autowired private CompanyManagementService companyService;
    @Autowired private EventManagementService eventManagementService;
    @Autowired private EventRepository eventRepository;
    @Autowired private EntityManagerFactory entityManagerFactory;
    @Autowired private Clock clock;

    @Autowired
    @Qualifier(NotificationAsyncConfig.NOTIFICATION_EXECUTOR)
    private ThreadPoolTaskExecutor notificationExecutor;

    // Calibrates the signal: publishEvent is @Transactional, so it must count as exactly one.
    @Test
    void GivenAConfiguredEvent_WhenPublished_ThenItRunsAsOneTransaction() {
        AuthTokenDTO owner = registerAndLogin("txPublishOwner");
        int companyId = registerCompany(owner, "TxPublishCo");
        int eventId = createConfiguredEvent(owner, companyId);

        long transactions = transactionsDuring(
                () -> eventManagementService.publishEvent(owner.token(), companyId, eventId));

        assertEquals(1, transactions, "publishEvent should run as exactly one transaction");
        assertEquals(EventStatus.ON_SALE, eventRepository.findById(eventId).getStatus(),
                "publishEvent should have put the event on sale");
    }

    @Test
    void GivenAConfiguredEvent_WhenItsStatusIsChanged_ThenItRunsAsOneTransaction() {
        AuthTokenDTO owner = registerAndLogin("txStatusOwner");
        int companyId = registerCompany(owner, "TxStatusCo");
        int eventId = createConfiguredEvent(owner, companyId);

        long transactions = transactionsDuring(
                () -> eventManagementService.changeEventStatus(owner.token(), eventId, EventStatus.SCHEDULED));

        assertEquals(1, transactions, "changeEventStatus should run as exactly one transaction (V3 appendix 4)");
        assertEquals(EventStatus.SCHEDULED, eventRepository.findById(eventId).getStatus(),
                "changeEventStatus should have scheduled the event");
    }

    @Test
    void GivenACanceledEventWithoutSales_WhenDeleted_ThenItRunsAsOneTransaction() {
        AuthTokenDTO owner = registerAndLogin("txDeleteOwner");
        int companyId = registerCompany(owner, "TxDeleteCo");
        int eventId = createConfiguredEvent(owner, companyId);
        eventManagementService.cancelEventAndRefund(owner.token(), eventId);

        long transactions = transactionsDuring(() -> eventManagementService.deleteEvent(owner.token(), eventId));

        assertEquals(1, transactions, "deleteEvent should run as exactly one transaction (V3 appendix 4)");
        assertThrows(EventNotFoundException.class, () -> eventRepository.findById(eventId),
                "deleteEvent should have removed the event");
    }

    // The transactions the SessionFactory completed while the use case ran, with no notice work
    // from this or an earlier call mixed in.
    private long transactionsDuring(Runnable useCase) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        awaitNoticesStored();
        long noticesBefore = notificationExecutor.getThreadPoolExecutor().getTaskCount();
        long before = statistics.getTransactionCount();

        useCase.run();

        long after = statistics.getTransactionCount();
        assertEquals(noticesBefore, notificationExecutor.getThreadPoolExecutor().getTaskCount(),
                "the measured use case queued a notice, whose own transaction would blur the count");
        return after - before;
    }

    private void awaitNoticesStored() {
        ThreadPoolExecutor executor = notificationExecutor.getThreadPoolExecutor();
        await().atMost(NOTICE_DRAIN_TIMEOUT)
                .until(() -> executor.getCompletedTaskCount() == executor.getTaskCount());
    }

    /** addEvent plus a one-zone seated venue map: a DRAFT event that can be scheduled or published. */
    private int createConfiguredEvent(AuthTokenDTO owner, int companyId) {
        LocalDateTime showStart = LocalDateTime.now(clock.withZone(VENUE_ZONE)).plusDays(DAYS_UNTIL_SHOW);
        EventDetailDTO event = eventManagementService.addEvent(owner.token(), new EventCreationDTO(
                companyId, "Transaction Show", "desc", List.of("Artist"), EventCategory.CONCERT, 4.5,
                new Location("Israel", "Tel Aviv"),
                List.of(new ShowDate(showStart, showStart.plusHours(SHOW_HOURS))),
                VENUE_ZONE,
                null));
        ZoneConfigDTO seated = new ZoneConfigDTO("Orchestra", true, null,
                List.of(new SeatConfigDTO("A1", 1, 0)), 100.0, new GridPlacementDTO(1, 1, 1, 1));
        eventManagementService.configureVenueMap(owner.token(), companyId,
                new VenueMapConfigDTO(event.eventId(), "Arena", 1, 1, List.of(seated)));
        return Integer.parseInt(event.eventId());
    }

    private int registerCompany(AuthTokenDTO owner, String name) {
        return companyService.registerCompany(owner.token(), new CompanyRegistrationDTO(name, "desc")).companyId();
    }

    private AuthTokenDTO registerAndLogin(String baseName) {
        String name = baseName + "-" + UUID.randomUUID().toString().substring(0, 8);
        String sessionId = authService.startGuestSession().sessionId();
        authService.register(new RegisterRequestDTO(name, name + "@test.com", PASSWORD, sessionId, MEMBER_AGE));
        return authService.login(new LoginRequestDTO(name, PASSWORD, sessionId)).authToken();
    }
}
