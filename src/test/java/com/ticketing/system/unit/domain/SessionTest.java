package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.support.BaseDomainTest;

class SessionTest extends BaseDomainTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T_EXPIRES = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant BEFORE_EXPIRY = Instant.parse("2026-01-01T00:30:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2026-01-01T01:01:00Z");

    @Test
    void guestSession_hasNullUserId() {
        Session guest = track(new Session("sid-1", null, T0, T_EXPIRES));

        assertTrue(guest.isGuest());
        assertFalse(guest.isMember());
        assertNull(guest.getUserId());
    }

    @Test
    void memberSession_hasUserId() {
        Session member = track(new Session("sid-2", 5, T0, T_EXPIRES));

        assertFalse(member.isGuest());
        assertTrue(member.isMember());
        assertEquals(5, member.getUserId());
    }

    @Test
    void lastSeenAt_initializedToCreatedAt() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertEquals(T0, s.getLastSeenAt());
    }

    @Test
    void constructor_rejectsBlankSessionId() {
        assertThrows(IllegalStateException.class,
                () -> new Session("", null, T0, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session("   ", null, T0, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session(null, null, T0, T_EXPIRES));
    }

    @Test
    void constructor_rejectsNullTimestamps() {
        assertThrows(IllegalStateException.class,
                () -> new Session("sid", null, null, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session("sid", null, T0, null));
    }

    @Test
    void promoteTo_setsUserId_andExtendsExpiry() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant newExpiry = Instant.parse("2026-01-02T00:00:00Z");

        s.promoteTo(7, newExpiry);

        assertTrue(s.isMember());
        assertEquals(7, s.getUserId());
        assertEquals(newExpiry, s.getExpiresAt());
    }

    @Test
    void promoteTo_preservesSessionId() {
        Session s = track(new Session("preserved-sid", null, T0, T_EXPIRES));

        s.promoteTo(7, T_EXPIRES.plusSeconds(60));

        assertEquals("preserved-sid", s.getSessionId());
    }

    @Test
    void promoteTo_rejectsAlreadyMember() {
        Session s = new Session("sid", 5, T0, T_EXPIRES);

        assertThrows(IllegalStateException.class,
                () -> s.promoteTo(7, T_EXPIRES.plusSeconds(60)));
    }

    @Test
    void promoteTo_rejectsNullExpiry() {
        Session s = new Session("sid", null, T0, T_EXPIRES);

        assertThrows(IllegalArgumentException.class,
                () -> s.promoteTo(7, null));
    }

    @Test
    void touch_updatesLastSeenAt() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant later = T0.plusSeconds(60);

        s.touch(later);

        assertEquals(later, s.getLastSeenAt());
    }

    @Test
    void touch_rejectsNull() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.touch(null));
    }

    @Test
    void extendExpiry_updatesExpiresAt() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant later = T_EXPIRES.plusSeconds(600);

        s.extendExpiry(later);

        assertEquals(later, s.getExpiresAt());
    }

    @Test
    void extendExpiry_rejectsNull() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.extendExpiry(null));
    }

    @Test
    void isExpiredAt_falseStrictlyBeforeExpiry() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertFalse(s.isExpiredAt(BEFORE_EXPIRY));
    }

    @Test
    void isExpiredAt_trueAtExpiryBoundary() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertTrue(s.isExpiredAt(T_EXPIRES));
    }

    @Test
    void isExpiredAt_trueAfterExpiry() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertTrue(s.isExpiredAt(AFTER_EXPIRY));
    }

    @Test
    void isExpiredAt_rejectsNull() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.isExpiredAt(null));
    }

    @Test
    void memberSession_acceptsZeroAndNegativeUserIds() {
        // Domain doesn't validate userId positivity — that's the User aggregate's job.
        assertDoesNotThrow(() -> new Session("sid-a", 0, T0, T_EXPIRES));
        assertDoesNotThrow(() -> new Session("sid-b", -1, T0, T_EXPIRES));
    }
}
