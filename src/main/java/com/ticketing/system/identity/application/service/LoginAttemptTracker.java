package com.ticketing.system.identity.application.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.ticketing.system.identity.config.LockoutProperties;

/**
 * Sign-in attempt bookkeeping for the brute-force lockout (SLR.2), shared by member and admin
 * sign-in in {@link AuthenticationService}.
 *
 * <p>Each attempt is counted, atomically, before its password is verified. Attempts are counted per
 * pool and username in a fixed window of {@code auth.lockout.lock-minutes} that opens at the key's
 * first attempt. Once the limit is reached, every further attempt in the window is refused without
 * verification, however many arrive at once; a success clears the key. Held in memory, so a restart
 * clears it.
 *
 * <p>Thread-safe. Memory is bounded: expired windows are purged on every write, and a key keeps no
 * more of a username than the username column can hold.
 */
final class LoginAttemptTracker {

    /** The length of the {@code users.username} column; no account has a longer name. */
    static final int MAX_USERNAME_LENGTH = 255;

    private final int maxAttempts;
    private final Duration window;
    private final Clock clock;
    private final ConcurrentMap<Key, AttemptWindow> windows = new ConcurrentHashMap<>();

    /**
     * @param lockout the policy; {@code maxAttempts} of {@code 0} disables the lockout
     * @param clock   the source of "now" for the windows
     */
    LoginAttemptTracker(LockoutProperties lockout, Clock clock) {
        this.maxAttempts = lockout.maxAttempts();
        this.window = Duration.ofMinutes(lockout.lockMinutes());
        this.clock = clock;
    }

    /**
     * Counts one sign-in attempt and says whether it may go on to password verification.
     *
     * @param pool     which sign-in the attempt is for; the pools are counted apart
     * @param username the username as the caller sent it; case and surrounding whitespace are
     *                 ignored, and {@code null} counts as empty
     * @return the admission: refused once the window already holds {@code maxAttempts} attempts
     */
    Admission admit(Pool pool, String username) {
        if (maxAttempts <= 0) {
            return Admission.UNLIMITED;
        }
        Instant now = clock.instant();
        purgeExpired(now);
        AttemptWindow counted = windows.compute(keyOf(pool, username), (key, current) ->
                current == null || current.hasEndedAt(now)
                        ? new AttemptWindow(now.plus(window), 1)
                        : current.withOneMore(maxAttempts));
        return new Admission(counted.attempts() <= maxAttempts, counted.attempts(), counted.endsAt());
    }

    /**
     * Forgets the key's attempts, after a successful sign-in.
     *
     * @param pool     the pool the sign-in was for
     * @param username the username as the caller sent it
     */
    void clear(Pool pool, String username) {
        purgeExpired(clock.instant());
        windows.remove(keyOf(pool, username));
    }

    /** How many keys are held, so a test can see that expired ones are purged. */
    int trackedKeyCount() {
        return windows.size();
    }

    // A full scan per write. The keys are bounded by the attempts one window admits, and every
    // admitted attempt also pays for a BCrypt verification, which costs far more than the scan.
    private void purgeExpired(Instant now) {
        windows.values().removeIf(attemptWindow -> attemptWindow.hasEndedAt(now));
    }

    private static Key keyOf(Pool pool, String username) {
        String normalized = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return new Key(pool, normalized.length() > MAX_USERNAME_LENGTH
                ? normalized.substring(0, MAX_USERNAME_LENGTH)
                : normalized);
    }

    /** The two sign-in pools, whose identical usernames are separate accounts. */
    enum Pool { MEMBER, ADMIN }

    /**
     * The outcome of {@link #admit}.
     *
     * @param admitted     whether the attempt may go on to password verification
     * @param attempts     the attempts counted in the window, this one included; past
     *                     {@code maxAttempts} it stays at {@code maxAttempts + 1}
     * @param windowEndsAt when the window, and so any lock, ends; {@code null} when the lockout is off
     */
    record Admission(boolean admitted, int attempts, Instant windowEndsAt) {
        static final Admission UNLIMITED = new Admission(true, 0, null);
    }

    private record Key(Pool pool, String username) { }

    private record AttemptWindow(Instant endsAt, int attempts) {

        boolean hasEndedAt(Instant now) {
            return !now.isBefore(endsAt);
        }

        // Capped one past the limit: that is enough to refuse, and the count cannot overflow.
        AttemptWindow withOneMore(int maxAttempts) {
            return new AttemptWindow(endsAt, Math.min(attempts + 1, maxAttempts + 1));
        }
    }
}
