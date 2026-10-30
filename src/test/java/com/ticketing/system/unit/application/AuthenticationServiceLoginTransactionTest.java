package com.ticketing.system.unit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import com.ticketing.system.identity.application.port.out.AdminRepository;
import com.ticketing.system.identity.application.port.out.CartRestorationPort;
import com.ticketing.system.identity.application.port.out.PasswordHasher;
import com.ticketing.system.identity.application.port.out.SessionManager;
import com.ticketing.system.identity.application.port.out.SessionRepository;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.identity.config.IdentityProperties;
import com.ticketing.system.identity.config.LockoutProperties;
import com.ticketing.system.identity.config.SessionProperties;
import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.User;
import com.ticketing.system.notifications.application.service.NotificationDispatchService;
import com.ticketing.system.shared.dto.LoginRequestDTO;
import com.ticketing.system.shared.metrics.ISystemMetrics;

/**
 * Login's transaction boundary (UC-12, UC-37, #187). Login delivers the member's pending
 * notifications, and delivery opens connections of its own; so it runs only after login's own
 * transaction has committed, never while that transaction still holds a connection (suspended or
 * not), and a login that fails delivers nothing.
 *
 * <p>The service is built by Spring with transaction management on, so the test holds whatever
 * boundary the service declares, annotation or {@code TransactionTemplate}, and does not depend on
 * its constructor. Every collaborator is a mock; the transaction manager journals each transaction
 * in the same list as the delivery, so the order is observable without a database.
 */
class AuthenticationServiceLoginTransactionTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final long GUEST_IDLE_MINUTES = 30;
    private static final long MEMBER_TTL_MINUTES = 1440;
    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_MINUTES = 15;
    private static final int USER_ID = 7;
    private static final String GUEST_SID = "guest-1";
    private static final String PASSWORD = "Password1";

    private static final String BEGIN = "begin";
    private static final String COMMIT = "commit";
    private static final String ROLLBACK = "rollback";
    private static final String DELIVER_PENDING = "deliverPending";

    private final List<String> journal = new ArrayList<>();
    private final RecordingTransactionManager transactionManager = new RecordingTransactionManager(journal);
    private final AtomicInteger openTransactionsAtDelivery = new AtomicInteger(-1);

    private final UserRepository userRepository = mock(UserRepository.class);
    private final PasswordHasher passwordHasher = mock(PasswordHasher.class);
    private final SessionManager sessionManager = mock(SessionManager.class);
    private final CartRestorationPort cartRestorationPort = mock(CartRestorationPort.class);
    private final NotificationDispatchService notifications = mock(NotificationDispatchService.class);
    private final SessionRepository sessionRepository = mock(SessionRepository.class);
    private final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TransactionalAuthentication.class)
            .withBean(PlatformTransactionManager.class, () -> transactionManager)
            .withBean(UserRepository.class, () -> userRepository)
            .withBean(PasswordHasher.class, () -> passwordHasher)
            .withBean(SessionManager.class, () -> sessionManager)
            .withBean(CartRestorationPort.class, () -> cartRestorationPort)
            .withBean(NotificationDispatchService.class, () -> notifications)
            .withBean(SessionRepository.class, () -> sessionRepository)
            .withBean(ISystemMetrics.class, () -> mock(ISystemMetrics.class))
            .withBean(Clock.class, () -> clock)
            .withBean(AdminRepository.class, () -> mock(AdminRepository.class))
            .withBean(SessionProperties.class, () -> new SessionProperties(MEMBER_TTL_MINUTES, GUEST_IDLE_MINUTES))
            .withBean(LockoutProperties.class, () -> new LockoutProperties(MAX_ATTEMPTS, LOCK_MINUTES))
            .withBean(IdentityProperties.class, () -> new IdentityProperties(List.of("admin"), "admin"));

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @Import(AuthenticationService.class)
    static class TransactionalAuthentication {
    }

    @BeforeEach
    void givenMemberAliceWithALiveGuestSession() {
        when(passwordHasher.hash(any())).thenReturn("DUMMY_HASH");
        when(userRepository.findByUsername("alice"))
                .thenReturn(Optional.of(new User(USER_ID, "alice", "alice@example.com", "STORED_HASH", 30)));
        when(passwordHasher.matches(PASSWORD, "STORED_HASH")).thenReturn(true);
        when(sessionRepository.findById(GUEST_SID)).thenAnswer(invocation -> Optional.of(
                new Session(GUEST_SID, null, T0, T0.plus(GUEST_IDLE_MINUTES, ChronoUnit.MINUTES))));
        when(sessionManager.generateTokenForSession(any(), eq("alice"))).thenReturn("TOK");
        when(notifications.deliverPending(anyInt())).thenAnswer(invocation -> {
            openTransactionsAtDelivery.set(transactionManager.openTransactions());
            journal.add(DELIVER_PENDING);
            return List.of();
        });
    }

    @Test
    void GivenValidCredentials_WhenLogin_ThenPendingNotificationsAreDeliveredAfterTheLoginCommits() {
        runner.run(context -> {
            context.getBean(AuthenticationService.class).login(new LoginRequestDTO("alice", PASSWORD, GUEST_SID));

            assertThat(journal).as("login's transaction must commit before delivery starts")
                    .containsSubsequence(COMMIT, DELIVER_PENDING);
            assertThat(openTransactionsAtDelivery.get())
                    .as("transactions still open (active or suspended) when deliverPending ran; journal %s", journal)
                    .isZero();
        });
    }

    @Test
    void GivenSessionSaveFails_WhenLogin_ThenNothingIsDeliveredAndNothingCommits() {
        doThrow(new DataAccessResourceFailureException("session store unreachable"))
                .when(sessionRepository).save(any(Session.class));

        runner.run(context -> {
            AuthenticationService service = context.getBean(AuthenticationService.class);

            assertThrows(DataAccessResourceFailureException.class,
                    () -> service.login(new LoginRequestDTO("alice", PASSWORD, GUEST_SID)));

            verify(notifications, never()).deliverPending(anyInt());
            assertThat(journal).as("a login whose promotion failed must roll back")
                    .contains(BEGIN, ROLLBACK).doesNotContain(COMMIT);
        });
    }

    @Test
    void GivenCartMergeFails_WhenLogin_ThenNothingIsDeliveredAndNothingCommits() {
        doThrow(new DataAccessResourceFailureException("cart store unreachable"))
                .when(cartRestorationPort).mergeGuestCartOnPromotion(any(), anyInt());

        runner.run(context -> {
            AuthenticationService service = context.getBean(AuthenticationService.class);

            assertThrows(DataAccessResourceFailureException.class,
                    () -> service.login(new LoginRequestDTO("alice", PASSWORD, GUEST_SID)));

            verify(notifications, never()).deliverPending(anyInt());
            assertThat(journal).as("a login whose cart merge failed must roll back, promotion included")
                    .contains(BEGIN, ROLLBACK).doesNotContain(COMMIT);
        });
    }

    /**
     * Journals each transaction it begins, commits and rolls back, and counts the ones begun and not
     * yet completed. Supports suspension, because {@code deliverPending} is declared
     * {@code NOT_SUPPORTED}: called inside a transaction, it suspends that transaction, which then
     * still holds its connection. Single-threaded, as each test is.
     */
    private static final class RecordingTransactionManager extends AbstractPlatformTransactionManager {

        private static final Object SUSPENDED = new Object();

        private final List<String> journal;
        private int open;
        private boolean bound;

        RecordingTransactionManager(List<String> journal) {
            this.journal = journal;
        }

        int openTransactions() {
            return open;
        }

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected boolean isExistingTransaction(Object transaction) {
            return bound;
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            bound = true;
            open++;
            journal.add(BEGIN);
        }

        @Override
        protected Object doSuspend(Object transaction) {
            bound = false;
            return SUSPENDED;
        }

        @Override
        protected void doResume(Object transaction, Object suspendedResources) {
            bound = true;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            journal.add(COMMIT);
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            journal.add(ROLLBACK);
        }

        @Override
        protected void doCleanupAfterCompletion(Object transaction) {
            bound = false;
            open--;
        }
    }
}
