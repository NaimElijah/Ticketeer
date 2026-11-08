package com.ticketing.system.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.ticketing.system.identity.application.dto.RegisterRequestDTO;
import com.ticketing.system.identity.application.port.out.UserRepository;
import com.ticketing.system.identity.application.service.AuthenticationService;
import com.ticketing.system.shared.exception.DuplicateEmailException;
import com.ticketing.system.shared.exception.DuplicateUsernameException;

/**
 * UC-11: two visitors who register one username, or one email, at the same moment (#185). {@code register} checks
 * {@code existsByUsername} and then saves, so both can pass the check before either commits. Exactly
 * one may get the name: two rows with one username make {@code SystemIntegrityVerifier} refuse the
 * next boot.
 *
 * <p>Runs on the Jpa adapters over H2, where the {@code users} table enforces the uniqueness;
 * {@code PostgresConcurrentRegistrationIT} runs the same tests on PostgreSQL. The user repository is spied
 * on only to hold each registration just after its uniqueness check until the other has made its own. Each
 * test asserts that both checks saw a free name and that the two met there, so a run in which they never
 * overlapped fails, rather than passing on the refusal a second, sequential registration gets anyway.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext // its bean overrides give this class a context no other class reuses
class ConcurrentRegistrationJpaTest {

    private static final String USERNAME = "racing.registrant";
    private static final String EMAIL = "racing.registrant@example.com";
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
        Overlap overlap = new Overlap();
        doAnswer(invocation -> overlap.afterCheck(invocation.callRealMethod()))
                .when(userRepository).existsByUsername(USERNAME);
        List<RegisterRequestDTO> requests = List.of(
                new RegisterRequestDTO(USERNAME, "racer-a@example.com", PASSWORD,
                        authService.startGuestSession().sessionId(), AGE),
                new RegisterRequestDTO(USERNAME, "racer-b@example.com", PASSWORD,
                        authService.startGuestSession().sessionId(), AGE));

        List<Throwable> outcomes = registerAtOnce(requests);

        overlap.assertBothChecksSaw(false);
        List<Throwable> failures = outcomes.stream().filter(outcome -> outcome != null).toList();
        assertEquals(1, outcomes.size() - failures.size(),
                "exactly one registration may take the username, but the outcomes were " + outcomes);
        assertEquals(1, failures.size(), "the other registration must fail, but the outcomes were " + outcomes);
        assertTrue(failures.get(0) instanceof DuplicateUsernameException,
                "the losing registration must fail with DuplicateUsernameException, but failed with " + failures.get(0));
        assertEquals(1, userRepository.findAll().stream().filter(u -> USERNAME.equals(u.getUsername())).count(),
                "exactly one user row may hold the username");
    }

    // The same race for an email: register checks findByEmail and then saves, so both registrations
    // can pass the check, and the users table's uk_users_email settles it.
    @Test
    void GivenTwoConcurrentRegistrationsOfOneEmail_WhenRegister_ThenExactlyOneSucceedsAndTheOtherGetsDuplicateEmail()
            throws Exception {
        Overlap overlap = new Overlap();
        doAnswer(invocation -> overlap.afterCheck(invocation.callRealMethod()))
                .when(userRepository).findByEmail(EMAIL);
        List<RegisterRequestDTO> requests = List.of(
                new RegisterRequestDTO("email.racer.a", EMAIL, PASSWORD, authService.startGuestSession().sessionId(), AGE),
                new RegisterRequestDTO("email.racer.b", EMAIL, PASSWORD, authService.startGuestSession().sessionId(), AGE));

        List<Throwable> outcomes = registerAtOnce(requests);

        overlap.assertBothChecksSaw(Optional.empty());
        List<Throwable> failures = outcomes.stream().filter(outcome -> outcome != null).toList();
        assertEquals(1, outcomes.size() - failures.size(),
                "exactly one registration may take the email, but the outcomes were " + outcomes);
        assertEquals(1, failures.size(), "the other registration must fail, but the outcomes were " + outcomes);
        assertTrue(failures.get(0) instanceof DuplicateEmailException,
                "the losing registration must fail with DuplicateEmailException, but failed with " + failures.get(0));
        assertEquals(1, userRepository.findAll().stream().filter(u -> EMAIL.equals(u.getEmail())).count(),
                "exactly one user row may hold the email");
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

    /** What each registration's uniqueness check answered, and how many of them met the other there. */
    private static final class Overlap {

        private final CyclicBarrier bothChecked = new CyclicBarrier(REGISTRATIONS);
        private final List<Object> answers = new CopyOnWriteArrayList<>();
        private final AtomicInteger met = new AtomicInteger();

        // A registration whose partner never reaches its check goes on alone once the bounded wait ends, so
        // an implementation that serialises registrations still finishes; assertBothChecksSaw then reports
        // that the race never happened.
        Object afterCheck(Object answer) throws InterruptedException {
            answers.add(answer);
            try {
                bothChecked.await(AWAIT_SECONDS / 2, TimeUnit.SECONDS);
                met.incrementAndGet();
            } catch (BrokenBarrierException | TimeoutException e) {
                // Not met: recorded by met staying short of REGISTRATIONS.
            }
            return answer;
        }

        void assertBothChecksSaw(Object freeAnswer) {
            assertEquals(REGISTRATIONS, met.get(), "both registrations must pass their check before either goes on, "
                    + "or the race was never run; checks that met: " + met.get() + ", answers: " + answers);
            assertEquals(Collections.nCopies(REGISTRATIONS, freeAnswer), answers,
                    "both checks must find the value free, or the race was never run");
        }
    }
}
