package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateUsernameException;

/**
 * UC-11: two visitors who register one username at the same moment (#185). {@code register} checks
 * {@code existsByUsername} and then saves, so both can pass the check before either commits. Exactly
 * one may get the name: two rows with one username make {@code SystemIntegrityVerifier} refuse the
 * next boot.
 *
 * <p>Runs on the Jpa adapters over H2, where the {@code users} table enforces the uniqueness. The
 * user repository is spied on only to hold each registration just after its uniqueness check until
 * the other has made its own, so both checks see a free name on every run.
 */
@SpringBootTest
@ActiveProfiles("test")
class ConcurrentRegistrationJpaTest {

    private static final String USERNAME = "racing.registrant";
    private static final String PASSWORD = "Password1";
    private static final int AGE = 30;
    private static final int REGISTRATIONS = 2;
    private static final long AWAIT_SECONDS = 10;

    @Autowired
    private AuthenticationService authService;

    @MockitoSpyBean
    private UserRepository userRepository;

    @Test
    void GivenTwoConcurrentRegistrationsOfOneUsername_WhenRegister_ThenExactlyOneSucceeds() throws Exception {
        CyclicBarrier bothChecked = new CyclicBarrier(REGISTRATIONS);
        doAnswer(invocation -> {
            Object exists = invocation.callRealMethod();
            awaitTheOtherRegistration(bothChecked);
            return exists;
        }).when(userRepository).existsByUsername(USERNAME);
        List<RegisterRequestDTO> requests = List.of(
                new RegisterRequestDTO(USERNAME, "racer-a@example.com", PASSWORD,
                        authService.startGuestSession().sessionId(), AGE),
                new RegisterRequestDTO(USERNAME, "racer-b@example.com", PASSWORD,
                        authService.startGuestSession().sessionId(), AGE));

        List<Throwable> outcomes = registerAtOnce(requests);

        List<Throwable> failures = outcomes.stream().filter(outcome -> outcome != null).toList();
        assertEquals(1, outcomes.size() - failures.size(),
                "exactly one registration may take the username, but the outcomes were " + outcomes);
        assertEquals(1, failures.size(), "the other registration must fail, but the outcomes were " + outcomes);
        assertTrue(failures.get(0) instanceof DuplicateUsernameException,
                "the losing registration must fail with DuplicateUsernameException, but failed with " + failures.get(0));
        assertEquals(1, userRepository.findAll().stream().filter(u -> USERNAME.equals(u.getUsername())).count(),
                "exactly one user row may hold the username");
    }

    /** Runs every request at once; each outcome is {@code null} for a success, else what it threw. */
    private List<Throwable> registerAtOnce(List<RegisterRequestDTO> requests) throws InterruptedException {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(requests.size());
        List<Future<?>> registrations = new ArrayList<>();
        try {
            for (RegisterRequestDTO request : requests) {
                registrations.add(pool.submit(() -> {
                    start.await();
                    authService.register(request);
                    return null;
                }));
            }
            start.countDown();
            List<Throwable> outcomes = new ArrayList<>();
            for (Future<?> registration : registrations) {
                outcomes.add(outcomeOf(registration));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Throwable outcomeOf(Future<?> registration) throws InterruptedException {
        try {
            registration.get(AWAIT_SECONDS, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause();
        } catch (TimeoutException e) {
            return e;
        }
    }

    // An implementation that serialises registrations never brings both to the barrier; the wait is
    // bounded so such an implementation is judged by its outcome rather than hanging the test.
    private static void awaitTheOtherRegistration(CyclicBarrier bothChecked) throws InterruptedException {
        try {
            bothChecked.await(AWAIT_SECONDS / 2, TimeUnit.SECONDS);
        } catch (BrokenBarrierException | TimeoutException e) {
            // The other registration did not reach its check in time; go on alone.
        }
    }
}
