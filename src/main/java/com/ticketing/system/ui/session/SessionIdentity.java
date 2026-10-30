package com.ticketing.system.ui.session;

import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.stereotype.Component;

import com.ticketing.system.identity.application.service.AuthenticationService;

/**
 * Single source of truth for "member or guest, and which backend credential to use."
 *
 * <p>A member is a visitor whose stored token is still live; everyone else is a guest using the
 * {@link GuestSession} id. Each method resolves the visitor afresh, with at most one
 * {@link AuthenticationService#liveMemberId} call, plus one
 * {@link AuthenticationService#isLiveGuestSession} call when it answers with a guest's session id.
 *
 * <p>A signed-in admin is never a member, whatever the admin's id: admin and member ids share one id
 * space, and member paths reject admin tokens. On member and buyer paths the admin is a guest on the
 * {@link GuestSession} id, and the admin's stored token is left alone (ADR-012).
 *
 * <p>Side effect: when the stored token is no longer live (expired or revoked), the first method to
 * notice calls {@link FreshGuestSession#restore()}, which drops the stored token and pins a new guest
 * session, so the visitor is answered as a guest on that fresh id and later calls see no token at all.
 * The old id cannot be reused: login promoted the guest session in place, so it is the member's own
 * dead session. The reset lives here rather than in a navigation guard because a dead token is also
 * detected outside navigation (the cart badge poll, presenters), and those calls would otherwise keep
 * using the dead id.
 *
 * <p>Side effect: a guest session expires a fixed time after it starts, since nothing extends it. When
 * {@link #credential()} or {@link #guestSessionId()} would answer with a guest session id that is no
 * longer live, it first calls {@link FreshGuestSession#renewGuest()}, which pins a new guest session
 * and signs nobody out, and answers with that. This applies to admins too, who are guests here.
 */
@Component
public class SessionIdentity {

    /** What {@link #memberUserId()} answers for a guest; no user has this id. */
    public static final int NO_MEMBER_USER_ID = 0;

    private final AuthenticationService authenticationService;
    private final FreshGuestSession freshGuestSession;

    public SessionIdentity(AuthenticationService authenticationService, FreshGuestSession freshGuestSession) {
        this.authenticationService = authenticationService;
        this.freshGuestSession = freshGuestSession;
    }

    /** @return {@code true} while the stored member token is live */
    public boolean isMember() {
        return liveMember().isPresent();
    }

    /** @return the live member's token, or {@code null} for a guest */
    public String memberToken() {
        return liveMember().map(LiveMember::token).orElse(null);
    }

    /**
     * @return the visitor's guest session id; for a live member, the session their login promoted.
     *     A dead token is resolved first, so this is never the dead member session's id, and a
     *     non-member's id is live or freshly minted
     */
    public String guestSessionId() {
        return liveMember().isPresent() ? GuestSession.sessionId() : liveGuestSessionId();
    }

    /** @return the live member's user id, or {@link #NO_MEMBER_USER_ID} for a guest */
    public int memberUserId() {
        return liveMember().map(LiveMember::userId).orElse(NO_MEMBER_USER_ID);
    }

    /** @return the credential the backend expects: the live member's token, else a live guest session id */
    public String credential() {
        return liveMember().map(LiveMember::token).orElseGet(this::liveGuestSessionId);
    }

    // A live member's id is the session their login promoted, no longer a guest row, so this is only
    // for visitors who are not members.
    private String liveGuestSessionId() {
        if (!authenticationService.isLiveGuestSession(GuestSession.sessionId())) {
            freshGuestSession.renewGuest();
        }
        return GuestSession.sessionId();
    }

    private Optional<LiveMember> liveMember() {
        String token = AuthSession.token();
        // An admin token is checked first: liveMemberId answers "no member" for it, and taking that as a
        // dead member token would restore a fresh guest session and sign the admin out.
        if (token == null || AuthSession.isAdmin()) {
            return Optional.empty();
        }
        OptionalInt memberId = authenticationService.liveMemberId(token);
        if (memberId.isEmpty()) {
            freshGuestSession.restore();
            return Optional.empty();
        }
        return Optional.of(new LiveMember(token, memberId.getAsInt()));
    }

    private record LiveMember(String token, int userId) {
    }
}
