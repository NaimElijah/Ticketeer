package com.ticketing.system.unit.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.ticketing.system.identity.domain.Session;
import com.ticketing.system.identity.domain.SessionKind;
import com.ticketing.system.support.BaseDomainTest;

class SessionTest extends BaseDomainTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T_EXPIRES = Instant.parse("2026-01-01T01:00:00Z");
    private static final Instant BEFORE_EXPIRY = Instant.parse("2026-01-01T00:30:00Z");
    private static final Instant AFTER_EXPIRY = Instant.parse("2026-01-01T01:01:00Z");

    @Test
    void GivenNoUserId_WhenConstructed_ThenGuestSessionHasNullUserId() {
        Session guest = track(new Session("sid-1", null, T0, T_EXPIRES));

        assertTrue(guest.isGuest());
        assertFalse(guest.isMember());
        assertNull(guest.getUserId());
    }

    @Test
    void GivenAUserId_WhenConstructed_ThenMemberSessionHasUserId() {
        Session member = track(new Session("sid-2", 5, T0, T_EXPIRES));

        assertFalse(member.isGuest());
        assertTrue(member.isMember());
        assertEquals(5, member.getUserId());
    }

    @Test
    void GivenACreatedAt_WhenConstructed_ThenLastSeenAtInitializedToCreatedAt() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertEquals(T0, s.getLastSeenAt());
    }

    @Test
    void GivenABlankOrNullSessionId_WhenConstructed_ThenRejects() {
        assertThrows(IllegalStateException.class,
                () -> new Session("", null, T0, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session("   ", null, T0, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session(null, null, T0, T_EXPIRES));
    }

    @Test
    void GivenNullTimestamps_WhenConstructed_ThenRejects() {
        assertThrows(IllegalStateException.class,
                () -> new Session("sid", null, null, T_EXPIRES));
        assertThrows(IllegalStateException.class,
                () -> new Session("sid", null, T0, null));
    }

    @Test
    void GivenAGuestSession_WhenPromoted_ThenSetsUserIdAndExtendsExpiry() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant newExpiry = Instant.parse("2026-01-02T00:00:00Z");

        s.promoteTo(7, newExpiry);

        assertTrue(s.isMember());
        assertEquals(7, s.getUserId());
        assertEquals(newExpiry, s.getExpiresAt());
    }

    @Test
    void GivenAGuestSession_WhenPromoted_ThenPreservesSessionId() {
        Session s = track(new Session("preserved-sid", null, T0, T_EXPIRES));

        s.promoteTo(7, T_EXPIRES.plusSeconds(60));

        assertEquals("preserved-sid", s.getSessionId());
    }

    @Test
    void GivenAMemberSession_WhenPromoted_ThenRejectsAlreadyMember() {
        Session s = track(new Session("sid", 5, T0, T_EXPIRES));

        assertThrows(IllegalStateException.class,
                () -> s.promoteTo(7, T_EXPIRES.plusSeconds(60)));

        assertEquals(5, s.getUserId(), "a refused promotion must not hand the row to another member");
        assertEquals(T_EXPIRES, s.getExpiresAt());
    }

    @Test
    void GivenAGuestSession_WhenPromotedWithNullExpiry_ThenRejects() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));

        assertThrows(IllegalArgumentException.class,
                () -> s.promoteTo(7, null));

        assertTrue(s.isGuest(), "a refused promotion leaves the row a guest row");
        assertNull(s.getUserId());
        assertEquals(T_EXPIRES, s.getExpiresAt());
    }

    @Test
    void GivenASession_WhenTouch_ThenUpdatesLastSeenAt() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant later = T0.plusSeconds(60);

        s.touch(later);

        assertEquals(later, s.getLastSeenAt());
    }

    @Test
    void GivenASession_WhenTouchWithNull_ThenRejects() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.touch(null));
    }

    @Test
    void GivenASession_WhenExtendExpiry_ThenUpdatesExpiresAt() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));
        Instant later = T_EXPIRES.plusSeconds(600);

        s.extendExpiry(later);

        assertEquals(later, s.getExpiresAt());
    }

    @Test
    void GivenASession_WhenExtendExpiryWithNull_ThenRejects() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.extendExpiry(null));
    }

    @Test
    void GivenAnInstantStrictlyBeforeExpiry_WhenIsExpiredAt_ThenFalse() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertFalse(s.isExpiredAt(BEFORE_EXPIRY));
    }

    @Test
    void GivenTheExpiryInstant_WhenIsExpiredAt_ThenTrueAtExpiryBoundary() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertTrue(s.isExpiredAt(T_EXPIRES));
    }

    @Test
    void GivenAnInstantAfterExpiry_WhenIsExpiredAt_ThenTrue() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertTrue(s.isExpiredAt(AFTER_EXPIRY));
    }

    @Test
    void GivenANullInstant_WhenIsExpiredAt_ThenRejects() {
        Session s = new Session("sid", null, T0, T_EXPIRES);
        assertThrows(IllegalArgumentException.class, () -> s.isExpiredAt(null));
    }

    // ---- the row records its kind (#185): member and admin ids share one id space ----

    @Test
    void GivenNoUserId_WhenConstructed_ThenKindIsGuest() {
        Session guest = track(new Session("sid", null, T0, T_EXPIRES));

        assertEquals(SessionKind.GUEST, guest.getKind());
    }

    @Test
    void GivenAUserId_WhenConstructed_ThenKindIsMember() {
        Session member = track(new Session("sid", 5, T0, T_EXPIRES));

        assertEquals(SessionKind.MEMBER, member.getKind());
    }

    @Test
    void GivenAnAdminId_WhenAdminSessionCreated_ThenKindIsAdminAndTheRowNamesTheAdmin() {
        Session admin = track(Session.admin("sid", 5, T0, T_EXPIRES));

        assertEquals(SessionKind.ADMIN, admin.getKind());
        assertEquals(5, admin.getUserId());
        assertFalse(admin.isGuest(), "a guest row is exactly a row without a user id");
        assertEquals(T0, admin.getCreatedAt());
        assertEquals(T_EXPIRES, admin.getExpiresAt());
    }

    @Test
    void GivenAMemberAndAnAdminWithTheSameId_WhenCreated_ThenTheirKindsDiffer() {
        Session member = track(new Session("sid-member", 1, T0, T_EXPIRES));
        Session admin = track(Session.admin("sid-admin", 1, T0, T_EXPIRES));

        assertEquals(member.getUserId(), admin.getUserId());
        assertNotEquals(member.getKind(), admin.getKind(),
                "the kind must tell an admin row from a member row with the same id");
    }

    @Test
    void GivenABlankSessionId_WhenAdminSessionCreated_ThenThrows() {
        assertThrows(IllegalStateException.class, () -> Session.admin("  ", 5, T0, T_EXPIRES));
    }

    @Test
    void GivenAGuestSession_WhenPromoted_ThenKindIsMember() {
        Session s = track(new Session("sid", null, T0, T_EXPIRES));

        s.promoteTo(7, T_EXPIRES.plusSeconds(60));

        assertEquals(SessionKind.MEMBER, s.getKind());
    }

    @Test
    void GivenAnAdminSession_WhenPromoted_ThenThrowsAndTheRowStaysAdmin() {
        Session admin = track(Session.admin("sid", 5, T0, T_EXPIRES));

        assertThrows(IllegalStateException.class, () -> admin.promoteTo(7, T_EXPIRES.plusSeconds(60)));
        assertEquals(SessionKind.ADMIN, admin.getKind());
        assertEquals(5, admin.getUserId());
    }

    // Pins today's behaviour, which Session cites: the domain doesn't validate userId positivity, that is
    // the User aggregate's job. Whether that is a requirement is not decided (#189).
    @Test
    void GivenZeroAndNegativeUserIds_WhenMemberSessionConstructed_ThenDoesNotThrow() {
        Session zero = track(assertDoesNotThrow(() -> new Session("sid-a", 0, T0, T_EXPIRES)));
        Session negative = track(assertDoesNotThrow(() -> new Session("sid-b", -1, T0, T_EXPIRES)));

        assertEquals(0, zero.getUserId());
        assertEquals(SessionKind.MEMBER, zero.getKind());
        assertEquals(-1, negative.getUserId());
        assertEquals(SessionKind.MEMBER, negative.getKind());
    }
}
